package com.webtestpro.common.enums;

import lombok.Getter;

/**
 * 执行记录状态机（单一真相来源）
 * ADR M3：所有状态转移逻辑必须参照此枚举，禁止在 Service 层散落定义
 *
 * 合法转移：
 *   WAITING     → RUNNING, CANCELLED
 *   RUNNING     → PASS, FAIL, INTERRUPTED, CANCELLED
 *   INTERRUPTED → WAITING（断点续跑重新入队）
 *
 * 终态（不可转移）：PASS / FAIL / CANCELLED
 *
 * MySQL CHECK 约束：
 *   CHECK (status IN ('WAITING','RUNNING','PASS','FAIL','INTERRUPTED','CANCELLED'))
 */
@Getter
public enum ExecutionStatus {

    WAITING("WAITING", "等待执行", false),
    RUNNING("RUNNING", "执行中", false),
    PASS("PASS", "全部通过", true),
    FAIL("FAIL", "存在失败", true),
    INTERRUPTED("INTERRUPTED", "已中断（心跳超时）", false),
    CANCELLED("CANCELLED", "已取消", true);

    private final String code;
    private final String description;
    /** 是否为终态（终态不可再转移） */
    private final boolean terminal;

    ExecutionStatus(String code, String description, boolean terminal) {
        this.code = code;
        this.description = description;
        this.terminal = terminal;
    }

    /**
     * 校验状态转移是否合法
     * @param from 当前状态
     * @param to   目标状态
     * @return true 合法
     */
    public static boolean isTransitionAllowed(ExecutionStatus from, ExecutionStatus to) {
        if (from.isTerminal()) {
            return false; // 终态不可转移
        }
        return switch (from) {
            case WAITING     -> to == RUNNING || to == CANCELLED;
            case RUNNING     -> to == PASS || to == FAIL || to == INTERRUPTED || to == CANCELLED;
            case INTERRUPTED -> to == WAITING; // 断点续跑
            default          -> false;
        };
    }
}
