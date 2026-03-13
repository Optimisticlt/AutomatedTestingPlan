package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * UI 元素定位器实体
 * 对应 tc_locator 表。
 * stabilityScore 由执行引擎统计，score 越高表示该定位器越可靠。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_locator")
public class TcLocator extends BaseEntity {

    /** 所属步骤 ID */
    private Long stepId;

    /**
     * 定位策略：
     * DATA_TESTID=data-testid 属性, ID_NAME=id 或 name 属性,
     * CSS=CSS 选择器, XPATH=XPath 表达式, IMAGE=图像识别
     */
    private String strategy;

    /** 定位器值 */
    private String value;

    /** 稳定性评分（0-100） */
    private Integer stabilityScore;

    /** 是否为主定位器：0=备用，1=主定位器 */
    private Integer isPrimary;

    /** 最近一次成功命中时的执行 ID */
    private Long lastHitExecutionId;
}
