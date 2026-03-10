-- ============================================================
-- Flyway V2: 种子数据
-- ============================================================

INSERT IGNORE INTO sys_role (id, role_name, role_code, description, created_time, updated_time, is_deleted, version)
VALUES
(1, '管理员',     'ADMIN',    '平台全部权限，含 SCRIPT 步骤审批权',    NOW(), NOW(), 0, 0),
(2, '测试工程师', 'ENGINEER', '用例编写、执行、查看报告（项目级）',     NOW(), NOW(), 0, 0),
(3, '观察者',     'VIEWER',   '只读权限，仅可查看报告和用例',           NOW(), NOW(), 0, 0);

-- 系统管理员初始账号（密码：Admin@2024，BCrypt 哈希）
-- 生产环境首次部署后须立即修改密码
INSERT IGNORE INTO sys_user (id, tenant_id, username, password, real_name, status, created_time, updated_time, is_deleted, version)
VALUES (1, 0, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyU.2WCRu', '系统管理员', 1, NOW(), NOW(), 0, 0);

-- 管理员平台级角色绑定
INSERT IGNORE INTO sys_user_role (id, tenant_id, user_id, role_id, project_id, created_time, is_deleted)
VALUES (1, 0, 1, 1, 0, NOW(), 0);

-- 默认系统配置
INSERT IGNORE INTO sys_config (id, tenant_id, config_key, config_value, value_type, description, created_time, updated_time, is_deleted, version)
VALUES
(1, 0, 'flaky.auto_isolate.min_sample',     '10',   'INT',     'Flaky 自动隔离最小样本量',           NOW(), NOW(), 0, 0),
(2, 0, 'flaky.auto_isolate.rate_threshold', '0.3',  'STRING',  'Flaky 自动隔离阈值（失败率）',        NOW(), NOW(), 0, 0),
(3, 0, 'case.max_version_keep',             '50',   'INT',     '用例版本快照最大保留数',              NOW(), NOW(), 0, 0),
(4, 0, 'snapshot.size_threshold_kb',        '100',  'INT',     '快照大小阈值（超过则存 MinIO）',     NOW(), NOW(), 0, 0),
(5, 0, 'execution.log.max_list_size',       '50000','INT',     'Redis List 单执行最大日志条数',      NOW(), NOW(), 0, 0);
