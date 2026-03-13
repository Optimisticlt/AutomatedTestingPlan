package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试用例实体
 * 对应 tc_case 表，存在多人协作编辑冲突风险，须开启乐观锁 (@Version 继承自 BaseEntity)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_case")
public class TcCase extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 用例名称 */
    private String name;

    /** 用例描述 */
    private String description;

    /** 优先级：P0=冒烟 P1=核心 P2=全量 P3=扩展 */
    private String priority;

    /** 标签（逗号分隔） */
    private String tags;

    /** 评审状态：DRAFT / PENDING / APPROVED */
    private String reviewStatus;

    /** 创建人（用例负责人） */
    private Long ownerId;

    /** 关联环境 ID（默认执行环境） */
    private Long defaultEnvId;

    /** 参数化数据集 ID */
    private Long testDataId;

    /** 历史通过率（0-100） */
    private Integer passRate;

    /** 通过率统计样本数（最近 30 天） */
    private Integer passRateSampleCount;

    /** 是否自动隔离（Flaky 用例隔离标记） */
    private Integer autoIsolated;

    /** 步骤级别失败重试次数（优先于计划级配置，0=不重试） */
    private Integer retryTimes;
}
