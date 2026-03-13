package com.webtestpro.worker.worker;

import com.webtestpro.worker.mapper.TcExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * 优雅停机处理器
 *
 * 监听 Spring ContextClosedEvent（SIGTERM / actuator /shutdown）。
 * 执行步骤：
 *   1. 通知 ExecutionQueueConsumer 停止拉取新任务。
 *   2. 轮询最多 270s 等待所有执行完成（每 10s 打印一次进度）。
 *   3. 超时后对每个残留 executionId：
 *        a. 数据库标记 INTERRUPTED（WHERE status='RUNNING'）
 *        b. 归还 Selenoid 信号量（有界 INCR Lua）
 *        c. 重新入队 {exec_queue}:P1（供其他 Worker 恢复执行）
 */
@Slf4j
@Component
public class GracefulShutdownHandler implements ApplicationListener<ContextClosedEvent> {

    private static final int    SHUTDOWN_TIMEOUT_SECONDS = 270;
    private static final int    LOG_INTERVAL_SECONDS     = 10;
    private static final String SELENOID_SEMAPHORE_KEY   = "selenoid:semaphore";
    private static final String QUEUE_P1                 = "{exec_queue}:P1";

    /**
     * 有界 INCR Lua 脚本：仅当当前值 < maxSessions 时才 INCR，防止信号量溢出。
     * KEYS[1] = selenoid:semaphore
     * ARGV[1] = maxSessions
     */
    private static final DefaultRedisScript<String> BOUNDED_INCR_SCRIPT;

    static {
        BOUNDED_INCR_SCRIPT = new DefaultRedisScript<>();
        BOUNDED_INCR_SCRIPT.setScriptText(
                "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') " +
                "local max = tonumber(ARGV[1]) " +
                "if cur < max then redis.call('INCR', KEYS[1]) end " +
                "return redis.call('GET', KEYS[1])"
        );
        BOUNDED_INCR_SCRIPT.setResultType(String.class);
    }

    @Autowired
    private ExecutionQueueConsumer executionQueueConsumer;

    @Autowired
    private HeartbeatManager heartbeatManager;

    @Autowired
    @Qualifier("redisTemplateLock")
    private StringRedisTemplate redisTemplateLock;

    @Autowired
    @Qualifier("redisTemplateQueue")
    private StringRedisTemplate redisTemplateQueue;

    @Value("${selenoid.max-sessions:5}")
    private int maxSessions;

    @Autowired
    private TcExecutionMapper tcExecutionMapper;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Graceful shutdown initiated (max wait {} s)", SHUTDOWN_TIMEOUT_SECONDS);

        // Step 1: 停止接收新任务
        executionQueueConsumer.stopConsuming();
        log.info("ExecutionQueueConsumer signalled to stop accepting new tasks");

        // Step 2: 等待进行中的执行完成
        boolean allDone = waitForActiveExecutions();
        if (allDone) {
            log.info("Graceful shutdown complete -- all executions finished normally");
            return;
        }

        // Step 3: 超时，强制中断残留执行
        Set<Long> remaining = heartbeatManager.getActiveExecutionIds();
        log.warn("Graceful shutdown timeout -- {} execution(s) still running, forcing interrupt",
                 remaining.size());
        for (Long executionId : remaining) {
            forceInterrupt(executionId);
        }
        log.info("Graceful shutdown complete ({} execution(s) force-interrupted)", remaining.size());
    }

    /**
     * 每 1s 轮询一次，每 LOG_INTERVAL_SECONDS 秒打印一次进度。
     * @return true 如果所有执行在超时前完成
     */
    private boolean waitForActiveExecutions() {
        int elapsed = 0;
        while (elapsed < SHUTDOWN_TIMEOUT_SECONDS) {
            Set<Long> active = heartbeatManager.getActiveExecutionIds();
            if (active.isEmpty()) {
                log.info("All active executions finished after {} s", elapsed);
                return true;
            }
            if (elapsed % LOG_INTERVAL_SECONDS == 0) {
                log.info("Waiting... elapsed={} s, still running={} ids={}",
                         elapsed, active.size(), active);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Graceful shutdown wait interrupted");
                return false;
            }
            elapsed++;
        }
        return false;
    }

    /**
     * 强制中断单个执行，三个步骤各自独立 try-catch，互不影响。
     */
    private void forceInterrupt(Long executionId) {
        // a. DB 状态更新
        try {
            int rows = tcExecutionMapper.forceMarkInterrupted(executionId);
            if (rows > 0) {
                log.warn("[exec={}] Force-interrupted: status set to INTERRUPTED in DB", executionId);
            } else {
                log.warn("[exec={}] Force-interrupted: DB update affected 0 rows (already changed)",
                         executionId);
            }
        } catch (Exception e) {
            log.error("[exec={}] Force-interrupted: DB update failed: {}",
                      executionId, e.getMessage(), e);
        }

        // b. 归还 Selenoid 信号量（有界 INCR，不超过 maxSessions）
        try {
            String newVal = redisTemplateLock.execute(
                    BOUNDED_INCR_SCRIPT,
                    Collections.singletonList(SELENOID_SEMAPHORE_KEY),
                    String.valueOf(maxSessions));
            log.debug("[exec={}] Semaphore returned, new value={}", executionId, newVal);
        } catch (Exception e) {
            log.error("[exec={}] Force-interrupted: semaphore return failed: {}",
                      executionId, e.getMessage(), e);
        }

        // c. 重新入队 P1，供其他 Worker 恢复执行
        try {
            redisTemplateQueue.opsForList().leftPush(QUEUE_P1, String.valueOf(executionId));
            log.warn("[exec={}] Force-interrupted: re-enqueued to {} for recovery",
                     executionId, QUEUE_P1);
        } catch (Exception e) {
            log.error("[exec={}] Force-interrupted: re-enqueue failed: {}",
                      executionId, e.getMessage(), e);
        }
    }
}
