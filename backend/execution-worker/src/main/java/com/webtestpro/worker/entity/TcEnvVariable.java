package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 环境变量实体
 * 对应 tc_env_variable 表。
 * 敏感变量值（is_encrypted=1）使用 AES-256-GCM + AAD 加密存储，
 * AAD = "tc_env_variable:var_value:{tenantId}:{id}"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_env_variable")
public class TcEnvVariable extends BaseEntity {

    private Long envId;
    private Long projectId;

    /** 变量名（支持 ${变量名} 在步骤中引用） */
    private String varKey;

    /**
     * 变量值：
     *   is_encrypted=0 → 明文存储
     *   is_encrypted=1 → AES-256-GCM 密文（Base64(IV+密文+AuthTag)）
     */
    private String varValue;

    /** 是否加密存储（1=是） */
    private Integer isEncrypted;

    /** 加密密钥版本（Vault 轮换用） */
    private Integer keyVersion;

    private String description;
}
