package com.webtestpro.worker.engine.locator;

/**
 * 元素定位策略枚举
 * 按稳定性优先级降序排列，SmartLocator 依此顺序自动降级。
 *
 * 定位优先级（设计文档 § 2. 元素定位体系）：
 *   DATA_TESTID  评分5 – 最稳定，需开发配合加 data-testid 属性
 *   ID_NAME      评分4 – id/name 属性，表单元素通用
 *   CSS          评分3 – CSS Selector，较稳定
 *   XPATH        评分2 – XPath，灵活但脆弱，兜底
 *   IMAGE        评分1 – 图像识别，完全不依赖 DOM，最后手段
 */
public enum LocatorStrategy {

    DATA_TESTID(5, "data-testid"),
    ID_NAME(4, "id/name"),
    CSS(3, "CSS Selector"),
    XPATH(2, "XPath"),
    IMAGE(1, "图像识别");

    /** 稳定性评分（1-5，值越大越优先） */
    private final int stabilityScore;

    /** 策略描述（UI 展示用） */
    private final String description;

    LocatorStrategy(int stabilityScore, String description) {
        this.stabilityScore = stabilityScore;
        this.description = description;
    }

    public int getStabilityScore() { return stabilityScore; }
    public String getDescription() { return description; }
}
