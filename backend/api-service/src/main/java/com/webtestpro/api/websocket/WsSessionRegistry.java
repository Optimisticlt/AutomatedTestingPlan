package com.webtestpro.api.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 会话注册表
 *
 * 跟踪每个用户当前活跃的 WebSocket 连接数，实现最多 5 个并发连接限制。
 * 使用 ConcurrentHashMap<userId, AtomicInteger> 保证线程安全。
 */
@Slf4j
@Component
public class WsSessionRegistry {

    public static final int MAX_CONNECTIONS_PER_USER = 5;

    /** userId -> 当前连接数 */
    private final ConcurrentHashMap<String, AtomicInteger> connectionCount = new ConcurrentHashMap<>();

    /**
     * 注册新连接。
     * @return true 如果允许（当前连接数 < MAX_CONNECTIONS_PER_USER），false 如果已达上限
     */
    public boolean register(String userId) {
        AtomicInteger count = connectionCount.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int current;
        do {
            current = count.get();
            if (current >= MAX_CONNECTIONS_PER_USER) {
                log.warn("WS connection limit reached for user={}, current={}", userId, current);
                return false;
            }
        } while (!count.compareAndSet(current, current + 1));
        log.debug("WS connection registered: user={}, count={}", userId, current + 1);
        return true;
    }

    /**
     * 注销连接。
     */
    public void unregister(String userId) {
        connectionCount.computeIfPresent(userId, (k, v) -> {
            int newVal = v.decrementAndGet();
            if (newVal <= 0) {
                return null; // 移除 key
            }
            return v;
        });
        log.debug("WS connection unregistered: user={}", userId);
    }

    public int getConnectionCount(String userId) {
        AtomicInteger count = connectionCount.get(userId);
        return count != null ? count.get() : 0;
    }
}
