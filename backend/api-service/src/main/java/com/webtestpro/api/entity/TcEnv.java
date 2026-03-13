package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试环境实体
 * 对应 tc_env 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_env")
public class TcEnv extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 环境名称（如 dev、staging、production） */
    private String name;

    /** 环境描述 */
    private String description;

    /** 环境 Base URL */
    private String baseUrl;

    /** 是否为默认环境：0=否，1=是 */
    private Integer isDefault;
}
