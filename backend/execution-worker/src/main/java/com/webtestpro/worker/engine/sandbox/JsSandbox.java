package com.webtestpro.worker.engine.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * GraalVM Polyglot JavaScript 沙箱执行器
 *
 * 安全配置（H1 已修复）：
 *   - allowAllAccess(false)    禁止访问宿主类
 *   - allowIO(NONE)            禁止文件系统访问
 *   - allowCreateThread(false) 禁止创建线程
 *   - 超时通过 context.close(true) 强制中止（Future.cancel 对 GraalVM 无效）
 *
 * 每次执行创建独立 Context（无状态，防止脚本间数据泄露）。
 * scheduler 为 Worker 级单例，复用线程池。
 */
@Slf4j
@Component
public class JsSandbox {

    /** 脚本执行超时（秒） */
    private static final int TIMEOUT_SECONDS = 30;

    /** 超时 killer 线程池（Worker 级单例） */
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> {
                    Thread t = new Thread(r, "js-sandbox-killer");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 在沙箱内执行 JavaScript 脚本。
     *
     * @param scriptSource JS 脚本源码
     * @return 脚本最后一个表达式的字符串值（null 表示无返回值）
     * @throws ScriptTimeoutException 脚本执行超过 30 秒
     * @throws ScriptExecutionException 脚本运行时错误
     */
    public String execute(String scriptSource) {
        Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        ScheduledFuture<?> killer = scheduler.schedule(() -> {
            log.warn("JS 脚本执行超时（{}s），强制关闭 Context", TIMEOUT_SECONDS);
            context.close(true);
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try {
            var result = context.eval("js", scriptSource);
            killer.cancel(false);
            return result.isNull() ? null : result.toString();
        } catch (PolyglotException e) {
            if (e.isCancelled()) {
                throw new ScriptTimeoutException("JS 脚本执行超时（" + TIMEOUT_SECONDS + "s）");
            }
            throw new ScriptExecutionException("JS 脚本执行失败: " + e.getMessage(), e);
        } finally {
            try { context.close(); } catch (Exception ignored) {}
        }
    }

    // ── 异常类 ────────────────────────────────────────────────────────────────

    public static class ScriptTimeoutException extends RuntimeException {
        public ScriptTimeoutException(String message) { super(message); }
    }

    public static class ScriptExecutionException extends RuntimeException {
        public ScriptExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
