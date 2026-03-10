package com.webtestpro.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * WebTestPro Execution Worker 启动类
 * 负责：TestNG 并行执行引擎、Selenium/RestAssured 测试驱动、Selenoid 会话管理、结果写库+MinIO上传
 * 与 API Service 进程分离，资源峰值互不影响
 */
@SpringBootApplication(scanBasePackages = {"com.webtestpro.worker", "com.webtestpro.common"})
@MapperScan({"com.webtestpro.worker.mapper", "com.webtestpro.common.mapper"})
@EnableTransactionManagement
@EnableAsync
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
