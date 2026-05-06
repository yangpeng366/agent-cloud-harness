# Agent Cloud Harness — Agent 指南

> 本文档面向 AI Coding Agent。阅读者应对本项目一无所知，所有信息均基于实际源码与配置推导，不含假设。

## 项目概述

Agent Cloud Harness 是一个面向多智能体协作场景的**轻量控制平面服务**（continuity-first agent cloud control plane）。它以单进程形式运行，暴露 HTTP JSON API，负责：

- 创建/管理会话（Session）与任务（Task）
- 给任务分配 Worker（自动路由），支持 Learning Memory 辅助路由决策
- 在暂停、恢复、移交等节点生成续跑上下文（Resume Packet / Handoff Packet）
- 单轮 Worker 执行支持 tool-aware 多步工具链（最多 3 步）
- 运行时 Judgment（执行判断 / 完成判断）
- 将过程数据（决策、产物、事件、关系、消息、工具调用、实验指标）持久化到本地 SQLite
- 内置 Web Console (`/console/`) 与 Dialogue 前端 (`/dialogue/`) 用于本地观测与交互

当前定位是**本地或单机 harness**，不是完整分布式控制面。没有 Spring、没有消息队列、没有外部数据库、没有容器化配置。

## 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 21 | `pom.xml` 显式启用 `--enable-preview` |
| 构建 | Maven | 3.x | 单模块，无子模块 |
| HTTP 服务 | `com.sun.net.httpserver.HttpServer` | JDK 自带 | 虚拟线程执行器处理请求 |
| JSON | Jackson | 2.17.2 | 统一输出 `snake_case`，启用 JSR310 模块 |
| 数据库 | SQLite | 3.46.0.0 (JDBC) | 本地文件，无需外部部署 |
| DAO | Jdbi3 (SQL Object) | 3.45.1 | 注解式 SQL，无 ORM |
| 连接池 | HikariCP | 5.1.0 | |
| 日志 | SLF4J + Logback | 2.0.13 / 1.5.6 | 控制台输出，root 级别 `INFO` |
| 前端 | Vanilla JS + CSS | N/A | 内置 `/console/` 与 `/dialogue/` 静态页面 |
| LLM 调用 | OpenAI-compatible client | N/A | 通过 `llm/OpenAiCompatibleClient` 对接兼容接口 |
| 工具层 | `ToolRegistry` + 受控本地工具 | N/A | 受 `ToolPolicy` 限制，支持 list/read/search/write/patch，且按宿主机真实可执行性动态暴露 `git/shell/powershell/cmd`（其中 `powershell/cmd` 仍仅 Windows 宿主可用） |
| 测试 | JUnit Jupiter | 5.11.0 | 已有覆盖 packet、orchestration、tool-aware execution、message projection 等方向的测试 |

## 构建与运行

### 环境要求

- Java 21（必须，因为使用了 preview 特性）
- Maven 3.9+ 为宜
- 运行用户对 `${user.home}/.agentcloud/` 目录有写权限（SQLite 文件落点）

### 构建命令

```bash
mvn package
```

构建产物：
- `target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` — 可执行的 uber JAR（推荐）
- `target/agent-cloud-harness-0.1.0-SNAPSHOT.jar` — 原始 JAR

### 运行命令

```bash
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

- 默认监听端口 `8080`
- 可通过 JVM 系统属性覆盖端口：`-Dserver.port=9090`
- SQLite 数据库路径：`${user.home}/.agentcloud/agent_cloud.db`

### 测试

当前仓库已经有一批 JUnit 5 测试，可直接通过 `src/test/java` 里的现有结构继续补充。运行测试前应先切到 JDK 21，推荐使用：

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1
```

## 代码组织

