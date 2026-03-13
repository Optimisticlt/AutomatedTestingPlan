package com.webtestpro.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密服务
 *
 * 设计：
 *   - 密钥从环境变量 ENCRYPTION_KEY 读取（Base64 编码的 32 字节）
 *   - IV = 12 字节随机（SecureRandom），每次加密生成不同 IV
 *   - AAD（附加认证数据）= "{tableName}:{columnName}:{tenantId}:{recordId}"，防止密文跨列移植
 *   - 密文格式（Base64）= IV(12) + ciphertext + GCM tag(16)
 *   - keyVersion 固定为 "v1"（密钥轮转时升为 "v2" 等）
 *
 * 使用方：
 *   String encrypted = encryptionService.encrypt(plaintext, tableName, columnName, tenantId, recordId);
 *   String decrypted = encryptionService.decrypt(encrypted, tableName, columnName, tenantId, recordId);
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH_BYTES  = 12;
    private static final int    TAG_LENGTH_BITS  = 128;
    public  static final String KEY_VERSION      = "v1";

    private final SecretKey secretKey;

    public EncryptionService(@Value("${encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("encryption.key not configured — using insecure fallback key (DEV ONLY)");
            base64Key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; // 32 zero bytes
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("encryption.key must decode to exactly 32 bytes (AES-256)");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文字段。
     *
     * @param plaintext  明文
     * @param tableName  表名（构建 AAD）
     * @param columnName 列名（构建 AAD）
     * @param tenantId   租户 ID（构建 AAD）
     * @param recordId   记录 ID（构建 AAD；新建记录尚无 ID 时传 0L）
     * @return Base64 密文（IV + ciphertext + tag）
     */
    public String encrypt(String plaintext, String tableName, String columnName,
                          Long tenantId, Long recordId) {
        if (plaintext == null) return null;
        try {
            byte[] iv = generateIV();
            byte[] aad = buildAad(tableName, columnName, tenantId, recordId);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 输出格式：IV(12) + ciphertext+tag
            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());

        } catch (Exception e) {
            throw new RuntimeException("Encryption failed for " + tableName + "." + columnName, e);
        }
    }

    /**
     * 解密密文字段。
     *
     * @param cipherBase64 Base64 密文（IV + ciphertext + tag）
     * @param tableName    表名（构建 AAD，必须与加密时一致）
     * @param columnName   列名（构建 AAD，必须与加密时一致）
     * @param tenantId     租户 ID（构建 AAD，必须与加密时一致）
     * @param recordId     记录 ID（构建 AAD，必须与加密时一致）
     * @return 明文
     */
    public String decrypt(String cipherBase64, String tableName, String columnName,
                          Long tenantId, Long recordId) {
        if (cipherBase64 == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH_BYTES);

            byte[] ciphertext = new byte[decoded.length - IV_LENGTH_BYTES];
            System.arraycopy(decoded, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            byte[] aad = buildAad(tableName, columnName, tenantId, recordId);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad);

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for " + tableName + "." + columnName, e);
        }
    }

    private byte[] generateIV() {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private byte[] buildAad(String tableName, String columnName, Long tenantId, Long recordId) {
        String aad = tableName + ":" + columnName + ":" + tenantId + ":" + recordId;
        return aad.getBytes(StandardCharsets.UTF_8);
    }
}
