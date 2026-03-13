package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 执行产物元数据（截图、HAR、HTML 快照、Allure 报告）
 * 对应 tc_execution_artifact 表，append-only，不含 is_deleted/version（继承 BaseEntity 但行为特殊）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_execution_artifact")
public class TcExecutionArtifact extends BaseEntity {

    private Long executionId;

    /** 关联的 case_result_id（null 表示计划级产物，如 Allure 总报告） */
    private Long caseResultId;

    /** 产物类型：SCREENSHOT / HTML_SNAPSHOT / HAR / ALLURE_REPORT */
    private String artifactType;

    /** MinIO bucket 名称 */
    private String bucket;

    /** MinIO 对象 key */
    private String fileKey;

    /** 文件大小（字节） */
    private Long fileSizeBytes;

    /** 内容类型（MIME） */
    private String contentType;

    /** 产物描述（如步骤名称） */
    private String description;
}