```
src/main/java/com/agentcloud/
├── cli/                          # 启动入口与组件装配
│   └── Main.java                 # 唯一进程入口：初始化 DB → DAO → Engine → HTTP Server
├── server/                       # HTTP 接入层（JDK HttpServer + Handler）
│   ├── NioHttpServer.java        # 服务器启动、虚拟线程、共享 ObjectMapper
│   ├── TaskHandler.java          # 任务 CRUD + 状态操作（pause/resume/handoff 等）+ 观测接口
│   ├── SessionHandler.java       # 会话 CRUD + close + pause/resume + 消息流
│   ├── WorkerHandler.java        # Worker 列表与注册
│   ├── SkillHandler.java         # Skill 列表与注册
│   ├── CheckpointHandler.java    # Checkpoint 查询
│   ├── LearningMemoryHandler.java # Learning Memory 查询
│   ├── ExperimentRunHandler.java # Experiment Run 查询
│   ├── ExperimentMatrixHandler.java # 实验矩阵批量运行与汇总
│   └── WebConsoleHandler.java    # 静态资源服务（/console/、/dialogue/）
├── engine/                       # 业务核心/应用层
│   ├── TaskService.java          # 任务创建、状态变更、控制图驱动
│   ├── SessionService.java       # 会话基础 CRUD + 消息投影
│   ├── ControlNodeGraph.java     # 6+ 节点状态机
│   ├── ConsolidationService.java # 五步巩固（Reactivation/Selection/Compression/Abstraction/Integration）
│   ├── RuntimeJudgmentService.java # 规则式运行时迁移动作判断
│   ├── LearningMemoryService.java # Learning Memory 读写与强化
│   ├── ExperimentRunService.java # 实验指标落盘与更新
│   ├── ExperimentMatrixService.java # 实验矩阵批量创建
│   ├── SkillRegistry.java        # 技能内存注册表
│   ├── SkillRouter.java          # 技能路由
│   ├── IdGenerator.java          # UUID 前缀生成器
│   ├── memory/
│   │   ├── PacketBuilder.java    # 构建 ResumePacket / HandoffPacket
│   │   └── ContextReconstructor.java # 上下文重建
│   └── router/
│       ├── WorkerRegistry.java   # Worker 内存注册表（预注册了 openclaw-native / codex / kimi）
│       └── WorkerRouter.java     # 按 capability + readiness + learning memory 选 Worker
├── judgment/                     # Judgment 层（执行判断与完成判断）
│   ├── JudgmentService.java      # 判断服务接口
│   ├── PromptBasedJudgmentService.java # 基于 LLM prompt 的判断实现
│   └── JudgmentContext.java      # 判断上下文
├── llm/                          # LLM 适配层
│   ├── LlmClient.java            # LLM 客户端接口
│   ├── LlmConfig.java            # 配置（从环境变量 / 系统属性读取）
│   └── OpenAiCompatibleClient.java # OpenAI 兼容协议实现
├── runtime/                      # 运行时上下文与 Active Context
│   ├── TaskRuntimeContext.java   # 单轮执行与判断所需完整上下文
│   ├── TaskRuntimeContextBuilder.java # 组装运行时上下文
│   ├── ActiveContext.java        # 工作记忆 / Active Context 结构
│   └── ActiveContextBuilder.java # 从 checkpoint / event / decision / artifact 构建 Active Context，并内含默认保留/排除策略
├── tool/                         # 受控本地工具（文件 + 命令）
│   ├── Tool.java                 # 工具接口
│   ├── ToolRegistry.java         # 工具注册表
│   ├── ToolPolicy.java           # 路径/CWD/命令策略校验
│   ├── ToolRequest.java / ToolResult.java
│   ├── AbstractLocalFileTool.java
│   ├── AbstractCommandTool.java
│   ├── ListFilesTool.java
│   ├── ReadFileTool.java
│   ├── SearchTextTool.java
│   ├── WriteFileTool.java
│   ├── PatchFileTool.java
│   ├── GitTool.java
│   ├── ShellTool.java
│   ├── PowerShellTool.java
│   └── CmdTool.java
├── worker/                       # Worker 执行层
│   ├── WorkerExecutor.java       # 执行器接口
│   ├── DefaultWorkerExecutor.java # 默认纯 LLM 执行器
│   ├── ToolAwareWorkerExecutor.java # Tool-aware 多步执行器（最多 3 步）
│   ├── WorkerExecutorRouter.java # 按 worker 合同分流执行器
│   └── WorkerExecutionResult.java
├── model/                        # 领域模型（Java Record，约 30+ 个）
│   ├── Task.java / TaskCreateRequest.java / TaskControlResult.java / TaskLiveFlowView.java
│   ├── Session.java / SessionMessage.java / SessionMessageCreateRequest.java
│   ├── Event.java / Decision.java / Artifact.java / Relation.java
│   ├── ResumePacket.java / HandoffPacket.java / HandoffPacketView.java / HandoffResult.java
│   ├── Checkpoint.java / LearningMemory.java / ToolInvocationRecord.java
│   ├── Worker.java / Skill.java / ApiResponse.java
│   ├── JudgmentTraceView.java / ExperimentRunRecord.java / ExperimentMatrixSummary.java / ...
│   └── PacketTaskIdentity.java / PacketArtifactRef.java / PacketDecisionRef.java
└── store/                        # 数据持久化层
    ├── DatabaseManager.java      # HikariCP + Jdbi 初始化、schema.sql 执行
    ├── *Dao.java                 # 各实体 Jdbi SQL Object DAO（共 15+ 个）
    ├── Mappers.java              # 显式 RowMapper（处理 SQLite 时间戳格式）
    ├── JsonMapper.java           # JSON 列序列化/反序列化工具
    └── InstantArgumentFactory.java # Jdbi Instant 参数绑定

src/main/resources/
├── schema.sql                    # SQLite 表结构与索引（启动时按分号拆分执行）
├── logback.xml                   # 日志配置
└── web/
    ├── console/                  # Web Console 前端（任务/会话/Worker 观测面板）
    │   ├── index.html
    │   ├── app.css
    │   └── app.js
    └── dialogue/                 # Dialogue 前端（会话消息流与任务交互）
        ├── index.html
        ├── app.css
        └── app.js
```

