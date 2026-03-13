package com.webtestpro.common.context;

/**
 * 多租户上下文（ThreadLocal 持有当前请求的 tenantId）
 *
 * 使用规范：
 *   1. 在请求入口（TenantFilter / 消息消费者）调用 set()
 *   2. 在 finally 块中必须调用 clear()，防止线程池复用时数据污染
 *   3. 在 MyBatis-Plus TenantLineInnerInterceptor 中调用 get() 获取当前租户 ID
 */
public class TenantContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private TenantContext() {
        // 工具类，禁止实例化
    }

    public static void set(Long tenantId) {
        HOLDER.set(tenantId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
