package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 系统角色实体
 * 对应 sys_role 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role")
public class SysRole extends BaseEntity {

    /** 角色编码（唯一标识，如 ADMIN、TESTER） */
    private String roleCode;

    /** 角色名称（展示用） */
    private String roleName;

    /** 角色描述 */
    private String description;

    /** 状态：0=正常，1=禁用 */
    private Integer status;
}
