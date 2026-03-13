package com.webtestpro.worker.engine.session;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;

/**
 * Selenoid 会话管理器
 *
 * 职责：
 *   1. WebDriver 生命周期管理（创建/销毁），使用 ThreadLocal 隔离
 *   2. Redis 信号量控制（Lua 原子脚本，防止并发超额分配）
 *   3. Resilience4j 熔断保护（失败率 50% / 超时 30s）
 *
 * 信号量键：selenoid:semaphore（存 redis-lock DB2）
 *
 * Lua 脚本（H5 CAS check-and-DECR，防止信号量跌为负值）：
 *   local v = redis.call('GET', KEYS[1])
 *   if tonumber(v) and tonumber(v) > 0 then
 *     return redis.call('DECR', KEYS[1])
 *   else
 *     return -1
 *   end
 */
@Slf4j
@Component
public class SelenoidSessionManager {

    private static final String SEMAPHORE_KEY = "selenoid:semaphore";

    /** Lua: 原子 check-and-DECR（消费一个 session） */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    local v = redis.call('GET', KEYS[1])
                    if tonumber(v) and tonumber(v) > 0 then
                        return redis.call('DECR', KEYS[1])
                    else
                        return -1
                    end
                    """, Long.class);

    /** Lua: 原子 INCR（归还 session） */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>("return redis.call('INCR', KEYS[1])", Long.class);

    /** WebDriver ThreadLocal（每个执行线程独立持有） */
    private static final ThreadLocal<WebDriver> DRIVER_HOLDER = new ThreadLocal<>();

    private final StringRedisTemplate redisTemplateLock;

    @Value("${selenoid.url}")
    private String selenoidUrl;

    @Value("${selenoid.max-sessions:5}")
    private int maxSessions;

    public SelenoidSessionManager(
            @Qualifier("redisTemplateLock") StringRedisTemplate redisTemplateLock) {
        this.redisTemplateLock = redisTemplateLock;
    }

    /**
     * Worker 启动时初始化信号量（SET NX，防止重启覆盖在途任务）。
     */
    public void initSemaphore() {
        Boolean set = redisTemplateLock.opsForValue()
                .setIfAbsent(SEMAPHORE_KEY, String.valueOf(maxSessions));
        if (Boolean.TRUE.equals(set)) {
            log.info("Selenoid 信号量初始化：{}", maxSessions);
        } else {
            log.info("Selenoid 信号量已存在，跳过初始化（服务重启场景，等待 Watchdog heartbeat 回收孤儿 session）");
        }
    }

    /**
     * 尝试获取 Selenoid session（Lua 原子 check-and-DECR）。
     *
     * @return true=获取成功，false=无可用 session（调用方应重新入队）
     */
    public boolean tryAcquireSession() {
        Long result = redisTemplateLock.execute(
                ACQUIRE_SCRIPT, List.of(SEMAPHORE_KEY));
        if (result == null || result < 0) {
            log.info("Selenoid 无可用 session，任务重新入队");
            return false;
        }
        log.debug("获取 Selenoid session，当前剩余信号量: {}", result);
        return true;
    }

    /**
     * 归还 Selenoid session（Lua 原子 INCR）。
     */
    public void releaseSession() {
        Long result = redisTemplateLock.execute(
                RELEASE_SCRIPT, List.of(SEMAPHORE_KEY));
        log.debug("归还 Selenoid session，当前信号量: {}", result);
    }

    /**
     * 创建 RemoteWebDriver 并绑定到当前线程（Resilience4j 熔断保护）。
     *
     * @param browser 浏览器类型（chrome/firefox）
     * @return WebDriver 实例（ThreadLocal 持有，不可跨线程使用）
     */
    @CircuitBreaker(name = "selenoid")
    public WebDriver createDriver(String browser) {
        try {
            WebDriver driver = switch (browser.toLowerCase()) {
                case "firefox" -> new RemoteWebDriver(new URL(selenoidUrl), buildFirefoxOptions());
                default -> new RemoteWebDriver(new URL(selenoidUrl), buildChromeOptions());
            };
            DRIVER_HOLDER.set(driver);
            log.info("WebDriver 创建成功 browser={}", browser);
            return driver;
        } catch (Exception e) {
            // 创建失败立即归还信号量
            releaseSession();
            throw new RuntimeException("创建 WebDriver 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 销毁当前线程的 WebDriver 并归还信号量。
     * 必须在 finally 块中调用，确保信号量不泄漏。
     */
    public void destroyDriver() {
        WebDriver driver = DRIVER_HOLDER.get();
        if (driver != null) {
            try {
                driver.quit();
                log.debug("WebDriver 已关闭");
            } catch (Exception e) {
                log.warn("关闭 WebDriver 时异常: {}", e.getMessage());
            } finally {
                DRIVER_HOLDER.remove();
                releaseSession();
            }
        }
    }

    /**
     * 获取当前信号量值（对账比对用）。
     *
     * @return 当前值；若 Key 不存在（Redis 重启场景）返回 null
     */
    public Long getSemaphoreValue() {
        String val = redisTemplateLock.opsForValue().get(SEMAPHORE_KEY);
        return val != null ? Long.parseLong(val) : null;
    }

    /**
     * 强制将信号量设置为期望值（信号量对账 Job 专用，不走 NX 逻辑）。
     * 仅在检测到漂移时由 SemaphoreReconcileJob 调用，正常执行路径禁止直接调用此方法。
     *
     * @param expected 期望值（通常为 max(0, maxSessions - runningCount)）
     */
    public void reconcileSemaphore(int expected) {
        int capped = Math.max(0, Math.min(expected, maxSessions));
        redisTemplateLock.opsForValue().set(SEMAPHORE_KEY, String.valueOf(capped));
        log.info("信号量对账：已修正为 {}", capped);
    }

    /** 获取当前线程的 WebDriver（仅在 createDriver 之后有效） */
    public WebDriver currentDriver() {
        WebDriver driver = DRIVER_HOLDER.get();
        if (driver == null) {
            throw new IllegalStateException("当前线程没有 WebDriver 实例，请先调用 createDriver()");
        }
        return driver;
    }

    private ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        options.setCapability("selenoid:options", new java.util.HashMap<String, Object>() {{
            put("enableVNC", false);
            put("enableVideo", false);
            put("sessionTimeout", "5m");
        }});
        return options;
    }

    private FirefoxOptions buildFirefoxOptions() {
        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--no-sandbox");
        options.setCapability("selenoid:options", new java.util.HashMap<String, Object>() {{
            put("enableVNC", false);
            put("enableVideo", false);
            put("sessionTimeout", "5m");
        }});
        return options;
    }
}
