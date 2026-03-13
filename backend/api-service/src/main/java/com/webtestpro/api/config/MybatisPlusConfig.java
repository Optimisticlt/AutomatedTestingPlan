package com.webtestpro.api.config;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.webtestpro.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * MyBatis-Plus 插件配置
 *
 * 插件顺序（重要，不可随意调换）：
 *   1. TenantLineInnerInterceptor  — 多租户隔离，自动追加 WHERE tenant_id = ?
 *   2. OptimisticLockerInnerInterceptor — 乐观锁，@Version 字段自动处理
 *   3. PaginationInnerInterceptor  — 分页，maxLimit=500
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /** 免租户过滤的表（系统级共享表） */
    private static final List<String> IGNORE_TENANT_TABLES = Arrays.asList(
            "sys_role",
            "sys_config"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 多租户插件
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.get();
                return new LongValue(tenantId != null ? tenantId : 0L);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return IGNORE_TENANT_TABLES.contains(tableName);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }
        }));

        // 2. 乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 3. 分页插件（MySQL，单页最大 500 条）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {

            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                Long currentUserId = safeGetLoginId();
                Long tenantId = TenantContext.get();

                // id 由 MyBatis-Plus @TableId(type=ASSIGN_ID) 自动处理，此处仅兜底
                strictFillStrategy(metaObject, "id",
                        () -> com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());

                strictFillStrategy(metaObject, "createdBy",   () -> currentUserId);
                strictFillStrategy(metaObject, "updatedBy",   () -> currentUserId);
                strictFillStrategy(metaObject, "createdTime", () -> now);
                strictFillStrategy(metaObject, "updatedTime", () -> now);
                strictFillStrategy(metaObject, "isDeleted",   () -> 0);
                strictFillStrategy(metaObject, "version",     () -> 0);
                strictFillStrategy(metaObject, "tenantId",    () -> tenantId != null ? tenantId : 0L);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictFillStrategy(metaObject, "updatedBy",   this::safeGetLoginId);
                this.strictFillStrategy(metaObject, "updatedTime", LocalDateTime::now);
            }

            /**
             * 安全获取当前登录用户 ID，未登录时返回 0L。
             */
            private Long safeGetLoginId() {
                try {
                    return StpUtil.getLoginIdAsLong();
                } catch (Exception e) {
                    // 未登录场景（定时任务、系统初始化等）
                    return 0L;
                }
            }
        };
    }
}