## 关键架构约定

### 1. HTTP Handler 模式

- 不使用任何 Web 框架（无 Spring、无 Jersey）。
- 每个资源一个 `HttpHandler`，内部用 `if/else` 手工匹配 `method + path`。
- URL 前缀统一为 `/api/v1/*`。
- 共享工具方法定义在 `NioHttpServer`：
  - `sendJson(HttpExchange, int, Object)` — 统一序列化并返回
  - `readBody(HttpExchange)` — 读取请求体
  - `pathVar(HttpExchange, int)` — 按 `/` 分割取路径变量（注意索引从 0 开始）

### 2. JSON 约定

- 全局共享 `ObjectMapper` 在 `NioHttpServer.SHARED_MAPPER`：
  - 属性命名策略：`SNAKE_CASE`
  - 包含策略：`NON_NULL`
  - 已注册 `JavaTimeModule`
- 所有模型类都标注 `@JsonInclude(JsonInclude.Include.NON_NULL)`
- DAO 层把 `Map<String, Object>` 序列化为 JSON 字符串存入 SQLite，反序列化时再还原

### 3. 领域模型风格

- 全部使用 **Java Record**。
- 时间字段类型为 `java.time.Instant`。
- 每个 record 提供 `withXxx()` 方法返回修改后的新实例（不可变更新）。
- 紧凑构造函数（compact constructor）用于设置默认值，例如 `Task` 中自动填充 `createdAt` / `updatedAt`。

### 4. DAO 与持久化风格

- 使用 **Jdbi SQL Object**（注解式），接口继承 `SqlObject`。
- 每个 DAO 同时提供：
  - 带 `@Bind` 参数的底层方法（接收原始字段或 JSON 字符串）
  - `default` 包装方法（接收领域对象，内部拆解并调用 `JsonMapper.toJson`）
- **没有使用 ConstructorMapper 自动映射**。`DatabaseManager` 显式注册了一组 `Mappers.*` RowMapper。
  - 原因是 SQLite 时间戳格式不标准（可能是 `"2026-04-21 13:46:44.123"`），`Mappers.instant()` 做了空格替换和补 `Z` 的兼容处理。
- `schema.sql` 在 `DatabaseManager` 构造函数中读取并按分号 `;` 拆分逐条执行。没有 Flyway/Liquibase 等迁移工具。

### 5. 控制节点图（Control Node Graph）

`ControlNodeGraph` 仍然保留 6 个命名控制节点，但 `scheduler` 和 `continue` 已经串起了路由、执行、判断、学习记忆与轨迹落库：

| 节点 | 职责 |
|------|------|
| `intake` | 入口，自动转到 `scheduler` |
| `scheduler` | 路由 worker、构建 runtime context、执行一轮 worker，并写入 worker artifact / route trace |
| `continue` | 基于 judgment prompt 做 execution/completion 判断，写入 decision trace，并把经验强化到 learning memory |
| `packet` | 构建并持久化 resume packet，触发 consolidation，必要时回到 `scheduler` |
| `human_gate` | 人工确认等待门；等待外部 resume/escalate/close 等动作 |
| `handoff` | 固化 handoff 前 checkpoint / packet，切换目标 worker 后回到 `scheduler` |

