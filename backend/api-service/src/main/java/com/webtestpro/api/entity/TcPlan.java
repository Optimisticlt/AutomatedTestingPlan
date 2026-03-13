package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试计划实体
 * 对应 tc_plan 表。
 * cronExpr 非空时由调度器定时触发；parallelCount 控制并发 Worker 数量。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_plan")
public class TcPlan extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 计划名称 */
    private String name;

    /** 计划描述 */
    private String description;

    /** 定时触发 Cron 表达式（null 表示仅手动触发） */
    private String cronExpr;

    /** 计划状态：0=正常，1=禁用 */
    private Integer status;

    /** 执行优先级：P0 / P1 / P2 */
    private String priority;

    /** 默认执行环境 ID */
    private Long envId;

    /** 最大并发 Worker 数量（0 表示使用系统默认配置） */
    private Integer parallelCount;

    /**
     * 失败重试策略：
     * 0=不重试，1=失败后重试一次，2=失败后重试两次
     */
    private Integer retryPolicy;
}
