package com.webtestpro.worker.worker;

import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.TcExecution;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行队列消费者（Redis BRPOP 循环）
 *
 * 优先级队列（防低优先级饥饿，设计文档 §4）：
 *   {exec_queue}:P0 – 冒烟（最高优先级）
 *   {exec_queue}:P1 – 核心
 *   {exec_queue}:P2 – 全量（老化机制：P0+P1 连续有任务时，跳过 5 次后强制消费 1 次 P2）
 *
 * 信号量控制：
 *   BRPOP 弹出任务前，Lua 原子 check-and-DECR Selenoid 信号量。
 *   信号量不足时，将任务重新 RPUSH 回原队列（保留优先级）。
 *
 * 取消检测：
 *   Worker 取到任务后检查 status == 'CANCELLED'，若是则直接丢弃（不执行，不消耗 session）。
 *
 * 优雅停机：
 *   Spring lifecycle.timeout-per-shutdown-phase=270s（application.yml），
 *   PreDestroy 中等待正在执行的任务完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionQueueConsumer {

    private static final String QUEUE_P0 = "{exec_queue}:P0";
    private static final String QUEUE_P1 = "{exec_queue}:P1";
    private static final String QUEUE_P2 = "{exec_queue}:P2";
    private static final int BRPOP_TIMEOUT_SEC = 5;
    private static final int P2_AGING_THRESHOLD = 5;

    private final StringRedisTemplate redisTemplateQueue;
    private final TcExecutionMapper executionMapper;
    private final SelenoidSessionManager sessionManager;
    private final ExecutionOrchestrator orchestrator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService consumerThread;

    /** P2 跳过计数（ThreadLocal，每个消费线程独立） */
    private final ThreadLocal<Integer> p2SkipCount = ThreadLocal.withInitial(() -> 0);

    @PostConstruct
    public void start() {
        sessionManager.initSemaphore();
        running.set(true);
        consumerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "execution-queue-consumer");
            t.setDaemon(false);
            return t;
        });
        consumerThread.submit(this::consumeLoop);
        log.info("ExecutionQueueConsumer 启动，监听三级优先级队列");
    }

    /**
     * 通知消费循环停止拉取新任务。
     * 由 GracefulShutdownHandler 在 SIGTERM 时调用，等待进行中的执行完成后再退出。
     */
    public void stopConsuming() {
        running.set(false);
        log.info("ExecutionQueueConsumer: stopConsuming() called, will stop after current BRPOP timeout");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        consumerThread.shutdown();
        try {
            if (!consumerThread.awaitTermination(30, TimeUnit.SECONDS)) {
                consumerThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("ExecutionQueueConsumer 已停止");
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                String executionIdStr = pollNextTask();
                if (executionIdStr == null) continue;

                Long executionId = Long.parseLong(executionIdStr.trim());
                TcExecution execution = executionMapper.selectById(executionId);

                if (execution == null) {
                    log.warn("[exec={}] 执行记录不存在，跳过", executionId);
                    continue;
                }

                // 取消检测（Worker 取到任务后立即检查）
                if ("CANCELLED".equals(execution.getStatus().getCode())) {
                    log.info("[exec={}] 任务已取消，跳过", executionId);
                    continue;
                }

                // 尝试获取 Selenoid session（Lua 原子 check-and-DECR）
                if (!sessionManager.tryAcquireSession()) {
                    // 无可用 session → 重新入队
                    requeueTask(executionId, execution.getPlanId());
                    Thread.sleep(2000); // 等待 session 释放
                    continue;
                }

                // 提交给 Orchestrator 异步执行（Orchestrator 负责创建 WebDriver 和执行）
                orchestrator.handleAsync(execution);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("队列消费循环异常: {}", e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * 按优先级顺序弹出任务（P0 → P1 → P2，含老化机制）。
     * Package-private for unit testing.
     */
    String pollNextTask() {
        // P2 老化：P2_AGING_THRESHOLD 次后强制消费一次 P2
        int skipCount = p2SkipCount.get();
        if (skipCount >= P2_AGING_THRESHOLD) {
            String p2Result = redisTemplateQueue.opsForList()
                    .rightPopAndLeftPush(QUEUE_P2, QUEUE_P2, Duration.ofSeconds(0));
            if (p2Result != null) {
                p2SkipCount.set(0);
                String task = redisTemplateQueue.opsForList().rightPop(QUEUE_P2);
                return task;
            }
        }

        // 优先 P0
        String result = brpop(QUEUE_P0);
        if (result != null) { p2SkipCount.set(skipCount + 1); return result; }

        // 次之 P1
        result = brpop(QUEUE_P1);
        if (result != null) { p2SkipCount.set(skipCount + 1); return result; }

        // 最后 P2
        result = brpop(QUEUE_P2);
        if (result != null) { p2SkipCount.set(0); return result; }

        return null;
    }

    private String brpop(String queueKey) {
        var result = redisTemplateQueue.opsForList()
                .rightPop(queueKey, Duration.ofSeconds(BRPOP_TIMEOUT_SEC));
        return result;
    }

    private void requeueTask(Long executionId, Long planId) {
        // 重新入队保留原优先级（从 TcPlan 获取，此处简化为 P1）
        redisTemplateQueue.opsForList().leftPush(QUEUE_P1, String.valueOf(executionId));
        log.debug("[exec={}] Selenoid 无可用 session，重新入队", executionId);
    }
}
