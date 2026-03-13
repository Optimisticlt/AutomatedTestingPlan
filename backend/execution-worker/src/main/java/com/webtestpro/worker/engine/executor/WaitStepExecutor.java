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
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * WAIT 步骤执行器
 *
 * 等待策略（config.type）：
 *   SLEEP        – 固定休眠（不推荐，优先使用显式等待）
 *   VISIBLE      – 等待元素可见
 *   INVISIBLE    – 等待元素不可见
 *   URL_CONTAINS – 等待 URL 包含指定字符串
 *   TITLE_CONTAINS – 等待页面标题包含指定字符串
 *
 * config JSON 示例：
 * { "type": "VISIBLE", "timeoutSeconds": 15 }
 * { "type": "SLEEP", "milliseconds": 500 }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitStepExecutor implements StepExecutor {

    private final SelenoidSessionManager sessionManager;
    private final SmartLocator smartLocator;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String stepType) {
        return "WAIT".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        String type = config.path("type").asText("SLEEP").toUpperCase();
        int timeoutSec = config.path("timeoutSeconds").asInt(10);

        switch (type) {
            case "SLEEP" -> {
                int ms = config.path("milliseconds").asInt(1000);
                Thread.sleep(ms);
                return StepResult.ok("休眠 " + ms + "ms");
            }
            case "VISIBLE" -> {
                WebDriver driver = sessionManager.currentDriver();
                SmartLocator.LocatorResult result = smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId());
                new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                        .until(ExpectedConditions.visibilityOf(result.element()));
                return StepResult.ok("等待元素可见（" + timeoutSec + "s）");
            }
            case "INVISIBLE" -> {
                WebDriver driver = sessionManager.currentDriver();
                SmartLocator.LocatorResult result = smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId());
                new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                        .until(ExpectedConditions.invisibilityOf(result.element()));
                return StepResult.ok("等待元素不可见（" + timeoutSec + "s）");
            }
            case "URL_CONTAINS" -> {
                String urlPart = context.resolve(config.path("value").asText(""));
                WebDriver driver = sessionManager.currentDriver();
                new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                        .until(ExpectedConditions.urlContains(urlPart));
                return StepResult.ok("等待 URL 包含: " + urlPart);
            }
            case "TITLE_CONTAINS" -> {
                String titlePart = context.resolve(config.path("value").asText(""));
                WebDriver driver = sessionManager.currentDriver();
                new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                        .until(ExpectedConditions.titleContains(titlePart));
                return StepResult.ok("等待标题包含: " + titlePart);
            }
            default -> throw new IllegalArgumentException("不支持的 WAIT 类型: " + type);
        }
    }
}
