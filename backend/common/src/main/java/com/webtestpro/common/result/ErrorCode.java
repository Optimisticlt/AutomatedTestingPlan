package com.webtestpro.common.result;

import lombok.Getter;

/**
 * 业务错误码枚举
 * 规范：1xxxx 系统级 / 2xxxx 认证授权 / 3xxxx 业务通用 / 4xxxx 执行引擎
 */
@Getter
public enum ErrorCode {

    // ---- 系统级 ----
    SYSTEM_ERROR(10001, "系统内部错误"),
    PARAM_ERROR(10002, "请求参数错误"),
    OPTIMISTIC_LOCK_CONFLICT(10003, "操作冲突，请刷新后重试"),

    // ---- 认证授权 ----
    UNAUTHORIZED(20001, "未登录或 Token 已过期"),
    FORBIDDEN(20002, "无权限执行此操作"),
    TOKEN_INVALID(20003, "Token 无效"),
    WEBHOOK_SIGN_FAIL(20004, "Webhook 签名验证失败"),
    NONCE_REPLAY(20005, "请求已被使用，禁止重放"),
    /** Webhook 综合鉴权失败（时间窗口 / 签名 / Nonce 任一不通过）统一对外返回此码，避免泄露具体失败原因 */
    WEBHOOK_AUTH_FAIL(20006, "Webhook 鉴权失败"),

    // ---- 业务通用 ----
    RESOURCE_NOT_FOUND(30001, "资源不存在"),
    RESOURCE_ALREADY_EXISTS(30002, "资源已存在"),
    OPERATION_NOT_ALLOWED(30003, "当前状态不允许此操作"),

    // ---- 用户管理 ----
    USER_NOT_FOUND(30004, "用户不存在"),
    USER_DISABLED(30005, "账号已禁用"),
    PASSWORD_WRONG(30006, "密码错误"),

    // ---- 用例管理 ----
    CASE_REVIEW_NOT_APPROVED(31001, "用例未通过评审，无法加入执行计划"),
    SCRIPT_NOT_APPROVED(31002, "SCRIPT 步骤未经 ADMIN 审批，无法执行"),

    // ---- 执行引擎 ----
    EXECUTION_QUEUE_FULL(40001, "执行队列已满，请稍后重试"),
    SELENOID_SESSION_EXHAUSTED(40002, "Selenoid 会话已用尽，请等待"),
    EXECUTION_STATUS_ILLEGAL(40003, "执行状态非法转移"),
    SQL_NOT_ALLOWED(40004, "仅允许 SELECT 语句"),
    SCRIPT_TIMEOUT(40005, "脚本执行超时（30s）"),
    MINIO_UNAVAILABLE(40006, "文件存储服务不可用，已降级处理");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
