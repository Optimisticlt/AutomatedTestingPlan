package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.locator.SmartLocator;
import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.TcStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Component;

/**
 * EXTRACT 步骤执行器（变量提取）
 *
 * 从页面元素或当前 URL/Cookie 中提取值，写入 ExecutionContext 运行时变量。
 *
 * config JSON 示例：
 * {
 *   "source": "ELEMENT_TEXT",   // 提取来源
 *   "varName": "orderNo",       // 写入变量名
 *   "attribute": "value"        // source=ELEMENT_ATTR 时用
 * }
 *
 * source 类型：
 *   ELEMENT_TEXT  – 元素 innerText
 *   ELEMENT_ATTR  – 元素属性值（需配置 attribute 字段）
 *   CURRENT_URL   – 当前 URL
 *   PAGE_TITLE    – 页面标题
 *   COOKIE        – Cookie 值（需配置 cookieName 字段）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractStepExecutor implements StepExecutor {

    private final SelenoidSessionManager sessionManager;
    private final SmartLocator smartLocator;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String stepType) {
        return "EXTRACT".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        String source = config.path("source").asText("ELEMENT_TEXT").toUpperCase();
        String varName = config.path("varName").asText();

        if (varName.isBlank()) {
            throw new IllegalArgumentException("EXTRACT 步骤必须指定 varName");
        }

        WebDriver driver = sessionManager.currentDriver();
        String extracted;

        extracted = switch (source) {
            case "ELEMENT_TEXT" -> {
                SmartLocator.LocatorResult result = smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId());
                yield result.element().getText();
            }
            case "ELEMENT_ATTR" -> {
                String attr = config.path("attribute").asText("value");
                SmartLocator.LocatorResult result = smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId());
                WebElement el = result.element();
                yield el.getAttribute(attr);
            }
            case "CURRENT_URL" -> driver.getCurrentUrl();
            case "PAGE_TITLE" -> driver.getTitle();
            case "COOKIE" -> {
                String cookieName = config.path("cookieName").asText();
                var cookie = driver.manage().getCookieNamed(cookieName);
                yield cookie != null ? cookie.getValue() : "";
            }
            default -> throw new IllegalArgumentException("不支持的 EXTRACT 来源: " + source);
        };

        context.setVariable(varName, extracted);
        log.debug("[exec={}] 提取变量 {}={}", context.getExecutionId(), varName, extracted);

        return StepResult.builder()
                .success(true)
                .message("提取变量 " + varName + " = " + extracted)
                .extractedKey(varName)
                .extractedValue(extracted)
                .build();
    }
}
