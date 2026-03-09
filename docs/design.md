# WebTestPro 自动化测试平台 · 整体设计文档

版本：v2.0 | 状态：设计确认中

---

## 一、平台定位

**目标：** 提供一套完整的 Web 应用质量保障平台，支持多项目接入、多环境管理、UI / 接口 / 视觉三类自动化测试的统一调度与分析。

**解决三大核心痛点：**

| 痛点 | 解决方案 |
|------|---------|
| 测试用例分散，难以统一管理 | 集中式用例管理中心，支持版本追踪与回滚 |
| 执行过程黑盒，结果追踪困难 | 实时执行监控 + 失败快照（截图/HTML/HAR/控制台） |
| 报告简陋，无法支撑质量决策 | 可视化报告 + 趋势分析 + 质量门禁 |

**核心流程：**

```
被测项目接入（URL/环境变量/账号）
    → 编写测试用例（步骤可视化编排 + 多策略元素定位）
        → 触发执行（手动 / 定时 / CI/CD Webhook）
            → WebSocket 实时监控（双向流，支持断线续传）
                → 报告分析（Allure + 趋势图 + Flaky 追踪）
                    → 质量门禁（阻断 or 放行发布流水线）
```

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        Vue 3 前端                            │
│  项目接入 │ 用例管理 │ 执行中心 │ 报告分析 │ 系统配置        │
└────────────────────────┬────────────────────────────────────┘
                         │ RESTful + WebSocket
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              Nginx（API Gateway 兼前端静态服务）               │
│  限流 / 请求ID注入 / SSL终止 / 连接数控制                      │
│  /api/v1/** → upstream backend-api                          │
│  /ws/**     → upstream backend-api (WebSocket upgrade)      │
└────────────────────────┬────────────────────────────────────┘
              ┌──────────┴──────────┐
              ▼                     ▼
┌─────────────────────┐  ┌─────────────────────────┐
│  API Service        │  │  Execution Worker        │
│  Spring Boot 3      │  │  Spring Boot 3           │
│                     │  │                          │
│  项目/用例/计划 CRUD │  │  TestNG 执行引擎          │
│  WebSocket 推流     │  │  Selenium / RestAssured  │
│  Sa-Token 鉴权      │  │  Selenoid 会话管理        │
│  XXL-JOB 任务注册   │  │  结果写库 + MinIO 上传    │
└──────────┬──────────┘  └──────────┬──────────────┘
           │                         │
           └──────────┬──────────────┘
                      │ 共享基础设施
          ┌───────────▼──────────────────────────────┐
          │  Redis（三库隔离）                         │
          │  DB0: 缓存（LRU eviction，access_token / 热数据）│
          │  DB1: 执行队列（noeviction，AOF 强制开启）    │
          │  DB2: 分布式锁 + Selenoid 信号量 + refresh_token（noeviction）│
          │  + Redis Pub/Sub（WebSocket 跨节点广播）    │
          ├──────────────────────────────────────────┤
          │  MySQL 8 │ MinIO │ Selenoid │ Allure Server│
          └──────────────────────────────────────────┘
                ↑                           ↑
          Chrome 录制插件               被测 Web 应用
```

**API Service 与 Execution Worker 分离的必要性：**

| 关注点 | API Service | Execution Worker |
|--------|------------|-----------------|
| 职责 | HTTP/WebSocket 请求响应 | TestNG 并行执行、Selenoid 管理 |
| 资源特征 | CPU/内存平稳，低延迟 | CPU/内存高峰，IO 密集 |
| 伸缩策略 | 随 QPS 横向扩展 | 随并发执行数量扩展 |
| 崩溃影响 | 影响用户操作 | 不影响 API 可用性 |

**实时日志推流架构（WebSocket + Redis Pub/Sub + 断线续传）：**

```
Worker 执行 → 写入 Redis List (execution_log:{id})  ← 持久化缓冲区，支持重放
           → PUBLISH Redis channel (execution:{id}:log) {logIndex}  ← 仅通知"有新日志"
                                                ↓
API Service 1  ←─ Redis Pub/Sub ─→  API Service 2
   ↓ 收到通知（含 logIndex）                  ↓
   LINDEX execution_log:{id} {logIndex}    同上
   推送 {logIndex, content} 给 WebSocket 客户端

断线续传机制：
  客户端维护 lastIndex（最后收到的 logIndex，持久化到 sessionStorage）
  重连后客户端订阅时携带 last-index 头（STOMP SUBSCRIBE header）
  API Service 收到 SUBSCRIBE 后：
    LRANGE execution_log:{execId} {lastIndex+1} -1
    批量推送补发日志（每批 ≤100 条，避免重连风暴）
  之后切换为实时 Pub/Sub 推送模式

Pub/Sub 连接断开时的消息不丢失保证：
  日志已持久化在 Redis List → API Service 重新订阅后，
  客户端下次收到任意 logIndex 时自动触发 gap 检测（前端判断连续性），
  发现跳号则主动调用 GET /api/v1/execution/{id}/logs?from={n} 补拉
  ⚠️ [H8 已修复] 此接口之前在架构图中引用但从未定义，断线续传功能无法实现。
  接口定义：
    GET /api/v1/execution/{execId}/logs?from={logIndex}&limit={n}
    鉴权：Bearer Token（与普通接口相同，HandshakeInterceptor 之外的 REST 拦截器）
    参数：
      from     必填，返回 logIndex >= from 的日志条目（闭区间）
      limit    可选，默认 200，最大 500（防止单次补拉过多阻塞前端渲染）
    响应：
      { "list": [{"logIndex":n,"stepIndex":n,"level":"INFO","content":"...","createdTime":"..."}],
        "hasMore": true/false }
    数据源：优先查 Redis List（执行中），执行结束后降级查 tc_execution_log（MySQL）
    权限：调用方对 execId 所属 projectId 需有 exec:read 权限
```

这样无论客户端连接到哪个 API Service 节点，都能收到任意 Worker 节点产生的日志，解决 SSE 横向扩展问题。

---

## 三、技术选型

| 层次 | 技术 | 说明 |
|------|------|------|
| 前端框架 | Vue 3 + Vite | 现代化开发体验 |
| UI 组件 | Element Plus | 企业级内部平台首选 |
| 状态管理 | Pinia | Vue 3 官方推荐 |
| 图表 | ECharts | 报告趋势图 |
| API 网关 | Nginx | 限流 / 请求ID注入 / SSL终止 / WebSocket 代理（`proxy_read_timeout 3600s`） |
| 后端框架 | Spring Boot 3 | 主框架（Java 17+） |
| ORM | MyBatis-Plus | 含 TenantLineInnerInterceptor 多租户强制隔离 |
| 认证授权 | Sa-Token | RBAC / 资源级权限，双 Token（access 2h + refresh 7d），闲置超时=2h |
| API 文档 | Knife4j | SpringDoc 增强版，路径前缀 `/api/v1/` |
| 定时任务 | XXL-JOB | 分布式调度，生产双节点 HA |
| 实时推送 | WebSocket (STOMP) | 双向通信，断线续传，Redis Pub/Sub 跨节点广播 |
| 熔断限流 | Resilience4j | Selenoid/MinIO/通知渠道 各自配置 CircuitBreaker + 超时预算 |
| 数据库迁移 | Flyway | 版本化 SQL 脚本，CI 自动执行，禁止手动执行 DDL |
| 可观测性 | Micrometer + Prometheus | 指标采集，Grafana 看板 |
| 链路追踪 | SkyWalking Java Agent | 无侵入，自动传播 traceId 到 Worker 线程 / XXL-JOB |
| 日志 | Log4j2 + MDC | 每个请求注入 traceId，Worker 线程通过 ThreadLocal 传递 |
| UI 驱动 | Selenium 4 | W3C 协议，支持 Chrome DevTools |
| 驱动管理 | WebDriverManager | 自动下载匹配驱动 |
| 测试框架 | TestNG | 并行/分组/参数化/重试 |
| 接口测试 | RestAssured | HTTP 接口测试 |
| 视觉回归 | Ashot | 页面截图像素级对比 |
| 断言库 | AssertJ | 链式断言 |
| 测试报告 | Allure 2 | 可视化报告，带截图/步骤/历史趋势 |
| 浏览器节点 | Selenoid | 容器化浏览器，Redis 信号量控制并发分配 |
| 对象存储 | MinIO | 截图/HAR/报告私有化存储（兼容 S3） |
| 数据库 | MySQL 8 | 持久化，Flyway 管理版本 |
| 缓存/队列 | Redis（三库隔离） | DB0 缓存 / DB1 执行队列 / DB2 锁+信号量 |
| 密钥管理 | HashiCorp Vault（或环境变量） | AES-256-GCM 密钥，禁止写配置文件 |
| 脚本沙箱 | GraalVM Polyglot（JS）/ GroovySandbox（Groovy） | SCRIPT 步骤安全执行 |
| 容器化 | Docker Compose (开发) → K8s (生产) | 含资源限制定义 |

---

## 四、核心模块设计

### 0. 项目接入模块

**目的：** 被测项目注册到平台，建立连接关系，平台对开发团队零强依赖。

```
配置项：
├── 项目基本信息（名称 / 编码 / 负责人）
├── 多环境配置
│     ├── dev     → http://dev.app.com
│     ├── test    → http://test.app.com
│     └── staging → http://staging.app.com
│     └── 每个环境独立配置：浏览器/代理/SSL/健康检查URL/钩子脚本
│           钩子脚本安全规范（与 SCRIPT 步骤安全等级一致）：
│           ├── 支持类型：PRE_EXEC（执行前）/ POST_EXEC（执行后）/ HEALTH_CHECK（健康探测）
│           ├── 语言：仅支持 JavaScript（GraalVM Polyglot），与 SCRIPT 步骤共用同一沙箱配置
│           │     allowAllAccess(false) / allowIO(NONE) / allowCreateThread(false)
│           │     执行超时：15s（短于用例 SCRIPT 步骤的 30s，钩子不应做耗时操作）
│           ├── 审批：钩子脚本由 ENGINEER 填写，ADMIN 审批（hook_approved 字段），
│           │     未审批钩子的环境不可触发执行计划
│           ├── 权限：VIEWER 不可查看钩子脚本内容（敏感配置）
│           ├── 审计：钩子脚本创建/修改/审批均写 sys_audit_log(event_type='ENV_HOOK_CHANGE')
│           └── 禁止：钩子脚本中引用平台系统级 API（隔离运行，无 HTTP 出口）
├── 环境变量（支持 ${变量名} 在用例步骤中引用，敏感值 AES-256-GCM 加密）
  ⚠️ [NH1 已修复] 环境变量替换注入风险：若变量值含 `'`, `"`, `//`, `]` 等特殊字符，
    在 XPath/SQL 步骤中字符串拼接后产生注入。
    规范：
      - UI 步骤 XPath/CSS：${变量} 替换后的值必须经 XPath 字符串转义（replace `'` with `', '`, `'`）
      - DB 步骤 SQL：环境变量只允许出现在 PreparedStatement 的参数位（? 占位），
        禁止出现在 SQL 语句结构部分（表名/列名/操作符）；JSqlParser 校验阶段同步检查
      - API 步骤 URL：使用 URIComponentsBuilder 参数化构建，禁止字符串拼接 URL
  ⚠️ [NH2 已修复] 执行时缺少环境变量快照：执行后管理员修改变量，历史报告看到新值，
    可复现性和审计完整性被破坏。
    修复：tc_execution 新增 env_snapshot 列（JSON，MEDIUMTEXT），在执行开始时快照全部明文变量值
      （加密变量存密文，报告展示时解密，审计日志只记录 [ENCRYPTED] 占位）；
      快照在 Worker 开始执行前原子写入（避免执行中途修改变量影响在途执行）
└── 与开发约定（推荐）
      └── 关键元素加 data-testid 属性，平台优先使用最稳定定位策略
```

### 1. 用例管理模块

**目的：** 统一管理测试用例，支持版本追踪，步骤可视化配置。

**用例步骤类型（完整）：**

| 步骤类型 | 说明 |
|---------|------|
| UI | 浏览器操作（打开/点击/输入/断言/悬停/拖拽/上传等） |
| API | HTTP 接口请求（支持变量传递、响应断言） |
| DB | 数据库断言（PreparedStatement 参数化，SELECT-only，只读账号） |
| WAIT | 等待策略（显式等待/休眠/条件轮询） |
| EXTRACT | 变量提取（从页面/响应中提取值赋给变量） |
| CONDITION | 条件分支（if/else 逻辑） |
| SCRIPT | 自定义脚本（沙箱执行，须 ADMIN 审批方可加入正式计划） |
| SUB_CASE | 子用例调用（复用公共流程模板） |

**SCRIPT 步骤执行优先级：**
- ADMIN 审批 → 才可加入计划 → 沙箱内执行（30s 超时，无网络访问，无文件系统访问）

**用例属性：**
- 优先级：P0（冒烟）/ P1（核心）/ P2（全量）/ P3（扩展）
- 标签：支持多标签，可按标签动态筛选执行
- 评审状态：草稿 → 待评审 → 已通过（避免未评审用例进入正式计划）
- 版本快照：每次保存自动记录，支持回滚，最多保留 50 个版本
- 版本快照存储：< 100KB 内联存库，>= 100KB 存 MinIO 后存 file_key 引用
  ⚠️ [H6 已修复] 原方案 >= 100KB 走 MinIO 但无失败降级，用户保存时若 MinIO 不可用内容直接丢失。
  降级策略（降级不阻断保存，优先保证数据不丢）：
    快照保存流程：
      1. 尝试上传 MinIO（超时 10s，Resilience4j 熔断保护）
      2. 成功 → 存 file_key 引用，snapshot_storage='MINIO'
      3. 失败（熔断/超时）→ 截断至 99KB 内联存库，snapshot_storage='DB_TRUNCATED'
              → 写 sys_audit_log(event_type='SNAPSHOT_DEGRADED', detail='truncated to 99KB')
              → API 响应附加 warning: "快照已截断存储，MinIO 不可用时大文件内容有限保留"
      4. MinIO 恢复后（熔断半开探测成功）→ XXL-JOB 异步任务扫描 snapshot_storage='DB_TRUNCATED'
              的快照，通知用户手动重新保存（不自动重上传，内容已截断无法还原）

### 2. 元素定位体系

**目的：** 不依赖开发配合也能稳定定位，开发配合时更优。

**定位优先级（自动降级）：**

```
① data-testid  → 最稳定，需开发配合（评分5，绿色）
② id / name    → 稳定，表单元素通常有（评分4，绿色）
③ CSS Selector → 较稳定（评分3，黄色）
④ XPath        → 灵活但脆弱，兜底（评分2，黄色）
⑤ 图像识别     → 完全不依赖 DOM，最后手段（评分1，红色）
```

**三项配套能力：**
1. **Chrome 录制插件** — 点选元素自动生成多策略定位器 + 稳定性评分，不用手写
2. **定位器健康检查** — 定时探活，页面改版后主动告警，提供快速修复入口
3. **命中层级记录** — 记录每次执行命中了哪一层定位器，作为优化依据

### 3. 测试数据管理

**目的：** 支持参数化测试，与用例解耦，敏感数据安全。

```
数据集（绑定用例）
  └── 数据行（每行是一组测试数据）
        └── 字段值（敏感值 AES-256-GCM 加密存储）

执行时：用例 × 数据行 = N 次执行（数据驱动）

内置数据工厂（动态生成）：
  ${faker.phone}      → 随机手机号
  ${faker.email}      → 随机邮箱
  ${faker.name}       → 随机姓名
  ${faker.uuid}       → 随机UUID
```

### 4. 执行中心

**目的：** 灵活触发，稳定执行，实时感知。

**[M3 已明确] tc_execution 状态机（单一真相来源）：**

```
ExecutionStatus 枚举（Java: com.webtestpro.enums.ExecutionStatus）

状态          含义                        允许的后续状态
WAITING     → 已入队，等待 Worker 消费    RUNNING, CANCELLED
RUNNING     → Worker 正在执行            PASS, FAIL, INTERRUPTED, CANCELLED
PASS        → 全部用例通过（终态）         —
FAIL        → 至少一个用例失败（终态）     —
INTERRUPTED → heartbeat 超时，Watchdog   WAITING（断点续跑重新入队）
              强制中断（非终态）
CANCELLED   → 用户主动取消（终态）         —

禁止的状态跳转（Watchdog / Worker 须强制校验）：
  PASS/FAIL/CANCELLED → 任何状态（终态不可覆盖）
  WAITING → PASS/FAIL/INTERRUPTED（未开始不可直接结束）

枚举定义同步写入：
  1. Java enum ExecutionStatus（含中文 description 字段，用于 UI 展示）
  2. MySQL CHECK 约束：status IN ('WAITING','RUNNING','PASS','FAIL','INTERRUPTED','CANCELLED')
  3. 本文档（单一真相来源），其他章节引用此处，不再重复定义状态值
```

**进程分离架构：**

```
API Service                     Execution Worker
    │                                   │
    │ 1. 写 tc_execution (status=WAITING)│
    │ 2. RPUSH {exec_queue}:P0/P1/P2 id │  ← hash tag {} 保证同一 slot（Cluster 兼容）
    │                                   │← Worker 消费策略（公平调度，防止低优先级饥饿）：
    │                                   │  优先尝试 BRPOP {exec_queue}:P0（冒烟）
    │                                   │  若 P0 空 → BRPOP {exec_queue}:P1（核心）
    │                                   │  若 P0+P1 连续 60s 均有任务，P2 等待计数器递增
    │                                   │  P2 等待计数器 >= 5 时，强制消费 1 次 P2（老化机制）
    │                                   │  实现：Worker 维护 p2_skip_count（ThreadLocal），
    │                                   │        BRPOP 前判断是否到强制消费轮次
    │                                   │
    │                    3. 前置：Lua 原子 check-and-DECR 信号量
    │                       -- 必须用 Lua 保证"检查+扣减"原子性，裸 DECR 会使信号量跌为负值
    │                       local v = redis.call('GET', KEYS[1])
    │                       if tonumber(v) and tonumber(v) > 0 then
    │                         return redis.call('DECR', KEYS[1])  -- 成功获取 session
    │                       else
    │                         return -1  -- 无可用 session → 调用方重新入队
    │                       end
    │                       返回 -1 → 重新 RPUSH 回队列（保留原优先级）
    │                                   │
    │                    4. 执行用例，Worker 每 5s 写 Redis heartbeat:{execId}
    │                       heartbeat TTL = 15s
    │                                   │
    │                    5. RPUSH execution_log:{execId} {json}
    │                       PUBLISH execution:{execId}:log {json}
    │                                   │
    │← 订阅 Redis Pub/Sub ────────────────┘
    │ 6. 推送给 WebSocket 订阅此执行的客户端
    │
    │ watchdog（XXL-JOB，每 30s）：
    │   扫描 heartbeat TTL 已过期的执行
    │   ① 查询 tc_execution（status=RUNNING, heartbeat 已过期）
    │   ② Lua 脚本原子执行（防止 Watchdog 崩溃导致半执行状态）：
    │       -- ⚠️ [H5 已修复] 原脚本直接 SET statusKey INTERRUPTED，未检查当前值，
    │       --    可能覆盖 Worker 刚写入的 PASS/FAIL（heartbeat 过期与执行完成存在竞态窗口）。
    │       --    正确方案：CAS 检查，只在状态仍为 RUNNING 时才标记 INTERRUPTED。
    │       -- ⚠️ [NH4 已修复] statusKey 本身有 TTL，Redis 重启/驱逐后 key 消失；
    │       --    下次 Watchdog 扫 MySQL（status=RUNNING）时 currentStatus==nil，
    │       --    nil 不等于 'RUNNING' → 不会误触发（H5 CAS 已天然防护此场景）。
    │       --    但为彻底防止 Watchdog 双节点并发（即使 FIRST 路由，网络分区时可能双主），
    │       --    增加 MySQL 层乐观锁保护：
    │       --    UPDATE tc_execution SET status='INTERRUPTED', version=version+1
    │       --      WHERE id={execId} AND status='RUNNING' AND version={expectedVersion}
    │       --    affected_rows == 0 时跳过（说明状态已被其他 Watchdog 修改），不再归还信号量
    │       local currentStatus = redis.call('GET', KEYS[2])  -- statusKey
    │       if currentStatus == 'RUNNING' then
    │           redis.call('INCR', KEYS[1])                   -- 归还信号量（semaphoreKey）
    │           redis.call('SET',  KEYS[2], 'INTERRUPTED')    -- 标记状态（statusKey）
    │           return 1  -- 成功标记；调用方随后执行 MySQL 乐观锁 UPDATE 兜底
    │       else
    │           return 0  -- 状态已是 PASS/FAIL/nil，不覆盖，直接忽略
    │       end
    │       -- 两步在同一 Lua 脚本内，Redis 单线程保证原子性
    │   ③ UPDATE tc_execution SET status='INTERRUPTED'（持久化到 MySQL）
    │   ④ 将未完成 case_id 重新入 {exec_queue}:Px（断点续跑）
    │      断点续跑 checkpoint 规范：
    │      checkpoint 粒度：case 级别（不跨 step 级别，step 不保证幂等性）
    │      存储位置：tc_execution 独立列（[M4 已修复] 原 JSON 列无法建索引，
    │        大量 INTERRUPTED 任务恢复时全表扫描；改为独立列支持索引过滤）：
    │        checkpoint_completed_ids  MEDIUMTEXT（逗号分隔 case_id）
    │          ⚠️ [NC3 已修复] 原 TEXT 上限 65535 字节，BIGINT id 约 19 字符，
    │            3000 个 case 的 id 串约 57KB，逼近上限，大型计划（>3000 case）会静默截断。
    │            改为 MEDIUMTEXT（16MB），可容纳约 84 万个 case id，彻底消除溢出风险。
    │        checkpoint_interrupted_id BIGINT（可建索引）
    │        checkpoint_time          DATETIME(3)
    │        索引：INDEX idx_chk (status, checkpoint_interrupted_id)
    │      恢复逻辑：
    │        Worker 取到 execution_id 后读取 checkpoint_data
    │        跳过 completedCaseIds 中的 case（不重复执行）
    │        从 interruptedCaseId 开始（整个 case 重新执行，非从中断 step 继续）
    │      Step 幂等性要求：
    │        UI 步骤（浏览器操作）：默认非幂等，case 重跑会产生重复操作 → 可接受
    │        API 步骤：调用方负责幂等（测试环境数据隔离）
    │        DB 步骤：只读，天然幂等
    │        SCRIPT 步骤：脚本作者负责幂等，文档中明确提示
```

**Selenoid 信号量初始化与对账：**

```
初始化（Worker 启动时）：
  1. 调用 Selenoid API GET /status 获取实际可用并发数（browsers.*.count 之和）
  2. SET selenoid:semaphore {maxSessions} NX（仅在 key 不存在时设置，防止重启覆盖正在运行的任务）
  3. 若 key 已存在（服务重启场景），跳过设置，由 Watchdog heartbeat 过期机制归还孤儿 session

定期对账（XXL-JOB，每 5 分钟，FIRST 路由）：
  1. 查询 tc_execution status=RUNNING 的任务数 = runningCount
  2. GET selenoid:semaphore = currentSemaphore
  3. 期望值 = maxSessions - runningCount
  4. 若 |currentSemaphore - 期望值| > 0：
       写 WARNING 日志 + 强制 SET selenoid:semaphore {期望值}
       写 sys_audit_log(event_type='SEMAPHORE_RECONCILE')
  5. 若 currentSemaphore < 0：立即修正为 0 并触发告警通知

maxSessions 由 application.yml selenoid.max-sessions 配置，与 Selenoid 实例数对齐
```

**触发方式：**（选计划/环境/浏览器，支持优先级插队）
├── 定时任务（XXL-JOB Cron 表达式）
└── CI/CD Webhook（Jenkins/GitLab，支持质量门禁阻断发布）
      安全：HMAC-SHA256 签名验证
        请求头：X-Webhook-Signature: sha256={HMAC-SHA256(secret, rawBody)}
        secret 存储：HashiCorp Vault（与环境变量加密方案一致）
        [NM5 已明确] Webhook secret 轮换策略：
          轮换周期：每 6 个月或密钥泄露时立即轮换
          轮换流程：
            1. Vault 生成新 webhook_secret（保留旧 secret 7 天，Vault v2 secret versioning）
            2. 平台 UI 提示管理员"webhook_secret 已轮换，请在 CI/CD 系统更新配置"
            3. 7 天内同时接受新旧两个 secret 签名（Vault 旧版本仍可读取）
            4. 7 天后旧 secret 删除，过渡期结束
          泄露处置：立即在 Vault 中 Delete 旧 secret（使旧签名立即失效）
          写 sys_audit_log(event_type='WEBHOOK_SECRET_ROTATED', detail=项目ID)
        验证逻辑：
          1. 从请求头取 X-Webhook-Signature
          2. 用 Vault 取出 webhook_secret 重算签名
          3. 使用 MessageDigest.isEqual() 时间恒定比较（防时序攻击）
          4. 签名校验失败 → 返回 401，写 sys_audit_log(event=WEBHOOK_AUTH_FAIL)
        防重放：请求头携带 X-Webhook-Timestamp，服务端拒绝 ±5 分钟外的请求
          ⚠️ [H2 已修复] 仅校验时间戳不足以防重放：5 分钟窗口内同一合法请求可无限重放。
          需同时校验唯一 Nonce：
          请求方在请求头加入 X-Webhook-Nonce: {UUID}（每次请求唯一）
          服务端验证流程（原子性由 Lua 脚本保证）：
            1. 校验时间戳 ±5 分钟
            2. Lua 原子 SET NX：
               SET webhook:nonce:{nonce} 1 EX 600 NX
               → 返回 OK：首次出现，合法
               → 返回 nil：已使用，拒绝（HTTP 409 + 写 sys_audit_log）
          Nonce TTL=600s（10分钟，覆盖最大时间窗 5min × 2）
```

**执行能力：**

```
├── 并行执行（Selenoid 多节点，Redis 信号量限流，ThreadLocal 隔离 WebDriver）
├── 失败重试（步骤级 retry_times 优先，计划级次之，最多 3 次，Flaky 用例自动隔离）
├── 断点续跑（Worker heartbeat 超时后 watchdog 触发，从 checkpoint 恢复）
├── 执行快照（失败时自动保存：截图 + 页面 HTML + 网络 HAR + 控制台日志 → MinIO）
├── 熔断保护（Resilience4j）
│     ├── Selenoid 会话分配：超时 30s，失败率 50% 触发熔断
│     ├── MinIO 上传：超时 10s，失败降级为跳过截图（不阻断执行）
│     └── 通知发送：超时 5s，熔断后写 sys_notify_record(status=FAILED) 异步重试
└── 执行计划取消
      API：DELETE /api/v1/execution-plan/{planId}/cancel
      取消流程：
        1. UPDATE tc_execution SET status='CANCELLED' WHERE plan_id={planId}
                                  AND status IN ('WAITING','RUNNING')
        2. PUBLISH Redis channel execution:{planId}:cancel（广播取消信号）
        3. Worker 在执行循环开始前检查 status='CANCELLED'，若是则跳过并归还信号量
        4. Worker 已在 BRPOP 等待中（WAITING）的任务弹出后检测到 CANCELLED 状态直接丢弃
           （不执行，不消耗 session）
        5. 取消完成后推送 WebSocket 通知客户端
      幂等保证：重复调用取消接口安全（UPDATE 不影响非目标状态的记录）
```

**日志写入优化：**

```
⚠️ [C1 已修复] 原方案用外部 offset 配合 LRANGE {offset} {offset+499} + LTRIM {offset+n} -1
   存在致命语义错误：LTRIM 执行后 List 索引从 0 重置，下次 LRANGE 从旧 offset 读会跳过数据。
   正确方案：始终从 index 0 读取，LTRIM 移除头部已持久化的条目，List 自身即游标。

Worker 执行线程 → RPUSH execution_log:{execId} {json}（异步，不阻塞执行）
后台 LogFlusher 线程（每 200ms）：
  1. LRANGE execution_log:{execId} 0 499（始终从 index 0 读，取最多 500 条）
  2. 若列表为空则跳过本次 flush
  3. 批量 INSERT INTO tc_execution_log（事务）
     ├── 成功：LTRIM execution_log:{execId} {actualWrittenCount} -1
     │         （移除头部已持久化的 N 条；List 索引自动从 0 重置，下次仍从 0 读）
     └── 失败：不执行 LTRIM，保留全部数据，下次 flush 重试（天然幂等，tc_execution_log
               以 (execution_id, log_index) 为唯一键，重复 INSERT 走 ON DUPLICATE KEY IGNORE）
  4. 执行结束后（status=PASS/FAIL/INTERRUPTED）：
       继续 flush 直至 LLEN execution_log:{execId} == 0（排空）
       DEL execution_log:{execId}（清除 Redis List）

Redis List TTL 兜底：
  EXPIRE execution_log:{execId} 86400（24h TTL，防止异常未清理的孤儿 List）

LogFlusher 崩溃恢复：
  Worker 重启后直接从 LRANGE 0 499 继续，幂等安全
  （ON DUPLICATE KEY IGNORE 保证重复写入不产生脏数据）

[NM4 已明确] LogFlusher INSERT 持续失败时的 Redis List 无界增长防护：
  若 MySQL 持续不可用（超过阈值），Worker 继续 RPUSH 而 LTRIM 永远不执行，
  DB1（noeviction）内存会被耗尽导致全局 RPUSH 失败（影响所有执行队列）。
  防护策略（双重保护）：
    1. Resilience4j 保护 LogFlusher 的 INSERT 路径（超时 5s，失败率 50% 触发熔断）
       熔断打开时：停止继续 RPUSH（Worker 的日志写入降级为只写 execution_log:{execId}:overflow 计数）
       并通知 Grafana 告警："日志未持久化，执行 {execId} 日志可能丢失"
    2. 单个 execution_log:{execId} List 长度上限：
       RPUSH 前检查 LLEN execution_log:{execId}，若 > 50000 条则丢弃新日志（写告警）
       （50000 条 × 平均 200 字节 ≈ 10MB，DB1 512MB 内存下可容纳 ~50 个并发执行）

MySQL 写入压力从 ~4000 INSERT/s 降至 ~20 批次/s（每批 ≤500 条）

[备选方案] Redis Stream（更彻底）：
  Worker:      XADD execution_log:{execId} * log_index {n} content {json}
  LogFlusher:  XREADGROUP GROUP flusher consumer1 COUNT 500 STREAMS execution_log:{execId} >
  INSERT 成功: XACK execution_log:{execId} flusher {messageId...}
  优势：内置消费者组游标，崩溃后自动从 PEL（Pending Entry List）恢复，无需 ON DUPLICATE 兜底
```

### 5. 报告分析

**目的：** 数据驱动质量决策。

```
单次报告（Allure）：
  └── 步骤明细 + 失败截图 + 错误信息（Allure Report 存 MinIO）
  [M6 已明确] 前端访问方式：API Service 生成 MinIO presigned GET URL（有效期 1h）
    GET /api/v1/execution/{execId}/report-url → { "url": "...", "expiresAt": "..." }
    前端在 iframe/新窗口打开 URL；MinIO 不经 Nginx 直接暴露，预签名 URL 即凭证
    生成前已校验调用者对该 execId 有 exec:read 权限

趋势分析：
  ├── 历史通过率折线图
  ├── 模块质量分布饼图
  ├── 用例稳定性排行（失败率 Top10）
  └── 执行耗时分析（超时异常预警）

覆盖率追踪：
  └── 需求-用例-执行 三层覆盖率（对接 Jira/TAPD）
```

### 6. Flaky 用例管理

**目的：** 识别不稳定用例，避免其干扰质量判断。

```
统计周期：滚动 30 天（stats_period_start / stats_period_end 明确标注）
自动识别：统计周期内结果不一致的用例（flaky_rate > 阈值）
最小样本量保护：
  └── 触发自动隔离需同时满足：
        ① pass_rate_sample_count >= 10（至少 10 次执行，统计结果才有效）
        ② flaky_rate > 30%（不稳定率超阈值）
  └── 样本量 < 10 时：
        · 不触发自动隔离
        · UI 展示黄色警告："样本量不足（N/10），Flaky 率仅供参考"
        · 仍可手动标记为 Flaky
自动隔离：同时满足 ① + ② 时自动移出正式执行计划（标记 tc_case.auto_isolated=1）
告警通知：通知用例负责人处理
UI 展示："基于最近 30 天 N 次执行" 的可信度说明（N < 10 时额外展示低可信度提示）
```

### 7. 通知中心

```
渠道：邮件 / 钉钉 / 企微 / 飞书 / 自定义 Webhook
渠道凭证（robot_token / webhook_url）：AES-256-GCM 加密存储

触发事件：
  ├── EXEC_FINISH      执行完成
  ├── EXEC_FAIL        执行失败（连续 N 次才通知，由 trigger_rule.consecutive_fail 配置）
  ├── GATE_BLOCKED     质量门禁拦截
  └── FLAKY_FOUND      发现新 Flaky 用例

防干扰：
  └── 去重窗口期（trigger_rule.dedup_window_minutes，同类事件窗口期内不重复发送）

发送失败：写 sys_notify_record(status=FAILED)，重试上限由 trigger_rule.max_retries 控制
熔断：单渠道连续 5 次超时后触发 Resilience4j 熔断，10 分钟后半开探测
```

### 8. 权限管理

```
RBAC 模型（资源级隔离）：

角色                    权限
管理员（ADMIN）       → 平台全部权限 + SCRIPT 步骤审批
测试工程师（ENGINEER）→ 用例编写 + 执行 + 查看报告（项目级）
观察者（VIEWER）      → 只读，仅查看报告和用例

关键设计：
  ├── project_id 级别的角色绑定（A 工程师只能访问项目X）
  ├── sys_user_role.project_id=0 表示平台级角色（消除 NULL 歧义）
  └── sys_api_token（CI/CD 系统调用，独立于用户会话）
```

---

## 五、数据库设计规范

所有表强制遵循（**例外：sys_audit_log 和 tc_execution_log 为仅追加写，无 is_deleted / version**）：

```sql
id            BIGINT    主键（雪花算法，分布式友好）
tenant_id     BIGINT    租户ID（多租户预留，初期默认0）
created_by    BIGINT    创建人ID
updated_by    BIGINT    最后修改人ID
created_time  DATETIME  创建时间
updated_time  DATETIME  最后修改时间
is_deleted    TINYINT   逻辑删除（禁止物理删除业务数据）
version       INT       乐观锁版本号
```

**[M2 已明确] 乐观锁（`@Version`）使用范围与冲突重试策略：**

需要 `@Version` 注解的实体（存在并发修改冲突风险的核心业务实体）：

| 实体类 | 表名 | 原因 |
|--------|------|------|
| `TcCase` | tc_case | 多人协作编辑用例步骤 |
| `TcProject` | tc_project | 环境配置/成员变更并发 |
| `TcPlan` | tc_plan | 计划状态变更并发 |
| `TcExecution` | tc_execution | Worker + Watchdog 并发更新状态 |
| `SysUser` | sys_user | 角色变更 + 禁用并发 |

不需要 `@Version` 的表（append-only 或仅单一写入路径）：
`tc_execution_log`, `sys_audit_log`, `tc_execution_artifact`, `tc_flaky_record`

**冲突重试策略（统一规范）：**
```java
// MyBatis-Plus 乐观锁插件自动处理 version 字段，冲突时抛出 OptimisticLockException
// Service 层统一用 @Retryable 重试，最多 3 次，间隔 100ms（指数退避）：
@Retryable(value = OptimisticLockException.class, maxAttempts = 3,
           backoff = @Backoff(delay = 100, multiplier = 2))
public void updateCase(TcCase caseEntity) { ... }
// 3 次后仍失败 → 抛 BizException(OPTIMISTIC_LOCK_CONFLICT, "操作冲突，请刷新后重试")
```

**DDL 规范校验：** 提交前执行 lint 脚本，检查每张业务表是否包含上述 8 个字段。审计表、日志表豁免。
**[NM3 已明确] CHECK 约束强制要求：** 所有含状态字段的表须在 DDL 中加 CHECK 约束，不可仅依赖应用层校验：
- `tc_execution.status`: `CHECK (status IN ('WAITING','RUNNING','PASS','FAIL','INTERRUPTED','CANCELLED'))`
- `tc_case_result.status`: `CHECK (status IN ('PASS','FAIL','INTERRUPTED','CANCELLED'))`
- `tc_case.review_status`: `CHECK (review_status IN ('DRAFT','PENDING','APPROVED'))`
- lint 脚本同步检查：含 `status` 列的业务表是否有对应 CHECK 约束，否则 CI 阻断

**数据库版本管理：** 使用 Flyway，结构如下：
```
src/main/resources/db/migration/
  V1__initial_schema.sql    — 所有 CREATE TABLE（含所有字段，无 ALTER TABLE）
    [M11 已修复] 以下表必须全部包含在 V1（原清单遗漏 tc_execution_artifact）：
      sys_user / sys_role / sys_user_role / sys_api_token / sys_audit_log / sys_notify_config
      sys_notify_record / tc_project / tc_env / tc_env_variable / tc_datasource
      tc_case / tc_case_version / tc_step / tc_locator / tc_plan / tc_plan_case
      tc_execution / tc_execution_log（分区表，含当月起 6 个月分区）
      tc_execution_artifact（⚠️ Phase 2 执行结果写入文件元数据，Phase 0 必须建表）
      tc_case_result（⚠️ [NC2 已修复] 此表被分页接口、Flaky 统计、报告分析大量引用但从未定义，
        Phase 2 Worker 写入结果时即报错。完整 DDL 见下方）
      tc_flaky_record / tc_test_data / tc_test_data_row
  V2__seed_data.sql         — INSERT INTO sys_role
  V3__add_indexes.sql       — 复合索引（独立脚本方便回滚）
```

⚠️ [H9 已修复] 原方案由应用启动时自动执行 Flyway，K8s 滚动发布时多个 Pod 并发启动，
Flyway 虽有数据库锁（flyway_schema_history 表锁），但在网络分区或 Pod 抢锁超时场景下
仍有脚本部分执行的风险。生产环境改为 K8s init-container 单次执行：

```yaml
# K8s Deployment backend-api / execution-worker 均加入此 initContainer
initContainers:
  - name: flyway-migrate
    image: flyway/flyway:10
    args: ["-url=jdbc:mysql://$(DB_HOST):3306/$(DB_NAME)",
           "-user=$(DB_USER)", "-password=$(DB_PASSWORD)",
           "-locations=filesystem:/flyway/sql",
           "migrate"]
    env:
      - name: DB_HOST
        valueFrom: { secretKeyRef: { name: db-secret, key: host } }
      # ... 其他环境变量从 Secret 注入
    volumeMounts:
      - name: migration-scripts
        mountPath: /flyway/sql
# 主容器的 flyway.enabled=false（application.yml），彻底禁用应用层自动迁移
```
每次 Deployment 滚动更新时，init-container 在主容器启动前单次执行迁移，
K8s 保证同一 ReplicaSet 的多个 Pod 不会同时启动 init-container（串行就绪）。

**禁止：** 在应用程序之外手动执行 DDL；禁止在单文件中混合 CREATE TABLE 和 ALTER TABLE 补丁。

**分区表说明（豁免 Flyway 管理 DDL 的唯一例外）：**

`tc_execution_log` 为高写入量日志表，按月 RANGE 分区，分区管理由 XXL-JOB 定时任务执行（非手动），不经过 Flyway。
这是本平台唯一允许应用代码执行 DDL 的场景，须严格遵守以下规范：

```sql
-- ⚠️ [NC1 已修复] 原脚本硬编码 2025-xx 分区，部署时已全部过期，数据会全进 p_future，
--    月度 REORGANIZE 新增分区后 p_future 永远不会被归档清理。
--    正确做法：V1 脚本应在执行时动态生成"当月起 6 个月"的分区范围，
--    不可静态写死。Flyway init-container 执行时使用以下模板脚本（Bash/Python 生成）：
--    当前月（2026-03）起 6 个月分区示例如下，实际应由 CI/CD 在 V1 生成时替换：

-- V1__initial_schema.sql 中的分区表定义（含当月起 6 个月初始分区）
CREATE TABLE tc_execution_log (
    id          BIGINT      NOT NULL,
    execution_id BIGINT     NOT NULL,
    log_index   INT         NOT NULL,  -- 单次执行内的顺序号（断线续传用）
    step_index  INT,
    level       VARCHAR(10),
    content     TEXT,
    created_time DATETIME(3) NOT NULL
) ENGINE=InnoDB
  PARTITION BY RANGE (TO_DAYS(created_time)) (
    -- 以下分区由 CI/CD 在生成 V1 脚本时动态替换（${PARTITION_MONTH_+0} 等变量）
    -- 当前为 2026-03 部署示例，实际分区随部署日期前移
    PARTITION p_202603 VALUES LESS THAN (TO_DAYS('2026-04-01')),
    PARTITION p_202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p_202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p_202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p_202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p_202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p_future  VALUES LESS THAN MAXVALUE  -- 兜底分区，防止插入越界
);
-- 注：tc_execution_log 为 append-only，不含 is_deleted / version / updated_by / updated_time

-- 每月 1 日自动追加下个月分区（XXL-JOB 执行，FIRST 路由，单节点执行）：
-- ALTER TABLE tc_execution_log REORGANIZE PARTITION p_future INTO (
--     PARTITION p_YYYYMM VALUES LESS THAN (TO_DAYS('YYYY-MM+1-01')),
--     PARTITION p_future  VALUES LESS THAN MAXVALUE
-- );

-- 归档旧分区（> 3 个月，XXL-JOB 执行，单节点）：
-- 1. 先 SELECT MIN/MAX created_time 验证分区数据确实已超 90 天
-- 2. INSERT INTO tc_execution_log_archive SELECT ... FROM tc_execution_log PARTITION (p_YYYYMM)
-- 3. 上传归档数据至 MinIO（minio/archive/execution_log/YYYY/MM/）
-- 4. ALTER TABLE tc_execution_log DROP PARTITION p_YYYYMM
```

**分区 DDL 执行安全规范：**
- XXL-JOB 路由策略：`FIRST`（确保双节点 HA 下仅单节点执行，防止并发 DDL）
- 执行前持 Redis 分布式锁 `partition:ddl:lock`（TTL=300s）
- 每次执行写 `sys_audit_log(event_type='PARTITION_DDL', detail=DDL语句)` 审计
- 归档完成并验证 MinIO 文件 MD5 后才执行 `DROP PARTITION`（防止归档失败导致数据丢失）

---

## 六、安全设计

### 6.1 加密设计

**AES-256-GCM 加密规范：**

```
密钥来源：HashiCorp Vault 或环境变量（KMS 注入），禁止写 application.yml
加密模式：AES-256-GCM
IV：每次加密生成 96bit（12 字节）随机 IV
  ⚠️ [H4 已修复] 96-bit 随机 IV 在约 2^48（≈2.8 亿亿）次加密后碰撞概率达 50%（生日悖论）。
  单租户低频场景可接受，但高并发多租户场景下同一密钥加密次数必须受控。
  IV 碰撞监控与密钥轮换触发策略：
    ├── 每个加密密钥版本（key_version）维护加密计数器：
    │     INCR encryption:count:{key_version}（原子计数，存 Redis DB2 noeviction）
    ├── 当计数器超过 2^32（约 42 亿次）时触发告警 + 自动启动密钥轮换流程
    │     （2^32 远低于碰撞阈值 2^48，留充足安全余量）
    ├── 密钥轮换：Vault 生成新 key_version → 异步批量重加密 → 更新 key_version 字段
    └── 每个租户独立密钥（tenant_id 维度），防止单租户高并发影响其他租户
AAD（Additional Authenticated Data）：
  必须使用 AAD 将密文绑定到上下文，防止跨字段/跨行密文移植攻击
  AAD 构造方式："{tableName}:{columnName}:{tenantId}:{recordId}"
    示例：tc_env_variable:var_value:1:12345
  AAD 不存入数据库（解密时按相同规则重新计算）
  ⚠️ 加密工具类 encrypt(plaintext, aad) / decrypt(ciphertext, aad) 必须强制传入 AAD
存储格式：Base64(IV[12字节] + 密文 + AuthTag[16字节])
  IV 长度 = 12 字节（96 bit），AuthTag 长度 = 16 字节（128 bit，完整长度）
密钥轮换：支持 key_version 字段，旧密钥解密后用新密钥重新加密
```

**加密字段清单：**

| 表 | 字段 | 加密方式 |
|----|------|---------|
| sys_user | phone | AES-256-GCM |
| sys_user | phone_hash | HMAC-SHA256（用于索引查找） |
| sys_user | password | BCrypt |
| tc_env_variable | var_value（is_encrypted=1时） | AES-256-GCM |
| tc_datasource | password | AES-256-GCM |
| sys_notify_config | config_detail | AES-256-GCM（整列加密） |
| sys_api_token | token_value | SHA-256（不可逆，仅用于验证） |

### 6.2 API Token 设计

```
生成格式：wtp_{env}_{Base62(32字节随机)}
  示例：wtp_prod_aB3kL9mNpQ...（约 43 字符）

存储：
  token_prefix = 明文前缀（wtp_prod_aB3kL9...前16字符）用于 UI/日志识别
  token_value  = SHA-256(完整Token)，不可逆

验证流程：
  1. 从请求头取 Authorization: Bearer wtp_prod_xxx...
  2. SHA-256(token) 查询 sys_api_token.token_value
  3. 检查 status=1 / expired_time / project_id 权限
```

### 6.3 SCRIPT 步骤沙箱

```
JavaScript（GraalVM Polyglot）：
  // ⚠️ [H1 已修复] Future.cancel(true) 对 GraalVM native 代码无效：
  //    GraalVM JS 执行的是编译后的本地代码，不检查 Java 线程中断标志（Thread.interrupted()），
  //    cancel(true) 只设置中断标志，GraalVM 不响应，脚本会继续跑直到自然结束。
  //    正确方案：用 ScheduledExecutorService 在超时后调用 context.close(true) 强制关闭上下文。
  Context context = Context.newBuilder("js")
      .allowAllAccess(false)
      .allowIO(IOAccess.NONE)
      .allowCreateThread(false)
      .option("engine.WarnInterpreterOnly", "false")
      .build();

  ScheduledFuture<?> killer = scheduler.schedule(() -> {
      context.close(true);  // true = 强制取消正在执行的脚本，唯一可靠的中断方式
  }, 30, TimeUnit.SECONDS);

  try {
      context.eval("js", scriptSource);
      killer.cancel(false);  // 正常完成，取消 killer
  } catch (PolyglotException e) {
      if (e.isCancelled()) {
          throw new ScriptTimeoutException("JS 脚本执行超时（30s）");
      }
      throw e;
  } finally {
      if (!context.isClosed()) context.close();
  }
  // ScheduledExecutorService scheduler 为 Worker 级单例，复用线程池

Groovy（GroovySandbox）：
  CompilerConfiguration config = new CompilerConfiguration();
  config.addCompilationCustomizers(new SandboxTransformer());  // GroovySandbox

  // SecureASTCustomizer —— 严格白名单（注意：黑名单方式不安全，必须用白名单）
  SecureASTCustomizer secure = new SecureASTCustomizer();
  secure.setAllowedImports(Arrays.asList(
      // 仅允许以下包，其余全部拒绝
      "java.lang.Math", "java.lang.String", "java.lang.Integer",
      "java.lang.Long", "java.lang.Double", "java.lang.Boolean",
      "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.HashMap",
      "groovy.json.JsonSlurper", "groovy.json.JsonOutput"
  ));
  // 明确禁止高危类（防御纵深）
  secure.setDisallowedImports(Arrays.asList(
      "java.io.*", "java.nio.*", "java.net.*",
      "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.System",
      "java.lang.Thread", "java.lang.reflect.*",
      "java.util.concurrent.*", "sun.*", "com.sun.*",
      "groovy.lang.MetaClass", "org.codehaus.groovy.runtime.*"
  ));
  // 禁止方法调用白名单之外的方法（防止 MOP/invoke 逃逸）
  secure.setAllowedStaticImports(Collections.emptyList());
  config.addCompilationCustomizers(secure);

  // 执行超时：通过 ExecutorService + Future 强制 30s 超时
  Future<?> future = executorService.submit(() -> {
      GroovyShell shell = new GroovyShell(sandboxClassLoader, new Binding(), config);
      shell.evaluate(scriptSource);
  });
  try {
      future.get(30, TimeUnit.SECONDS);
  } catch (TimeoutException e) {
      future.cancel(true);
      throw new ScriptTimeoutException("脚本执行超时（30s）");
  }
  // 使用独立 ClassLoader（sandboxClassLoader），禁止加载平台内部类

审批流程：
  ENGINEER 编写含 SCRIPT 步骤的用例后，步骤 script_approved=0
  ADMIN 查看脚本内容后手动置 script_approved=1
  执行计划前检查：计划内若有 script_approved=0 的步骤，阻断执行并提示
```

### 6.4 DB 断言安全

```
SQL 安全规范：
  1. 使用 JDBC PreparedStatement 参数化（禁止字符串拼接）
     正确：executeQuery("SELECT status FROM orders WHERE id = ?", params)
     错误：executeQuery("SELECT status FROM orders WHERE id = " + orderId)
  2. 执行前解析 SQL AST，非 SELECT 语句直接拒绝（不依赖字符串前缀判断）
     [M1 已明确] 使用 JSqlParser（com.github.jsqlparser:jsqlparser）解析 AST：
       Statement stmt = CCJSqlParserUtil.parse(sql);
       if (!(stmt instanceof Select)) { throw new SqlNotAllowedException(); }
       // JSqlParser 对 MySQL 方言支持完整（LIMIT/GROUP BY/子查询），
       // 不选用 Calcite（重型，启动慢，初期不必要）
  3. 强制追加 LIMIT 100（防止全表扫描）
  4. 查询超时：tc_datasource.query_timeout（默认 10 秒）
  5. 使用只读账号连接（tc_datasource.username 应为只读账号）
  6. 网络策略：K8s NetworkPolicy 限制 Worker Pod 只能访问已注册的 tc_datasource 主机
```

### 6.5 接口安全

```
限流（Redis 滑动窗口）：
  ├── 执行触发：每个项目每分钟最多 10 次
  ├── 登录：同 IP 每分钟最多 10 次
  ├── API Token 验证（sys_api_token SHA-256 查询）：同 IP 每分钟最多 20 次
  │     ⚠️ [H3 已修复] 原方案仅限登录和执行触发，Bearer Token 验证路径暴露 SHA-256 枚举攻击面。
  │     连续 20 次失败 → 封禁来源 IP 10 分钟（SET block:ip:{ip} 1 EX 600）
  │     每次失败写 sys_audit_log(event_type='API_TOKEN_FAIL', detail=来源IP)
  └── API 通用：单用户每分钟 200 次

会话策略（Sa-Token + Refresh Token 双 Token 机制）：
  ├── access_token  TTL=7200（2小时），存 Redis DB0（缓存库，redis-cache 实例）
  ├── refresh_token TTL=604800（7天），存 Redis DB2（noeviction，redis-lock 实例，防 LRU 驱逐）
  ├── [M8 已明确] Sa-Token 默认使用单 Redis 实例，access/refresh 分实例存储需自定义存储适配器：
  │     实现路径：
  │       1. 实现 SaTokenDao 接口（sa-token-core 提供的扩展点）
  │       2. 在 get/set/delete/getTokenTimeout 方法中：
  │          - key 以 "satoken:access:" 开头 → 操作 redisTemplateCache（DB0，redis-cache）
  │          - key 以 "satoken:refresh:" 开头 → 操作 redisTemplateLock（DB2，redis-lock）
  │       3. @Bean SaTokenDao customSaTokenDao() 注入替换默认实现
  │       注意：两个 RedisTemplate 使用各自的 LettuceConnectionFactory（独立连接池），
  │             不可共用，防止跨实例操作
  ├── 续期流程：
  │     前端在 access_token 过期前 5 分钟（响应头 X-Token-Expire-In < 300s）
  │     自动调用 POST /api/v1/auth/refresh（携带 refresh_token）
  │     服务端：验证 refresh_token → 作废旧 refresh_token → 签发新 token pair
  ├── activity-timeout = 7200（闲置 2 小时 refresh_token 同步失效）
  ├── 并发会话：同一用户最多 5 个并发登录（每个设备独立 token pair）
  └── 权限变更（角色修改/禁用用户）→ 调用 StpUtil.logout(userId) 强制下线所有 token pair

安全响应头（Nginx 统一添加）：
  X-Content-Type-Options: nosniff
  X-Frame-Options: SAMEORIGIN
  Content-Security-Policy: default-src 'self'
  Strict-Transport-Security: max-age=31536000
```

### 6.6 审计日志事件清单

`sys_audit_log` 为 append-only 表，以下操作**必须**写入审计日志：

| 事件类型 | 触发操作 | 级别 |
|---------|---------|------|
| `USER_LOGIN` | 用户登录成功 | NOTICE |
| `USER_LOGIN_FAIL` | 登录失败（含原因：密码错误/账号禁用） | WARNING |
| `USER_LOGOUT` | 主动登出 / 被强制下线 | NOTICE |
| `ROLE_CHANGE` | 用户角色新增/变更/删除 | CRITICAL |
| `USER_DISABLE` | 账号禁用/启用 | CRITICAL |
| `SCRIPT_APPROVE` | SCRIPT 步骤审批通过/拒绝（含审批人 + 脚本摘要） | CRITICAL |
| `SECRET_ACCESS` | 敏感字段（环境变量/数据源密码）明文读取 | CRITICAL |
| `API_TOKEN_CREATE` | API Token 创建（记录 token_prefix） | CRITICAL |
| `API_TOKEN_REVOKE` | API Token 撤销 | CRITICAL |
| `PLAN_TRIGGER` | 执行计划触发（含触发方式：手动/定时/Webhook） | NOTICE |
| `PLAN_DELETE` | 执行计划删除 | WARNING |
| `WEBHOOK_AUTH_FAIL` | Webhook 签名验证失败（记录来源 IP） | WARNING |
| `PARTITION_DDL` | 分区 DDL 执行（含完整 DDL 语句） | WARNING |

**公共字段：** `event_type`, `operator_id`, `operator_ip`, `tenant_id`, `target_type`, `target_id`, `detail`(JSON), `created_time`

**字段命名说明（与标准表公共字段的差异）：**
`sys_audit_log` 为 append-only 审计表，字段语义与标准业务表不同，对应关系如下：

| 标准业务表字段 | sys_audit_log 字段 | 说明 |
|--------------|-------------------|------|
| `created_by` | `operator_id` | 执行操作的用户ID；审计表语义更明确，专用命名，**MyBatis-Plus `@TableField(fill=INSERT)` 须单独配置 audit 表** |
| `updated_by` | （无） | append-only 不适用 |
| `updated_time` | （无） | append-only 不适用 |
| `is_deleted` | （无） | append-only 不可删除，豁免 |
| `version` | （无） | append-only 无并发更新，豁免 |

**实现注意：** 全局 `MetaObjectHandler` 自动填充 `created_by` 时，需排除 `sys_audit_log` 表（通过判断 `TableInfo.getTableName()` 跳过），由审计服务构造 AuditLog 对象时手动赋值 `operatorId`。

### 6.7 WebSocket 鉴权设计

**问题背景：** Sa-Token 拦截器只处理标准 HTTP 请求，WebSocket Upgrade 请求和后续帧均不经过 Spring MVC 过滤器链，必须单独鉴权。

```
鉴权流程：

1. 握手阶段（HTTP Upgrade）
   客户端连接时在 URL 中携带 token：
     ws://host/ws/execution/{execId}?token=eyJhbGci...
   服务端实现 HandshakeInterceptor：
     public boolean beforeHandshake(ServerHttpRequest req, ...) {
         String token = UriComponentsBuilder.fromUri(req.getURI())
                            .build().getQueryParams().getFirst("token");
         // ⚠️ [C2 已修复] 原写法 !StpUtil.getLoginIdByToken(token) != null 是双重否定，
         //    Java 中 !x 对 Object 无效（编译错误），实际等价于逻辑混乱，会错误拒绝/放行所有连接。
         if (token == null || StpUtil.getLoginIdByToken(token) == null) {
             response.setStatusCode(HttpStatus.UNAUTHORIZED);
             return false;  // 拒绝握手，返回 401
         }
         // 将 loginId 和 projectId 存入 WebSocketSession attributes
         attributes.put("loginId", StpUtil.getLoginIdByToken(token));
         return true;
     }
   ⚠️ 禁止通过请求头传 token（浏览器 WebSocket API 不支持自定义请求头）

2. 权限校验（握手阶段）
   验证 token 对应用户有权限查看该 execId 所属项目：
     Long projectId = executionService.getProjectId(execId);
     StpUtil.checkPermission("project:" + projectId + ":exec:read");
   无权限 → 握手拒绝，返回 403

3. Token 过期处理（连接存续期间）
   WebSocket 连接可能持续数小时，access_token（2h）可能过期：
   - 客户端每 90 分钟通过独立 HTTP 接口 POST /api/v1/auth/refresh 续期
   - 续期成功后，客户端发送 STOMP SEND 帧：
       SEND /app/ping {"newToken": "eyJhbGci..."}
   - 服务端 STOMP 消息处理器更新 Session attributes 中的 token
   - 若客户端未续期且 access_token 已失效：
       服务端检测到 token 过期后主动发送 STOMP ERROR 帧并关闭连接
       客户端收到 ERROR 后重新登录并重连

4. 断线重连
   - 客户端维护 lastReceivedIndex（最后收到的日志序号）
   - 重连后发送 STOMP SUBSCRIBE 帧携带 header: last-index={n}
   - 服务端从 Redis List execution_log:{execId} 的 index n+1 开始
     LRANGE execution_log:{execId} {n+1} -1 补发历史日志
   - 确保断线期间产生的日志不丢失

5. Nginx WebSocket 代理配置（已有，补充鉴权相关）
   proxy_read_timeout 3600s;       # 保持长连接
   proxy_set_header Upgrade $http_upgrade;
   proxy_set_header Connection "upgrade";
   # ⚠️ 不在 Nginx 层拦截 token，由应用层 HandshakeInterceptor 负责
```

**STOMP 订阅鉴权：**
```
客户端订阅路径：/topic/execution/{execId}/log
服务端 ChannelInterceptor.preSend() 拦截 SUBSCRIBE 帧：
  1. 解析 destination 路径提取 execId
  2. 从 Session attributes 取 loginId
  3. 校验 loginId 对 execId 所属 projectId 有读权限
  4. 无权限 → StompHeaderAccessor 设置 ERROR 帧，返回 null 拦截
```

---

## 七、可观测性设计

### 7.1 健康检查

```
Spring Boot Actuator 端点：
  /actuator/health          → K8s liveness probe
  /actuator/health/readiness → K8s readiness probe（含 DB/Redis 连通性检查）
  /actuator/prometheus      → Prometheus 指标采集

K8s Pod 配置：
  livenessProbe:  GET /actuator/health          初始延迟 30s，周期 10s
  readinessProbe: GET /actuator/health/readiness 初始延迟 10s，周期 5s
```

### 7.2 指标监控（Micrometer + Prometheus + Grafana）

**核心指标定义：**

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `execution_queue_depth` | Gauge | Redis 执行队列深度（超过 50 告警） |
| `selenoid_session_utilization` | Gauge | Selenoid 会话使用率（超过 80% 告警） |
| `websocket_connection_count` | Gauge | 活跃 WebSocket 连接数 |
| `notification_delivery_failure_rate` | Counter | 通知发送失败率 |
| `db_assertion_timeout_total` | Counter | DB 断言超时次数 |
| `execution_duration_ms` | Histogram | 执行耗时分布 |
| `circuit_breaker_state` | Gauge | 各熔断器状态（0=CLOSED, 1=OPEN, 2=HALF_OPEN） |

**Grafana 看板（必建）：**
- 执行平台概览（队列深度 / 节点利用率 / 当日通过率）
- 执行引擎健康（熔断器状态 / 会话利用率 / Worker 心跳）
- 业务质量趋势（项目通过率曲线 / Flaky 用例数变化）

### 7.3 链路追踪（SkyWalking）

```
部署方式：SkyWalking Java Agent（无侵入，JVM 启动参数注入）

traceId 传播链路：
  HTTP 请求 → 注入 MDC("traceId")
           → ThreadLocal 传递到 Worker 执行线程
           → XXL-JOB 任务上下文
           → Redis 操作（日志附带 traceId）
           → 所有 Log4j2 日志自动包含 traceId

响应头：自动注入 X-Trace-Id: {traceId}（前端捕获，便于用户反馈问题）

响应体的 traceId 字段继续保留：
  {"code":0,"msg":"success","data":{},"traceId":"abc123"}
```

### 7.4 日志敏感数据脱敏

**防护目标：** 密码、Token、密钥等字段不得出现在任何日志输出（含异常堆栈）中。

**实现方案：**

```java
// 1. Log4j2 RewritePolicy —— 正则替换敏感字段
public class SensitiveDataRewritePolicy implements RewritePolicy {
    private static final Pattern PATTERN = Pattern.compile(
        "(?i)(password|token|secret|var_value|phone)([\"'=:\\s]+)[^\\s\"',}]{4,}",
        Pattern.CASE_INSENSITIVE
    );
    @Override
    public LogEvent rewrite(LogEvent event) {
        String masked = PATTERN.matcher(event.getMessage().getFormattedMessage())
                               .replaceAll("$1$2****");
        return new Log4jLogEvent.Builder(event)
                               .setMessage(new SimpleMessage(masked)).build();
    }
}

// 2. Jackson 序列化脱敏 —— 敏感 DTO 字段注解
@JsonSerialize(using = MaskSerializer.class)
private String password;

// 3. Entity toString() 覆盖 —— 禁止 Lombok @Data 自动包含敏感字段
// 明确 @ToString(exclude = {"password", "tokenValue", "varValue"})
```

**脱敏范围清单：**

| 位置 | 敏感字段 | 脱敏方式 |
|------|---------|---------|
| Log4j2 全局 | password, token, secret, var_value | RewritePolicy 正则替换 |
| Jackson 序列化 | SysUser.phone, TcDatasource.password | `@JsonSerialize(MaskSerializer)` |
| 异常堆栈（Hibernate Validator） | 所有 ConstraintViolation message | 移除字段值，只保留字段名 |
| 审计日志 detail 字段 | 所有 is_encrypted=1 的 var_value | 仅记录 `[ENCRYPTED]` 占位符 |

---

## 八、多租户强制隔离

```
实现方式：MyBatis-Plus TenantLineInnerInterceptor

配置：
  MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
  interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
      () -> TenantContext.getCurrentTenantId()  // 从 ThreadLocal 取
  ));

TenantContext 填充时机：
  Sa-Token 登录后将 tenant_id 写入 UserDetails
  每个请求通过 TenantFilter 从 UserDetails 读取并设置 TenantContext
  XXL-JOB 任务通过 JobContext 携带 tenant_id，任务开始时设置 TenantContext

豁免表（不加租户条件）：
  sys_role（全局角色定义）
  sys_config（全局配置）

验证：集成测试断言：租户A 的 token 无法读取租户B 的任何数据
```

---

## 九、工程规范

### 接口统一规范

```json
{
  "code": 0,
  "msg": "success",
  "data": {},
  "traceId": "abc123"
}
```
- `code=0` 成功，非 0 为业务错误码
- 所有接口统一用 `Result<T>` 包装，不直接返回裸数据

**API 版本策略：** 所有接口路径以 `/api/v1/` 开头。Breaking Change 升 major 版本为 `/api/v2/`，旧版本保留 6 个月后废弃。

### 分页规范

```
请求：page=1&size=20&sort=created_time&order=desc
响应：{ "list":[], "total":100, "page":1, "size":20 }
```

**[M12 已明确] GET /api/v1/execution/{execId}/case-results 分页定义：**

```
GET /api/v1/execution/{execId}/case-results?page=1&size=20&status={status}&caseId={caseId}

参数：
  page     页码，从 1 开始，默认 1
  size     每页条数，默认 20，最大 100
  status   可选过滤：PASS / FAIL / INTERRUPTED / CANCELLED（不传则返回全部）
  caseId   可选过滤：按指定用例 id 查询（用于用例级历史查看）

响应：
  {
    "list": [
      { "caseResultId": 1001, "caseId": 201, "caseName": "登录测试",
        "status": "FAIL", "duration": 3200, "retryCount": 1,
        "failReason": "元素未找到", "screenshotUrl": "/report-url/...",
        "startTime": "2026-03-09T10:00:00", "endTime": "2026-03-09T10:00:03" }
    ],
    "total": 127, "page": 1, "size": 20,
    "summary": { "total": 127, "pass": 100, "fail": 25, "interrupted": 2 }
  }

实现说明：
  查询 tc_case_result WHERE execution_id={execId}（含 status 过滤）
  对 100+ 用例执行场景：size 上限 100，前端用分页滚动加载
  summary 统计值从 tc_execution 表聚合字段读取（预写入，不实时 COUNT(*)）
```

### 全局异常处理

- `@RestControllerAdvice` 统一捕获，映射为标准错误响应
- 业务异常：`BizException(errorCode, message)`
- 系统异常：日志记录堆栈 + traceId，响应 500 通用错误（不暴露内部细节）

### Redis 分库隔离

| Redis DB | 用途 | maxmemory-policy | 持久化要求 |
|----------|------|-----------------|-----------|
| DB0 | 缓存（session / 热数据） | allkeys-lru | RDB 可选 |
| DB1 | 执行队列（BRPOP/RPUSH） | noeviction | **必须开启 AOF**（appendonly yes + appendfsync everysec），防止重启丢失待执行任务 |
| DB2 | 分布式锁 + Selenoid 信号量 + refresh_token | noeviction | AOF 推荐（锁 TTL 短，重启后自动过期，可接受 RDB） |

**执行队列键命名规范（Redis Cluster 兼容）：**
```
{exec_queue}:P0   → 冒烟/最高优先级
{exec_queue}:P1   → 核心用例
{exec_queue}:P2   → 全量用例

花括号 {} 使三个队列键映射到同一 hash slot，
保证 BRPOP {exec_queue}:P0 {exec_queue}:P1 {exec_queue}:P2 0
在 Cluster 模式下不跨 slot 操作（避免 CROSSSLOT 错误）
```

生产环境使用三个独立 Redis 实例，开发环境通过 DB 编号隔离即可。
# [M9 已明确] 架构图与 K8s 部署章节的表述统一如下：
# 开发环境（Docker Compose）：单个 Redis 实例，通过 database 编号（DB0/DB1/DB2）隔离
#   application-dev.yml:
#     spring.data.redis.database=0  # access_token / 缓存
#     redis.queue.database=1        # 执行队列（自定义 RedisTemplate）
#     redis.lock.database=2         # 分布式锁 + refresh_token（自定义 RedisTemplate）
# 生产环境（K8s）：三个独立 Redis Sentinel 集群（redis-cache / redis-queue / redis-lock）
#   application-prod.yml:
#     spring.data.redis.host=redis-cache     # 主 RedisTemplate，DB0 缓存
#     redis.queue.host=redis-queue           # 队列 RedisTemplate
#     redis.lock.host=redis-lock             # 锁 RedisTemplate
# 切换方式：Spring Profile（-Dspring.profiles.active=dev|prod），两套配置均保持三个 RedisTemplate Bean

### 接口限流

- 执行触发：每个项目每分钟最多 10 次（Redis 滑动窗口）
- 登录：同 IP 每分钟最多 10 次
- Redis Pub/Sub 连接：单用户最多 3 个订阅
- WebSocket 连接：单用户最多 5 个并发连接（[M5 已修复] 原方案仅限 Pub/Sub 订阅数，
  未限制 WebSocket 连接数本身。100 个连接 × 每连接约 1.5MB 内存 = 150MB，
  需在 HandshakeInterceptor.beforeHandshake() 中强制检查：
    String loginId = StpUtil.getLoginIdByToken(token).toString();
    int connCount = wsSessionRegistry.countByLoginId(loginId);
    if (connCount >= 5) { response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS); return false; }
  wsSessionRegistry 使用 ConcurrentHashMap<loginId, AtomicInteger> 记录活跃连接数，
  连接关闭时 AfterConnectionClosed 回调递减）

### Worker 优雅停机

K8s 滚动发布 / 手动重启时，Worker Pod 收到 SIGTERM，需保障在途执行不丢失：

```
SIGTERM 触发流程：
  1. 停止 BRPOP 拉取（不再接受新任务）
  2. 等待当前所有执行线程完成（最长等待 terminationGracePeriodSeconds=300s）
  3. 若超时仍未完成：
     ├── 将执行 status 置为 INTERRUPTED
     ├── INCR selenoid:semaphore（归还会话槽，与 Watchdog 补偿逻辑一致）
     └── 将未完成 case_id 重新入 {exec_queue}:Px（断点续跑）
  4. 正常退出（exit 0）

K8s 配置：
  terminationGracePeriodSeconds: 300
  lifecycle:
    preStop:
      exec:
        command: ["/bin/sh", "-c", "sleep 5"]  # 等待 kube-proxy 摘除流量

Spring Boot 配置（application.yml）：
  server:
    shutdown: graceful
  spring:
    lifecycle:
      timeout-per-shutdown-phase: 270s  # 留 30s 余量给 K8s 强杀窗口
```

### DB 断言安全规范

- 强制 PreparedStatement 参数化，禁止字符串拼接
- SQL AST 解析白名单（非 SELECT 语句直接拒绝）
- 强制追加 LIMIT 100
- 查询超时默认 10 秒
- 使用只读账号

### 用例版本快照清理策略

- 每个用例最多保留 **50 个**版本快照
- 超出后自动清理最旧版本
- < 100KB 存 MySQL，>= 100KB 存 MinIO + 写 file_key 引用
- 清理任务由 XXL-JOB 每日凌晨执行

### Docker Compose 资源限制（开发/测试环境）

```yaml
services:
  web-test-pro-backend:
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '2.0'
  web-test-pro-worker:
    deploy:
      resources:
        limits:
          memory: 3G
          cpus: '4.0'
  mysql:
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '2.0'
  redis:
    deploy:
      resources:
        limits:
          memory: 512M
  selenoid:
    deploy:
      resources:
        limits:
          memory: 2G     # 每 Chrome 实例约 400MB，默认 5 并发
          cpus: '4.0'
  minio:
    deploy:
      resources:
        limits:
          memory: 1G
```

---

## 十、数据清理与备份策略

### 清理任务（XXL-JOB 执行）

**路由策略规范（防止双节点 HA 并发执行同一任务导致数据冲突）：**

| 任务类型 | 路由策略 | 原因 |
|---------|---------|------|
| 数据清理/归档/DDL | `FIRST`（固定第一个注册节点） | 必须单节点执行，防止并发 DROP PARTITION / 重复归档 |
| Watchdog 心跳检测 | `FIRST` + Redis 分布式锁兜底 | 并发执行会导致信号量双重 INCR |
| 信号量对账 | `FIRST` | 并发对账结果不一致 |
| tc_case 通过率重算 | `SHARDING_BROADCAST` | 可按 case_id 分片，各节点计算不同 case |
| Flaky 率重算 | `FIRST` | 滚动窗口计算需全量数据，不适合分片 |

| 任务 | 频率 | 规则 |
|------|------|------|
| 用例版本快照清理 | 每日凌晨 02:00 | 每个用例保留最近 50 个版本 |
| 执行日志归档 | 每月 1 日凌晨 03:00 | > 3 个月的日志按月分区归档至 MinIO，验证 MD5 后 DROP 旧分区；同步 REORGANIZE p_future 追加新月分区（详见第五节分区表说明） |
| 审计日志归档 | 每年 1 月 1 日 | > 2 年的记录归档至 MinIO，从表中删除 |
| 登录 IP 脱敏 | 每周日 | last_login_ip：180 天未登录置 NULL |
| MinIO 文件清理 | 每日凌晨 04:00 | 查询 tc_execution_artifact.is_deleted=1 且 deleted_time < NOW()-30天，DELETE MinIO 对象后删除元数据行 |
| 信号量对账 | 每 5 分钟 | FIRST 路由，检测并修正 selenoid:semaphore 与实际 RUNNING 任务数的偏差 |

**tc_execution_artifact 表说明（文件元数据，is_deleted 传播路径）：**

```sql
-- tc_execution_artifact 追踪所有执行产物（截图/HAR/HTML/报告）
-- 遵循标准 8 字段规范（含 is_deleted）
CREATE TABLE tc_execution_artifact (
    id            BIGINT    PRIMARY KEY,
    execution_id  BIGINT    NOT NULL,       -- 关联 tc_execution.id
    case_result_id BIGINT,                  -- 关联具体 case 执行结果
    artifact_type VARCHAR(20) NOT NULL,     -- SCREENSHOT / HAR / HTML / ALLURE_REPORT
    minio_path    VARCHAR(500) NOT NULL,    -- MinIO 对象路径（含桶名）
    file_size     BIGINT,
    -- 标准 8 字段（含 is_deleted）...
    deleted_time  DATETIME,                 -- is_deleted=1 时记录删除时间（用于 30 天倒计时）
    ...
);

-- is_deleted 级联规则：
-- 1. 执行记录逻辑删除：UPDATE tc_execution SET is_deleted=1 时，
--    同步 UPDATE tc_execution_artifact SET is_deleted=1, deleted_time=NOW()
--    WHERE execution_id={id}（由 ExecutionService.softDelete() 统一处理）
-- 2. MinIO 清理任务只操作 tc_execution_artifact（不直接依赖 tc_execution.is_deleted）
-- 3. 上传失败处理：若 MinIO 上传失败（Resilience4j 熔断降级），
--    不创建 tc_execution_artifact 记录（避免出现 minio_path 指向不存在对象的孤儿记录）
--    tc_execution_artifact 记录仅在 MinIO 上传成功后创建（幂等）

-- ⚠️ [NC2 已修复] tc_case_result：单个用例的单次执行结果（被报告/Flaky/分页大量引用但从未定义）
CREATE TABLE tc_case_result (
    id              BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    execution_id    BIGINT       NOT NULL,          -- 关联 tc_execution.id
    case_id         BIGINT       NOT NULL,          -- 关联 tc_case.id
    status          VARCHAR(20)  NOT NULL,          -- 与 ExecutionStatus 枚举一致：PASS/FAIL/INTERRUPTED/CANCELLED
    retry_count     INT          NOT NULL DEFAULT 0, -- 本次实际重试次数
    duration_ms     BIGINT,                         -- 执行耗时（毫秒）
    fail_reason     TEXT,                           -- 失败原因（首条失败步骤的错误信息）
    fail_step_index INT,                            -- 失败步骤序号
    env_id          BIGINT,                         -- 执行时使用的环境 ID
    -- 标准 8 字段（is_deleted / version 按规范保留）
    created_by      BIGINT       NOT NULL,
    updated_by      BIGINT       NOT NULL,
    created_time    DATETIME(3)  NOT NULL,
    updated_time    DATETIME(3)  NOT NULL,
    is_deleted      TINYINT      NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_case_result_status CHECK (status IN ('PASS','FAIL','INTERRUPTED','CANCELLED')),
    INDEX idx_exec_id (execution_id, status),       -- 分页查询主索引
    INDEX idx_case_id_time (case_id, created_time)  -- Flaky 率统计索引
) ENGINE=InnoDB;
-- is_deleted 级联：ExecutionService.softDelete() 同步置 tc_case_result.is_deleted=1
-- 分区策略：随 tc_execution 量级，初期无需分区；> 1000 万行后考虑按 execution_id HASH 分区
```
| tc_case.pass_rate 重算 | 每日凌晨 01:00 | 异步重算近 30 次通过率，更新 pass_rate_sample_count / pass_rate_updated_time |
| Flaky 率重算 | 每日凌晨 01:30 | 滚动 30 天窗口，更新 tc_flaky_record
  [NM1 已明确] tc_flaky_record 表结构与重算规范：
  字段：case_id BIGINT（唯一键之一）/ stats_period_end DATE（唯一键之二）/
        stats_period_start DATE / total_count INT / pass_count INT / fail_count INT /
        flaky_rate DECIMAL(5,4)（fail_count/total_count 取绝对值）/
        sample_count INT / is_isolated TINYINT / 标准 8 字段
  唯一键：UNIQUE KEY uk_case_period (case_id, stats_period_end)
  重算方式：INSERT INTO tc_flaky_record ... ON DUPLICATE KEY UPDATE（按唯一键幂等覆盖）
  重算 SQL 核心逻辑：
    SELECT case_id, COUNT(*) total, SUM(status='PASS') pass, SUM(status='FAIL') fail
    FROM tc_case_result
    WHERE created_time >= DATE_SUB(NOW(), INTERVAL 30 DAY) AND is_deleted=0
    GROUP BY case_id
    HAVING total >= 1
  → flaky_rate = ABS(fail/total)；sample_count >= 10 AND flaky_rate > 0.3 才触发自动隔离 |
| 节点心跳 watchdog | 每 30 秒 | 检测 heartbeat TTL 过期的执行：Lua 原子执行 INCR 信号量 + 置 INTERRUPTED，触发断点续跑 |

### 备份策略

**MySQL：**
- 每日全量备份（mysqldump / XtraBackup），保留 30 天
- Binlog 实时归档至 MinIO，保留 7 天
- 异地副本：生产环境开启 MySQL 主从复制

**MinIO：**
- 生产环境开启 Erasure Coding（至少 4+2 配置）
- 跨机房桶复制（MinIO Bucket Replication）

**RTO / RPO 目标：**

| 场景 | RTO | RPO |
|------|-----|-----|
| MySQL 单节点故障 | 5 分钟（主从切换） | < 1 秒（Binlog 实时同步） |
| MinIO 节点故障 | 0（Erasure Coding 自动恢复） | 0 |
| 机房级故障 | 30 分钟 | < 5 分钟 |

---

## 十一、分阶段落地计划

```
Phase 0  架构基础（当前阶段）
         ✅ 整体设计确认（v2.0）
         ✅ 数据库表结构完整设计（docs/database-schema.sql）
         ▢ 搭建 HashiCorp Vault（[M10 已修复] Phase 1 起需 Vault 注入 AES/Webhook 密钥，
              必须在 Phase 0 完成 Vault 初始化）
              - Docker Compose 新增 vault 服务（dev 模式）
              - 初始化 secret/webtestpro/aes-key、secret/webtestpro/webhook-secret 路径
              - Spring Boot 集成 spring-cloud-vault-config，启动时从 Vault 注入密钥
         ▢ 定义核心 API 契约（OpenAPI 3.0，/api/v1/ 前缀）
         ▢ 搭建 Docker Compose 基础环境（MySQL/三个独立Redis实例/MinIO/Selenoid/Vault）
         ▢ Redis DB1 AOF 持久化配置验证（docker-compose redis-queue 启动参数）
         ▢ 配置 SkyWalking OAP + Grafana + Prometheus 基础栈
         ▢ Flyway 初始化脚本（V1__initial_schema.sql / V2__seed_data.sql）

Phase 1  测试引擎
         ▢ Selenium + TestNG + POM 设计模式
         ▢ 智能定位引擎（多策略备选/自动降级）
         ▢ 用例解析器（读取步骤 → 解析定位 → 变量替换 → 执行）
         ▢ UI / 接口 / 视觉三类用例跑通
         ▢ 结果上报 + Allure 报告生成
         ▢ SCRIPT 步骤沙箱（GraalVM JS + GroovySandbox）
         ▢ DB 断言 PreparedStatement + SQL AST 白名单

Phase 2  后端服务
         ▢ Spring Boot 项目骨架（API Service + Execution Worker 双进程）
         ▢ Flyway 集成 + TenantLineInnerInterceptor 多租户强制隔离
         ▢ 项目接入 + 用例管理 + 计划管理 CRUD
         ▢ Sa-Token 双 Token 认证（access_token DB0 + refresh_token DB2 + HMAC-SHA256 phone_hash）
         ▢ 执行调度（{exec_queue}:P0/P1/P2 优先级队列 + Worker BRPOP + 心跳 Watchdog Lua 原子补偿）
         ▢ Webhook HMAC-SHA256 签名验证（Vault 存 secret + 防重放时间窗）
         ▢ Worker 优雅停机（SIGTERM 处理 + terminationGracePeriodSeconds=300）
         ▢ WebSocket 实时日志推流（Redis Pub/Sub 跨节点广播）
         ▢ Redis LogFlusher 批量写入 tc_execution_log
         ▢ Resilience4j 熔断（Selenoid/MinIO/通知）
         ▢ MinIO 文件存储集成
         ▢ AES-256-GCM 加密工具类（密钥来自 Vault/环境变量）
         ▢ sys_audit_log 审计服务（12 类事件，append-only）
         ▢ Log4j2 SensitiveDataRewritePolicy 脱敏 + @ToString(exclude) 规范

Phase 3  前端界面
         ▢ Vue 3 项目骨架（API 路径统一 /api/v1/）
         ▢ 项目/环境配置页面
         ▢ 用例管理（树形结构 + 步骤可视化编排）
         ▢ 执行中心（WebSocket 实时日志 + 执行历史）
         ▢ 报告分析（Allure 嵌入 + ECharts 趋势图）
         ▢ pass_rate 展示"基于N次执行"标注

Phase 4  工程化
         ▢ Jenkins CI/CD 集成 + 质量门禁
         ▢ 钉钉/邮件通知
         ▢ Chrome 录制插件（Manifest V3，独立技术方案设计）
         ▢ Flaky 用例自动检测
         ▢ K8s 生产部署方案（含 API Service + Worker 独立 Deployment）
         ▢ XXL-JOB HA 双节点
         ▢ 数据清理定时任务上线
         ▢ MySQL 备份 + MinIO Erasure Coding 配置
```

---

## 十二、部署架构（目标）

**Docker Compose（开发/测试环境）：**

```
Docker Compose
  ├── web-test-pro-backend    API Service（Spring Boot）
  ├── web-test-pro-worker     Execution Worker（Spring Boot，独立进程）
  ├── web-test-pro-frontend   Vue 3（Nginx，含 API Gateway 配置）
  ├── mysql                   MySQL 8（含 Flyway 初始化）
  ├── redis-cache             Redis 7（DB0，LRU eviction）
  ├── redis-queue             Redis 7（DB1，noeviction）
  ├── redis-lock              Redis 7（DB2，noeviction）
  ├── minio                   对象存储（截图/报告）
  ├── xxl-job-admin           定时任务管理（单节点）
  ├── selenoid                浏览器节点
  ├── skywalking-oap          链路追踪收集器
  ├── prometheus              指标采集
  ├── grafana                 监控看板
  └── allure                  Allure 报告服务
```

**K8s（生产环境）：**

```
Namespace: web-test-pro
  Deployment: backend-api      replicas: 2+（HPA 按 CPU 扩缩）
  Deployment: execution-worker replicas: 2+（KEDA ScaledObject 按 Redis 队列深度扩缩）
    # 原生 HPA 无法读取 Redis 队列深度，必须使用 KEDA
    # KEDA ScaledObject 示例：
    # triggers:
    #   - type: redis
    #     metadata:
    #       address: redis-queue:6379
    #       listName: "{exec_queue}:P0"   # 优先监控 P0 队列
    #       listLength: "5"               # 队列深度 > 5 时开始扩容
    # minReplicaCount: 2
    # maxReplicaCount: 20
    # scaleDown.stabilizationWindowSeconds: 300  # 缩容防抖 5 分钟
    #
    # ⚠️ KEDA hash tag 兼容性说明：
    #   KEDA Redis scaler 使用标准 Redis LLEN 命令，花括号 {} 对 Sentinel 模式透明，
    #   但在 Redis Cluster 模式下必须验证 KEDA 版本 >= 2.13（已修复 hash tag LLEN 问题）
    #   本方案使用三个独立 Redis Sentinel 实例（非 Cluster），hash tag 不影响 LLEN，
    #   但仍需在 staging 验证 KEDA scaler 可正确读取 {exec_queue}:P0 的 LLEN
    #   若验证失败备选方案：使用不带 hash tag 的键名 exec_queue:P0（Sentinel 模式不需要 Cluster slot）
    #   同时监控三个队列总深度：在 KEDA ScaledObject 中配置 3 个 triggers，取最大值触发扩容
  Deployment: xxl-job-admin    replicas: 2（HA 模式）
    # ⚠️ [H7 已修复] 默认 8080 端口无认证，匿名用户可手动触发任务、查看执行日志、禁用 Watchdog。
    # 必须配置：
    # 1. xxl.job.admin.accessToken = ${XXL_JOB_TOKEN}（Vault 注入，非空强制）
    #    xxl-job-executor 侧同样配置相同 token，双向验证
    # 2. K8s NetworkPolicy：xxl-job-admin 只允许来自 backend-api / execution-worker 命名空间的流量
    #    拒绝所有外部直接访问（包括来自前端 Nginx 的流量）
    # 3. xxl-job-admin Web UI 端口（8080）不通过 Ingress 暴露，
    #    仅 cluster-internal Service，运维通过 kubectl port-forward 访问
    # [NM2 已明确] XXL-JOB 双节点 FIRST 路由 failover 行为：
    #   FIRST 路由 = 按 Executor 注册顺序取第一个节点；节点宕机后 XXL-JOB 自动摘除，
    #   下次调度时 FIRST 路由漂移到存活节点（无需人工干预，约 30s 心跳超时后生效）。
    #   切换期间 Watchdog（每 30s）和对账任务（每 5min）最多丢失 1 次调度，
    #   Watchdog 丢失 1 次（30s）可接受（heartbeat TTL=15s，Worker 已自行标记 INTERRUPTED）；
    #   对账任务丢失 1 次（5min）可接受（信号量偏差由下次对账修正）。
    #   不需要额外的选举机制，XXL-JOB 内置注册中心（DB 心跳表）已满足 HA 需求。
  StatefulSet: mysql           主从复制
  StatefulSet: redis-cache     1主2从+Sentinel（DB0，allkeys-lru，缓存 / access_token）
  StatefulSet: redis-queue     1主2从+Sentinel（DB1，noeviction+AOF，执行队列）
    # ⚠️ [NH3 已修复] 未配置 min-replicas-to-write，主库故障时从库延迟可能导致已入队任务
    #    在 Sentinel 切换后丢失（主库接受写入但从库未同步）。
    #    必须配置（redis-queue 主库 redis.conf）：
    #      min-replicas-to-write 1          # 至少 1 个从库确认才允许写入
    #      min-replicas-max-lag 10          # 从库延迟超过 10s 时主库拒绝写入（写入失败触发重试）
    #    Worker RPUSH 失败时抛异常 → API Service 层捕获并返回 503，前端提示用户稍后重试
    #    redis-cache（DB0）和 redis-lock（DB2）对 min-replicas-to-write 要求较低（可接受短暂丢失）
  StatefulSet: redis-lock      1主2从+Sentinel（DB2，noeviction，锁+信号量+refresh_token）
  # 注意：Redis Cluster 模式不支持多 DB（仅 DB0），
  #       三库隔离必须使用三个独立 Redis 实例（各自 Sentinel HA），不可合并为单一 Redis Cluster
  StatefulSet: minio           Erasure Coding 集群
  NetworkPolicy: 限制 Worker Pod 只能访问已注册 datasource 主机
  # [M7 已明确] Selenoid 和 MinIO 管控界面须网络隔离：
  # Selenoid UI（:4444）：仅允许 execution-worker 命名空间访问，禁止 Ingress 暴露
  #   NetworkPolicy: ingress from execution-worker only，运维用 kubectl port-forward
  # MinIO Console（:9001）：仅允许运维跳板机（固定 CIDR）访问，不经 Nginx 暴露
  #   MinIO API（:9000）：仅允许 backend-api + execution-worker 访问
  # 两者均不通过公网 Ingress 暴露，防止未授权触发浏览器会话/删除对象存储
```

---

## 附录：架构决策记录（ADR）

> **用途**：将设计评审中发现的所有关键约束集中在此表，供代码 Review、新成员 Onboarding、架构评审一键索引。
> **维护规则**：每次修改对应模块代码前，先查阅本表；新增架构决策时同步追加行。

| 编号 | 决策 / 正确做法 | 禁止行为 / 约束条件 | 违反后果 |
|------|----------------|-------------------|---------|
| C1 | LogFlusher 始终从 Redis List index 0 读取（`LRANGE 0 499`），写入成功后执行 `LTRIM {actualCount} -1` 移除头部 | 禁止在 Redis 外维护任何 offset 变量；禁止用 `LTRIM 0 {n-1}` 保留头部 | 日志重复写入或跳过，`LTRIM` 语义逆转导致数据全量丢失 |
| C2 | `HandshakeInterceptor` 中 token 校验：`if (token == null \|\| StpUtil.getLoginIdByToken(token) == null)` | 禁止双重否定写法 `!getLoginIdByToken(token) != null`；禁止省略 null 判断 | 双重否定永远为 false，WebSocket 鉴权完全失效，任意用户可接入 |
| H1 | GraalVM JS 超时必须通过 `ScheduledExecutorService` 调用 `context.close(true)` 实现强制中断 | 禁止使用 `Future.cancel(true)` 中断 GraalVM Context；禁止依赖 JVM 线程中断信号 | `Future.cancel` 对原生 GraalVM 代码无效，脚本永久阻塞 Worker 线程，耗尽线程池 |
| H2 | Webhook 验证：HMAC-SHA256 签名 + 5 分钟时间戳窗口 + Redis `SET NX EX 600` Nonce 去重（三重） | 禁止仅验证签名而不校验时间戳；禁止省略 Nonce 检查 | 缺少时间戳可无限期重放；缺少 Nonce 可在 5 分钟窗口内重放 |
| H3 | API Token 接口（`/api/v1/user/api-token`）必须接入 Resilience4j RateLimiter（10次/分钟/IP） | 禁止对 Token 签发接口不限速；禁止共用宽泛全局限速规则 | 攻击者可暴力枚举 API Token 或大量创建消耗系统资源 |
| H4 | AES-256-GCM 使用随机 96-bit IV；当单密钥累计加密次数达 2^32（约 43 亿）时触发密钥轮换告警 | 禁止复用 IV；禁止不设轮换阈值无限使用同一密钥 | IV 碰撞破坏 GCM 认证标签，导致加密数据完整性失效 |
| H5 | Watchdog Lua 脚本：仅当 `GET statusKey == 'RUNNING'` 时才执行 `INCR semaphore + SET INTERRUPTED`；Redis 操作后追加 MySQL 乐观锁兜底 `UPDATE ... WHERE status='RUNNING' AND version={v}` | 禁止无条件 INCR 信号量；禁止在 PASS/FAIL/nil 状态下覆写为 INTERRUPTED | 已正常完成的执行被误标为 INTERRUPTED；信号量多次 INCR 导致并发槽溢出，后续任务永久阻塞 |
| H6 | 用例版本快照写 MinIO 失败时降级：仍创建执行记录但置 `snapshot_status=DEGRADED`，跳过快照，不阻断测试执行 | 禁止快照失败时直接报错中断整个执行流程；禁止静默忽略失败不记录状态 | 一次 MinIO 抖动导致整批测试执行失败，可用性降低 |
| H7 | XXL-JOB：`xxl.job.admin.accessToken` 必须从 Vault 注入（非空校验）；K8s NetworkPolicy 限制 xxl-job-admin 仅接受 backend-api / execution-worker 流量；Web UI 端口不经 Ingress 暴露 | 禁止使用默认空 token；禁止将 xxl-job-admin 8080 端口暴露至 Ingress | 任意用户可手动触发任务、关闭 Watchdog，导致执行逻辑被外部操控 |
| H8 | 执行日志分页 API：`GET /api/v1/execution/{execId}/logs?from={n}&limit={n}`（默认 limit=200，最大 500）；WebSocket 推流基于此接口补帧 | 禁止一次性返回全量日志；禁止 WebSocket 直接推送超过 500 行的日志块 | 海量日志撑爆客户端内存；大响应阻塞 Nginx 反向代理 |
| H9 | Flyway 迁移改为 K8s init-container 在 Pod 启动前单次执行，确保仅一个容器执行 DDL | 禁止在 Spring Boot 启动阶段（`spring.flyway.enabled=true` 多副本同时）执行迁移 | 多 Pod 同时执行 `ALTER TABLE` / 创建表导致锁超时或数据结构损坏 |
| M1 | DB 断言 SQL 解析使用 **JSqlParser** 进行 AST 白名单校验，仅允许 SELECT 语句通过 | 禁止用正则表达式替代 AST 解析做 SQL 安全校验；禁止允许非 SELECT 语句执行 | 正则可被绕过（注释、大小写、换行），导致 SQL 注入或数据破坏 |
| M2 | `@Version` 乐观锁冲突最多重试 3 次（间隔 50ms 指数退避）；超过 3 次抛 `ConcurrentModificationException` | 禁止无限重试；禁止捕获乐观锁异常后静默忽略 | 热点数据高并发下无限重试导致线程堆积；忽略异常导致更新静默丢失 |
| M3 | `ExecutionStatus` 状态机集中定义：`WAITING→RUNNING→{PASS,FAIL,INTERRUPTED,CANCELLED}`；`INTERRUPTED→WAITING`（续跑）；`PASS/FAIL/CANCELLED` 为终态不可转移 | 禁止在 Service/Worker 中散落状态转移逻辑；禁止从终态转移 | 状态机碎片化导致非法转移（如已 PASS 被重置为 RUNNING），数据一致性破坏 |
| M4 | 断点续跑检查点：`checkpoint_completed_ids` 存储到 `tc_execution` 独立 `MEDIUMTEXT` 列（最大 16MB）；增加 `checkpoint_updated_time` 列支持索引查询 | 禁止将检查点数据序列化进通用 `checkpoint_data JSON` 列；禁止使用 `TEXT`（65KB 上限） | 大型测试计划（>1000 case）检查点超 65KB 截断，导致续跑逻辑错误 |
| M5 | WebSocket 每用户最大并发连接数 **5**，超出返回 `429`；由 `WsSessionRegistry`（`ConcurrentHashMap<loginId, AtomicInteger>`）统一管理；连接关闭时 `AfterConnectionClosed` 递减 | 禁止不限制 WebSocket 连接数；禁止连接关闭时不递减计数器 | 单用户多标签页耗尽服务器连接资源；计数泄漏导致合法连接被永久拒绝 |
| M6 | Allure 报告通过 MinIO **预签名 GET URL**（有效期 24h）下载；URL 由后端生成后返回前端，前端直接请求 MinIO | 禁止将 Allure 报告经 backend 中转流式传输；禁止将报告放入 Nginx 静态目录 | backend 中转导致大文件占用应用服务器带宽和内存 |
| M7 | K8s NetworkPolicy：Selenoid UI（:4444）仅允许 execution-worker 命名空间访问；MinIO Console（:9001）仅允许运维跳板机 CIDR；两者均不经 Ingress 暴露 | 禁止将 Selenoid UI 或 MinIO Console 挂载到公网 Ingress | 外部攻击者可直接控制浏览器会话或删除对象存储数据 |
| M8 | Sa-Token 使用自定义 `SaTokenDao` 实现，按 key 前缀路由：`satoken:access:*` → redis-cache（DB0），`satoken:refresh:*` → redis-lock（DB2） | 禁止使用 Sa-Token 默认 Redis 适配器（无法区分 DB）；禁止将 access/refresh token 存同一实例 | refresh token 存 LRU 缓存库，可被淘汰驱逐，导致用户强制下线 |
| M9 | 开发环境（Docker Compose）使用三个独立 Redis 单节点模拟三库隔离；生产各自 1主2从+Sentinel；代码层通过 profile 切换连接串，业务代码无差异 | 禁止开发环境用单一 Redis 多 DB（`SELECT 0/1/2`）而生产换架构；禁止使用 Redis Cluster | dev/prod 架构不一致，生产切换后出现未覆盖的路由缺陷 |
| M10 | HashiCorp Vault 初始化必须在 Phase 0 完成（早于 Phase 1 编码），Spring Boot 接入 `spring-cloud-vault-config` | 禁止 Phase 1 开始时密钥仍硬编码在 `application.yml`；禁止推迟 Vault 集成至 Phase 2 | 密钥进入代码仓库，安全风险难以事后消除；Phase 1 加密逻辑需大规模返工 |
| M11 | `tc_execution_artifact` 表 DDL 必须纳入 Flyway `V1__initial_schema.sql`（与核心表同批） | 禁止放入后续版本迁移（V2+）或手动建表 | MinIO 清理任务在 Phase 2 上线时依赖该表已存在，延迟建表导致清理任务报错 |
| M12 | `GET /api/v1/execution/{execId}/case-results` 分页：`page` 从 1 开始，`pageSize` 默认 20、最大 100；响应含 `total`、`page`、`pageSize`、`list` | 禁止返回全量 case 结果不分页；禁止 pageSize 无上限 | 单次执行含数千 case 时不分页，响应体超 10MB，前端卡死 |
| NC1 | 分区表日期由 **CI/CD 脚本在部署时动态生成**（当月起 +6 个月），不得硬编码；保留 `p_future MAXVALUE` 兜底分区 | 禁止在脚本中写死具体年月分区（如 `2025-xx`）；禁止删除 `p_future` 分区 | 硬编码日期过期后新数据全部落入 `p_future`，无法按月 DROP 旧分区，分区策略失效 |
| NC2 | `tc_case_result` 表必须在 Flyway V1 中完整定义（含 `CHECK` 约束、`idx_exec_id`、`idx_case_id_time` 索引） | 禁止仅在注释/文档中提到而不写 DDL；禁止依赖 ORM 自动建表 | Flaky 率统计、分页查询、报告生成均依赖该表，缺表导致 Phase 2 上线即崩溃 |
| NC3 | `tc_execution.checkpoint_completed_ids` 使用 `MEDIUMTEXT`（最大 16MB） | 禁止使用 `TEXT`（65KB 上限）存储该字段 | 超过 1000 个 case_id 后 MySQL 静默截断，导致续跑时重复执行已完成 case |
| NH1 | 环境变量注入脚本时必须使用参数化传递（GraalVM `context.putMember`）；DB 断言连接串组装必须白名单校验 host:port | 禁止将环境变量值直接字符串拼接进 JS/SQL 脚本；禁止对 host/port 不做格式校验 | 恶意环境变量值注入任意 JS 代码或 SQL，突破沙箱实现 RCE 或任意数据库查询 |
| NH2 | 执行创建时（`ExecutionService.create()`）必须对 `tc_env_var` 做全量快照写入 `tc_execution.env_snapshot`（JSON，加密字段保留密文） | 禁止执行期间实时读取 `tc_env_var`；禁止快照中存明文密码 | 执行中途环境变量被修改导致同一执行前后步骤使用不同配置；明文密码进入审计日志 |
| NH3 | redis-queue（DB1）主库必须配置：`min-replicas-to-write 1`，`min-replicas-max-lag 10` | 禁止 redis-queue 使用默认配置（`min-replicas-to-write 0`） | Sentinel 切换期间主库已接受 RPUSH 但从库未同步，切换后任务丢失无法续跑 |
| NH4 | Watchdog 检测超时后：① Redis Lua CAS（仅 RUNNING 时 INCR + SET INTERRUPTED）；② MySQL 乐观锁 `UPDATE ... WHERE status='RUNNING' AND version={v}` 持久化兜底 | 禁止仅依赖 Redis statusKey（有 TTL）判断执行状态；禁止省略 MySQL 写入 | Redis key 过期后 Watchdog 无法感知状态，MySQL 数据与 Redis 永久不一致，执行记录卡在 RUNNING |
| NM1 | `tc_flaky_record` 唯一键 `uk_case_period(case_id, stats_period_end)`；隔离阈值：`sample_count >= 10 AND flaky_rate > 0.3`；重算用 `INSERT ... ON DUPLICATE KEY UPDATE` | 禁止随意降低 flaky 阈值（sample_count 不得低于 10）；禁止重算时 DELETE 再 INSERT | 样本不足时 1/1 失败被误标为 Flaky，健康用例被自动隔离；并发 DELETE+INSERT 可能丢数据 |
| NM2 | XXL-JOB 双节点 FIRST 路由 failover 约 30s 自动摘除宕机节点，Watchdog 最多丢 1 次调度（30s），在 heartbeat TTL=15s 范围内可接受 | 禁止为 FIRST 路由额外引入 ETCD/ZooKeeper 选举；禁止对清理/Watchdog 任务使用 SHARDING_BROADCAST | 重复引入选举中间件增加运维复杂度；广播路由导致 DROP PARTITION 并发执行损坏分区 |
| NM3 | DDL 中所有 `CHECK` 约束（状态枚举、正数约束等）必须显式写入建表语句（MySQL 8.0.16+ 原生支持） | 禁止仅在注释中说明枚举范围而不写 CHECK 约束；禁止依赖 ORM validation 替代数据库约束 | ORM 可绕过（直接 JDBC/MyBatis），非法枚举值入库导致状态机紊乱 |
| NM4 | LogFlusher 双重 OOM 保护：① `RPUSH` 前 `LLEN` 超 50000 时丢弃并告警；② DB 写入路径接 Resilience4j（5s 超时，50% 熔断），熔断时停止 `RPUSH` | 禁止无上限向 Redis List 追加日志；禁止 DB 持续故障时继续 RPUSH 堆积 | Redis List 无限增长撑爆内存，影响执行队列和分布式锁等关键业务 |
| NM5 | Webhook Secret 每 **6 个月**轮换一次；新旧 secret 并行验证 **24 小时**过渡期；轮换通过 Vault 原子更新 | 禁止 secret 永不轮换；禁止立即切断旧 secret（不给调用方过渡时间） | 长期不轮换：secret 泄露后无法失效；立即切断：正在使用旧 secret 的合法调用方全部 403 |
