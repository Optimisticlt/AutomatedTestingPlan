package com.webtestpro.api.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 订阅器
 *
 * 订阅 Worker 推送的执行日志消息，频道模式：exec:log:*
 * 收到消息后通过 STOMP Simple Broker 广播到 /topic/execution/{execId}/log
 *
 * 消息格式（Worker 发布的 JSON 行）：
 *   {"execId":"12345","level":"INFO","timestamp":"...","message":"..."}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionLogSubscriber implements MessageListener {

    private static final String CHANNEL_PATTERN = "exec:log:*";
    private static final String TOPIC_PREFIX    = "/topic/execution/";
    private static final String TOPIC_SUFFIX    = "/log";

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    @PostConstruct
    public void init() {
        redisMessageListenerContainer.addMessageListener(this, new PatternTopic(CHANNEL_PATTERN));
        log.info("ExecutionLogSubscriber subscribed to pattern: {}", CHANNEL_PATTERN);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body    = new String(message.getBody(), StandardCharsets.UTF_8);

            // channel format: exec:log:{execId}
            String execId = channel.substring("exec:log:".length());
            String destination = TOPIC_PREFIX + execId + TOPIC_SUFFIX;

            messagingTemplate.convertAndSend(destination, body);
            log.trace("Log forwarded: execId={}, dest={}", execId, destination);

        } catch (Exception e) {
            log.error("ExecutionLogSubscriber onMessage error: {}", e.getMessage(), e);
        }
    }
}
