package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试项目实体
 * 对应 tc_project 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_project")
public class TcProject extends BaseEntity {

    /** 项目名称 */
    private String name;

    /** 项目描述 */
    private String description;

    /** 项目默认 Base URL */
    private String baseUrl;

    /** 项目状态：0=正常，1=归档 */
    private Integer status;
}
