package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行日志实体
 * 对应 tc_execution_log 分区表（按月 RANGE 分区）。
 * append-only，无 is_deleted/version/updated_by/updated_time（豁免 BaseEntity）。
 * 唯一键：(execution_id, log_index)，INSERT 使用 ON DUPLICATE KEY IGNORE 保幂等。
 */
@Data
@TableName("tc_execution_log")
public class TcExecutionLog {

    private Long id;
    private Long executionId;

    /** 单次执行内的日志顺序号（断线续传用） */
    private Integer logIndex;

    /** 关联步骤序号（null 表示执行级日志） */
    private Integer stepIndex;

    /** 日志级别：INFO / WARN / ERROR / DEBUG */
    private String level;

    /** 日志内容（TEXT） */
    private String content;

    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdTime;
}
