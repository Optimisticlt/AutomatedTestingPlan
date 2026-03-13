package com.webtestpro.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 消息监听容器配置
 *
 * 使用 redis-cache (DB0) 的连接工厂订阅执行日志消息频道（exec:log:*）。
 * Worker 在 redis-cache 上发布日志，API Service 在同一实例上订阅。
 */
@Configuration
public class RedisMessageListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            @Qualifier("redisCacheConnectionFactory") LettuceConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
