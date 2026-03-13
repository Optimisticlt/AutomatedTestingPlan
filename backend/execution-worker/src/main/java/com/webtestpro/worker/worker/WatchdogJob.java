package com.webtestpro.worker.worker;

import com.webtestpro.common.enums.ExecutionStatus;
import com.webtestpro.worker.entity.TcExecution;
import com.webtestpro.worker.entity.TcPlan;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import com.webtestpro.worker.mapper.TcPlanMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Watchdog Job（XXL-JOB 定时任务）
 *
 * 职责：
 *   每 30s 扫描所有 RUNNING 状态的执行记录，检查其心跳 Key 是否已在 Redis 中过期。
 *   若心跳过期（Worker 进程崩溃），执行以下恢复流程：
 *     1. MySQL CAS：RUNNING → INTERRUPTED（乐观锁，记录中断事实）
 *     2. 信号量归还：Lua 上限检查 INCR（防止超额归还）
 *     3. MySQL CAS：INTERRUPTED → WAITING（断点续跑，重新排队）
 *     4. LPUSH 回执行队列（保留原优先级，从队头插入加速恢复）
 *
 * 心跳 Key：heartbeat:{executionId}（redis-lock DB2，TTL=15s，Worker 每 5s 续期）
 *
 * XXL-JOB Admin 配置建议：
 *   - JobHandler: watchdogJobHandler
 *   - Cron: 0/30 * * * * ?（每 30s）
 *   - ExecutorRouteStrategy: FIRST（只需一个节点执行）
 */
@Slf4j
@Component
public class WatchdogJob {

    private static final String HEARTBEAT_PREFIX  = "heartbeat:";
    private static final String QUEUE_PREFIX       = "{exec_queue}:";
    private static final String QUEUE_DEFAULT      = QUEUE_PREFIX + "P1";

    /**
     * Lua: 上限检查 INCR，防止信号量超过 maxSessions（孤儿 session 已释放则不再重复 INCR）。
     * KEYS[1] = selenoid:semaphore
     * ARGV[1] = maxSessions
     */
    private static final DefaultRedisScript<Long> CAPPED_INCR_SCRIPT =
            new DefaultRedisScript<>("""
                    local v = tonumber(redis.call('GET', KEYS[1])) or 0
                    local maxV = tonumber(ARGV[1])
                    if v < maxV then
                        return redis.call('INCR', KEYS[1])
                    else
                        return v
                    end
                    """, Long.class);

    private final TcExecutionMapper executionMapper;
    private final TcPlanMapper       planMapper;
    private final StringRedisTemplate redisTemplateLock;
    private final StringRedisTemplate redisTemplateQueue;

    @Value("${selenoid.max-sessions:5}")
    private int maxSessions;

    public WatchdogJob(
            TcExecutionMapper executionMapper,
            TcPlanMapper planMapper,
            @Qualifier("redisTemplateLock")  StringRedisTemplate redisTemplateLock,
            @Qualifier("redisTemplateQueue") StringRedisTemplate redisTemplateQueue) {
        this.executionMapper  = executionMapper;
        this.planMapper        = planMapper;
        this.redisTemplateLock  = redisTemplateLock;
        this.redisTemplateQueue = redisTemplateQueue;
    }

    /**
     * XXL-JOB 任务入口：心跳扫描 + 崩溃恢复。
     * 在 Admin 中配置为 ExecutorRouteStrategy=FIRST，防止多 Worker 节点重复执行（幂等但浪费）。
     */
    @XxlJob("watchdogJobHandler")
    public void watchdogJobHandler() {
        List<TcExecution> runningList = executionMapper.selectRunningExecutions();
        if (runningList.isEmpty()) {
            log.debug("[Watchdog] 无 RUNNING 执行记录，跳过");
            return;
        }

        int interrupted = 0;
        int alive       = 0;

        for (TcExecution execution : runningList) {
            Long executionId = execution.getId();
            String heartbeatKey = HEARTBEAT_PREFIX + executionId;

            boolean heartbeatAlive = Boolean.TRUE.equals(
                    redisTemplateLock.hasKey(heartbeatKey));

            if (heartbeatAlive) {
                alive++;
                continue;
            }

            // 心跳已过期，Worker 进程崩溃 → 执行恢复流程
            log.warn("[Watchdog] 检测到心跳过期 exec={}, 开始崩溃恢复", executionId);

            boolean recovered = recoverCrashedExecution(execution);
            if (recovered) {
                interrupted++;
            }
        }

        log.info("[Watchdog] 扫描完成：总={}, 存活={}, 恢复={}", runningList.size(), alive, interrupted);
    }

