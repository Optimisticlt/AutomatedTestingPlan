package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据源实体（DB 步骤断言用）
 * 对应 tc_datasource 表。
 * password 字段使用 AES-256-GCM 加密，AAD = "tc_datasource:password:{tenantId}:{id}"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_datasource")
public class TcDatasource extends BaseEntity {

    private Long projectId;

    private String name;

    /** JDBC URL（如 jdbc:mysql://host:3306/db） */
    private String jdbcUrl;

    /** 只读账号（DB 断言强制只读） */
    private String username;

    /** 密码（AES-256-GCM 密文） */
    private String password;

    /** 加密密钥版本 */
    private Integer keyVersion;

    /** 查询超时（秒，默认 10） */
    private Integer queryTimeout;

    /** 连接池最大连接数 */
    private Integer maxPoolSize;
}
