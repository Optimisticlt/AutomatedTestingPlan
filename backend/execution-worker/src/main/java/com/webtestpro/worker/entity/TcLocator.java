package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 元素定位器实体
 * 对应 tc_locator 表，一个步骤可配置多种定位策略，引擎按优先级自动降级。
 *
 * 定位优先级（strategy 字段）：
 *   DATA_TESTID  评分5 – 最稳定，需开发配合
 *   ID_NAME      评分4 – id/name 属性，表单元素通用
 *   CSS          评分3 – CSS Selector
 *   XPATH        评分2 – XPath，灵活但脆弱
 *   IMAGE        评分1 – 图像识别，最后手段
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_locator")
public class TcLocator extends BaseEntity {

    private Long stepId;

    /** 定位策略：DATA_TESTID / ID_NAME / CSS / XPATH / IMAGE */
    private String strategy;

    /** 定位器值（XPath 表达式 / CSS 选择器 / testid 值 / 图像 file_key） */
    private String value;

    /** 稳定性评分（1-5，UI 展示用颜色区分） */
    private Integer stabilityScore;

    /** 是否是默认/首选定位器（1=是） */
    private Integer isPrimary;

    /** 最近一次命中此定位器的执行 ID（健康检查/命中层级统计用） */
    private Long lastHitExecutionId;
}
