package com.webtestpro.worker.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置
 *
 * 对接 XXL-JOB Admin，注册当前 Worker 为执行器节点。
 * 执行器端口 9998（见 application.yml），与 Spring Server 端口 8081 分离。
 *
 * 注册的 Job Handler（@XxlJob 注解）：
 *   - watchdogJobHandler      – 每 30s 扫描心跳过期的 RUNNING 任务，标记 INTERRUPTED 并重新入队
 *   - semaphoreReconcileHandler – 每 60s 对账 Selenoid 信号量与 MySQL RUNNING 数量
 */
@Slf4j
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.executor.app-name}")
    private String appName;

    @Value("${xxl.job.executor.address:}")
    private String address;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port}")
    private int port;

    @Value("${xxl.job.executor.log-path}")
    private String logPath;

    @Value("${xxl.job.executor.log-retention-days}")
    private int logRetentionDays;

    @Value("${xxl.job.access-token}")
    private String accessToken;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        executor.setAccessToken(accessToken);
        log.info("XXL-JOB Executor 初始化：appName={}, adminAddresses={}, port={}",
                appName, adminAddresses, port);
        return executor;
    }
}
