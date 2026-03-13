package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 测试数据源实体
 * 对应 tc_datasource 表。
 * dbPassword 使用 AES-256-GCM 加密存储，keyVersion 标记密钥版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_datasource")
public class TcDatasource extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 数据源名称 */
    private String name;

    /** JDBC 连接 URL */
    private String jdbcUrl;

    /** 数据库用户名 */
    private String dbUser;

    /** 数据库密码（AES-256-GCM 加密存储，不输出到日志） */
    @ToString.Exclude
    private String dbPassword;

    /** 加密密钥版本（用于密钥轮转） */
    private String keyVersion;
}
