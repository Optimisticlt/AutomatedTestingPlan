package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import com.webtestpro.common.enums.ExecutionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 执行记录实体
 * 对应 tc_execution 表。
 * Worker 和 Watchdog 并发更新状态，必须走乐观锁 (@Version 继承自 BaseEntity)。
 * 所有状态变更必须通过 ExecutionStatus.isTransitionAllowed() 校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_execution")
public class TcExecution extends BaseEntity {

    private Long planId;
    private Long projectId;
    private Long envId;

    /** 浏览器（chrome/firefox） */
    private String browser;

    /** 执行状态（单一真相来源，见 ExecutionStatus 枚举） */
    private ExecutionStatus status;

    /** 触发方式：MANUAL / SCHEDULE / WEBHOOK */
    private String triggerType;

    /** Webhook 触发时的来源 token（sys_api_token.id） */
    private Long triggerTokenId;

    /** 执行开始时间 */
    private LocalDateTime startTime;

    /** 执行结束时间 */
    private LocalDateTime endTime;

    /** 总用例数 */
    private Integer totalCases;

    /** 通过用例数 */
    private Integer passCases;

    /** 失败用例数 */
    private Integer failCases;

    /**
     * 执行开始时的环境变量快照（JSON MEDIUMTEXT）。
     * 明文变量存明文值，加密变量存密文，审计日志仅记录 [ENCRYPTED]。
     * 快照在 Worker 开始执行前原子写入，防止执行中途修改影响在途执行。
     */
    private String envSnapshot;

    /**
     * 断点续跑：已完成的 case_id 列表（逗号分隔 MEDIUMTEXT，最大约 84 万条）
     */
    private String checkpointCompletedIds;

    /**
     * 断点续跑：中断时正在执行的 case_id（可建索引 BIGINT）
     */
    private Long checkpointInterruptedId;

    /** 断点续跑记录时间 */
    private LocalDateTime checkpointTime;
}
