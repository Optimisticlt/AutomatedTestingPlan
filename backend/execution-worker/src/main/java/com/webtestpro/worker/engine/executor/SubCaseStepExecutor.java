package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.entity.TcStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * SUB_CASE 步骤执行器（子用例调用）
 *
 * 用于复用公共流程（如登录、数据准备等），嵌套调用 CaseRunner.runCase()。
 * 子用例共享父用例的 ExecutionContext（变量双向流通）。
 *
 * config JSON 示例：
 * {
 *   "subCaseId": 456
 * }
 *
 * 注意：
 *   - 禁止循环引用（CaseRunner 负责检测调用栈深度，最大 5 层）
 *   - 子用例中的 EXTRACT 步骤写入的变量，在父用例中可见
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = {@Lazy})
public class SubCaseStepExecutor implements StepExecutor {

    private final ObjectMapper objectMapper;

    /** 延迟注入 CaseRunner，避免循环依赖（CaseRunner → SubCaseStepExecutor → CaseRunner） */
    @Lazy
    private final com.webtestpro.worker.engine.runner.CaseRunner caseRunner;

    /** 最大子用例嵌套深度 */
    private static final int MAX_DEPTH = 5;

    @Override
    public boolean supports(String stepType) {
        return "SUB_CASE".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        long subCaseId = config.path("subCaseId").asLong(0);

        if (subCaseId <= 0) {
            throw new IllegalArgumentException("SUB_CASE 步骤必须指定有效的 subCaseId");
        }

        log.info("[exec={}] 执行子用例 subCaseId={}", context.getExecutionId(), subCaseId);

        // 共享 ExecutionContext（变量双向流通）
        caseRunner.runCase(subCaseId, context);

        return StepResult.ok("子用例 [id=" + subCaseId + "] 执行完成");
    }
}
