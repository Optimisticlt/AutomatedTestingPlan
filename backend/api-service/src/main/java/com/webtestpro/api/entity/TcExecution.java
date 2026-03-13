package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 执行记录实体
 * 对应 tc_execution 表。
 * Worker 和 Watchdog 并发更新状态，必须走乐观锁（@Version 继承自 BaseEntity）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_execution")
public class TcExecution extends BaseEntity {

    /** 所属执行计划 ID（null 表示单用例手动执行） */
    private Long planId;

    /** 所属项目 ID */
    private Long projectId;

    /** 执行使用的环境 ID */
    private Long envId;

    /** 执行状态（对应 ExecutionStatus 枚举） */
    private String status;

    /** 触发方式：MANUAL=手动，SCHEDULE=定时，WEBHOOK=外部触发 */
    private String triggerType;

    /** 触发人 ID */
    private Long triggeredBy;

    /** 执行开始时间 */
    private LocalDateTime startTime;

    /** 执行结束时间 */
    private LocalDateTime endTime;

    /** 执行总用例数 */
    private Integer totalCount;

    /** 通过用例数 */
    private Integer passCount;

    /** 失败用例数 */
    private Integer failCount;

    /** 执行开始时的环境变量快照（JSON MEDIUMTEXT） */
    @TableField("env_snapshot")
    private String envSnapshot;

    /** 断点续跑：已完成的 case_id 列表（逗号分隔 MEDIUMTEXT） */
    @TableField("checkpoint_completed_ids")
    private String checkpointCompletedIds;

    /** 执行优先级：P0 / P1 / P2 */
    private String priority;

    /** 分布式追踪 ID */
    private String traceId;
}
