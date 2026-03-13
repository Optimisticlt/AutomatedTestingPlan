package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试用例实体
 * 对应 tc_case 表。
 * autoIsolated=1 表示 Flaky 用例被系统自动隔离，不参与常规执行计划。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_case")
public class TcCase extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 用例名称 */
    private String name;

    /** 用例描述 */
    private String description;

    /** 优先级：P0=冒烟，P1=核心，P2=全量，P3=扩展 */
    private String priority;

    /** 标签（逗号分隔） */
    private String tags;

    /** 评审状态：DRAFT / PENDING / APPROVED */
    private String reviewStatus;

    /** 用例负责人 ID */
    private Long ownerId;

    /** 默认执行环境 ID */
    private Long defaultEnvId;

    /** 参数化数据集 ID */
    private Long testDataId;

    /** 历史通过率（0-100） */
    private Integer passRate;

    /** 通过率统计样本数 */
    private Integer passRateSampleCount;

    /** 是否自动隔离 Flaky 用例：0=否，1=是 */
    private Integer autoIsolated;

    /** 步骤级别失败重试次数（0=不重试） */
    private Integer retryTimes;
}
