package com.webtestpro.api.service;

import com.webtestpro.api.entity.SysAuditLog;
import com.webtestpro.api.mapper.SysAuditLogMapper;
import com.webtestpro.common.enums.AuditEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务（append-only）
 *
 * 特性：
 *   - 所有写入为异步（@Async），不阻塞主请求链路
 *   - 不使用 MyBatis-Plus 自动填充（SysAuditLog 不继承 BaseEntity），手动填充字段
 *   - 绑定 AuditEventType 枚举（每种事件对应固定 level：NOTICE / WARNING / CRITICAL）
 *   - 不支持 UPDATE / DELETE，记录一旦写入不可修改（append-only 设计）
 *   - tenantId 必须由调用方传入（不从 TenantContext 读取，防止 ThreadLocal 在异步线程丢失）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogMapper auditLogMapper;

    /**
     * 记录审计事件（异步写入，不阻塞调用方）。
     *
     * @param eventType  审计事件类型
     * @param tenantId   租户 ID
     * @param operatorId 操作人 ID（未知时传 0L）
     * @param operatorIp 操作人 IP
     * @param targetType 操作目标类型（如 "TcCase", "TcPlan"），null 表示无具体目标
     * @param targetId   操作目标 ID，null 表示无具体目标
     * @param detail     操作详情（JSON 文本），可为 null
     */
    @Async
    public void log(AuditEventType eventType, Long tenantId, Long operatorId,
                    String operatorIp, String targetType, Long targetId, String detail) {
        try {
            SysAuditLog entry = SysAuditLog.builder()
                    .eventType(eventType.name())
                    .tenantId(tenantId != null ? tenantId : 0L)
                    .operatorId(operatorId != null ? operatorId : 0L)
                    .operatorIp(operatorIp)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .createdTime(LocalDateTime.now())
                    .build();
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 审计日志失败不应影响主业务流程
            log.error("审计日志写入失败: eventType={}, tenantId={}, operatorId={}",
                    eventType, tenantId, operatorId, e);
        }
    }

    /**
     * 便捷方法：记录不涉及具体业务对象的审计事件（如登录、登出）。
     */
    @Async
    public void log(AuditEventType eventType, Long tenantId, Long operatorId, String operatorIp) {
        log(eventType, tenantId, operatorId, operatorIp, null, null, null);
    }
}
