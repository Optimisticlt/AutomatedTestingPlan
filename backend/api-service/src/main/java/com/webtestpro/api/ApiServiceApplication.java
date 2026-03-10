package com.webtestpro.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * WebTestPro API Service 启动类
 * 负责：HTTP/WebSocket 请求响应、用例/项目/计划 CRUD、Sa-Token 鉴权、实时日志推流
 */
@SpringBootApplication(scanBasePackages = {"com.webtestpro.api", "com.webtestpro.common"})
@MapperScan({"com.webtestpro.api.mapper", "com.webtestpro.common.mapper"})
@EnableTransactionManagement
@EnableAsync
@EnableScheduling
public class ApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiServiceApplication.class, args);
    }
}
