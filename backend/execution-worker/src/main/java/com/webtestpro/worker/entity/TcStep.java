package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试步骤实体
 * 对应 tc_step 表，一个用例包含多个有序步骤。
 *
 * 步骤类型（step_type）：
 *   UI        – 浏览器操作（点击/输入/断言/截图等）
 *   API       – HTTP 接口请求
 *   DB        – 数据库断言（只读，PreparedStatement）
 *   WAIT      – 等待策略（显式/休眠/条件轮询）
 *   EXTRACT   – 变量提取
 *   CONDITION – 条件分支（if/else）
 *   SCRIPT    – 自定义脚本（沙箱执行，须 ADMIN 审批）
 *   SUB_CASE  – 子用例调用
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_step")
public class TcStep extends BaseEntity {

    private Long caseId;

    /** 步骤在用例内的顺序（从 1 开始） */
    private Integer stepOrder;

    /** 步骤类型：UI/API/DB/WAIT/EXTRACT/CONDITION/SCRIPT/SUB_CASE */
    private String stepType;

    /** 步骤名称（用于报告展示） */
    private String name;

    /** 步骤配置（JSON，结构依 stepType 而定） */
    private String config;

    /** UI 步骤关联的定位器 ID */
    private Long locatorId;

    /** 步骤级失败重试次数（0=使用用例级配置） */
    private Integer retryTimes;

    /** SCRIPT 步骤是否已经 ADMIN 审批（0=未审批，1=已审批） */
    private Integer scriptApproved;

    /** 条件分支：父步骤 ID（CONDITION 子步骤使用） */
    private Long parentStepId;

    /** 条件分支：分支类型 THEN / ELSE */
    private String branchType;

    /** 子用例调用：被调用的 case_id */
    private Long subCaseId;
}
