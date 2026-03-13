package com.webtestpro.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池配置
 * executionThreadPool 用于 ExecutionOrchestrator.handleAsync()，
 * 每个执行记录在独立线程中运行，线程数受 Selenoid 信号量约束。
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    @Bean("executionThreadPool")
    public Executor executionThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("exec-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(270);
        executor.initialize();
        return executor;
    }
}