当前构造注入已包括：`workerExecutor`、`runtimeContextBuilder`、`judgmentService`、`artifactDao`、`decisionDao`、`learningMemoryService`。外部触发方法仍是 `triggerPause`、`triggerResume`、`triggerEscalate`、`triggerHandoff`、`triggerHalt`。

### 6. Worker 路由策略

- `WorkerRegistry` 内存维护 worker 列表，启动时预注册了 3 个内置 worker。
- `WorkerRouter.selectWorker` 逻辑：
  1. 从 `task.metadata.task_type` 提取目标能力。
  2. 结合 `model_mode` 收窄 model tier：`strong_only` 强制 strong，`small_only` 强制 small，`orchestrated` 按阶段在 planner/judge 与 executor 之间切 tier。
  3. 读取 `LearningMemoryService.selectPreferredWorker(taskType)` 作为 preferred worker hint，仅在候选集允许时应用。
  4. 输出 `selected_worker`、`route_source`、`why_selected`、`fallback_reason`、`preferred_worker_hint`、`learning_hint_applied` 等显式 trace 字段。

### 7. 依赖注入

没有框架级 DI。所有组件在 `Main.main` 中手工实例化，按以下顺序装配：

```
DatabaseManager → DAOs →
LearningMemoryService → ExperimentRunService →
WorkerRegistry → WorkerRouter → PacketBuilder → ContextReconstructor →
ConsolidationService → RuntimeJudgmentService →
LLM Config/Client → ActiveContextBuilder → TaskRuntimeContextBuilder →
JudgmentService → ToolPolicy/Registry → DefaultWorkerExecutor → ToolAwareWorkerExecutor → WorkerExecutorRouter →
ControlNodeGraph → SkillRegistry/Router →
SessionService → TaskService → ExperimentMatrixService → NioHttpServer
```

