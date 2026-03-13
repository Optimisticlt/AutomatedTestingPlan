package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 执行计划实体
 * 对应 tc_plan 表，计划包含多个用例，支持定时/手动/Webhook 触发。
 * 存在并发状态变更风险，继承乐观锁 (@Version)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_plan")
public class TcPlan extends BaseEntity {

    private Long projectId;

    private String name;

    private String description;

    /** 默认执行环境 ID */
    private Long defaultEnvId;

    /** 默认浏览器（chrome/firefox） */
    private String defaultBrowser;

    /** 计划级失败重试次数（步骤级优先） */
    private Integer retryTimes;

    /** 并发线程数（受 Selenoid 信号量约束） */
    private Integer parallelCount;

    /** 优先级：P0/P1/P2（决定入哪条 Redis 队列） */
    private String priority;

    /** XXL-JOB 定时 Cron 表达式（为空则不定时触发） */
    private String cronExpression;

    /** 关联 XXL-JOB 任务 ID */
    private Long xxlJobId;

    /** 计划状态：ACTIVE / PAUSED */
    private String status;

    /** 质量门禁：最低通过率阈值（0-100，低于此值阻断 Webhook 流水线） */
    private Integer qualityGatePassRate;
}
