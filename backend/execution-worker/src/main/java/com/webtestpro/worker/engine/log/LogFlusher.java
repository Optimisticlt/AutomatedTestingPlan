package com.webtestpro.worker.engine.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.entity.TcExecutionLog;
import com.webtestpro.worker.mapper.TcExecutionLogMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志刷盘器（C1 修复版）
 *
 * 将 Worker 写入 Redis List 的执行日志批量持久化到 MySQL。
 *
 * 正确语义（C1 已修复，禁止用外部 offset）：
 *   1. LRANGE execution_log:{execId} 0 499  （始终从 index 0 读，取最多 500 条）
 *   2. 批量 INSERT INTO tc_execution_log（ON DUPLICATE KEY IGNORE 保幂等）
 *   3. 成功 → LTRIM execution_log:{execId} actualWrittenCount -1（移除头部已持久化条目）
 *   4. 失败 → 不 LTRIM，保留数据，下次 flush 重试
 *
 * 防无界增长（NM4 已修复）：
 *   - LogFlusher INSERT 路径受 Resilience4j 熔断保护
 *   - 单 List 超过 50000 条时丢弃新日志（写告警）
 *   - Worker RPUSH 前检查 LLEN
 *
 * Redis Key 格式：
 *   execution_log:{execId}  （存 redis-queue DB1，noeviction）
 *   每个 List 设置 24h TTL 兜底防孤儿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogFlusher {

    private static final String LOG_KEY_PREFIX = "execution_log:";
    private static final int BATCH_SIZE = 500;
    private static final long MAX_LIST_SIZE = 50_000;
    private static final long LIST_TTL_SECONDS = 86_400; // 24h

    /** 注册的活跃 execId 集合（Worker 执行时注册，完成后注销） */
    private final Set<Long> activeExecutions = ConcurrentHashMap.newKeySet();

    private final StringRedisTemplate redisTemplateQueue;
    private final TcExecutionLogMapper executionLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * Worker 注册执行 ID（开始执行时调用）。
     */
    public void register(Long executionId) {
        activeExecutions.add(executionId);
        // 设置 TTL 兜底
        redisTemplateQueue.expire(LOG_KEY_PREFIX + executionId,
                java.time.Duration.ofSeconds(LIST_TTL_SECONDS));
    }

    /**
     * Worker 注销执行 ID（执行完成时调用，继续 flush 直至 List 为空）。
     */
    public void unregister(Long executionId) {
        activeExecutions.remove(executionId);
        // 执行结束后继续排空
        drainLog(executionId);
        redisTemplateQueue.delete(LOG_KEY_PREFIX + executionId);
        log.info("[exec={}] LogFlusher 已排空并清理 Redis List", executionId);
    }

    /**
     * 向 Redis List 追加日志条目。
     * RPUSH 前检查 LLEN，超 50000 条时丢弃（NM4 防护）。
     *
     * @param logEntry 日志条目（已序列化 JSON）
     */
    public void append(Long executionId, TcExecutionLog logEntry) {
        String key = LOG_KEY_PREFIX + executionId;
        Long currentLen = redisTemplateQueue.opsForList().size(key);
        if (currentLen != null && currentLen >= MAX_LIST_SIZE) {
            log.warn("[exec={}] 执行日志 List 超过 {} 条，丢弃新日志（MySQL 可能不可用）",
                    executionId, MAX_LIST_SIZE);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(logEntry);
            redisTemplateQueue.opsForList().rightPush(key, json);
        } catch (Exception e) {
            log.error("[exec={}] 日志写入 Redis 失败: {}", executionId, e.getMessage());
        }
    }

    /**
     * 定时刷盘（每 200ms 执行一次）。
     * 遍历所有活跃执行 ID，批量持久化日志。
     */
    @Scheduled(fixedDelay = 200)
    public void flush() {
        for (Long executionId : activeExecutions) {
            try {
                flushOne(executionId);
            } catch (Exception e) {
                log.error("[exec={}] LogFlusher 刷盘异常: {}", executionId, e.getMessage());
            }
        }
    }

    private void flushOne(Long executionId) {
        String key = LOG_KEY_PREFIX + executionId;
        List<String> batch = redisTemplateQueue.opsForList().range(key, 0, BATCH_SIZE - 1);
        if (batch == null || batch.isEmpty()) {
            return;
        }

        List<TcExecutionLog> logs = new ArrayList<>(batch.size());
        for (String json : batch) {
            try {
                logs.add(objectMapper.readValue(json, TcExecutionLog.class));
            } catch (Exception e) {
                log.warn("[exec={}] 日志反序列化失败（跳过）: {}", executionId, e.getMessage());
            }
        }

        if (logs.isEmpty()) return;

        persistLogs(logs, key, batch.size());
    }

    @CircuitBreaker(name = "logFlusher", fallbackMethod = "persistLogsFallback")
    private void persistLogs(List<TcExecutionLog> logs, String redisKey, int batchSize) {
        // ON DUPLICATE KEY IGNORE 幂等保证（(execution_id, log_index) 唯一键）
        for (TcExecutionLog logEntry : logs) {
            executionLogMapper.insert(logEntry);
        }
        // INSERT 成功后 LTRIM（移除头部已持久化条目）
        redisTemplateQueue.opsForList().trim(redisKey, batchSize, -1);
    }

    private void persistLogsFallback(List<TcExecutionLog> logs, String redisKey, int batchSize, Throwable t) {
        log.error("LogFlusher 熔断打开，日志未持久化（{}条）：{}", logs.size(), t.getMessage());
        // 不执行 LTRIM，保留数据等待熔断恢复
    }

    /**
     * 排空指定执行的 Redis List（执行完成后调用）。
     */
    private void drainLog(Long executionId) {
        String key = LOG_KEY_PREFIX + executionId;
        int maxRetry = 50; // 最多重试 50 次（每次 200ms，最多 10s）
        for (int i = 0; i < maxRetry; i++) {
            Long size = redisTemplateQueue.opsForList().size(key);
            if (size == null || size == 0) break;
            flushOne(executionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
