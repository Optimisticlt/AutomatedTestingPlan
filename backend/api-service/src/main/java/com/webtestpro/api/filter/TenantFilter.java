package com.webtestpro.api.filter;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.webtestpro.common.context.TenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 租户上下文过滤器
 *
 * 职责：每次 HTTP 请求进入时，将当前登录用户所属的 tenantId 写入 TenantContext，
 * 使后续 MyBatis-Plus TenantLineInnerInterceptor 能自动拼接 WHERE tenant_id = ?。
 *
 * 执行顺序：Order(5)，在 Sa-Token 认证过滤器之后、业务 Controller 之前。
 *
 * 白名单路径（无需认证，设置 tenantId=0L）：
 *   /api/v1/auth/login, /api/v1/auth/refresh, /actuator/**, /v3/api-docs/**, /swagger-ui/**
 */
@Slf4j
@Component
@Order(5)
public class TenantFilter implements Filter {

    private static final List<String> EXCLUDE_PREFIXES = Arrays.asList(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/",
            "/v3/api-docs/",
            "/swagger-ui/"
    );

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String path = request.getRequestURI();

        try {
            if (isExcluded(path)) {
                TenantContext.set(0L);
                chain.doFilter(servletRequest, servletResponse);
                return;
            }

            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                TenantContext.set(0L);
            } else {
                SaSession session = StpUtil.getTokenSession();
                Long tenantId = session.get("tenantId", 0L);
                TenantContext.set(tenantId);
            }

            chain.doFilter(servletRequest, servletResponse);

        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void destroy() {
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : EXCLUDE_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
