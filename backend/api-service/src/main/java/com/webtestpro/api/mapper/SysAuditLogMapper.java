package com.webtestpro.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.api.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

/** 审计日志 Mapper（append-only，禁止执行 UPDATE / DELETE 操作） */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
