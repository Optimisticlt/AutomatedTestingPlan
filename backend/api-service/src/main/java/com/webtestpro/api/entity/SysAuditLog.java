package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审计日志实体（append-only）
 * 对应 sys_audit_log 表。
 * 本实体不继承 BaseEntity，因为审计日志是只追加的记录，不需要逻辑删除、乐观锁等字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_audit_log")
public class SysAuditLog {

    /** 主键（Snowflake ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID */
    private Long tenantId;

    /** 事件类型（对应 AuditEventType 枚举 name()） */
    private String eventType;

    /** 操作人 ID */
    private Long operatorId;

    /** 操作人 IP 地址 */
    private String operatorIp;

    /** 操作目标类型（如 TcCase、TcPlan 等） */
    private String targetType;

    /** 操作目标 ID */
    private Long targetId;

    /** 操作详情（JSON 文本） */
    private String detail;

    /** 记录创建时间 */
    private LocalDateTime createdTime;
}
