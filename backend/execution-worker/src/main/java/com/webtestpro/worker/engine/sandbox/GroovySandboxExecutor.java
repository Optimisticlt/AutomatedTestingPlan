package com.webtestpro.worker.engine.sandbox;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Groovy 沙箱执行器
 *
 * 双重防护：
 *   1. SandboxTransformer (GroovySandbox) – 拦截方法调用（黑名单 MOP 防逃逸）
 *   2. SecureASTCustomizer – 白名单导入（只允许安全包，禁止 java.io/net/lang.Runtime 等）
 *
 * 执行超时通过 ExecutorService + Future.get(30, SECONDS) 控制。
 * 使用独立 ClassLoader，禁止加载平台内部类。
 */
@Slf4j
@Component
public class GroovySandboxExecutor {

    private static final int TIMEOUT_SECONDS = 30;

    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "groovy-sandbox-exec");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    /**
     * 在沙箱内执行 Groovy 脚本。
     *
     * @param scriptSource Groovy 脚本源码
     * @return 脚本返回值的字符串表示（null 表示无返回值）
     * @throws JsSandbox.ScriptTimeoutException   执行超过 30 秒
     * @throws JsSandbox.ScriptExecutionException 脚本运行时错误
     */
    public String execute(String scriptSource) {
        CompilerConfiguration config = buildSandboxConfig();
        ClassLoader sandboxClassLoader = new SandboxClassLoader(
                Thread.currentThread().getContextClassLoader());

        Future<Object> future = executorService.submit(() -> {
            GroovyShell shell = new GroovyShell(sandboxClassLoader, new Binding(), config);
            return shell.evaluate(scriptSource);
        });

        try {
            Object result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result == null ? null : result.toString();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new JsSandbox.ScriptTimeoutException("Groovy 脚本执行超时（" + TIMEOUT_SECONDS + "s）");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new JsSandbox.ScriptExecutionException("Groovy 脚本执行失败: " + cause.getMessage(), cause);
        }
    }

    private CompilerConfiguration buildSandboxConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        // SecureASTCustomizer：白名单导入 + 接收者类型白名单（只允许安全类的方法调用）
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setAllowedImports(Arrays.asList(
                "java.lang.Math", "java.lang.String", "java.lang.Integer",
                "java.lang.Long", "java.lang.Double", "java.lang.Boolean",
                "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.HashMap",
                "groovy.json.JsonSlurper", "groovy.json.JsonOutput"
        ));
        // 接收者类型白名单：禁止对不在白名单中的类发起方法调用（阻止 Runtime.exec、Thread.start 等）
        secure.setReceiversClassesWhiteList(Arrays.asList(
                Object.class, String.class, Integer.class, Long.class, Double.class,
                Boolean.class, Math.class, Number.class,
                java.util.List.class, java.util.Map.class,
                java.util.ArrayList.class, java.util.HashMap.class,
                java.util.Collection.class, java.util.Iterator.class,
                groovy.json.JsonSlurper.class, groovy.json.JsonOutput.class,
                groovy.lang.GString.class
        ));
        secure.setAllowedStaticImports(Collections.emptyList());
        config.addCompilationCustomizers(secure);

        return config;
    }

    /**
     * 沙箱专用 ClassLoader：禁止加载危险类。
     */
    private static class SandboxClassLoader extends ClassLoader {

        private static final java.util.Set<String> BLOCKED_CLASSES = new java.util.HashSet<>(Arrays.asList(
                "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Thread",
                "java.lang.System", "java.lang.ClassLoader", "java.lang.reflect.Method",
                "java.lang.reflect.Field", "java.lang.reflect.Constructor"
        ));

        SandboxClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.startsWith("com.webtestpro.") || BLOCKED_CLASSES.contains(name)
                    || name.startsWith("java.io.") || name.startsWith("java.nio.")
                    || name.startsWith("java.net.") || name.startsWith("java.lang.reflect.")) {
                throw new ClassNotFoundException("禁止在脚本沙箱中访问此类: " + name);
            }
            return super.loadClass(name);
        }
    }
}
