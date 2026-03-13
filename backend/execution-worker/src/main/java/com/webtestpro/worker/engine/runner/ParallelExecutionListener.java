package com.webtestpro.worker.engine.runner;

import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.log.LogFlusher;
import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.TcEnvVariable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.List;

/**
 * TestNG 并行执行监听器
 *
 * 在 TestNG 并行模式下，每个 ITestContext 对应一个 caseId。
 * 此监听器从 TestNG 参数中获取 caseId，调用 CaseRunner 并统计结果。
 *
 * 注意：TestNG 使用独立线程调用 onStart，此处创建 WebDriver 并绑定到线程 ThreadLocal。
 */
@Slf4j
@RequiredArgsConstructor
class ParallelExecutionListener implements ISuiteListener, ITestListener {

    private final CaseRunner caseRunner;
    private final SelenoidSessionManager sessionManager;
    private final LogFlusher logFlusher;
    private final List<TcEnvVariable> envVariables;
    private final int[] passCount;
    private final int[] failCount;

    @Override
    public void onStart(ITestContext context) {
        String caseIdStr = context.getCurrentXmlTest().getParameter("caseId");
        String executionIdStr = context.getCurrentXmlTest().getParameter("executionId");
        String browser = context.getCurrentXmlTest().getParameter("browser");

        if (caseIdStr == null || executionIdStr == null) return;

        Long caseId = Long.parseLong(caseIdStr);
        Long executionId = Long.parseLong(executionIdStr);

        logFlusher.register(executionId);
        ExecutionContext ctx = new ExecutionContext(executionId, envVariables);

        try {
            sessionManager.createDriver(browser);
            boolean passed = caseRunner.runCase(caseId, ctx);
            synchronized (passCount) {
                if (passed) passCount[0]++; else failCount[0]++;
            }
        } catch (Exception e) {
            log.error("[exec={}][case={}] 并行执行异常: {}", executionId, caseId, e.getMessage());
            synchronized (failCount) { failCount[0]++; }
        } finally {
            sessionManager.destroyDriver();
            logFlusher.unregister(executionId);
        }
    }

    // 以下回调不需要额外逻辑（TestNG 要求实现接口）
    @Override public void onTestStart(ITestResult result) {}
    @Override public void onTestSuccess(ITestResult result) {}
    @Override public void onTestFailure(ITestResult result) {}
    @Override public void onTestSkipped(ITestResult result) {}
    @Override public void onStart(ISuite suite) {}
    @Override public void onFinish(ISuite suite) {}
    @Override public void onFinish(ITestContext context) {}
}
