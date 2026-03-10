-- ============================================================
-- Flyway V1: 完整建表脚本
-- 说明：与 docs/database-schema.sql 保持一致（DDL 权威来源）
--       包含所有业务表，不含种子数据（种子数据在 V2）
-- 生产环境：由 K8s init-container 执行，非应用启动触发（ADR H9）
-- ============================================================

-- ============================================================
-- 一、系统管理
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id               BIGINT          NOT NULL                                COMMENT '主键（雪花算法）',
    tenant_id        BIGINT          NOT NULL DEFAULT 0                      COMMENT '租户ID',
    username         VARCHAR(50)     NOT NULL                                COMMENT '用户名',
    password         VARCHAR(128)    NOT NULL                                COMMENT '密码（BCrypt）',
    real_name        VARCHAR(50)                                             COMMENT '真实姓名',
    email            VARCHAR(100)                                            COMMENT '邮箱',
    phone            VARCHAR(200)                                            COMMENT '手机号（AES-256-GCM 加密）',
    phone_hash       VARCHAR(64)                                             COMMENT '手机号 HMAC-SHA256（用于索引查找）',
    avatar_url       VARCHAR(500)                                            COMMENT '头像URL',
    status           TINYINT         NOT NULL DEFAULT 1                      COMMENT '状态（0禁用 1启用）',
    last_login_time  DATETIME                                                COMMENT '最后登录时间',
    last_login_ip    VARCHAR(50)                                             COMMENT '最后登录IP（180天未登录后置NULL）',
    created_by       BIGINT                                                  COMMENT '创建人ID',
    updated_by       BIGINT                                                  COMMENT '最后修改人ID',
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username, tenant_id),
    KEY idx_email (email),
    KEY idx_phone_hash (phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    role_name        VARCHAR(50)     NOT NULL,
    role_code        VARCHAR(50)     NOT NULL                                COMMENT 'ADMIN/ENGINEER/VIEWER',
    description      VARCHAR(200),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    user_id          BIGINT          NOT NULL,
    role_id          BIGINT          NOT NULL,
    project_id       BIGINT          NOT NULL DEFAULT 0                      COMMENT '0=平台级',
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role_project (user_id, role_id, project_id),
    KEY idx_user_project (user_id, project_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_api_token (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    token_name       VARCHAR(100)    NOT NULL,
    token_prefix     VARCHAR(20)     NOT NULL                                COMMENT 'wtp_prod_xxx 前16字符，明文',
    token_value      VARCHAR(256)    NOT NULL                                COMMENT 'SHA-256(完整Token)，不可逆',
    project_id       BIGINT          NOT NULL DEFAULT 0,
    permissions      JSON,
    status           TINYINT         NOT NULL DEFAULT 1,
    expired_time     DATETIME,
    last_used_time   DATETIME,
    last_used_ip     VARCHAR(50),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_value (token_value),
    UNIQUE KEY uk_token_prefix (token_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API Token 表';

-- append-only，无 is_deleted/version
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    operator_id      BIGINT          NOT NULL,
    operator_name    VARCHAR(50)     NOT NULL,
    action           VARCHAR(50)     NOT NULL,
    resource_type    VARCHAR(50)     NOT NULL,
    resource_id      BIGINT,
    resource_name    VARCHAR(200),
    before_value     TEXT,
    after_value      TEXT,
    ip_address       VARCHAR(50),
    user_agent       VARCHAR(500),
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_operator_id (operator_id),
    KEY idx_resource (resource_type, resource_id),
    KEY idx_tenant_time (tenant_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志（仅追加写）';

CREATE TABLE IF NOT EXISTS sys_notify_config (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_id       BIGINT          NOT NULL DEFAULT 0,
    config_name      VARCHAR(100)    NOT NULL,
    notify_type      TINYINT         NOT NULL                                COMMENT '1邮件 2钉钉 3企微 4飞书 5Webhook',
    config_detail    TEXT            NOT NULL                                COMMENT 'AES-256-GCM 加密密文',
    trigger_events   JSON            NOT NULL,
    trigger_rule     JSON,
    is_enabled       TINYINT(1)      NOT NULL DEFAULT 1,
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知配置表';

CREATE TABLE IF NOT EXISTS sys_notify_record (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    config_id        BIGINT          NOT NULL,
    notify_type      TINYINT         NOT NULL,
    title            VARCHAR(200),
    content          LONGTEXT,
    receivers        JSON,
    trigger_event    VARCHAR(50),
    related_id       BIGINT,
    send_status      TINYINT         NOT NULL DEFAULT 0                      COMMENT '0待发 1成功 2失败 3已放弃',
    send_time        DATETIME,
    fail_reason      VARCHAR(500),
    retry_count      INT             NOT NULL DEFAULT 0,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_config_id (config_id),
    KEY idx_status_retry (send_status, retry_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知发送记录';

CREATE TABLE IF NOT EXISTS sys_config (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    config_key       VARCHAR(100)    NOT NULL,
    config_value     TEXT,
    value_type       VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    description      VARCHAR(200),
    is_encrypted     TINYINT(1)      NOT NULL DEFAULT 0,
    created_by       BIGINT,
    updated_by       BIGINT,
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

CREATE TABLE IF NOT EXISTS tc_project (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_name     VARCHAR(100)    NOT NULL,
    project_code     VARCHAR(50)     NOT NULL,
    description      VARCHAR(500),
    owner_id         BIGINT,
    avatar_url       VARCHAR(500),
    status           TINYINT         NOT NULL DEFAULT 1,
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (project_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';

CREATE TABLE IF NOT EXISTS tc_module (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_id       BIGINT          NOT NULL,
    parent_id        BIGINT          NOT NULL DEFAULT 0,
    module_name      VARCHAR(100)    NOT NULL,
    module_path      VARCHAR(500),
    sort_order       INT             NOT NULL DEFAULT 0,
    description      VARCHAR(500),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模块表';

CREATE TABLE IF NOT EXISTS tc_env (
    id                BIGINT          NOT NULL,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    project_id        BIGINT          NOT NULL,
    env_name          VARCHAR(50)     NOT NULL,
    env_type          TINYINT         NOT NULL DEFAULT 1                     COMMENT '1开发 2测试 3预发 4生产',
    base_url          VARCHAR(500)    NOT NULL,
    description       VARCHAR(200),
    browser_config    JSON,
    proxy_config      JSON,
    ssl_ignore        TINYINT(1)      NOT NULL DEFAULT 0,
    health_check_url  VARCHAR(500),
    pre_hooks         JSON,
    post_hooks        JSON,
    is_enabled        TINYINT(1)      NOT NULL DEFAULT 1,
    created_by        BIGINT,
    updated_by        BIGINT,
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境表';

CREATE TABLE IF NOT EXISTS tc_env_variable (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    env_id           BIGINT          NOT NULL,
    var_key          VARCHAR(100)    NOT NULL,
    var_value        TEXT                                                    COMMENT 'is_encrypted=1时为 AES-256-GCM 加密密文',
    var_type         TINYINT         NOT NULL DEFAULT 0,
    is_encrypted     TINYINT(1)      NOT NULL DEFAULT 0,
    description      VARCHAR(200),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_env_var_key (env_id, var_key),
    KEY idx_env_id (env_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境变量表';

CREATE TABLE IF NOT EXISTS tc_datasource (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_id       BIGINT          NOT NULL,
    env_id           BIGINT          NOT NULL DEFAULT 0,
    ds_name          VARCHAR(100)    NOT NULL,
    db_type          VARCHAR(20)     NOT NULL DEFAULT 'MYSQL',
    host             VARCHAR(200)    NOT NULL,
    port             INT             NOT NULL,
    db_name          VARCHAR(100)    NOT NULL,
    username         VARCHAR(100)    NOT NULL,
    password         VARCHAR(500)    NOT NULL                                COMMENT 'AES-256-GCM 加密',
    connect_timeout  INT             NOT NULL DEFAULT 5,
    query_timeout    INT             NOT NULL DEFAULT 10,
    status           TINYINT         NOT NULL DEFAULT 1,
    created_by       BIGINT,
    updated_by       BIGINT,
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

CREATE TABLE IF NOT EXISTS tc_tag (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_id       BIGINT          NOT NULL,
    tag_name         VARCHAR(50)     NOT NULL,
    tag_color        VARCHAR(20),
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (project_id, tag_name),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签表';

CREATE TABLE IF NOT EXISTS tc_case (
    id                BIGINT          NOT NULL,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    project_id        BIGINT          NOT NULL,
    module_id         BIGINT,
    case_name         VARCHAR(200)    NOT NULL,
    case_type         TINYINT         NOT NULL DEFAULT 0                     COMMENT '0 UI 1接口 2视觉 3混合',
    priority          TINYINT         NOT NULL DEFAULT 1                     COMMENT '0 P0 1 P1 2 P2 3 P3',
    description       VARCHAR(1000),
    precondition      VARCHAR(1000),
    owner_id          BIGINT,
    review_status     TINYINT         NOT NULL DEFAULT 0
                          CHECK (review_status IN (0,1,2,3))                COMMENT '0草稿 1待评审 2已通过 3已废弃',
    is_template       TINYINT(1)      NOT NULL DEFAULT 0,
    expected_duration INT,
    last_run_status   TINYINT,
    last_run_time     DATETIME,
    pass_rate         DECIMAL(5,2),
    pass_rate_sample_count INT        NOT NULL DEFAULT 0,
    pass_rate_updated_time DATETIME,
    run_count         INT             NOT NULL DEFAULT 0,
    auto_isolated     TINYINT(1)      NOT NULL DEFAULT 0                     COMMENT 'Flaky 自动隔离标记',
    created_by        BIGINT,
    updated_by        BIGINT,
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

CREATE TABLE IF NOT EXISTS tc_case_step (
    id                  BIGINT          NOT NULL,
    tenant_id           BIGINT          NOT NULL DEFAULT 0,
    case_id             BIGINT          NOT NULL,
    step_order          INT             NOT NULL,
    step_name           VARCHAR(200),
    step_type           VARCHAR(20)     NOT NULL                             COMMENT 'UI/API/DB/WAIT/EXTRACT/CONDITION/SCRIPT/SUB_CASE',
    action_type         VARCHAR(30),
    locators            JSON,
    input_value         VARCHAR(2000),
    expected_result     VARCHAR(2000),
    wait_config         JSON,
    extract_config      JSON,
    condition_config    JSON,
    script_content      TEXT,
    script_type         VARCHAR(20),
    script_approved     TINYINT(1)      NOT NULL DEFAULT 0,
    sub_case_id         BIGINT,
    sub_case_params     JSON,
    api_config          JSON,
    db_config           JSON,
    on_failure          VARCHAR(20)     NOT NULL DEFAULT 'STOP',
    retry_times         INT             NOT NULL DEFAULT 0,
    screenshot_policy   VARCHAR(20)     NOT NULL DEFAULT 'ON_FAILURE',
    created_by          BIGINT,
    updated_by          BIGINT,
    created_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted          TINYINT(1)      NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_case_step_order (case_id, step_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例步骤表';

CREATE TABLE IF NOT EXISTS tc_case_tag_relation (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    case_id          BIGINT          NOT NULL,
    tag_id           BIGINT          NOT NULL,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_tag (case_id, tag_id),
    KEY idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例标签关联表';

CREATE TABLE IF NOT EXISTS tc_case_dependency (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    case_id          BIGINT          NOT NULL,
    depend_case_id   BIGINT          NOT NULL,
    dep_type         VARCHAR(20)     NOT NULL DEFAULT 'BEFORE',
    description      VARCHAR(200),
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_dependency (case_id, depend_case_id),
    KEY idx_case_id (case_id),
    KEY idx_depend_case_id (depend_case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例依赖关系表';

CREATE TABLE IF NOT EXISTS tc_case_version (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    case_id          BIGINT          NOT NULL,
    version_no       INT             NOT NULL,
    version_desc     VARCHAR(200),
    storage_type     TINYINT         NOT NULL DEFAULT 0                      COMMENT '0内联 1MinIO',
    snapshot_storage VARCHAR(20)     NOT NULL DEFAULT 'DB'                   COMMENT 'DB/MINIO/DB_TRUNCATED（ADR H6降级）',
    case_snapshot    MEDIUMTEXT,
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_case_version (case_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例版本快照表';

CREATE TABLE IF NOT EXISTS tc_case_review_record (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    case_id          BIGINT          NOT NULL,
    reviewer_id      BIGINT          NOT NULL,
    review_result    TINYINT         NOT NULL                                COMMENT '1通过 2拒绝 3待修改',
    review_comment   VARCHAR(1000),
    case_version_no  INT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_case_id (case_id),
    KEY idx_reviewer_id (reviewer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例评审记录表';

-- ============================================================
-- 四、元素库
-- ============================================================

CREATE TABLE IF NOT EXISTS tc_element_library (
    id                BIGINT          NOT NULL,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    project_id        BIGINT          NOT NULL,
    element_name      VARCHAR(100)    NOT NULL,
    element_desc      VARCHAR(500),
    page_url          VARCHAR(500),
    locators          JSON            NOT NULL,
    screenshot_url    VARCHAR(1000),
    locator_status    TINYINT         NOT NULL DEFAULT 1                     COMMENT '0失效 1健康 2降级',
    last_check_time   DATETIME,
    hit_level         TINYINT,
    is_shared         TINYINT(1)      NOT NULL DEFAULT 0,
    created_by        BIGINT,
    updated_by        BIGINT,
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

CREATE TABLE IF NOT EXISTS tc_test_data_set (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    project_id       BIGINT          NOT NULL,
    case_id          BIGINT          NOT NULL DEFAULT 0,
    set_name         VARCHAR(100)    NOT NULL,
    description      VARCHAR(500),
    column_schema    JSON            NOT NULL,
    data_source      TINYINT         NOT NULL DEFAULT 0,
    created_by       BIGINT,
    updated_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_case_id (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试数据集表';

CREATE TABLE IF NOT EXISTS tc_test_data_row (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    set_id           BIGINT          NOT NULL,
    row_index        INT             NOT NULL,
    row_data         JSON            NOT NULL,
    description      VARCHAR(200),
    is_enabled       TINYINT(1)      NOT NULL DEFAULT 1,
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_set_id (set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试数据行表';

-- ============================================================
-- 六、执行管理
-- ============================================================

CREATE TABLE IF NOT EXISTS tc_plan (
    id                BIGINT          NOT NULL,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    project_id        BIGINT          NOT NULL,
    plan_name         VARCHAR(100)    NOT NULL,
    description       VARCHAR(500),
    env_id            BIGINT,
    browser           VARCHAR(20),
    case_filter       JSON,
    max_parallel      INT             NOT NULL DEFAULT 1,
    timeout_seconds   INT             NOT NULL DEFAULT 3600,
    retry_times       INT             NOT NULL DEFAULT 0,
    fail_fast         TINYINT(1)      NOT NULL DEFAULT 0,
    schedule_type     TINYINT         NOT NULL DEFAULT 0,
    schedule_config   JSON,
    quality_gate      JSON,
    snapshot_config   JSON,
    notify_config     JSON,
    created_by        BIGINT,
    updated_by        BIGINT,
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试计划表';

CREATE TABLE IF NOT EXISTS tc_plan_case_relation (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    plan_id          BIGINT          NOT NULL,
    case_id          BIGINT          NOT NULL,
    sort_order       INT             NOT NULL DEFAULT 0,
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_case (plan_id, case_id),
    UNIQUE KEY uk_plan_sort_order (plan_id, sort_order),
    KEY idx_case_id (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划与用例关联表';

-- ADR M3: status CHECK 约束（MySQL 8.0.16+ 原生支持）
-- ADR NC2: 所有含 status 字段的表须加 CHECK 约束
CREATE TABLE IF NOT EXISTS tc_execution (
    id                      BIGINT          NOT NULL,
    tenant_id               BIGINT          NOT NULL DEFAULT 0,
    project_id              BIGINT          NOT NULL,
    plan_id                 BIGINT,
    execution_name          VARCHAR(200),
    env_id                  BIGINT          NOT NULL,
    browser                 VARCHAR(20)     NOT NULL,
    trigger_type            TINYINT         NOT NULL                         COMMENT '0手动 1定时 2Webhook 3CI/CD',
    trigger_info            JSON,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'WAITING'
                                CHECK (status IN ('WAITING','RUNNING','PASS','FAIL','INTERRUPTED','CANCELLED')),
    queue_priority          INT             NOT NULL DEFAULT 5,
    total_count             INT             NOT NULL DEFAULT 0,
    pass_count              INT             NOT NULL DEFAULT 0,
    fail_count              INT             NOT NULL DEFAULT 0,
    skip_count              INT             NOT NULL DEFAULT 0,
    error_count             INT             NOT NULL DEFAULT 0,
    pass_rate               DECIMAL(5,2),
    started_time            DATETIME,
    finished_time           DATETIME,
    duration_ms             BIGINT,
    env_snapshot            JSON                                             COMMENT '执行时环境变量快照（ADR NH2）',
    executor_node           VARCHAR(200),
    -- 断点续跑（ADR M4 / NC3）
    checkpoint_completed_ids    MEDIUMTEXT                                   COMMENT '已完成 case_id 逗号分隔（MEDIUMTEXT 16MB，ADR NC3）',
    checkpoint_interrupted_id   BIGINT                                       COMMENT '中断时的 case_id（可建索引）',
    checkpoint_time             DATETIME(3),
    allure_report_url       VARCHAR(1000),
    quality_gate_result     JSON,
    created_by              BIGINT,
    created_time            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted              TINYINT(1)      NOT NULL DEFAULT 0,
    version                 INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_status_time (project_id, status, created_time),
    KEY idx_plan_id (plan_id),
    KEY idx_status (status),
    KEY idx_chk (status, checkpoint_interrupted_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行记录表';

-- ADR NC2: tc_case_result 被大量引用（报告/Flaky/分页），必须在 V1 中定义
CREATE TABLE IF NOT EXISTS tc_case_result (
    id              BIGINT          NOT NULL,
    tenant_id       BIGINT          NOT NULL DEFAULT 0,
    execution_id    BIGINT          NOT NULL,
    case_id         BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL
                        CHECK (status IN ('PASS','FAIL','INTERRUPTED','CANCELLED')),
    retry_count     INT             NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    fail_reason     TEXT,
    fail_step_index INT,
    env_id          BIGINT,
    created_by      BIGINT          NOT NULL DEFAULT 0,
    updated_by      BIGINT          NOT NULL DEFAULT 0,
    created_time    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_deleted      TINYINT(1)      NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_exec_id (execution_id, status),
    KEY idx_case_id_time (case_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用例执行结果表';

-- ADR M11: tc_execution_artifact 必须在 V1 中定义（MinIO 清理任务依赖）
CREATE TABLE IF NOT EXISTS tc_execution_artifact (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    execution_id     BIGINT          NOT NULL,
    case_result_id   BIGINT,
    artifact_type    VARCHAR(20)     NOT NULL                                COMMENT 'SCREENSHOT/HAR/HTML/ALLURE_REPORT',
    minio_path       VARCHAR(500)    NOT NULL,
    file_size        BIGINT,
    created_by       BIGINT          NOT NULL DEFAULT 0,
    updated_by       BIGINT          NOT NULL DEFAULT 0,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_time     DATETIME                                                COMMENT 'is_deleted=1 时记录，用于 30 天 MinIO 清理',
    version          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_execution_id (execution_id),
    KEY idx_case_result_id (case_result_id),
    KEY idx_deleted (is_deleted, deleted_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行产物表（截图/HAR/HTML/报告）';

-- append-only 日志表，无 is_deleted/version
-- ADR NC1: 分区由 CI/CD 动态生成，初始分区为部署时当月起 6 个月
CREATE TABLE IF NOT EXISTS tc_execution_log (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    execution_id     BIGINT          NOT NULL,
    log_index        INT             NOT NULL                                COMMENT '单次执行内顺序号（断线续传用）',
    step_index       INT,
    level            VARCHAR(10)     NOT NULL DEFAULT 'INFO',
    content          TEXT            NOT NULL,
    created_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_execution_log (execution_id, log_index),
    KEY idx_exec_level_time (execution_id, level, created_time)
    -- 生产环境按月 RANGE 分区，由 XXL-JOB 管理（见设计文档第五节）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行日志表（仅追加写）';

CREATE TABLE IF NOT EXISTS tc_execution_node (
    id               BIGINT          NOT NULL,
    tenant_id        BIGINT          NOT NULL DEFAULT 0,
    node_name        VARCHAR(100)    NOT NULL,
    node_ip          VARCHAR(50)     NOT NULL,
    node_port        INT,
    node_type        TINYINT         NOT NULL DEFAULT 1                      COMMENT '0本地 1Selenoid',
    max_sessions     INT             NOT NULL DEFAULT 5,
    current_sessions INT             NOT NULL DEFAULT 0,
    status           TINYINT         NOT NULL DEFAULT 0                      COMMENT '0离线 1在线 2维护中',
    browser_versions JSON,
    last_heartbeat   DATETIME,
    created_by       BIGINT,
    created_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行节点表';

-- ============================================================
-- 七、质量分析
-- ============================================================

-- ADR NM1: 唯一键 uk_case_period(case_id, stats_period_end)
CREATE TABLE IF NOT EXISTS tc_flaky_record (
    id                BIGINT          NOT NULL,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    case_id           BIGINT          NOT NULL,
    project_id        BIGINT          NOT NULL,
    stats_period_start DATE           NOT NULL,
    stats_period_end   DATE           NOT NULL,
    total_count       INT             NOT NULL DEFAULT 0,
    pass_count        INT             NOT NULL DEFAULT 0,
    fail_count        INT             NOT NULL DEFAULT 0,
    flaky_rate        DECIMAL(5,4)                                           COMMENT '失败率 = fail_count/total_count',
    sample_count      INT             NOT NULL DEFAULT 0,
    is_isolated       TINYINT(1)      NOT NULL DEFAULT 0,
    auto_isolated     TINYINT(1)      NOT NULL DEFAULT 0,
    quarantine_time   DATETIME,
    created_by        BIGINT          NOT NULL DEFAULT 0,
    updated_by        BIGINT          NOT NULL DEFAULT 0,
    created_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT(1)      NOT NULL DEFAULT 0,
    version           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_period (case_id, stats_period_end),
    KEY idx_project_rate (project_id, flaky_rate),
    KEY idx_isolated (is_isolated)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Flaky 用例追踪表';

CREATE TABLE IF NOT EXISTS tc_requirement_coverage (
    id                  BIGINT          NOT NULL,
    tenant_id           BIGINT          NOT NULL DEFAULT 0,
    project_id          BIGINT          NOT NULL,
    requirement_id      VARCHAR(100)    NOT NULL,
    requirement_title   VARCHAR(500),
    requirement_source  VARCHAR(50),
    case_id             BIGINT          NOT NULL,
    coverage_status     TINYINT         NOT NULL DEFAULT 0,
    last_execution_id   BIGINT,
    created_by          BIGINT,
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
