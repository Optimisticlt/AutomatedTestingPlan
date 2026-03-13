package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.sandbox.GroovySandboxExecutor;
import com.webtestpro.worker.engine.sandbox.JsSandbox;
import com.webtestpro.worker.entity.TcStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SCRIPT 步骤执行器
 *
 * 安全要求（设计文档 §6.3）：
 *   1. 执行前检查 step.scriptApproved == 1（ADMIN 审批），否则阻断执行
 *   2. JS 脚本使用 GraalVM Polyglot JsSandbox（30s 超时，无网络/IO/线程）
 *   3. Groovy 脚本使用 GroovySandboxExecutor（SandboxTransformer + SecureASTCustomizer）
 *
 * config JSON 示例：
 * {
 *   "lang": "js",                   // js 或 groovy
 *   "source": "var x = 1; x + 1;",  // 脚本源码
 *   "extractVar": "result"           // 可选：将脚本返回值写入变量
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptStepExecutor implements StepExecutor {

    private final JsSandbox jsSandbox;
    private final GroovySandboxExecutor groovySandboxExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String stepType) {
        return "SCRIPT".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        // 强制校验审批状态
        if (step.getScriptApproved() == null || step.getScriptApproved() != 1) {
            throw new SecurityException(
                    "SCRIPT 步骤 [id=" + step.getId() + "] 未经 ADMIN 审批，禁止执行（script_approved=0）");
        }

        JsonNode config = objectMapper.readTree(step.getConfig());
        String lang = config.path("lang").asText("js").toLowerCase();
        String source = config.path("source").asText();
        String extractVar = config.path("extractVar").asText(null);

        if (source.isBlank()) {
            return StepResult.ok("SCRIPT 步骤：脚本为空，跳过");
        }

        // 变量替换（将上下文变量注入脚本头部）
        String resolvedSource = injectContextVariables(source, context);

        String resultValue = switch (lang) {
            case "js" -> jsSandbox.execute(resolvedSource);
            case "groovy" -> groovySandboxExecutor.execute(resolvedSource);
            default -> throw new IllegalArgumentException("不支持的脚本语言: " + lang + "（支持 js/groovy）");
        };

        if (extractVar != null && !extractVar.isBlank() && resultValue != null) {
            context.setVariable(extractVar, resultValue);
        }

        return StepResult.builder()
                .success(true)
                .message("SCRIPT [" + lang + "] 执行完成，返回: " + resultValue)
                .extractedKey(extractVar)
                .extractedValue(resultValue)
                .build();
    }

    /**
     * 将执行上下文变量注入脚本头部（以 var 声明形式）。
     * 仅注入当前运行时变量（不含 Faker，减少脚本污染）。
     */
    private String injectContextVariables(String source, ExecutionContext context) {
        StringBuilder vars = new StringBuilder();
        context.getAllVariables().forEach((k, v) -> {
            // 只注入合法标识符（防止 key 含特殊字符破坏语法）
            if (k.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                String escaped = v.replace("\\", "\\\\").replace("'", "\\'");
                vars.append("var ").append(k).append(" = '").append(escaped).append("';\n");
            }
        });
        return vars + source;
    }
}
