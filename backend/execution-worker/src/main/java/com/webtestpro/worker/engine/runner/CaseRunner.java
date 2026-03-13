package com.webtestpro.worker.engine.runner;

import com.webtestpro.worker.engine.artifact.ArtifactUploader;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.executor.StepExecutor;
import com.webtestpro.worker.engine.executor.StepResult;
import com.webtestpro.worker.engine.log.LogFlusher;
import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.*;
import com.webtestpro.worker.mapper.*;
import io.qameta.allure.Allure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单用例执行器
 *
 * 职责：
 *   1. 加载用例步骤
 *   2. 按序执行各步骤（CONDITION 步骤处理分支逻辑）
 *   3. 步骤失败时重试（step.retryTimes → case.retryTimes，最多 3 次）
 *   4. 失败时自动截图 + HTML 快照 → ArtifactUploader → MinIO
 *   5. 写 TcCaseResult 到 MySQL
 *   6. Allure 步骤报告
 *   7. SUB_CASE 嵌套调用深度检测（最大 5 层）
 *
 * 线程安全：每个并发执行线程持有独立 ExecutionContext，通过 SelenoidSessionManager ThreadLocal 隔离 WebDriver。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseRunner {

    private static final int MAX_RETRY = 3;
    private static final int MAX_SUB_CASE_DEPTH = 5;

    private final TcStepMapper stepMapper;
    private final TcCaseMapper caseMapper;
    private final TcCaseResultMapper caseResultMapper;
    private final List<StepExecutor> stepExecutors;
    private final SelenoidSessionManager sessionManager;
    private final ArtifactUploader artifactUploader;
    private final LogFlusher logFlusher;

    /** 当前线程的子用例调用栈（循环引用检测） */
    private static final ThreadLocal<Set<Long>> CALL_STACK = ThreadLocal.withInitial(ConcurrentHashMap::newKeySet);

    /**
     * 执行单个用例（可被 SUB_CASE 步骤递归调用）。
     *
     * @param caseId  用例 ID
     * @param context 执行上下文（父子用例共享）
     * @return true=通过，false=失败
     */
    public boolean runCase(Long caseId, ExecutionContext context) {
        Set<Long> callStack = CALL_STACK.get();
        if (callStack.size() >= MAX_SUB_CASE_DEPTH) {
            throw new IllegalStateException("子用例嵌套深度超过 " + MAX_SUB_CASE_DEPTH + " 层，疑似循环引用");
        }
        if (!callStack.add(caseId)) {
            throw new IllegalStateException("检测到子用例循环引用，caseId=" + caseId);
        }

        context.setCurrentCaseId(caseId);
        long startMs = System.currentTimeMillis();

        TcCaseResult result = new TcCaseResult();
        result.setExecutionId(context.getExecutionId());
        result.setCaseId(caseId);
        result.setRetryCount(0);

        try {
            TcCase tc = caseMapper.selectById(caseId);
            if (tc == null) {
                throw new IllegalArgumentException("用例 [id=" + caseId + "] 不存在");
            }

            List<TcStep> steps = stepMapper.selectByCaseIdOrdered(caseId);
            appendLog(context, "INFO", null, "开始执行用例 [" + tc.getName() + "]");

            return Allure.step("用例: " + tc.getName(), () -> {
                executeSteps(steps, context, result, tc);
                result.setStatus("PASS");
                result.setDurationMs(System.currentTimeMillis() - startMs);
                caseResultMapper.insert(result);
                appendLog(context, "INFO", null, "用例通过 ✓");
                return true;
            });

        } catch (Exception e) {
            log.error("[exec={}][case={}] 用例失败: {}", context.getExecutionId(), caseId, e.getMessage());
            appendLog(context, "ERROR", null, "用例失败: " + e.getMessage());
            collectFailureArtifacts(context, result);
            result.setStatus("FAIL");
            result.setErrorMessage(truncate(e.getMessage(), 2000));
            result.setDurationMs(System.currentTimeMillis() - startMs);
            caseResultMapper.insert(result);
            return false;
        } finally {
            callStack.remove(caseId);
        }
    }

    private void executeSteps(List<TcStep> steps, ExecutionContext context,
                               TcCaseResult result, TcCase tc) throws Exception {
        boolean skipElse = false;
        boolean inCondition = false;
        boolean conditionResult = false;

        for (TcStep step : steps) {
            // CONDITION 分支逻辑
            if ("CONDITION".equalsIgnoreCase(step.getStepType()) && step.getParentStepId() == null) {
                inCondition = true;
                StepResult sr = executeWithRetry(step, context, tc);
                conditionResult = Boolean.TRUE.equals(sr.getConditionResult());
                skipElse = conditionResult;
                continue;
            }
            if (inCondition && step.getParentStepId() != null) {
                String branch = step.getBranchType();
                if ("THEN".equalsIgnoreCase(branch) && !conditionResult) continue;
                if ("ELSE".equalsIgnoreCase(branch) && conditionResult) continue;
            } else {
                inCondition = false;
            }

            appendLog(context, "INFO", step.getStepOrder(), "执行步骤: " + step.getName());
            StepResult sr = executeWithRetry(step, context, tc);

            if (sr.getExtractedKey() != null) {
                context.setVariable(sr.getExtractedKey(), sr.getExtractedValue());
            }
        }
    }

    private StepResult executeWithRetry(TcStep step, ExecutionContext context, TcCase tc) throws Exception {
        // 步骤级重试优先，为 0 时使用用例级，兜底 0
        int maxRetry = (step.getRetryTimes() != null && step.getRetryTimes() > 0)
                ? step.getRetryTimes()
                : (tc.getRetryTimes() != null ? tc.getRetryTimes() : 0);
        maxRetry = Math.min(maxRetry, MAX_RETRY);

        StepExecutor executor = findExecutor(step.getStepType());
        Exception lastEx = null;

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                return executor.execute(step, context);
            } catch (Exception e) {
                lastEx = e;
                if (attempt < maxRetry) {
                    log.warn("[exec={}][step={}] 步骤失败（第 {}/{} 次），重试中: {}",
                            context.getExecutionId(), step.getId(), attempt + 1, maxRetry, e.getMessage());
                    appendLog(context, "WARN", step.getStepOrder(),
                            "步骤失败，重试 " + (attempt + 1) + "/" + maxRetry);
                    Thread.sleep(500L * (attempt + 1));
                }
            }
        }
        throw lastEx;
    }

    private StepExecutor findExecutor(String stepType) {
        return stepExecutors.stream()
                .filter(e -> e.supports(stepType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到步骤类型 [" + stepType + "] 的执行器"));
    }

    private void collectFailureArtifacts(ExecutionContext context, TcCaseResult result) {
        try {
            WebDriver driver = sessionManager.currentDriver();
            // 截图
            String screenshotKey = artifactUploader.takeAndUploadScreenshot(
                    driver, context.getExecutionId(), null);
            result.setScreenshotKey(screenshotKey);
            if (screenshotKey != null) {
                // 截图附加到 Allure
                byte[] screenshot = ((org.openqa.selenium.TakesScreenshot) driver)
                        .getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
                Allure.getLifecycle().addAttachment("失败截图", "image/png", "png", screenshot);
            }
            // HTML 快照
            String html = (String) ((JavascriptExecutor) driver)
                    .executeScript("return document.documentElement.outerHTML;");
            String htmlKey = artifactUploader.uploadHtmlSnapshot(html, context.getExecutionId(), null);
            result.setHtmlSnapshotKey(htmlKey);
        } catch (Exception e) {
            log.warn("[exec={}] 收集失败产物时异常: {}", context.getExecutionId(), e.getMessage());
        }
    }

    private void appendLog(ExecutionContext context, String level, Integer stepIndex, String message) {
        TcExecutionLog logEntry = new TcExecutionLog();
        logEntry.setExecutionId(context.getExecutionId());
        logEntry.setLogIndex(context.nextLogIndex());
        logEntry.setStepIndex(stepIndex);
        logEntry.setLevel(level);
        logEntry.setContent(message);
        logEntry.setCreatedTime(LocalDateTime.now());
        logFlusher.append(context.getExecutionId(), logEntry);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
