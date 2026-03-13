package com.webtestpro.common.enums;

import lombok.Getter;

/**
 * 审计事件类型枚举
 *
 * 级别说明：
 *   NOTICE   — 正常操作，低优先级，用于追溯
 *   WARNING  — 可疑或失败操作，需关注
 *   CRITICAL — 高风险操作，需立即审查
 */
@Getter
public enum AuditEventType {

    // ── 认证类 ──────────────────────────────────────────────────────────────
    USER_LOGIN("NOTICE"),
    USER_LOGIN_FAIL("WARNING"),
    USER_LOGOUT("NOTICE"),

    // ── 权限变更 ────────────────────────────────────────────────────────────
    ROLE_CHANGE("CRITICAL"),
    USER_DISABLE("CRITICAL"),

    // ── 脚本审核 ────────────────────────────────────────────────────────────
    SCRIPT_APPROVE("CRITICAL"),

    // ── 密钥 / Token ────────────────────────────────────────────────────────
    SECRET_ACCESS("CRITICAL"),
    API_TOKEN_CREATE("CRITICAL"),
    API_TOKEN_REVOKE("CRITICAL"),

    // ── 执行计划 ────────────────────────────────────────────────────────────
    PLAN_TRIGGER("NOTICE"),
    PLAN_DELETE("WARNING"),

    // ── Webhook 安全 ────────────────────────────────────────────────────────
    WEBHOOK_AUTH_FAIL("WARNING"),

    // ── DDL 操作 ────────────────────────────────────────────────────────────
    PARTITION_DDL("WARNING");

    private final String level;

    AuditEventType(String level) {
        this.level = level;
    }
}
