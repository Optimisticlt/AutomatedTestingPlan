package com.webtestpro.api.controller;

import com.webtestpro.api.entity.TcExecution;
import com.webtestpro.api.service.ExecutionService;
import com.webtestpro.common.exception.BizException;
import com.webtestpro.common.result.ErrorCode;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Webhook 触发执行控制器
 *
 * 安全验证（三层）：
 *   1. HMAC-SHA256 签名验证：X-Webhook-Signature = "sha256={hex(hmac(secret, body))}"
 *   2. 时间窗口验证：X-Webhook-Timestamp 与服务器时间差 ≤ 300 秒
 *   3. Nonce 重放保护：X-Webhook-Nonce 存入 Redis DB2，SET NX EX 600，已存在则拒绝
 *
 * 触发方式：
 *   POST /api/v1/webhook/trigger/{planId}
 *   Header: X-Webhook-Signature, X-Webhook-Timestamp, X-Webhook-Nonce, X-Api-Token
 */
@Tag(name = "Webhook 触发")
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private static final String NONCE_KEY_PREFIX  = "webhook:nonce:";
    private static final long   TIMESTAMP_WINDOW  = 300L;  // 秒
    private static final long   NONCE_TTL_SECONDS = 600L;

    private final ExecutionService executionService;

    @Qualifier("redisTemplateLock")
    private final StringRedisTemplate redisTemplateLock;

    @Value("${webhook.secret:wtp-webhook-secret-2024}")
    private String webhookSecret;

    @Operation(summary = "Webhook 触发执行计划")
    @PostMapping("/trigger/{planId}")
    public Result<TcExecution> trigger(
            @PathVariable Long planId,
            @RequestParam(required = false) Long envId,
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestHeader("X-Webhook-Timestamp") String timestampStr,
            @RequestHeader("X-Webhook-Nonce")     String nonce,
            HttpServletRequest httpRequest) throws IOException {

        // 1. 读取请求体（用于签名验证）
        byte[] bodyBytes = StreamUtils.copyToByteArray(httpRequest.getInputStream());
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // 2. 时间窗口验证
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            log.warn("Webhook invalid timestamp: {}", timestampStr);
            throw new BizException(ErrorCode.WEBHOOK_AUTH_FAIL);
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestamp) > TIMESTAMP_WINDOW) {
            log.warn("Webhook timestamp out of window: timestamp={}, now={}", timestamp, now);
            throw new BizException(ErrorCode.WEBHOOK_AUTH_FAIL);
        }

        // 3. Nonce 重放保护
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean isNew = redisTemplateLock.opsForValue()
                .setIfAbsent(nonceKey, "1", NONCE_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Webhook nonce replay detected: nonce={}", nonce);
            throw new BizException(ErrorCode.WEBHOOK_AUTH_FAIL);
        }

        // 4. HMAC-SHA256 签名验证
        String expectedSig = computeHmacSha256(webhookSecret, timestampStr + "." + body);
        String expectedHeader = "sha256=" + expectedSig;
        if (!constantTimeEquals(expectedHeader, signature)) {
            log.warn("Webhook signature mismatch: planId={}", planId);
            throw new BizException(ErrorCode.WEBHOOK_AUTH_FAIL);
        }

        // 5. 触发执行
        TcExecution execution = executionService.trigger(planId, envId);
        log.info("Webhook triggered execution={} for planId={}", execution.getId(), planId);
        return Result.ok(execution);
    }

    private String computeHmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    /** 恒定时间字符串比较，防止时序攻击 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
