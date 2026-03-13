package com.webtestpro.worker.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.worker.entity.TcExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TcExecutionMapper extends BaseMapper<TcExecution> {

    /**
     * 乐观锁更新状态（Worker / Watchdog 竞争安全）。
     * affected_rows == 0 表示状态已被其他进程修改，调用方应放弃本次更新。
     */
    @Update("UPDATE tc_execution SET status = #{newStatus}, version = version + 1, updated_time = NOW() " +
            "WHERE id = #{executionId} AND status = #{expectedStatus} AND version = #{version}")
    int casUpdateStatus(@Param("executionId") Long executionId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus,
                        @Param("version") Integer version);

    /**
     * 查询所有 RUNNING 状态的执行记录（Watchdog 心跳扫描用）。
     * 跨租户查询，绕过 TenantLineInnerInterceptor。
     */
    @Select("SELECT * FROM tc_execution WHERE status = 'RUNNING' AND is_deleted = 0")
    @InterceptorIgnore(tenantLine = "true")
    List<TcExecution> selectRunningExecutions();

    /**
     * 统计当前 RUNNING 状态的执行记录数（信号量对账用）。
     * 跨租户统计，绕过 TenantLineInnerInterceptor。
     */
    @Select("SELECT COUNT(*) FROM tc_execution WHERE status = 'RUNNING' AND is_deleted = 0")
    @InterceptorIgnore(tenantLine = "true")
    long countRunningExecutions();

    /**
     * 强制将 RUNNING 状态的执行记录标记为 INTERRUPTED（优雅停机专用）。
     * WHERE 子句包含 status='RUNNING' 防止重复中断。
     *
     * @return 受影响行数（0=状态已变更，1=成功标记为 INTERRUPTED）
     */
    @Update("UPDATE tc_execution SET status = 'INTERRUPTED', updated_time = NOW() " +
            "WHERE id = #{executionId} AND status = 'RUNNING'")
    @InterceptorIgnore(tenantLine = "true")
    int forceMarkInterrupted(@Param("executionId") Long executionId);
}
