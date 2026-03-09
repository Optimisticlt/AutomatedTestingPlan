-- ============================================================
-- WebTestPro 自动化测试平台 · 完整数据库设计
-- 版本：v2.0
-- 变更说明：
--   1. 所有 ALTER TABLE 补丁折叠回原表 CREATE TABLE，消除两段式 DDL
--   2. 移除废弃字段 tc_plan.case_ids（未上线无需向前兼容）
--   3. 移除冗余字段 tc_execution_result.step_results（由 tc_execution_step_result 替代）
--   4. sys_audit_log / tc_execution_log 明确为仅追加写，无 is_deleted / version
--   5. sys_user 新增 phone_hash 列，支持手机号加密存储后仍可索引查找
--   6. sys_api_token 新增 token_prefix 列，支持 Token 可读标识
--   7. sys_user_role 补全唯一约束 (user_id, role_id, project_id)
--   8. tc_case 新增 pass_rate_updated_time / pass_rate_sample_count
--   9. tc_case_dependency 补全唯一约束 (case_id, depend_case_id)
--  10. tc_plan_case_relation.sort_order 补全唯一约束 (plan_id, sort_order)
--  11. tc_flaky_record 新增统计周期字段并折叠所有补充字段
--  12. 全面补充高频复合索引
--  13. sys_notify_config.config_detail 改为加密存储（应用层 AES-GCM，注释说明）
-- ============================================================

CREATE DATABASE IF NOT EXISTS web_test_pro DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE web_test_pro;

-- ============================================================
-- 一、系统管理
-- ============================================================

