package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.entity.TcStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CONDITION 步骤执行器（条件分支）
 *
 * 支持简单表达式求值，返回布尔结果，由 CaseRunner 根据结果决定执行 THEN 或 ELSE 分支。
 *
 * config JSON 示例：
 * {
 *   "left": "${orderStatus}",
 *   "operator": "EQUALS",
 *   "right": "PAID"
 * }
 *
 * operator 类型：
 *   EQUALS        – 相等
 *   NOT_EQUALS    – 不相等
 *   CONTAINS      – 包含（left.contains(right)）
 *   STARTS_WITH   – 前缀
 *   ENDS_WITH     – 后缀
 *   IS_EMPTY      – 为空
 *   IS_NOT_EMPTY  – 非空
 *   GREATER_THAN  – 数值比较（大于）
 *   LESS_THAN     – 数值比较（小于）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionStepExecutor implements StepExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String stepType) {
        return "CONDITION".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        String left = context.resolve(config.path("left").asText(""));
        String operator = config.path("operator").asText("EQUALS").toUpperCase();
        String right = context.resolve(config.path("right").asText(""));

        boolean result = evaluate(left, operator, right);
        log.debug("[exec={}] CONDITION [{}] {} [{}] = {}", context.getExecutionId(), left, operator, right, result);

        return StepResult.builder()
                .success(true)
                .conditionResult(result)
                .message("条件判断: [" + left + "] " + operator + " [" + right + "] → " + result)
                .build();
    }

    private boolean evaluate(String left, String operator, String right) {
        return switch (operator) {
            case "EQUALS" -> left.equals(right);
            case "NOT_EQUALS" -> !left.equals(right);
            case "CONTAINS" -> left.contains(right);
            case "STARTS_WITH" -> left.startsWith(right);
            case "ENDS_WITH" -> left.endsWith(right);
            case "IS_EMPTY" -> left.isBlank();
            case "IS_NOT_EMPTY" -> !left.isBlank();
            case "GREATER_THAN" -> {
                try {
                    yield Double.parseDouble(left) > Double.parseDouble(right);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("GREATER_THAN 需要数值类型，left=" + left + " right=" + right);
                }
            }
            case "LESS_THAN" -> {
                try {
                    yield Double.parseDouble(left) < Double.parseDouble(right);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("LESS_THAN 需要数值类型，left=" + left + " right=" + right);
                }
            }
            default -> throw new IllegalArgumentException("不支持的条件操作符: " + operator);
        };
    }
}
