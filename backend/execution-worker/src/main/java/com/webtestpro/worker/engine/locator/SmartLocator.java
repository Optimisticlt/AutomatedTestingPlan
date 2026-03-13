package com.webtestpro.worker.engine.locator;

import com.webtestpro.worker.entity.TcLocator;
import com.webtestpro.worker.mapper.TcLocatorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 智能多策略元素定位器
 *
 * 按稳定性评分从高到低尝试各定位策略，自动降级直至找到元素或全部失败。
 * 每次命中后更新 tc_locator.last_hit_execution_id 供健康检查使用。
 *
 * 定位优先级（见 LocatorStrategy 枚举）：
 *   DATA_TESTID(5) → ID_NAME(4) → CSS(3) → XPATH(2) → IMAGE(1)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartLocator {

    private final TcLocatorMapper locatorMapper;

    /**
     * 定位结果 DTO
     */
    public record LocatorResult(WebElement element, TcLocator usedLocator) {}

    /**
     * 按定位器列表顺序尝试定位，返回第一个成功的结果。
     *
     * @param driver      WebDriver 实例（ThreadLocal 持有）
     * @param stepId      步骤 ID（从 tc_locator 表加载定位器列表）
     * @param executionId 当前执行 ID（用于更新 last_hit_execution_id）
     * @return 定位结果（包含 WebElement 和命中的定位器）
     * @throws NoSuchElementException 所有策略均失败时抛出
     */
    public LocatorResult locate(WebDriver driver, Long stepId, Long executionId) {
        List<TcLocator> locators = locatorMapper.selectByStepIdOrdered(stepId);
        if (locators.isEmpty()) {
            throw new NoSuchElementException("步骤 [stepId=" + stepId + "] 没有配置定位器");
        }

        // 按稳定性评分降序排序（数据库已排序，此处作为安全兜底）
        locators.sort(Comparator.comparingInt(TcLocator::getStabilityScore).reversed());

        StringBuilder traceLog = new StringBuilder();
        for (TcLocator locator : locators) {
            try {
                By by = toBy(locator);
                WebElement element = driver.findElement(by);
                // 命中：更新统计
                recordHit(locator, executionId);
                log.debug("[stepId={}] 命中定位器 strategy={} value={}", stepId,
                        locator.getStrategy(), locator.getValue());
                return new LocatorResult(element, locator);
            } catch (NoSuchElementException e) {
                traceLog.append(String.format("[%s:%s 未找到] ", locator.getStrategy(), locator.getValue()));
            }
        }

        throw new NoSuchElementException(
                "步骤 [stepId=" + stepId + "] 所有定位策略均失败: " + traceLog);
    }

    /**
     * 将 TcLocator 转换为 Selenium By 对象。
     * IMAGE 策略不走 Selenium，由调用方单独处理（此处抛出 UnsupportedOperationException）。
     */
    private By toBy(TcLocator locator) {
        LocatorStrategy strategy = LocatorStrategy.valueOf(locator.getStrategy());
        String value = locator.getValue();
        return switch (strategy) {
            case DATA_TESTID -> By.cssSelector("[data-testid='" + value + "']");
            case ID_NAME -> {
                // 尝试 id 优先，回退 name（By.id 内部已包含 id 属性匹配）
                // 格式约定：value 直接是属性值，策略类型决定使用 id 还是 name
                // 用 "id:" 前缀区分；默认按 id 处理
                if (value.startsWith("name:")) {
                    yield By.name(value.substring(5));
                }
                yield By.id(value.startsWith("id:") ? value.substring(3) : value);
            }
            case CSS -> By.cssSelector(value);
            case XPATH -> By.xpath(value);
            case IMAGE -> throw new UnsupportedOperationException(
                    "IMAGE 定位策略需要 Ashot 图像识别，不支持转换为 By");
        };
    }

    /** 异步更新命中记录（不阻塞执行流） */
    private void recordHit(TcLocator locator, Long executionId) {
        try {
            TcLocator update = new TcLocator();
            update.setId(locator.getId());
            update.setLastHitExecutionId(executionId);
            locatorMapper.updateById(update);
        } catch (Exception e) {
            // 统计失败不影响执行，仅打印 warn
            log.warn("[locatorId={}] 更新命中记录失败: {}", locator.getId(), e.getMessage());
        }
    }
}
