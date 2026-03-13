package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 通知配置实体
 * 对应 sys_notify_config 表。
 * configDetail 字段存储加密后的 JSON（含 webhook URL、鉴权 token 等敏感信息）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_notify_config")
public class SysNotifyConfig extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 通知类型：DINGTALK / EMAIL / WEBHOOK */
    private String notifyType;

    /** 通知配置详情（加密后的 JSON） */
    private String configDetail;

    /** 状态：0=启用，1=禁用 */
    private Integer status;
}
