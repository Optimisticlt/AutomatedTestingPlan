package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次执行中各用例的执行结果
 * 对应 tc_case_result 表，append-only，豁免 BaseEntity 中 updated_* /version/is_deleted.
 */
@Data
@TableName("tc_case_result")
public class TcCaseResult {

    private Long id;
    private Long executionId;
    private Long caseId;
    private Long planId;
    private Long tenantId;

    /** 结果状态：PASS / FAIL / INTERRUPTED / CANCELLED */
    private String status;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 失败截图 MinIO file_key */
    private String screenshotKey;

    /** 失败时页面 HTML 的 MinIO file_key */
    private String htmlSnapshotKey;

    /** HAR 网络录制的 MinIO file_key */
    private String harKey;

    /** 失败错误消息（截断至 2000 字符） */
    private String errorMessage;

    /** 重试次数（实际执行了几次） */
    private Integer retryCount;

    private Long createdBy;
    private LocalDateTime createdTime;
}
