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
 * 系统用户实体
 * 对应 sys_user 表。
 * passwordHash 使用 BCrypt 存储，phone 存储明文（可选），phoneHash 存储 HMAC-SHA256 哈希用于索引查找。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 用户名（唯一） */
    private String username;

    /** 密码 BCrypt 哈希（不输出到日志） */
    @ToString.Exclude
    @TableField("password_hash")
    private String passwordHash;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 手机号明文（可选，不输出到日志） */
    @ToString.Exclude
    private String phone;

    /** 手机号 HMAC-SHA256 哈希，用于索引查找 */
    @TableField("phone_hash")
    private String phoneHash;

    /** 账户状态：0=正常，1=禁用 */
    private Integer status;

    /** 最后登录 IP */
    @TableField("last_login_ip")
    private String lastLoginIp;

    /** 最后登录时间 */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;
}
