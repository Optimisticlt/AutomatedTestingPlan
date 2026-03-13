package com.webtestpro.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.webtestpro.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 参数化测试数据集实体
 * 对应 tc_test_data 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tc_test_data")
public class TcTestData extends BaseEntity {

    /** 所属项目 ID */
    private Long projectId;

    /** 数据集名称 */
    private String name;

    /** 数据集描述 */
    private String description;

    /** 数据行数（冗余字段） */
    private Integer rowCount;
}
