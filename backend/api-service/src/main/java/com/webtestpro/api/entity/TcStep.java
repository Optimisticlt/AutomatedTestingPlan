package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试步骤实体
 * 对应 tc_step 表。
 * config 字段存储步骤执行配置的 JSON，结构依据 stepType 不同而不同。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_step")
public class TcStep extends BaseEntity {

    /** 所属用例 ID */
    private Long caseId;

    /** 步骤执行顺序（同级步骤内排序） */
    private Integer stepOrder;

    /**
     * 步骤类型：
     * UI=界面操作, API=接口请求, DB=数据库操作, WAIT=等待,
     * EXTRACT=变量提取, CONDITION=条件分支, SCRIPT=自定义脚本, SUB_CASE=子用例引用
     */
    private String stepType;

    /** 步骤名称 */
    private String name;

    /** 步骤执行配置（JSON，结构随 stepType 变化） */
    private String config;

    /** 关联定位器 ID（UI 类型步骤使用） */
    private Long locatorId;

    /** 步骤失败重试次数（0=不重试） */
    private Integer retryTimes;

    /** 脚本是否已审核（仅 SCRIPT 类型有效）：0=未审核，1=已审核 */
    private Integer scriptApproved;

    /** 父步骤 ID（条件分支子步骤使用，null 表示顶层步骤） */
    private Long parentStepId;

    /** 分支类型（条件分支子步骤）：THEN=真分支，ELSE=假分支 */
    private String branchType;

    /** 引用的子用例 ID（SUB_CASE 类型步骤使用） */
    private Long subCaseId;
}
