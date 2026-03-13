package com.webtestpro.worker.worker;

import com.webtestpro.common.enums.ExecutionStatus;
import com.webtestpro.worker.entity.TcExecution;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 心跳管理器
 *
 * Worker 在执行用例期间每 5s 写一次 Redis heartbeat（TTL=15s）。
 * Watchdog（XXL-JOB，每 30s 扫描）检测 heartbeat 过期的 RUNNING 任务，标记 INTERRUPTED。
 *
 * Redis Key：heartbeat:{executionId}（存 redis-lock DB2，noeviction）
 *
 * 心跳管理：
 *   - register(executionId)   – 执行开始时注册
 *   - unregister(executionId) – 执行完成时注销
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatManager {

    private static final String HEARTBEAT_PREFIX = "heartbeat:";
    private static final long HEARTBEAT_TTL_SECONDS = 15;

    /** 活跃执行 ID 集合（本 Worker 节点正在执行的） */
    private final Set<Long> activeExecutions = ConcurrentHashMap.newKeySet();

    private final StringRedisTemplate redisTemplateLock;

    /**
     * 注册执行 ID，开始定期发送心跳。
     */
    public void register(Long executionId) {
        activeExecutions.add(executionId);
        writeHeartbeat(executionId);
        log.debug("[exec={}] 心跳注册", executionId);
    }

    /**
     * 注销执行 ID，停止心跳并删除 Key。
     */
    public void unregister(Long executionId) {
        activeExecutions.remove(executionId);
        redisTemplateLock.delete(HEARTBEAT_PREFIX + executionId);
        log.debug("[exec={}] 心跳注销", executionId);
    }

    /**
     * 返回本 Worker 节点正在执行的任务 ID 集合（实时视图，非副本）。
     * 由 GracefulShutdownHandler 在停机等待期间轮询，判断是否所有执行已完成。
     */
    public Set<Long> getActiveExecutionIds() {
        return activeExecutions;
    }

    /**
     * 定时刷新心跳（每 5s）。
     */
    @Scheduled(fixedDelay = 5000)
    public void refreshHeartbeats() {
        for (Long executionId : activeExecutions) {
            writeHeartbeat(executionId);
        }
    }

    private void writeHeartbeat(Long executionId) {
        try {
            redisTemplateLock.opsForValue().set(
                    HEARTBEAT_PREFIX + executionId,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofSeconds(HEARTBEAT_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[exec={}] 写心跳失败: {}", executionId, e.getMessage());
        }
    }
}
