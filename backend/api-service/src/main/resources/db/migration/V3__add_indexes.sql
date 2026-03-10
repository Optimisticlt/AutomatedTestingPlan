-- ============================================================
-- Flyway V3: 复合索引
-- （独立脚本，方便单独回滚而不影响表结构）
-- ============================================================

-- tc_execution: 断点续跑 Watchdog 扫描索引
CREATE INDEX IF NOT EXISTS idx_exec_running_checkpoint
    ON tc_execution (status, checkpoint_interrupted_id);

-- tc_case_result: Flaky 率统计索引
CREATE INDEX IF NOT EXISTS idx_case_result_flaky
    ON tc_case_result (case_id, status, created_time);

-- tc_execution_artifact: MinIO 清理任务索引
CREATE INDEX IF NOT EXISTS idx_artifact_cleanup
    ON tc_execution_artifact (is_deleted, deleted_time);

-- sys_audit_log: 按事件类型查询（包含 action 字段）
CREATE INDEX IF NOT EXISTS idx_audit_action_time
    ON sys_audit_log (action, created_time);

-- tc_flaky_record: 每日 Flaky 率重算扫描
CREATE INDEX IF NOT EXISTS idx_flaky_project_period
    ON tc_flaky_record (project_id, stats_period_end, is_deleted);
