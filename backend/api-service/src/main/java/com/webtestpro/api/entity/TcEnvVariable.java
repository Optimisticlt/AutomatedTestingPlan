package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 环境变量实体
 * 对应 tc_env_variable 表。
 * isEncrypted=1 时，varValue 存储 AES-256-GCM 密文，keyVersion 标记密钥版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_env_variable")
public class TcEnvVariable extends BaseEntity {

    /** 所属环境 ID */
    private Long envId;

    /** 变量键名 */
    private String varKey;

    /** 变量值（isEncrypted=1 时为 AES-256-GCM 密文） */
    private String varValue;

    /** 是否加密：0=明文，1=加密 */
    private Integer isEncrypted;

    /** 加密密钥版本（用于密钥轮转，isEncrypted=0 时为 null） */
    private String keyVersion;
}
