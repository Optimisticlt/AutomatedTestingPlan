package com.webtestpro.api.config;

import cn.dev33.satoken.dao.SaTokenDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sa-Token 自定义 Redis 持久化实现
 *
 * 路由策略（按 key 前缀分库）：
 *   "satoken:access:"  → DB0 redis-cache  (redisTemplateCache)
 *   "satoken:refresh:" → DB2 redis-lock   (redisTemplateLock)
 *   其他 key           → DB0 redis-cache  (redisTemplateCache，默认)
 *
 * 注意：Sa-Token 自身将对象序列化为 JSON String，本实现直接使用
 * StringRedisTemplate，无需额外 ObjectMapper。
 */
@Slf4j
@Component
public class SaTokenDaoImpl implements SaTokenDao {

    private static final String PREFIX_ACCESS  = "satoken:access:";
    private static final String PREFIX_REFRESH = "satoken:refresh:";

    /** DB0：access_token 及缓存数据 */
    @Autowired
    @Qualifier("redisTemplateCache")
    private StringRedisTemplate redisTemplateCache;

    /** DB2：refresh_token 及分布式锁 */
    @Autowired
    @Qualifier("redisTemplateLock")
    private StringRedisTemplate redisTemplateLock;

    // ── 路由选择 ──────────────────────────────────────────────────────────────

    private StringRedisTemplate route(String key) {
        if (key != null && key.startsWith(PREFIX_REFRESH)) {
            return redisTemplateLock;
        }
        return redisTemplateCache;
    }

    // ── String 操作 ───────────────────────────────────────────────────────────

    @Override
    public String get(String key) {
        return route(key).opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == 0 || timeout == SaTokenDao.NEVER_EXPIRE) {
            route(key).opsForValue().set(key, value);
        } else {
            route(key).opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public void update(String key, String value) {
        long ttl = getTimeout(key);
        if (ttl == SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        if (ttl == SaTokenDao.NEVER_EXPIRE) {
            route(key).opsForValue().set(key, value);
        } else {
            route(key).opsForValue().set(key, value, ttl, TimeUnit.SECONDS);
        }
    }

    @Override
    public void delete(String key) {
        route(key).delete(key);
    }

    @Override
    public long getTimeout(String key) {
        Long expire = route(key).getExpire(key, TimeUnit.SECONDS);
        if (expire == null) {
            return SaTokenDao.NOT_VALUE_EXPIRE;
        }
        return expire;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            route(key).persist(key);
        } else {
            route(key).expire(key, timeout, TimeUnit.SECONDS);
        }
    }

    // ── Object 操作（Sa-Token 自行序列化为 JSON String，直接复用 String 方法）──

    @Override
    public Object getObject(String key) {
        return get(key);
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        set(key, String.valueOf(object), timeout);
    }

    @Override
    public void updateObject(String key, Object object) {
        update(key, String.valueOf(object));
    }

    @Override
    public void deleteObject(String key) {
        delete(key);
    }

    @Override
    public long getObjectTimeout(String key) {
        return getTimeout(key);
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        updateTimeout(key, timeout);
    }

    // ── Session 搜索 ─────────────────────────────────────────────────────────

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        StringRedisTemplate template = route(prefix);
        String pattern = prefix + "*" + keyword + "*";

        List<String> result = new ArrayList<>();
        try {
            Collection<String> keys = template.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return result;
            }
            List<String> keyList = new ArrayList<>(keys);
            if (sortType) {
                keyList.sort(String::compareTo);
            } else {
                keyList.sort((a, b) -> b.compareTo(a));
            }
            int end = Math.min(start + size, keyList.size());
            if (start < keyList.size()) {
                result = keyList.subList(start, end);
            }
        } catch (Exception e) {
            log.error("Sa-Token searchData 异常, prefix={}, keyword={}", prefix, keyword, e);
        }
        return result;
    }
}
