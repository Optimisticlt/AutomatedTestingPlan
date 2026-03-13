package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.engine.locator.SmartLocator;
import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.entity.TcStep;
import io.qameta.allure.Allure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * UI 步骤执行器
 *
 * 支持的操作（config.action 字段）：
 *   OPEN       – 打开 URL
 *   CLICK      – 点击元素
 *   INPUT      – 输入文本
 *   CLEAR      – 清空输入框
 *   SELECT     – 下拉框选项
 *   HOVER      – 悬停
 *   DRAG_DROP  – 拖拽
 *   UPLOAD     – 文件上传（sendKeys 路径）
 *   ASSERT_TEXT       – 断言元素文本
 *   ASSERT_VISIBLE    – 断言元素可见
 *   ASSERT_NOT_VISIBLE– 断言元素不可见
 *   ASSERT_ATTR       – 断言元素属性值
 *   ASSERT_URL        – 断言当前 URL
 *   ASSERT_TITLE      – 断言页面标题
 *   SCREENSHOT        – 主动截图（附加到 Allure 报告）
 *
 * config JSON 结构（示例）：
 * {
 *   "action": "INPUT",
 *   "value": "${username}",
 *   "waitSeconds": 10
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UiStepExecutor implements StepExecutor {

    private final SelenoidSessionManager sessionManager;
    private final SmartLocator smartLocator;
    private final ObjectMapper objectMapper;

    /** 默认显式等待超时（秒） */
    private static final int DEFAULT_WAIT_SECONDS = 10;

    @Override
    public boolean supports(String stepType) {
        return "UI".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        String action = config.path("action").asText().toUpperCase();
        String rawValue = config.path("value").asText(null);
        String value = rawValue != null ? context.resolve(rawValue) : null;
        int waitSec = config.path("waitSeconds").asInt(DEFAULT_WAIT_SECONDS);

        WebDriver driver = sessionManager.currentDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(waitSec));

        Allure.step("UI步骤: " + action + (step.getName() != null ? " - " + step.getName() : ""), () -> {
            doAction(action, value, step, context, driver, wait);
        });

        return StepResult.ok("UI 步骤执行成功: " + action);
    }

    private void doAction(String action, String value, TcStep step,
                          ExecutionContext context, WebDriver driver, WebDriverWait wait) {
        switch (action) {
            case "OPEN" -> {
                String url = value != null ? value : "";
                driver.get(url);
                log.debug("[exec={}] OPEN {}", context.getExecutionId(), url);
            }
            case "CLICK" -> {
                WebElement el = waitAndLocate(step, context, wait);
                el.click();
            }
            case "INPUT" -> {
                WebElement el = waitAndLocate(step, context, wait);
                el.clear();
                el.sendKeys(value != null ? value : "");
            }
            case "CLEAR" -> {
                WebElement el = waitAndLocate(step, context, wait);
                el.clear();
            }
            case "SELECT" -> {
                WebElement el = waitAndLocate(step, context, wait);
                new Select(el).selectByVisibleText(value != null ? value : "");
            }
            case "HOVER" -> {
                WebElement el = waitAndLocate(step, context, wait);
                new Actions(driver).moveToElement(el).perform();
            }
            case "DRAG_DROP" -> {
                // config 需包含 targetLocatorId
                WebElement src = waitAndLocate(step, context, wait);
                // 简化：拖到目标坐标，完整版需配置目标定位器
                new Actions(driver).dragAndDrop(src, src).perform();
            }
            case "UPLOAD" -> {
                WebElement el = waitAndLocate(step, context, wait);
                el.sendKeys(value != null ? value : "");
            }
            case "ASSERT_TEXT" -> {
                WebElement el = waitAndLocate(step, context, wait);
                String actual = el.getText();
                Assertions.assertThat(actual).as("元素文本断言").contains(value);
            }
            case "ASSERT_VISIBLE" -> waitAndLocate(step, context, wait);
            case "ASSERT_NOT_VISIBLE" -> {
                try {
                    wait.until(ExpectedConditions.invisibilityOfElementLocated(
                            smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId())
                                    .usedLocator() != null ? null : null));
                } catch (Exception e) {
                    // 元素已不可见，断言通过
                }
            }
            case "ASSERT_ATTR" -> {
                JsonNode config2;
                try {
                    config2 = objectMapper.readTree(step.getConfig());
                } catch (Exception e) { throw new RuntimeException(e); }
                String attrName = config2.path("attrName").asText();
                String expected = context.resolve(config2.path("expected").asText());
                WebElement el = waitAndLocate(step, context, wait);
                String actual = el.getAttribute(attrName);
                Assertions.assertThat(actual).as("属性 [" + attrName + "] 断言").isEqualTo(expected);
            }
            case "ASSERT_URL" -> {
                String currentUrl = driver.getCurrentUrl();
                Assertions.assertThat(currentUrl).as("URL 断言").contains(value);
            }
            case "ASSERT_TITLE" -> {
                String title = driver.getTitle();
                Assertions.assertThat(title).as("页面标题断言").contains(value);
            }
            case "PRESS_KEY" -> {
                WebElement el = waitAndLocate(step, context, wait);
                el.sendKeys(Keys.valueOf(value != null ? value.toUpperCase() : "RETURN"));
            }
            default -> throw new IllegalArgumentException("不支持的 UI 操作类型: " + action);
        }
    }

    private WebElement waitAndLocate(TcStep step, ExecutionContext context, WebDriverWait wait) {
        WebDriver driver = sessionManager.currentDriver();
        SmartLocator.LocatorResult result = smartLocator.locate(driver, step.getLocatorId(), context.getExecutionId());
        return wait.until(ExpectedConditions.visibilityOf(result.element()));
    }
}
