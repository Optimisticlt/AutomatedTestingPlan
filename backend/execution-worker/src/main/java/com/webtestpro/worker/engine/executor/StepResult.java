package com.webtestpro.worker.engine.executor;

import lombok.Builder;
import lombok.Getter;

/**
 * 步骤执行结果 DTO
 */
@Getter
@Builder
public class StepResult {

    /** 是否成功 */
    private final boolean success;

    /** 提取的变量名（EXTRACT 步骤使用） */
    private final String extractedKey;

    /** 提取的变量值（EXTRACT 步骤使用） */
    private final String extractedValue;

    /** 执行日志消息（附加到执行日志） */
    private final String message;

    /** 条件分支：是否进入 THEN 分支（CONDITION 步骤使用） */
    private final Boolean conditionResult;

    public static StepResult ok() {
        return StepResult.builder().success(true).build();
    }

    public static StepResult ok(String message) {
        return StepResult.builder().success(true).message(message).build();
    }

    public static StepResult fail(String message) {
        return StepResult.builder().success(false).message(message).build();
    }
}