    /**
     * 崩溃恢复流程（原子步骤，乐观锁保护）：
     * 1. CAS RUNNING → INTERRUPTED
     * 2. 信号量 Capped-INCR
     * 3. CAS INTERRUPTED → WAITING
     * 4. LPUSH 回优先级队列
     *
     * @return true=恢复成功，false=CAS 冲突（其他进程已处理）
     */
    private boolean recoverCrashedExecution(TcExecution execution) {
        Long executionId = execution.getId();

        // ── Step 1：RUNNING → INTERRUPTED ─────────────────────────────────
        int affected = executionMapper.casUpdateStatus(
                executionId,
                ExecutionStatus.RUNNING.getCode(),
                ExecutionStatus.INTERRUPTED.getCode(),
                execution.getVersion());

        if (affected == 0) {
            log.info("[Watchdog] exec={} 状态 CAS 失败（已被其他进程处理），跳过", executionId);
            return false;
        }

        // 记录中断时间点（非关键路径，失败不影响恢复）
        try {
            TcExecution checkpointUpdate = new TcExecution();
            checkpointUpdate.setId(executionId);
            checkpointUpdate.setCheckpointTime(LocalDateTime.now());
            executionMapper.updateById(checkpointUpdate);
        } catch (Exception e) {
            log.warn("[Watchdog] exec={} 写 checkpoint_time 失败: {}", executionId, e.getMessage());
        }

        // ── Step 2：信号量 Capped-INCR（归还崩溃前消耗的 session 槽）──────
        try {
            Long semaphoreAfter = redisTemplateLock.execute(
                    CAPPED_INCR_SCRIPT,
                    List.of("selenoid:semaphore"),
                    String.valueOf(maxSessions));
            log.debug("[Watchdog] exec={} 信号量归还，当前值={}", executionId, semaphoreAfter);
        } catch (Exception e) {
            // 信号量归还失败不阻断恢复，由 semaphoreReconcileHandler 兜底修复
            log.warn("[Watchdog] exec={} 信号量 INCR 失败（将由对账 Job 修复）: {}", executionId, e.getMessage());
        }

        // ── Step 3：INTERRUPTED → WAITING（断点续跑重新排队）────────────────
        TcExecution reloaded = executionMapper.selectById(executionId);
        if (reloaded == null) {
            log.error("[Watchdog] exec={} 重新加载失败，放弃 WAITING 迁移", executionId);
            return false;
        }

        int waitingAffected = executionMapper.casUpdateStatus(
                executionId,
                ExecutionStatus.INTERRUPTED.getCode(),
                ExecutionStatus.WAITING.getCode(),
                reloaded.getVersion());

        if (waitingAffected == 0) {
            log.warn("[Watchdog] exec={} INTERRUPTED→WAITING CAS 失败（乐观锁冲突）", executionId);
            return false;
        }

        // ── Step 4：LPUSH 回优先级队列（从队头插入，加速断点续跑）───────────
        String queueKey = resolveQueueKey(reloaded.getPlanId());
        redisTemplateQueue.opsForList().leftPush(queueKey, String.valueOf(executionId));

        log.info("[Watchdog] exec={} 崩溃恢复完成，已重新入队 → {}", executionId, queueKey);
        return true;
    }

    /**
     * 根据执行计划的优先级确定目标队列。
     * 无法获取计划或优先级不合法时，默认投入 P1（核心级别）。
     */
    private String resolveQueueKey(Long planId) {
        if (planId == null) {
            return QUEUE_DEFAULT;
        }
        try {
            TcPlan plan = planMapper.selectById(planId);
            if (plan != null && plan.getPriority() != null) {
                String p = plan.getPriority().toUpperCase();
                if (p.equals("P0") || p.equals("P1") || p.equals("P2")) {
                    return QUEUE_PREFIX + p;
                }
            }
        } catch (Exception e) {
            log.warn("[Watchdog] 获取计划优先级失败 planId={}: {}", planId, e.getMessage());
        }
        return QUEUE_DEFAULT;
    }
}
