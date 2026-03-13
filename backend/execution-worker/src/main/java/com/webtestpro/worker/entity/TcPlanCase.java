package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 计划-用例关联实体
 * 对应 tc_plan_case_relation 表（设计文档中称 tc_plan_case）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_plan_case_relation")
public class TcPlanCase extends BaseEntity {

    private Long planId;

    private Long caseId;

    /** 用例在计划内的执行顺序 */
    private Integer sortOrder;
}