## API 端点速查

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sessions` | 创建会话 |
| GET | `/api/v1/sessions` | 列会话 |
| GET | `/api/v1/sessions/{id}` | 查会话 |
| GET | `/api/v1/sessions/{id}/tasks` | 会话下任务 |
| GET | `/api/v1/sessions/{id}/messages` | 会话消息流（Query: `limit?`, `task_id?`） |
| POST | `/api/v1/sessions/{id}/messages` | 追加会话消息 |
| POST | `/api/v1/sessions/{id}/pause` | 暂停会话 |
| POST | `/api/v1/sessions/{id}/resume` | 恢复会话 |
| POST | `/api/v1/sessions/{id}/close` | 关闭会话 |
| GET | `/api/v1/sessions/{id}/close` | 兼容旧客户端的关闭入口 |
| POST | `/api/v1/tasks` | 创建任务（支持 `parent_task_id`、`auto_start`） |
| GET | `/api/v1/tasks` | 列任务（Query: `status`/`state`、`task_type`、`assigned_worker`） |
| GET | `/api/v1/tasks/{id}` | 查任务 |
| POST | `/api/v1/tasks/{id}/state` | 更新状态（Body: `state`, `reason`） |
| GET | `/api/v1/tasks/{id}/packet` | 获取最近 resume packet |
| GET | `/api/v1/tasks/{id}/refresh_packet` | 重新生成并保存 resume packet |
| GET | `/api/v1/tasks/{id}/select_worker` | 预览当前任务路由决策 |
| GET | `/api/v1/tasks/{id}/runtime_context` | 查看运行时上下文与 active context |
| GET | `/api/v1/tasks/{id}/judgment_trace` | 查看最近 execution/completion judgment 诊断 |
| GET | `/api/v1/tasks/{id}/live_flow` | 聚合 live flow 诊断面板 |
| GET | `/api/v1/tasks/{id}/experiment_run` | 查看该任务最新 experiment run 指标 |
| GET | `/api/v1/tasks/{id}/experiment_summary` | 按 experiment_name 汇总 matrix 结果 |
| GET | `/api/v1/tasks/{id}/tool_trace` | 查看最近工具调用轨迹 |
| GET | `/api/v1/tasks/{id}/handoff_packet` | 预览移交 packet |
| POST | `/api/v1/tasks/{id}/pause` | 暂停任务 |
| POST | `/api/v1/tasks/{id}/resume` | 恢复任务 |
| POST | `/api/v1/tasks/{id}/continue` | 继续进入控制图 |
| POST | `/api/v1/tasks/{id}/escalate` | 升级为人工等待 |
| GET | `/api/v1/tasks/{id}/pause` | 兼容旧客户端的暂停入口 |
| GET | `/api/v1/tasks/{id}/resume` | 兼容旧客户端的恢复入口 |
| GET | `/api/v1/tasks/{id}/continue` | 兼容旧客户端的继续入口 |
| GET | `/api/v1/tasks/{id}/escalate` | 兼容旧客户端的升级入口 |
| POST | `/api/v1/tasks/{id}/handoff` | 移交（Body: `target_worker`） |
| GET | `/api/v1/workers` | 列 worker |
| POST | `/api/v1/workers` | 注册 worker |
| GET | `/api/v1/workers/{id}` | 查 worker |
| GET | `/api/v1/workers/{id}/readiness` | readiness 检查 |
| GET/POST | `/api/v1/skills` | 列/注册 skill |
| GET | `/api/v1/skills/{id}` | 查 skill |
| GET | `/api/v1/skills/{id}/readiness` | skill readiness |
| GET | `/api/v1/checkpoints/{taskId}` | checkpoint 列表 |
| GET | `/api/v1/learning_memories` | 按类型查询 learning memories |
| GET | `/api/v1/learning_memories/{taskId}` | 查询某任务的 learning memories |
| GET | `/api/v1/experiment_runs` | 过滤查询 experiment runs |
| GET | `/api/v1/experiment_runs/{taskId}` | 查某任务的 experiment run |
| GET | `/api/v1/experiment_matrix/cases` | 列出内置 baseline case catalog |
| POST | `/api/v1/experiment_matrix/runs` | 批量创建可比较基线 run |
| GET | `/api/v1/experiment_matrix/summary` | 按实验名聚合 matrix 结果 |
| GET | `/api/v1/health` | 健康检查 |
| GET | `/` | 重定向到 `/dialogue/` |
| GET | `/console/` | Web Console 静态前端 |
| GET | `/dialogue/` | Dialogue 静态前端 |

> ⚠️ **注意**：`pause/resume/continue/escalate` 已正式切到 **POST**；服务端暂时保留旧 `GET` 兼容入口。新接入不要再依赖 `GET`，否则仍有被缓存/预取误触发的风险。

## 已知陷阱（修改前必读）

以下条目按当前源码状态区分为“已收口的历史回归点”和“仍然存在的真实风险”：

### T01: pause 持久化缺口已收口

- **当前状态**：`pause -> packet` 路径现在会持久化最新 `ResumePacket`，暂停后可直接通过 `/api/v1/tasks/{id}/packet` 取回。
- **回归保护**：`TaskServicePacketContractTest.pauseTaskPersistsResumePacketAndPauseCheckpoint()`
- **修改时注意**：不要只构建 packet 不落库；暂停链路还要同时保留 `pause_before` checkpoint。

### T02: Consolidation artifact 查询参数已收口

- **当前状态**：`ConsolidationService` 已按 `task.sessionId()` + `task.id()` 查询 artifact，`key_artifacts` 会进入 checkpoint/refined packet。
- **回归保护**：`ConsolidationServiceProtocolTest.consolidateProducesCheckpointProtocolPayload()`
- **修改时注意**：任何 checkpoint/refined packet 相关重构，都不要把 sessionId/taskId 顺序再改坏。

### T03: 列表查询参数兼容已收口

- **当前状态**：`GET /api/v1/tasks` 现已同时接受 `status` 和旧参数 `state`。
- **回归保护**：`TaskHandlerControlActionHttpTest.listTasksAcceptsStatusAndLegacyStateQueryParams()`
- **修改时注意**：新代码统一以 `status` 为主，但不要轻易移除 `state` 兼容，除非同步做 API 版本升级。

### T04: 错误响应脱敏已收口

- **当前状态**：Handler 统一通过 `NioHttpServer` 返回稳定错误体；`500` 固定为 `internal error`，不再直接回传内部异常细节。
- **回归保护**：`ControlActionHttpRouteTest.postPauseHidesInternalFailureDetails()`、`ApiErrorContractHttpTest`
- **修改时注意**：日志里可以保留异常详情，但 HTTP 响应层不要重新暴露 `e.getMessage()`。

### T05: 仍然存在的真实风险

- **位置**：所有 Handler
- **现状**：API 仍然是匿名访问，尚无认证、授权、租户隔离和限流。`WorkerHandler` / `SkillHandler` 已补了基础字段校验与部分类型校验，但这不等于安全边界。
- **影响**：任何能访问 HTTP 端口的调用方都能读写控制面数据，仍然只适合本地或受控环境。

## 代码风格指南

- **语言**：代码标识符用英文，注释以**中文**为主。
- **类/方法**：标准 Java 命名（PascalCase 类名、camelCase 方法名）。
- **不可变更新**：领域对象状态变更一律使用 `withXxx()` 返回新 record，不要直接修改字段。
- **日志**：统一使用 SLF4J，`private static final Logger log = LoggerFactory.getLogger(Xxx.class)`。
- **空值处理**：使用 `@JsonInclude(Include.NON_NULL)` 控制 JSON 输出；DAO 查询返回 `Optional<T>`。
- **异常**：服务层常用 `IllegalArgumentException` / `IllegalStateException` 表达参数错误、资源缺失或非法状态；Handler 会把可识别问题映射成 `400/404`，其他未处理异常统一脱敏成 `500 internal error`。
- **时间戳**：业务层使用 `Instant.now()`；存储层通过 `InstantArgumentFactory` 和 `Mappers.instant()` 做 SQLite 兼容。
- **新增接口**：
  - 若新增 HTTP 资源，沿用现有 `XxxHandler implements HttpHandler` 模式。
  - 若新增数据实体，在 `model/` 定义 Record，在 `store/` 定义 DAO，在 `schema.sql` 加表，在 `DatabaseManager` 注册 RowMapper。
  - 不要在现有项目中引入 Spring Boot 或其他 Web 框架。

## 安全注意事项

| 编号 | 风险 | 等级 | 说明 |
|------|------|------|------|
| S01 | 无认证/授权 | 高 | 所有端点匿名可访问 |
| S02 | 无输入校验 | 中 | Worker/Skill 注册直接写内存 |
| S03 | 信息泄露 | 低 | ~~500 错误直接返回异常消息~~ 已收口：500 统一返回 `internal error`，异常详情仅写日志 |
| S04 | 无租户隔离 | 高 | 所有数据共享同一个 SQLite 文件 |

## 调试与排查

- **日志**：默认输出到控制台，格式 `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`。
- **数据库**：可直接用 SQLite CLI 打开 `${user.home}/.agentcloud/agent_cloud.db` 排查。
- **健康检查**：`GET /api/v1/health` 返回 `{"status":"up","virtual_threads":true,"version":"0.2.0"}`。
- **前端入口**：
  - Web Console: `http://localhost:8080/console/`
  - Dialogue: `http://localhost:8080/dialogue/`
