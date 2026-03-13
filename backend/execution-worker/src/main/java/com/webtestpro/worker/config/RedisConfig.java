package com.webtestpro.worker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 三库隔离配置
 *
 * DB0 (redis-cache  :6379) – access_token、热数据缓存，LRU eviction
 * DB1 (redis-queue  :6380) – 执行队列，noeviction + AOF
 * DB2 (redis-lock   :6381) – 分布式锁 + Selenoid 信号量 + refresh_token，noeviction
 *
 * 三个 Redis 实例各自独立连接池，禁止复用，防止跨实例操作。
 */
@Configuration
public class RedisConfig {

    // ── redis-cache (DB0) ────────────────────────────────────────────────────

    @Bean("redisCacheProperties")
    @ConfigurationProperties(prefix = "redis.cache")
    public RedisInstanceProperties redisCacheProperties() {
        return new RedisInstanceProperties();
    }

    @Primary
    @Bean("redisCacheConnectionFactory")
    public LettuceConnectionFactory redisCacheConnectionFactory(
            @Qualifier("redisCacheProperties") RedisInstanceProperties p) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(p.getHost(), p.getPort());
        if (p.getPassword() != null && !p.getPassword().isBlank()) {
            cfg.setPassword(p.getPassword());
        }
        cfg.setDatabase(p.getDatabase());
        return new LettuceConnectionFactory(cfg);
    }

    @Primary
    @Bean("redisTemplateCache")
    public StringRedisTemplate redisTemplateCache(
            @Qualifier("redisCacheConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    // ── redis-queue (DB1) ────────────────────────────────────────────────────

    @Bean("redisQueueProperties")
    @ConfigurationProperties(prefix = "redis.queue")
    public RedisInstanceProperties redisQueueProperties() {
        return new RedisInstanceProperties();
    }

    @Bean("redisQueueConnectionFactory")
    public LettuceConnectionFactory redisQueueConnectionFactory(
            @Qualifier("redisQueueProperties") RedisInstanceProperties p) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(p.getHost(), p.getPort());
        if (p.getPassword() != null && !p.getPassword().isBlank()) {
            cfg.setPassword(p.getPassword());
        }
        cfg.setDatabase(p.getDatabase());
        return new LettuceConnectionFactory(cfg);
    }

    @Bean("redisTemplateQueue")
    public StringRedisTemplate redisTemplateQueue(
            @Qualifier("redisQueueConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    // ── redis-lock (DB2) ─────────────────────────────────────────────────────

    @Bean("redisLockProperties")
    @ConfigurationProperties(prefix = "redis.lock")
    public RedisInstanceProperties redisLockProperties() {
        return new RedisInstanceProperties();
    }

    @Bean("redisLockConnectionFactory")
    public LettuceConnectionFactory redisLockConnectionFactory(
            @Qualifier("redisLockProperties") RedisInstanceProperties p) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(p.getHost(), p.getPort());
        if (p.getPassword() != null && !p.getPassword().isBlank()) {
            cfg.setPassword(p.getPassword());
        }
        cfg.setDatabase(p.getDatabase());
        return new LettuceConnectionFactory(cfg);
    }

    @Bean("redisTemplateLock")
    public StringRedisTemplate redisTemplateLock(
            @Qualifier("redisLockConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    // ── 连接属性 POJO ─────────────────────────────────────────────────────────

    public static class RedisInstanceProperties {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
    }
}
