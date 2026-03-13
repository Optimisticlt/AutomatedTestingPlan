package com.webtestpro.worker.engine.runner;

import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.log.LogFlusher;
import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.*;
import com.webtestpro.worker.mapper.*;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.testng.ISuite;
import org.testng.SuiteRunner;
import org.testng.TestNG;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.time.LocalDateTime;
import java.util.*;

/**
 * TestNG 套件执行器
 *
 * 将执行计划的多个用例动态构建为 TestNG Suite，支持：
 *   - 并行执行（parallelCount 控制线程数）
 *   - 断点续跑（跳过 checkpointCompletedIds 中的 case_id）
 *   - Allure 报告集成（通过 allure-testng 监听器自动生成）
 *
 * TestNG 仅用作并行调度框架，实际执行逻辑委托给 CaseRunner.runCase()。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestNGSuiteRunner {

    private final CaseRunner caseRunner;
    private final TcPlanCaseMapper planCaseMapper;
    private final TcEnvVariableMapper envVariableMapper;
    private final TcExecutionMapper executionMapper;
    private final SelenoidSessionManager sessionManager;
    private final LogFlusher logFlusher;

    /**
     * 执行一个计划内的所有用例。
     *
     * @param execution     执行记录实体
     * @param plan          执行计划实体
     * @param envVariables  环境变量列表（已解密明文）
     * @param completedIds  断点续跑：已完成的 case_id 集合（可为空）
     * @return pass=true，存在失败=false
     */
    public boolean runSuite(TcExecution execution, TcPlan plan,
                            List<TcEnvVariable> envVariables,
                            Set<Long> completedIds) {
        List<TcPlanCase> planCases = planCaseMapper.selectByPlanIdOrdered(plan.getId());
        if (planCases.isEmpty()) {
            log.warn("[exec={}] 计划 [{}] 没有用例，直接标记 PASS", execution.getId(), plan.getName());
            return true;
        }

        // 过滤断点续跑已完成用例
        List<Long> caseIds = planCases.stream()
                .map(TcPlanCase::getCaseId)
                .filter(id -> completedIds == null || !completedIds.contains(id))
                .toList();

        int parallelCount = plan.getParallelCount() != null ? plan.getParallelCount() : 1;
        int total = caseIds.size();
        int[] passCount = {0};
        int[] failCount = {0};

        log.info("[exec={}] 开始执行 {} 个用例（并行度={}）", execution.getId(), total, parallelCount);

        if (parallelCount <= 1) {
            // 串行执行
            for (Long caseId : caseIds) {
                ExecutionContext ctx = new ExecutionContext(execution.getId(), envVariables);
                boolean passed = executeSingleCase(caseId, ctx, execution);
                if (passed) passCount[0]++; else failCount[0]++;
            }
        } else {
            // 并行执行（使用 TestNG 并行分组）
            runParallel(caseIds, parallelCount, execution, envVariables, passCount, failCount);
        }

        // 更新执行统计
        updateExecutionStats(execution.getId(), total, passCount[0], failCount[0]);

        return failCount[0] == 0;
    }

    private boolean executeSingleCase(Long caseId, ExecutionContext ctx, TcExecution execution) {
        logFlusher.register(execution.getId());
        try {
            // 创建浏览器会话
            sessionManager.createDriver(execution.getBrowser() != null ? execution.getBrowser() : "chrome");
            return caseRunner.runCase(caseId, ctx);
        } catch (Exception e) {
            log.error("[exec={}][case={}] 执行异常: {}", execution.getId(), caseId, e.getMessage());
            return false;
        } finally {
            sessionManager.destroyDriver();
            logFlusher.unregister(execution.getId());
        }
    }

    private void runParallel(List<Long> caseIds, int parallelCount, TcExecution execution,
                              List<TcEnvVariable> envVariables,
                              int[] passCount, int[] failCount) {
        // 将 caseIds 分组，每组一个 TestNG Test（利用 TestNG parallel=TESTS）
        XmlSuite suite = new XmlSuite();
        suite.setName("WTP-Execution-" + execution.getId());
        suite.setParallel(XmlSuite.ParallelMode.TESTS);
        suite.setThreadCount(parallelCount);

        // 每个 case_id 创建一个独立 XmlTest
        for (Long caseId : caseIds) {
            XmlTest xmlTest = new XmlTest(suite);
            xmlTest.setName("case-" + caseId);
            xmlTest.addParameter("caseId", String.valueOf(caseId));
            xmlTest.addParameter("executionId", String.valueOf(execution.getId()));
            xmlTest.addParameter("browser", execution.getBrowser() != null ? execution.getBrowser() : "chrome");
        }

        TestNG testNG = new TestNG();
        testNG.setXmlSuites(List.of(suite));

        // 注入执行回调（通过 Suite Listener 统计结果）
        ParallelExecutionListener listener = new ParallelExecutionListener(
                caseRunner, sessionManager, logFlusher, envVariables, passCount, failCount);
        testNG.addListener(listener);

        testNG.run();
    }

    private void updateExecutionStats(Long executionId, int total, int pass, int fail) {
        TcExecution update = new TcExecution();
        update.setId(executionId);
        update.setTotalCases(total);
        update.setPassCases(pass);
        update.setFailCases(fail);
        update.setEndTime(LocalDateTime.now());
        executionMapper.updateById(update);
    }
}
