package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用例执行结果实体
 * 对应 tc_case_result 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_case_result")
public class TcCaseResult extends BaseEntity {

    /** 所属执行记录 ID */
    private Long executionId;

    /** 执行的用例 ID */
    private Long caseId;

    /** 执行结果：PASS=通过，FAIL=失败，SKIP=跳过 */
    private String status;

    /** 失败错误信息 */
    private String errorMessage;

    /** 失败截图路径 */
    private String screenshotPath;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 实际重试次数（0=未重试） */
    private Integer retryCount;

    /** 该用例执行开始时间 */
    private LocalDateTime startTime;

    /** 该用例执行结束时间 */
    private LocalDateTime endTime;
}