-- 1.1 用户表
-- 安全说明：
--   password  → BCrypt 哈希
--   phone     → AES-256-GCM 加密密文（密钥来自 KMS/Vault，不写配置文件）
--   phone_hash → HMAC-SHA256(明文手机号, secret_key)，用于手机号登录索引查找
--   last_login_ip → 合规保留策略：超 180 天未登录由定时任务置 NULL
CREATE TABLE sys_user (
    id               BIGINT          NOT NULL                                COMMENT '主键（雪花算法）',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID（多租户预留，platform级=0）',
    username         VARCHAR(50)     NOT NULL                                COMMENT '用户名',
    password         VARCHAR(128)    NOT NULL                                COMMENT '密码（BCrypt）',
    real_name        VARCHAR(50)                                             COMMENT '真实姓名',
    email            VARCHAR(100)                                            COMMENT '邮箱',
    phone            VARCHAR(200)                                            COMMENT '手机号（AES-256-GCM 加密，含 IV 前缀）',
    phone_hash       VARCHAR(64)                                             COMMENT '手机号 HMAC-SHA256，用于索引查找',
    avatar_url       VARCHAR(500)                                            COMMENT '头像URL',
    status           TINYINT         NOT NULL DEFAULT 1                      COMMENT '状态（0禁用 1启用）',
    last_login_time  DATETIME                                                COMMENT '最后登录时间',
    last_login_ip    VARCHAR(50)                                             COMMENT '最后登录IP（合规：180天未登录后置NULL）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP      COMMENT '创建时间',
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP             COMMENT '修改时间',
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0                      COMMENT '逻辑删除（0正常 1删除）',
    version          INT             NOT NULL DEFAULT 0                      COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username, tenant_id),
    KEY idx_email (email),
    KEY idx_phone_hash (phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';


-- 1.2 角色表
CREATE TABLE sys_role (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    role_name        VARCHAR(50)     NOT NULL                                COMMENT '角色名称',
    role_code        VARCHAR(50)     NOT NULL                                COMMENT '角色编码（ADMIN/ENGINEER/VIEWER）',
    description      VARCHAR(200)                                            COMMENT '角色描述',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';


-- 1.3 用户角色关联表
-- project_id=0 表示平台级别角色；有值表示项目级别角色（资源级权限隔离）
-- 唯一约束防止同一用户在同一项目中重复授予同一角色
-- 注意：NULL 在唯一索引中视为不同值，故统一用 0 作为"无项目"哨兵值
CREATE TABLE sys_user_role (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    user_id          BIGINT          NOT NULL                                COMMENT '用户ID',
    role_id          BIGINT          NOT NULL                                COMMENT '角色ID',
    project_id       BIGINT          NOT NULL DEFAULT 0                      COMMENT '项目ID（0=平台级 有值=项目级）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role_project (user_id, role_id, project_id),
    KEY idx_user_project (user_id, project_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';


-- 1.4 API Token 表（供 CI/CD 系统调用，独立于用户会话）
-- Token 生成格式：wtp_<env>_<Base62(32字节随机)>
--   token_prefix  → 明文存储前16字符（wtp_prod_xxxxxxxx），用于日志/UI识别
--   token_value   → SHA-256(完整Token)，不可逆，用于验证
-- Token 熵要求：后缀部分 >= 128bit 随机熵（32字节 Base62）
CREATE TABLE sys_api_token (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    token_name       VARCHAR(100)    NOT NULL                                COMMENT 'Token 名称',
    token_prefix     VARCHAR(20)     NOT NULL                                COMMENT 'Token 可读前缀（wtp_prod_xxxxxxxx，明文，用于识别）',
    token_value      VARCHAR(256)    NOT NULL                                COMMENT 'Token SHA-256 哈希（不可逆，用于验证）',
    project_id       BIGINT          NOT NULL DEFAULT 0                      COMMENT '关联项目ID（0=全局）',
    permissions      JSON                                                    COMMENT '权限列表（["execute","read","report"]）',
    status           TINYINT         NOT NULL DEFAULT 1                      COMMENT '状态（0禁用 1启用）',
    expired_time     DATETIME                                                COMMENT '过期时间（NULL=永不过期）',
    last_used_time   DATETIME                                                COMMENT '最后使用时间',
    last_used_ip     VARCHAR(50)                                             COMMENT '最后使用IP',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_value (token_value),
    UNIQUE KEY uk_token_prefix (token_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API Token 表';


-- 1.5 操作审计日志
-- [合规要求] 仅追加写，无 is_deleted / version 字段，禁止应用账号执行 UPDATE/DELETE
-- before_value/after_value 超过 64KB 时，应用层将内容存 MinIO 后此处写 file_key 引用
-- 保留策略：超过 2 年的记录由 XXL-JOB 归档至 MinIO 后从此表删除（合规归档，非业务删除）
CREATE TABLE sys_audit_log (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    operator_id      BIGINT          NOT NULL                                COMMENT '操作人ID',
    operator_name    VARCHAR(50)     NOT NULL                                COMMENT '操作人姓名（冗余）',
    action           VARCHAR(50)     NOT NULL                                COMMENT '操作类型（CREATE/UPDATE/DELETE/EXECUTE）',
    resource_type    VARCHAR(50)     NOT NULL                                COMMENT '资源类型（CASE/PLAN/PROJECT/ENV等）',
    resource_id      BIGINT                                                  COMMENT '资源ID',
    resource_name    VARCHAR(200)                                            COMMENT '资源名称（冗余）',
    before_value     TEXT                                                    COMMENT '修改前内容（JSON，超64KB存MinIO后写file_key）',
    after_value      TEXT                                                    COMMENT '修改后内容（JSON，超64KB存MinIO后写file_key）',
    ip_address       VARCHAR(50)                                             COMMENT '操作IP',
    user_agent       VARCHAR(500)                                            COMMENT '浏览器UA',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_operator_id (operator_id),
    KEY idx_resource (resource_type, resource_id),
    KEY idx_tenant_time (tenant_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志（仅追加写，无软删除）';


-- 1.6 通知配置表
-- [安全说明] config_detail 存储 webhook_url / robot_token 等敏感信息
--   应用层写入前必须 AES-256-GCM 加密，读取后解密
--   不可将明文 robot_token 存入此字段
CREATE TABLE sys_notify_config (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_id       BIGINT          NOT NULL DEFAULT 0                      COMMENT '关联项目ID（0=全局）',
    config_name      VARCHAR(100)    NOT NULL                                COMMENT '配置名称',
    notify_type      TINYINT         NOT NULL                                COMMENT '通知类型（1邮件 2钉钉 3企微 4飞书 5Webhook）',
    config_detail    TEXT            NOT NULL                                COMMENT '通知渠道配置（AES-256-GCM 加密密文，应用层解密后得到 JSON）',
    trigger_events   JSON            NOT NULL                                COMMENT '触发事件（["EXEC_FINISH","EXEC_FAIL","FLAKY_FOUND","GATE_BLOCKED"]）',
    trigger_rule     JSON                                                    COMMENT '触发规则 {"consecutive_fail":3,"dedup_window_minutes":30}',
    is_enabled       TINYINT(1)      NOT NULL DEFAULT 1                      COMMENT '是否启用',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知配置表';


-- 1.7 通知发送记录
-- max_retries 上限由 trigger_rule 控制，retry_count 到达上限后 send_status 置为最终失败
CREATE TABLE sys_notify_record (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    config_id        BIGINT          NOT NULL                                COMMENT '通知配置ID',
    notify_type      TINYINT         NOT NULL                                COMMENT '通知类型',
    title            VARCHAR(200)                                            COMMENT '通知标题',
    content          LONGTEXT                                                COMMENT '通知内容',
    receivers        JSON                                                    COMMENT '接收者列表',
    trigger_event    VARCHAR(50)                                             COMMENT '触发事件类型',
    related_id       BIGINT                                                  COMMENT '关联资源ID（执行ID等）',
    send_status      TINYINT         NOT NULL DEFAULT 0                      COMMENT '发送状态（0待发 1成功 2失败 3已放弃）',
    send_time        DATETIME                                                COMMENT '发送时间',
    fail_reason      VARCHAR(500)                                            COMMENT '失败原因',
    retry_count      INT             NOT NULL DEFAULT 0                      COMMENT '已重试次数',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_config_id (config_id),
    KEY idx_status_retry (send_status, retry_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知发送记录';


-- 1.8 文件存储管理（截图/HTML快照/HAR/报告 统一存 MinIO，此表存元数据）
CREATE TABLE sys_file_store (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    file_name        VARCHAR(200)    NOT NULL                                COMMENT '原始文件名',
    file_key         VARCHAR(500)    NOT NULL                                COMMENT 'MinIO 对象路径',
    file_url         VARCHAR(1000)                                           COMMENT '访问 URL（预签名，有效期由应用层控制）',
    file_type        VARCHAR(50)                                             COMMENT '文件类型（SCREENSHOT/HTML/HAR/CONSOLE_LOG/REPORT/ATTACHMENT）',
    file_size        BIGINT                                                  COMMENT '文件大小（字节）',
    bucket_name      VARCHAR(100)                                            COMMENT 'MinIO Bucket 名称',
    related_type     VARCHAR(50)                                             COMMENT '关联资源类型',
    related_id       BIGINT                                                  COMMENT '关联资源ID',
    uploader_id      BIGINT                                                  COMMENT '上传人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_related (related_type, related_id),
    KEY idx_file_key (file_key(200))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件存储管理';


-- 1.9 系统全局配置表
CREATE TABLE sys_config (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    config_key       VARCHAR(100)    NOT NULL                                COMMENT '配置键',
    config_value     TEXT                                                    COMMENT '配置值',
    value_type       VARCHAR(20)     NOT NULL DEFAULT 'STRING'               COMMENT '值类型（STRING/INT/BOOLEAN/JSON）',
    description      VARCHAR(200)                                            COMMENT '配置说明',
    is_encrypted     TINYINT(1)      NOT NULL DEFAULT 0                      COMMENT '是否加密存储',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统全局配置表';


-- ============================================================
-- 二、项目与环境管理
-- ============================================================

-- 2.1 项目表
CREATE TABLE tc_project (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_name     VARCHAR(100)    NOT NULL                                COMMENT '项目名称',
    project_code     VARCHAR(50)     NOT NULL                                COMMENT '项目编码（唯一标识，用于 API 调用）',
    description      VARCHAR(500)                                            COMMENT '项目描述',
    owner_id         BIGINT                                                  COMMENT '负责人ID',
    avatar_url       VARCHAR(500)                                            COMMENT '项目图标URL',
    status           TINYINT         NOT NULL DEFAULT 1                      COMMENT '状态（0归档 1启用）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (project_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';


-- 2.2 模块表（支持多级树形结构）
CREATE TABLE tc_module (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_id       BIGINT          NOT NULL                                COMMENT '所属项目ID',
    parent_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '父模块ID（0=根节点）',
    module_name      VARCHAR(100)    NOT NULL                                COMMENT '模块名称',
    module_path      VARCHAR(500)                                            COMMENT '模块全路径（冗余，如 /支付/收银台/下单）',
    sort_order       INT             NOT NULL DEFAULT 0                      COMMENT '同级排序序号',
    description      VARCHAR(500)                                            COMMENT '模块描述',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模块表';


-- 2.3 环境表
CREATE TABLE tc_env (
    id                BIGINT          NOT NULL                               COMMENT '主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0                     COMMENT '租户ID',
    project_id        BIGINT          NOT NULL                               COMMENT '所属项目ID',
    env_name          VARCHAR(50)     NOT NULL                               COMMENT '环境名称（dev/test/staging/prod）',
    env_type          TINYINT         NOT NULL DEFAULT 1                     COMMENT '环境类型（1开发 2测试 3预发 4生产）',
    base_url          VARCHAR(500)    NOT NULL                               COMMENT '被测系统基础 URL',
    description       VARCHAR(200)                                           COMMENT '环境描述',
    browser_config    JSON                                                   COMMENT '浏览器配置 {"type":"chrome","headless":false,"resolution":"1920x1080","args":[]}',
    proxy_config      JSON                                                   COMMENT '代理配置 {"host":"","port":0,"bypass":[]}',
    ssl_ignore        TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT '是否忽略SSL证书验证',
    health_check_url  VARCHAR(500)                                           COMMENT '健康检查URL（执行前自动探活）',
    pre_hooks         JSON                                                   COMMENT '前置钩子脚本（执行前操作，如初始化登录态）',
    post_hooks        JSON                                                   COMMENT '后置钩子脚本（执行后清理数据）',
    is_enabled        TINYINT(1)      NOT NULL DEFAULT 1                     COMMENT '是否启用',
    created_by        BIGINT                                                 COMMENT '创建人ID',
    updated_by        BIGINT                                                 COMMENT '最后修改人ID',
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境表';


-- 2.4 环境变量表（敏感值 AES-256-GCM 加密）
CREATE TABLE tc_env_variable (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    env_id           BIGINT          NOT NULL                                COMMENT '所属环境ID',
    var_key          VARCHAR(100)    NOT NULL                                COMMENT '变量名',
    var_value        TEXT                                                    COMMENT '变量值（is_encrypted=1时为 AES-256-GCM 加密密文）',
    var_type         TINYINT         NOT NULL DEFAULT 0                      COMMENT '变量类型（0字符串 1数字 2布尔 3JSON）',
    is_encrypted     TINYINT(1)      NOT NULL DEFAULT 0                      COMMENT '是否加密存储',
    description      VARCHAR(200)                                            COMMENT '变量说明',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_env_var_key (env_id, var_key),
    KEY idx_env_id (env_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境变量表';


-- 2.5 数据源管理表（DB断言步骤连接被测系统数据库）
-- [安全说明] password 字段 AES-256-GCM 加密；连接时使用只读账号；SQL 白名单只允许 SELECT
CREATE TABLE tc_datasource (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_id       BIGINT          NOT NULL                                COMMENT '所属项目ID',
    env_id           BIGINT          NOT NULL DEFAULT 0                      COMMENT '关联环境ID（0=所有环境共用）',
    ds_name          VARCHAR(100)    NOT NULL                                COMMENT '数据源名称',
    db_type          VARCHAR(20)     NOT NULL DEFAULT 'MYSQL'                COMMENT '数据库类型（MYSQL/POSTGRESQL/ORACLE）',
    host             VARCHAR(200)    NOT NULL                                COMMENT '数据库主机',
    port             INT             NOT NULL                                COMMENT '端口',
    db_name          VARCHAR(100)    NOT NULL                                COMMENT '数据库名',
    username         VARCHAR(100)    NOT NULL                                COMMENT '用户名（应为只读账号）',
    password         VARCHAR(500)    NOT NULL                                COMMENT '密码（AES-256-GCM 加密）',
    connect_timeout  INT             NOT NULL DEFAULT 5                      COMMENT '连接超时（秒）',
    query_timeout    INT             NOT NULL DEFAULT 10                     COMMENT 'SQL 查询超时（秒）',
    status           TINYINT         NOT NULL DEFAULT 1                      COMMENT '状态（0禁用 1启用）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源管理表';


-- ============================================================
-- 三、用例管理
-- ============================================================

-- 3.1 标签表
CREATE TABLE tc_tag (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_id       BIGINT          NOT NULL                                COMMENT '所属项目ID',
    tag_name         VARCHAR(50)     NOT NULL                                COMMENT '标签名称',
    tag_color        VARCHAR(20)                                             COMMENT '标签颜色（十六进制）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (project_id, tag_name),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签表';


-- 3.2 测试用例表
CREATE TABLE tc_case (
    id                BIGINT          NOT NULL                               COMMENT '主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0                     COMMENT '租户ID',
    project_id        BIGINT          NOT NULL                               COMMENT '所属项目ID',
    module_id         BIGINT                                                 COMMENT '所属模块ID',
    case_name         VARCHAR(200)    NOT NULL                               COMMENT '用例名称',
    case_type         TINYINT         NOT NULL DEFAULT 0                     COMMENT '用例类型（0 UI 1接口 2视觉 3混合）',
    priority          TINYINT         NOT NULL DEFAULT 1                     COMMENT '优先级（0 P0冒烟 1 P1核心 2 P2全量 3 P3扩展）',
    description       VARCHAR(1000)                                          COMMENT '用例描述',
    precondition      VARCHAR(1000)                                          COMMENT '前置条件',
    owner_id          BIGINT                                                 COMMENT '用例负责人ID',
    review_status     TINYINT         NOT NULL DEFAULT 0                     COMMENT '评审状态（0草稿 1待评审 2已通过 3已废弃）',
    is_template       TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT '是否为公共模板（可被其他用例通过 SUB_CASE 步骤调用）',
    expected_duration INT                                                    COMMENT '预期执行时长（秒），用于超时控制',
    -- 冗余统计字段（定期异步计算，不实时聚合）
    last_run_status   TINYINT                                                COMMENT '最近执行状态（0通过 1失败 2跳过 3错误）',
    last_run_time     DATETIME                                               COMMENT '最近执行时间',
    pass_rate         DECIMAL(5,2)                                           COMMENT '近 N 次通过率',
    pass_rate_sample_count INT        NOT NULL DEFAULT 0                     COMMENT '通过率统计样本数（显示"基于N次执行"）',
    pass_rate_updated_time DATETIME                                          COMMENT '通过率最后计算时间（UI 可展示数据新鲜度）',
    run_count         INT             NOT NULL DEFAULT 0                     COMMENT '累计执行次数',
    created_by        BIGINT                                                 COMMENT '创建人ID',
    updated_by        BIGINT                                                 COMMENT '最后修改人ID',
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_module_id (module_id),
    KEY idx_project_review (project_id, review_status, is_deleted),
    KEY idx_project_priority (project_id, priority, is_deleted),
    KEY idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例表';


-- 3.3 用例步骤表
-- step_type 枚举：UI / API / DB / WAIT / EXTRACT / CONDITION / SCRIPT / SUB_CASE
-- [安全说明] SCRIPT 步骤：
--   JS  → 使用 GraalVM Context.newBuilder().allowAllAccess(false) 执行
--   GROOVY → 使用 GroovySandbox + CompileTimeCheckedExtension 白名单
--   两种脚本均强制 30 秒超时，禁止网络访问和文件系统访问
--   SCRIPT 步骤进入正式计划须 ADMIN 角色审批
CREATE TABLE tc_case_step (
    id                  BIGINT          NOT NULL                             COMMENT '主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0                   COMMENT '租户ID',
    case_id             BIGINT          NOT NULL                             COMMENT '所属用例ID',
    step_order          INT             NOT NULL                             COMMENT '步骤序号（从1开始）',
    step_name           VARCHAR(200)                                         COMMENT '步骤描述',
    step_type           VARCHAR(20)     NOT NULL                             COMMENT '步骤类型（UI/API/DB/WAIT/EXTRACT/CONDITION/SCRIPT/SUB_CASE）',

    -- UI 步骤
    action_type         VARCHAR(30)                                          COMMENT 'UI操作（OPEN_URL/CLICK/INPUT/ASSERT_TEXT/ASSERT_URL/HOVER/CLEAR/SELECT/UPLOAD/JS_CLICK等）',
    locators            JSON                                                 COMMENT '多策略定位器数组（按优先级排序）
                                                                              [{"strategy":"data-testid","value":"login-btn","score":5},
                                                                               {"strategy":"id","value":"btnLogin","score":4},
                                                                               {"strategy":"css","value":".login button","score":3},
                                                                               {"strategy":"xpath","value":"//button[text()=\'登录\']","score":2}]',
    input_value         VARCHAR(2000)                                        COMMENT '输入值（支持 ${变量名} 动态引用）',
    expected_result     VARCHAR(2000)                                        COMMENT '预期结果（断言内容）',

    -- 等待策略
    wait_config         JSON                                                 COMMENT '等待配置 {"type":"EXPLICIT/SLEEP/POLL","timeout":10,"interval":0.5,"condition":"VISIBLE/CLICKABLE/TEXT_PRESENT"}',

    -- 变量提取
    extract_config      JSON                                                 COMMENT '变量提取 {"from":"TEXT/ATTR/URL/COOKIE/JSON_PATH","locator":"...","attr_name":"","variable_name":"token","regex":""}',

    -- 条件分支
    condition_config    JSON                                                 COMMENT '分支条件 {"condition":"${status}==200","then_step_ids":[],"else_step_ids":[]}',

    -- 自定义脚本（须经 ADMIN 审批才能进入正式计划）
    script_content      TEXT                                                 COMMENT '自定义脚本内容（沙箱执行，30秒超时）',
    script_type         VARCHAR(20)                                          COMMENT '脚本类型（JS/GROOVY）',
    script_approved     TINYINT(1)      NOT NULL DEFAULT 0                   COMMENT 'SCRIPT步骤是否已经 ADMIN 审批（0未审批 1已审批）',

    -- 子用例调用
    sub_case_id         BIGINT                                               COMMENT '被调用的子用例ID（is_template=1）',
    sub_case_params     JSON                                                 COMMENT '传入子用例的参数 {"username":"${adminUser}"}',

    -- 接口步骤
    api_config          JSON                                                 COMMENT '接口配置
                                                                              {"method":"POST","url":"/api/login",
                                                                               "headers":{},"body":{},"form_data":{},
                                                                               "assertions":[{"type":"STATUS","expected":200},
                                                                                             {"type":"JSON_PATH","path":"$.code","expected":"0"}]}',

    -- 数据库断言（使用 PreparedStatement 参数化，不做字符串拼接）
    db_config           JSON                                                 COMMENT 'DB断言配置
                                                                              {"datasource_id":1,
                                                                               "sql":"SELECT status FROM orders WHERE id = ?",
                                                                               "params":["${orderId}"],
                                                                               "assertions":[{"column":"status","expected":"PAID"}],
                                                                               "row_limit":100}',

    -- 失败处理策略
    on_failure          VARCHAR(20)     NOT NULL DEFAULT 'STOP'              COMMENT '失败处理（STOP停止/CONTINUE继续/RETRY重试）',
    retry_times         INT             NOT NULL DEFAULT 0                   COMMENT '步骤级重试次数（on_failure=RETRY时生效，优先于计划级）',

    -- 截图策略
    screenshot_policy   VARCHAR(20)     NOT NULL DEFAULT 'ON_FAILURE'        COMMENT '截图时机（ALWAYS/ON_FAILURE/NEVER）',

    created_by          BIGINT                                               COMMENT '创建人ID',
    updated_by          BIGINT                                               COMMENT '最后修改人ID',
    created_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted          TINYINT(1)      NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_case_step_order (case_id, step_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例步骤表';


-- 3.4 用例标签关联表
CREATE TABLE tc_case_tag_relation (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    case_id          BIGINT          NOT NULL                                COMMENT '用例ID',
    tag_id           BIGINT          NOT NULL                                COMMENT '标签ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_tag (case_id, tag_id),
    KEY idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例标签关联表';


-- 3.5 用例依赖关系表
-- [注意] 应用层新增依赖时须做环检测（BFS），防止循环依赖导致执行死循环
CREATE TABLE tc_case_dependency (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    case_id          BIGINT          NOT NULL                                COMMENT '当前用例ID',
    depend_case_id   BIGINT          NOT NULL                                COMMENT '依赖的用例ID',
    dep_type         VARCHAR(20)     NOT NULL DEFAULT 'BEFORE'               COMMENT '依赖类型（BEFORE前置/DATA数据依赖取结果变量）',
    description      VARCHAR(200)                                            COMMENT '依赖说明',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_dependency (case_id, depend_case_id),
    KEY idx_case_id (case_id),
    KEY idx_depend_case_id (depend_case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例依赖关系表';


-- 3.6 用例版本快照表（每次保存时记录完整快照，支持回滚）
-- [说明] case_snapshot 存储用例完整 JSON（含所有步骤）
--   超过 100KB 的快照存储至 MinIO，此处 case_snapshot 改写为 file_key 引用
--   应用层在写入前判断大小，决定存库还是存 MinIO
CREATE TABLE tc_case_version (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    case_id          BIGINT          NOT NULL                                COMMENT '用例ID',
    version_no       INT             NOT NULL                                COMMENT '版本号（递增）',
    version_desc     VARCHAR(200)                                            COMMENT '版本说明',
    storage_type     TINYINT         NOT NULL DEFAULT 0                      COMMENT '存储方式（0 JSON内联 1 MinIO file_key引用）',
    case_snapshot    TEXT                                                    COMMENT '用例完整快照（JSON内联）或 MinIO file_key',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_case_version (case_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例版本快照表';


-- 3.7 用例评审记录表
CREATE TABLE tc_case_review_record (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    case_id          BIGINT          NOT NULL                                COMMENT '用例ID',
    reviewer_id      BIGINT          NOT NULL                                COMMENT '评审人ID',
    review_result    TINYINT         NOT NULL                                COMMENT '评审结果（1通过 2拒绝 3待修改）',
    review_comment   VARCHAR(1000)                                           COMMENT '评审意见',
    case_version_no  INT                                                     COMMENT '被评审的用例版本号',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_case_id (case_id),
    KEY idx_reviewer_id (reviewer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例评审记录表';


-- ============================================================
-- 四、元素库
-- ============================================================

CREATE TABLE tc_element_library (
    id                BIGINT          NOT NULL                               COMMENT '主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0                     COMMENT '租户ID',
    project_id        BIGINT          NOT NULL                               COMMENT '所属项目ID',
    element_name      VARCHAR(100)    NOT NULL                               COMMENT '元素名称（如：登录按钮）',
    element_desc      VARCHAR(500)                                           COMMENT '元素描述',
    page_url          VARCHAR(500)                                           COMMENT '所在页面URL（健康检查时打开此页面）',
    locators          JSON            NOT NULL                               COMMENT '多策略定位器数组（同 tc_case_step.locators 格式）',
    screenshot_url    VARCHAR(1000)                                          COMMENT '元素截图（健康检查时自动更新）',
    locator_status    TINYINT         NOT NULL DEFAULT 1                     COMMENT '定位器健康状态（0失效 1健康 2降级到次选）',
    last_check_time   DATETIME                                               COMMENT '最后一次健康检查时间',
    hit_level         TINYINT                                                COMMENT '上次执行命中的定位器层级（1最优 5最差）',
    is_shared         TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT '是否跨项目共享',
    created_by        BIGINT                                                 COMMENT '创建人ID',
    updated_by        BIGINT                                                 COMMENT '最后修改人ID',
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_locator_status (locator_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元素库表';


-- ============================================================
-- 五、测试数据管理
-- ============================================================

-- 5.1 测试数据集
CREATE TABLE tc_test_data_set (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    project_id       BIGINT          NOT NULL                                COMMENT '所属项目ID',
    case_id          BIGINT          NOT NULL DEFAULT 0                      COMMENT '关联用例ID（0=全局数据集）',
    set_name         VARCHAR(100)    NOT NULL                                COMMENT '数据集名称',
    description      VARCHAR(500)                                            COMMENT '数据集描述',
    column_schema    JSON            NOT NULL                                COMMENT '列定义 [{"name":"username","type":"STRING","is_encrypted":false}]',
    data_source      TINYINT         NOT NULL DEFAULT 0                      COMMENT '数据来源（0手动录入 1Excel导入 2数据库查询）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_case_id (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试数据集表';


-- 5.2 测试数据行
CREATE TABLE tc_test_data_row (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    set_id           BIGINT          NOT NULL                                COMMENT '所属数据集ID',
    row_index        INT             NOT NULL                                COMMENT '行序号（从1开始）',
    row_data         JSON            NOT NULL                                COMMENT '行数据（敏感值 AES-256-GCM 加密）',
    description      VARCHAR(200)                                            COMMENT '数据行备注',
    is_enabled       TINYINT(1)      NOT NULL DEFAULT 1                      COMMENT '是否参与执行',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_set_id (set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试数据行表';


-- ============================================================
-- 六、执行管理
-- ============================================================

-- 6.1 测试计划表
-- [注意] case_ids 字段已移除，固定用例列表统一使用 tc_plan_case_relation 表管理
CREATE TABLE tc_plan (
    id                BIGINT          NOT NULL                               COMMENT '主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0                     COMMENT '租户ID',
    project_id        BIGINT          NOT NULL                               COMMENT '所属项目ID',
    plan_name         VARCHAR(100)    NOT NULL                               COMMENT '计划名称',
    description       VARCHAR(500)                                           COMMENT '计划描述',
    env_id            BIGINT                                                 COMMENT '执行环境ID',
    browser           VARCHAR(20)                                            COMMENT '浏览器（chrome/firefox/edge）',
    -- 动态筛选规则（与 tc_plan_case_relation 二选一）
    case_filter       JSON                                                   COMMENT '动态筛选规则 {"tags":["smoke"],"priority":[0,1],"modules":[1,2]}',
    -- 执行控制
    max_parallel      INT             NOT NULL DEFAULT 1                     COMMENT '最大并行执行数（受 Selenoid 节点容量限制）',
    timeout_seconds   INT             NOT NULL DEFAULT 3600                  COMMENT '整体超时时间（秒）',
    retry_times       INT             NOT NULL DEFAULT 0                     COMMENT '计划级失败重试次数（低于步骤级重试优先级）',
    fail_fast         TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT '失败后快速停止',
    -- 调度配置
    schedule_type     TINYINT         NOT NULL DEFAULT 0                     COMMENT '调度类型（0不调度 1Cron 2固定间隔）',
    schedule_config   JSON                                                   COMMENT '调度配置 {"cron":"0 2 * * *"} 或 {"interval":3600}',
    -- 质量门禁
    quality_gate      JSON                                                   COMMENT '质量门禁 {"pass_rate_threshold":80,"max_fail_count":5}',
    -- 快照策略
    snapshot_config   JSON                                                   COMMENT '快照策略 {"screenshot":true,"html":true,"har":false,"console":true}',
    -- 通知
    notify_config     JSON                                                   COMMENT '该计划专属通知配置（覆盖全局配置）',
    created_by        BIGINT                                                 COMMENT '创建人ID',
    updated_by        BIGINT                                                 COMMENT '最后修改人ID',
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试计划表';


-- 6.2 计划与用例关联表
-- sort_order 使用间隔 1000 的稀疏排序（如 1000/2000/3000），支持拖拽插入不需全量更新
-- 唯一约束防止同一计划内 sort_order 重复
CREATE TABLE tc_plan_case_relation (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    plan_id          BIGINT          NOT NULL                                COMMENT '计划ID',
    case_id          BIGINT          NOT NULL                                COMMENT '用例ID',
    sort_order       INT             NOT NULL DEFAULT 0                      COMMENT '执行排序（间隔1000，支持插入）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_case (plan_id, case_id),
    UNIQUE KEY uk_plan_sort_order (plan_id, sort_order),
    KEY idx_case_id (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划与用例关联表';


-- 6.3 执行记录表
-- [并发安全] status 字段的状态机流转（WAITING→RUNNING→FINISHED）使用乐观锁保护
-- [断点续跑] checkpoint 记录已完成的 case_id 列表，worker 重启后可跳过已完成用例继续执行
CREATE TABLE tc_execution (
    id                      BIGINT          NOT NULL                         COMMENT '主键',
    tenant_id               BIGINT          NOT NULL DEFAULT 0               COMMENT '租户ID',
    project_id              BIGINT          NOT NULL                         COMMENT '所属项目ID',
    plan_id                 BIGINT                                           COMMENT '关联计划ID（手动执行时可为NULL）',
    execution_name          VARCHAR(200)                                     COMMENT '执行名称（自动生成：计划名+时间戳）',
    env_id                  BIGINT          NOT NULL                         COMMENT '执行环境ID',
    browser                 VARCHAR(20)     NOT NULL                         COMMENT '浏览器类型',
    trigger_type            TINYINT         NOT NULL                         COMMENT '触发类型（0手动 1定时 2Webhook 3CI/CD）',
    trigger_info            JSON                                             COMMENT '触发来源 {"git_commit":"abc123","pipeline_id":"123","branch":"main"}',
    status                  TINYINT         NOT NULL DEFAULT 0               COMMENT '执行状态（0等待队列 1执行中 2已完成 3已中断 4已超时）',
    queue_priority          INT             NOT NULL DEFAULT 5               COMMENT '队列优先级（1最高 10最低）',
    -- 统计汇总
    total_count             INT             NOT NULL DEFAULT 0               COMMENT '总用例数',
    pass_count              INT             NOT NULL DEFAULT 0               COMMENT '通过数',
    fail_count              INT             NOT NULL DEFAULT 0               COMMENT '失败数',
    skip_count              INT             NOT NULL DEFAULT 0               COMMENT '跳过数',
    error_count             INT             NOT NULL DEFAULT 0               COMMENT '错误数',
    pass_rate               DECIMAL(5,2)                                     COMMENT '通过率',
    started_time            DATETIME                                         COMMENT '实际开始时间',
    finished_time           DATETIME                                         COMMENT '实际结束时间',
    duration_ms             BIGINT                                           COMMENT '总耗时（毫秒）',
    -- 可复现性保障
    environment_snapshot    JSON                                             COMMENT '执行时环境配置快照',
    executor_node           VARCHAR(200)                                     COMMENT '执行节点信息（IP/节点名）',
    -- 断点续跑
    checkpoint              JSON                                             COMMENT '断点信息（已完成的 case_id 列表）',
    -- 报告
    allure_report_url       VARCHAR(1000)                                    COMMENT 'Allure 报告访问URL（MinIO 预签名或 Allure Server URL）',
    quality_gate_result     JSON                                             COMMENT '质量门禁检查结果 {"passed":true,"pass_rate":95.0}',
    created_by              BIGINT                                           COMMENT '触发人ID',
    created_time            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted              TINYINT(1)      NOT NULL DEFAULT 0,
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁（状态机流转并发保护）',
    PRIMARY KEY (id),
    KEY idx_project_status_time (project_id, status, created_time),
    KEY idx_plan_id (plan_id),
    KEY idx_status (status),
    KEY idx_created_time (created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行记录表';


-- 6.4 执行结果表（用例级）
-- [重要] step_results JSON 字段已移除，步骤级结果统一存储在 tc_execution_step_result 表
CREATE TABLE tc_execution_result (
    id                  BIGINT          NOT NULL                             COMMENT '主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0                   COMMENT '租户ID',
    execution_id        BIGINT          NOT NULL                             COMMENT '所属执行记录ID',
    case_id             BIGINT          NOT NULL                             COMMENT '用例ID',
    case_name           VARCHAR(200)                                         COMMENT '用例名称（冗余，防用例删除后丢失）',
    case_version_no     INT                                                  COMMENT '执行时用例版本号',
    status              TINYINT         NOT NULL                             COMMENT '结果状态（0通过 1失败 2跳过 3错误）',
    failure_category    VARCHAR(50)                                          COMMENT '失败分类（ENV_ERROR/CODE_BUG/TEST_BUG/FLAKY/TIMEOUT）',
    error_message       TEXT                                                 COMMENT '错误信息',
    error_stack         TEXT                                                 COMMENT '错误堆栈',
    started_time        DATETIME                                             COMMENT '开始时间',
    finished_time       DATETIME                                             COMMENT '结束时间',
    duration_ms         BIGINT                                               COMMENT '执行耗时（毫秒）',
    retry_count         INT             NOT NULL DEFAULT 0                   COMMENT '实际重试次数',
    hit_level           TINYINT                                              COMMENT '元素定位命中的层级（1最优 5最差）',
    -- 失败快照（存储路径指向 MinIO）
    screenshot_url      VARCHAR(1000)                                        COMMENT '失败截图URL',
    page_html_url       VARCHAR(1000)                                        COMMENT '失败时页面HTML',
    har_file_url        VARCHAR(1000)                                        COMMENT '网络请求HAR文件',
    console_log_url     VARCHAR(1000)                                        COMMENT '浏览器控制台日志',
    created_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_execution_status (execution_id, status),
    KEY idx_case_id (case_id),
    KEY idx_execution_id (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行结果表（用例级）';


-- 6.5 执行步骤结果表（步骤级，tc_execution_result.step_results JSON 的规范化替代）
CREATE TABLE tc_execution_step_result (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    execution_id     BIGINT          NOT NULL                                COMMENT '所属执行记录ID',
    result_id        BIGINT          NOT NULL                                COMMENT '所属用例执行结果ID',
    case_id          BIGINT          NOT NULL                                COMMENT '用例ID',
    step_order       INT             NOT NULL                                COMMENT '步骤序号',
    step_name        VARCHAR(200)                                            COMMENT '步骤描述',
    step_type        VARCHAR(20)                                             COMMENT '步骤类型',
    action_type      VARCHAR(30)                                             COMMENT '操作类型',
    status           TINYINT         NOT NULL                                COMMENT '状态（0通过 1失败 2跳过 3错误）',
    error_message    TEXT                                                    COMMENT '错误信息',
    duration_ms      BIGINT                                                  COMMENT '步骤耗时（毫秒）',
    screenshot_url   VARCHAR(1000)                                           COMMENT '步骤截图URL',
    actual_value     VARCHAR(2000)                                           COMMENT '实际值（断言步骤）',
    expected_value   VARCHAR(2000)                                           COMMENT '预期值（断言步骤）',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_result_id (result_id),
    KEY idx_execution_id (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行步骤结果表';


-- 6.6 执行节点表（Selenoid 节点管理）
-- [并发安全] current_sessions 使用 Redis INCR/DECR 作为权威计数器
--   此表的 current_sessions 仅用于监控展示，由定时任务每 10 秒同步 Redis 值
--   不能作为 session 分配的判断依据，分配前须通过 Redis 信号量判断
CREATE TABLE tc_execution_node (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    node_name        VARCHAR(100)    NOT NULL                                COMMENT '节点名称',
    node_ip          VARCHAR(50)     NOT NULL                                COMMENT '节点IP',
    node_port        INT                                                     COMMENT '节点端口',
    node_type        TINYINT         NOT NULL DEFAULT 0                      COMMENT '节点类型（0本地 1Selenoid 2Selenium Grid）',
    max_sessions     INT             NOT NULL DEFAULT 5                      COMMENT '最大并发会话数',
    current_sessions INT             NOT NULL DEFAULT 0                      COMMENT '当前会话数（监控用，Redis 为权威值）',
    heartbeat_interval_seconds INT   NOT NULL DEFAULT 10                     COMMENT '心跳间隔（秒），超过 3 倍间隔无心跳视为离线）',
    status           TINYINT         NOT NULL DEFAULT 0                      COMMENT '节点状态（0离线 1在线 2维护中）',
    browser_versions JSON                                                    COMMENT '支持的浏览器版本 {"chrome":"120","firefox":"119"}',
    resource_tags    JSON                                                    COMMENT '节点标签（用于定向分配）',
    last_heartbeat   DATETIME                                                COMMENT '最后心跳时间（超时由 watchdog 置节点状态为离线）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行节点表';


-- 6.7 执行日志表
-- [仅追加写] 无 is_deleted / version / updated_time，禁止应用账号执行 UPDATE/DELETE
-- [性能策略]
--   应用层使用 Redis List 缓冲，每 200ms 批量 INSERT 500 条，避免逐行写入 MySQL
--   按月 RANGE 分区：每月自动新建分区，历史分区归档至 MinIO 后 DROP PARTITION
--   建议保留最近 3 个月的分区在 MySQL，更早的查询通过 MinIO 归档数据提供
-- [查询说明] 查询日志时始终带上 execution_id，以利用分区裁剪
CREATE TABLE tc_execution_log (
    id               BIGINT          NOT NULL                                COMMENT '主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    execution_id     BIGINT          NOT NULL                                COMMENT '所属执行记录ID',
    case_id          BIGINT                                                  COMMENT '关联用例ID（NULL=执行级日志）',
    log_level        VARCHAR(10)     NOT NULL DEFAULT 'INFO'                 COMMENT '日志级别（INFO/WARN/ERROR/DEBUG）',
    log_content      TEXT            NOT NULL                                COMMENT '日志内容',
    log_time         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)  COMMENT '日志时间（毫秒精度）',
    PRIMARY KEY (id),
    KEY idx_execution_id (execution_id),
    KEY idx_exec_level_time (execution_id, log_level, log_time)
    -- 生产环境建议按月 RANGE PARTITION BY RANGE(YEAR(log_time)*100+MONTH(log_time))
    -- 分区管理由 XXL-JOB 负责：每月初建下月分区，3 个月前的分区归档后 DROP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行日志表（仅追加写，Redis批量写入，按月分区）';


-- ============================================================
-- 七、质量分析
-- ============================================================

-- 7.1 Flaky 用例追踪表
-- stats_period_start / stats_period_end：统计周期明确，便于数据可信度判断
-- （全时段统计无意义：1年前的失败不代表今天的稳定性）
CREATE TABLE tc_flaky_record (
    id                BIGINT          NOT NULL                               COMMENT '主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0                     COMMENT '租户ID',
    case_id           BIGINT          NOT NULL                               COMMENT '用例ID',
    project_id        BIGINT          NOT NULL                               COMMENT '项目ID',
    stats_period_start DATE           NOT NULL                               COMMENT '统计周期开始日期',
    stats_period_end   DATE           NOT NULL                               COMMENT '统计周期结束日期',
    total_runs        INT             NOT NULL DEFAULT 0                     COMMENT '周期内总执行次数',
    flaky_count       INT             NOT NULL DEFAULT 0                     COMMENT '不稳定次数',
    flaky_rate        DECIMAL(5,2)                                           COMMENT '不稳定率（%）',
    last_flaky_time   DATETIME                                               COMMENT '最近一次不稳定时间',
    status            TINYINT         NOT NULL DEFAULT 0                     COMMENT '状态（0正常 1疑似Flaky 2已确认Flaky 3已隔离）',
    auto_quarantine   TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT '是否已自动隔离',
    quarantine_time   DATETIME                                               COMMENT '隔离时间',
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        BIGINT                                                 COMMENT '创建人ID',
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_period (case_id, stats_period_start),
    KEY idx_project_status_rate (project_id, status, flaky_rate),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Flaky 用例追踪表';


-- 7.2 需求-用例覆盖追踪
CREATE TABLE tc_requirement_coverage (
    id                  BIGINT          NOT NULL                             COMMENT '主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0                   COMMENT '租户ID',
    project_id          BIGINT          NOT NULL                             COMMENT '项目ID',
    requirement_id      VARCHAR(100)    NOT NULL                             COMMENT '需求ID（外部系统 Issue Key，如 PROJ-123）',
    requirement_title   VARCHAR(500)                                         COMMENT '需求标题（冗余）',
    requirement_source  VARCHAR(50)                                          COMMENT '需求来源系统（JIRA/TAPD/ZENTAO）',
    case_id             BIGINT          NOT NULL                             COMMENT '关联用例ID',
    coverage_status     TINYINT         NOT NULL DEFAULT 0                   COMMENT '覆盖状态（0未执行 1已通过 2最近失败）',
    last_execution_id   BIGINT                                               COMMENT '最近执行记录ID',
    created_by          BIGINT                                               COMMENT '创建人ID',
    created_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted          TINYINT(1)      NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_req_case (requirement_id, case_id),
    KEY idx_requirement_id (requirement_id),
    KEY idx_case_id (case_id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求-用例覆盖追踪表';


-- ============================================================
-- 初始化基础数据
-- ============================================================

INSERT INTO sys_role (id, role_name, role_code, description, created_time, updated_time, is_deleted, version)
VALUES
(1, '管理员',     'ADMIN',    '平台全部权限，含SCRIPT步骤审批权',       NOW(), NOW(), 0, 0),
(2, '测试工程师', 'ENGINEER', '用例编写、执行、查看报告（项目级）',      NOW(), NOW(), 0, 0),
(3, '观察者',     'VIEWER',   '只读权限，仅可查看报告和用例',            NOW(), NOW(), 0, 0);
