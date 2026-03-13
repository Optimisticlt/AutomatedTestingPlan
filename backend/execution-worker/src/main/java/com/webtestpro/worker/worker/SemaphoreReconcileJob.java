package com.webtestpro.worker.worker;

import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 信号量对账 Job（XXL-JOB 定时任务）
 *
 * 职责：
 *   每 60s 对账 Redis 中的 Selenoid 信号量（selenoid:semaphore）与 MySQL 中实际
 *   RUNNING 执行数量，检测并修复信号量漂移。
 *
 * 漂移场景：
 *   1. Watchdog INCR 失败（Redis 短暂不可用）→ 信号量偏低
 *   2. Worker 在 destroyDriver 后崩溃，semaphore 未 INCR → 信号量偏低
 *   3. 同一 exec 被重复归还（bugfix/手动干预）→ 信号量偏高
 *
 * 对账公式：
 *   expected = max(0, maxSessions - runningCount)
 *
 * 注意事项：
 *   在 tryAcquireSession（DECR）与 status 写为 RUNNING 之间存在极短的窗口期（通常 <100ms），
 *   此期间对账可能将信号量多加 1（把即将 RUNNING 的任务视为空闲槽），最多造成一次额外 session
 *   被消耗（Selenoid 并发数 +1），在可接受范围内，下次对账会自动修正。
 *
 * XXL-JOB Admin 配置建议：
 *   - JobHandler: semaphoreReconcileHandler
 *   - Cron: 0 * * * * ?（每 60s）
 *   - ExecutorRouteStrategy: FIRST
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemaphoreReconcileJob {

    private final TcExecutionMapper    executionMapper;
    private final SelenoidSessionManager sessionManager;

    @Value("${selenoid.max-sessions:5}")
    private int maxSessions;

    /**
     * XXL-JOB 任务入口：信号量对账。
     */
    @XxlJob("semaphoreReconcileHandler")
    public void semaphoreReconcileHandler() {
        // Step 1：统计 MySQL 中当前 RUNNING 的执行数量
        long runningCount = executionMapper.countRunningExecutions();

        // Step 2：计算期望信号量
        int expected = (int) Math.max(0, maxSessions - runningCount);

        // Step 3：读取 Redis 当前信号量
        Long current = sessionManager.getSemaphoreValue();

        if (current == null) {
            // Key 不存在（Redis 重启或首次部署），重新初始化
            log.warn("[SemaphoreReconcile] 信号量 Key 不存在，重新初始化为 {} (maxSessions={}, running={})",
                    expected, maxSessions, runningCount);
            sessionManager.reconcileSemaphore(expected);
            return;
        }

        long drift = Math.abs(current - expected);
        if (drift == 0) {
            log.debug("[SemaphoreReconcile] 信号量正常：current={}, expected={}, running={}",
                    current, expected, runningCount);
            return;
        }

        // Step 4：存在漂移，修正信号量
        log.warn("[SemaphoreReconcile] 检测到信号量漂移：current={}, expected={}, running={}, maxSessions={}, drift={}",
                current, expected, runningCount, maxSessions, current - expected);

        sessionManager.reconcileSemaphore(expected);

        log.info("[SemaphoreReconcile] 信号量已修正：{} → {}", current, expected);
    }
}
