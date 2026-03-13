package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * API Token 实体
 * 对应 sys_api_token 表。
 * tokenHash 存储 SHA-256 哈希用于安全校验，原始 token 仅在创建时返回一次，不落库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_api_token")
public class SysApiToken extends BaseEntity {

    /** 所属用户 ID */
    private Long userId;

    /** Token 名称（用户自定义，便于管理） */
    private String tokenName;

    /** Token SHA-256 哈希（安全校验用，不输出到日志） */
    @ToString.Exclude
    @TableField("token_hash")
    private String tokenHash;

    /** Token 前 8 位明文（UI 展示用） */
    @TableField("token_prefix")
    private String tokenPrefix;

    /** Token 过期时间（null 表示永不过期） */
    @TableField("expired_at")
    private LocalDateTime expiredAt;

    /** 状态：0=active，1=revoked */
    private Integer status;
}
