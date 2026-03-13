package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试计划用例关联实体
 * 对应 tc_plan_case 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_plan_case")
public class TcPlanCase extends BaseEntity {

    /** 所属计划 ID */
    private Long planId;

    /** 关联用例 ID */
    private Long caseId;

    /** 计划内排序序号（升序执行） */
    private Integer sortOrder;
}