- **快速验证**（服务启动后）：
  ```bash
  curl -X POST http://localhost:8080/api/v1/tasks \
    -H "Content-Type: application/json" \
    -d '{"title":"demo","task_type":"coding","source":"user","priority":"high","intent":"fix bug"}'
  ```

## 文件清单（关键配置）

| 文件 | 用途 |
|------|------|
| `pom.xml` | Maven 构建、依赖版本、shade 插件配置 |
| `src/main/resources/schema.sql` | 数据库表结构与索引 |
| `src/main/resources/logback.xml` | 日志级别与输出格式 |
| `docs/ARCHITECTURE.md` | 详细架构说明（中文） |
| `docs/SPEC.md` | 功能规格与状态机（中文） |
| `docs/API_CONTRACTS.md` | API 契约与数据库设计（中文） |
| `docs/TROUBLESHOOT.md` | 已知坑点与排查指南（中文） |
| `docs/TOOL_LAYER_IMPLEMENTATION_PLAN.md` | 工具层实现计划 |
| `docs/DIALOGUE_MESSAGE_LAYER_PLAN.md` | Dialogue 消息层计划 |
| `docs/LIVE_FLOW_RUNBOOK.md` | Live Flow 运行手册 |
| `docs/LOCAL_DOC_WORKER_PILOT.md` | 本地文档 Worker 试点指南 |
| `docs/EVAL_SCENARIOS.md` | 评估场景说明 |
| `docs/GOAL_ORIENTED_EVAL_PLAN.md` | 目标导向评估计划 |
| `docs/PHASE2_ROADMAP.md` | Phase 2 路线图 |
| `docs/NEXT_5_ENGINEERING_PRIORITIES.md` | 接下来 5 项工程优先级 |
