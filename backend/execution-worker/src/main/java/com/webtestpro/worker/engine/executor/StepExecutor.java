package com.webtestpro.worker.engine.executor;

import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.entity.TcStep;

/**
 * 步骤执行器接口
 * 每种步骤类型对应一个实现，通过 stepType 匹配。
 */
public interface StepExecutor {

    /**
     * 判断本执行器是否处理指定步骤类型。
     */
    boolean supports(String stepType);

    /**
     * 执行步骤，成功返回 StepResult，失败抛出异常（由 CaseRunner 统一捕获）。
     *
     * @param step    步骤实体
     * @param context 执行上下文（变量沙箱）
     * @return 步骤执行结果
     */
    StepResult execute(TcStep step, ExecutionContext context) throws Exception;
}
