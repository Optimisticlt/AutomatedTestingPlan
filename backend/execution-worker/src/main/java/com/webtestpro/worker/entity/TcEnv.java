package com.webtestpro.worker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 执行环境实体
 * 对应 tc_env 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_env")
public class TcEnv extends BaseEntity {

    private Long projectId;

    private String name;

    /** 基础 URL（如 http://test.app.com） */
    private String baseUrl;

    /** 浏览器（chrome/firefox） */
    private String browser;

    /** 健康检查 URL */
    private String healthCheckUrl;

    /** 执行前钩子脚本（GraalVM JS，须 ADMIN 审批） */
    private String preExecScript;

    /** 执行后钩子脚本 */
    private String postExecScript;

    /** 钩子脚本是否已通过 ADMIN 审批（0=未审批，未审批不可触发执行计划） */
    private Integer hookApproved;
}
