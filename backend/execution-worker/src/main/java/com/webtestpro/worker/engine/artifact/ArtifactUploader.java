package com.webtestpro.worker.engine.artifact;

import com.webtestpro.worker.entity.TcExecutionArtifact;
import com.webtestpro.worker.mapper.TcExecutionArtifactMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 执行产物上传器（截图 / HTML / HAR / Allure 报告）
 *
 * 所有上传通过 Resilience4j minio 熔断器保护（H6 MinIO 降级策略）：
 *   - 上传成功 → 写 tc_execution_artifact 元数据
 *   - 上传失败（熔断/超时）→ 降级为跳过截图（不阻断执行）
 *
 * MinIO Bucket 配置：
 *   wtp-screenshots – 失败截图
 *   wtp-reports     – Allure 报告
 *   wtp-snapshots   – 用例快照
 *   wtp-archive     – 归档数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactUploader {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final MinioClient minioClient;
    private final TcExecutionArtifactMapper artifactMapper;

    @Value("${minio.bucket.screenshot:wtp-screenshots}")
    private String screenshotBucket;

    @Value("${minio.bucket.report:wtp-reports}")
    private String reportBucket;

    /**
     * 截图并上传 MinIO（失败时降级为 null，不阻断执行）。
     *
     * @return MinIO file_key（失败时返回 null）
     */
    public String takeAndUploadScreenshot(WebDriver driver, Long executionId, Long caseResultId) {
        try {
            byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            String key = buildKey("screenshots", executionId, "png");
            return uploadBytes(screenshot, screenshotBucket, key, "image/png",
                    executionId, caseResultId, "SCREENSHOT", "失败截图");
        } catch (Exception e) {
            log.warn("[exec={}] 截图失败（降级跳过）: {}", executionId, e.getMessage());
            return null;
        }
    }

    /**
     * 上传页面 HTML 快照（失败时降级为 null）。
     */
    public String uploadHtmlSnapshot(String html, Long executionId, Long caseResultId) {
        try {
            byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String key = buildKey("html", executionId, "html");
            return uploadBytes(bytes, screenshotBucket, key, "text/html",
                    executionId, caseResultId, "HTML_SNAPSHOT", "页面 HTML 快照");
        } catch (Exception e) {
            log.warn("[exec={}] HTML 快照上传失败（降级跳过）: {}", executionId, e.getMessage());
            return null;
        }
    }

    /**
     * 上传任意字节流（受 Resilience4j minio 熔断保护）。
     */
    @CircuitBreaker(name = "minio", fallbackMethod = "uploadBytesFallback")
    private String uploadBytes(byte[] data, String bucket, String key, String contentType,
                               Long executionId, Long caseResultId,
                               String artifactType, String description) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(contentType)
                .build());

        // 写元数据到 MySQL
        TcExecutionArtifact artifact = new TcExecutionArtifact();
        artifact.setExecutionId(executionId);
        artifact.setCaseResultId(caseResultId);
        artifact.setArtifactType(artifactType);
        artifact.setBucket(bucket);
        artifact.setFileKey(key);
        artifact.setFileSizeBytes((long) data.length);
        artifact.setContentType(contentType);
        artifact.setDescription(description);
        artifactMapper.insert(artifact);

        log.debug("[exec={}] 上传产物 {} → {}/{}", executionId, artifactType, bucket, key);
        return key;
    }

    /** 熔断降级：跳过上传，不阻断执行流 */
    private String uploadBytesFallback(byte[] data, String bucket, String key, String contentType,
                                        Long executionId, Long caseResultId,
                                        String artifactType, String description, Throwable t) {
        log.error("[exec={}] MinIO 熔断，产物 {} 上传失败（已降级跳过）: {}", executionId, artifactType, t.getMessage());
        return null;
    }

    private String buildKey(String folder, Long executionId, String ext) {
        String date = LocalDateTime.now().format(DATE_FMT);
        return folder + "/" + date + "/" + executionId + "_" + UUID.randomUUID() + "." + ext;
    }
}
