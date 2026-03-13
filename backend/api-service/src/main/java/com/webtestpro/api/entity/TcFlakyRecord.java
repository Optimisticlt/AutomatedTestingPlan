package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Flaky 用例统计实体
 * 对应 tc_flaky_record 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_flaky_record")
public class TcFlakyRecord extends BaseEntity {

    /** 关联用例 ID */
    private Long caseId;

    /** 所属项目 ID */
    private Long projectId;

    /** 统计周期内总运行次数 */
    private Integer totalRuns;

    /** 统计周期内失败次数 */
    private Integer failCount;

    /** Flaky 率（failCount / totalRuns，精度 4 位小数） */
    private BigDecimal flakyRate;

    /** 统计周期结束时间 */
    private LocalDateTime statsPeriodEnd;
}
