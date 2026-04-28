# Architecture

<!-- 生成时间：2026-04-22 -->
<!-- 分析工具：Claude Code reverse-doc -->
<!-- 分析范围：整个项目 -->

## 1. 项目简介

Agent Cloud Harness 是一个面向多智能体协作场景的轻量控制平面服务。它负责创建会话与任务、给任务分配 worker、在暂停/恢复/移交等节点生成续跑上下文，并把过程数据持久化到本地 SQLite。项目没有引入 Spring 一类大型框架，而是直接用 JDK 自带 `HttpServer`、Jdbi 和虚拟线程搭建一个单进程 API 服务。当前形态更像本地或单机环境的 harness，而不是完整分布式控制面。

## 2. 技术栈与约束

### 2.1 技术栈全景

| 类别 | 技术 | 版本 | 备注 |
|------|------|------|------|
| 后端语言 | Java | 21 | `pom.xml` 启用了 `--enable-preview` |
| 前端框架 | Vanilla JS + CSS | N/A | 内置 `/console/` 与 `/dialogue/` 静态页面 |
| 数据库 | SQLite | 3.46.0.0 驱动 | 通过 Jdbi + HikariCP 访问 |
| 缓存 | N/A | N/A | 本项目未发现相关内容 |
| 搜索引擎 | N/A | N/A | 本项目未发现相关内容 |
| 消息队列 | N/A | N/A | 本项目未发现相关内容 |
| 容器化 | N/A | N/A | 本项目未发现 Docker/K8S 文件 |
| CI/CD | N/A | N/A | 本项目未发现流水线配置 |
| LLM 适配 | OpenAI 兼容协议 | N/A | 通过 `llm/OpenAiCompatibleClient` 调用 |
| 测试框架 | JUnit Jupiter | 5.11.0 | 已有 20+ 测试类覆盖核心链路 |
| 其他 | Jackson / SLF4J / Logback / JDK HttpServer | 2.17.2 / 2.0.13 / 1.5.6 | JSON、日志、HTTP 接入 |

### 2.2 技术栈约束规则

- **语言版本**: Java 21，编译参数显式启用 preview 特性，不应降级到更低 JDK。
- **包管理**: Maven，依赖集中定义在 `pom.xml`，应保持单模块结构一致。
- **Web 框架**: 当前使用 `com.sun.net.httpserver.HttpServer`，新增接口应沿用现有 handler 模式，不要混入另一套 Web 框架。
- **持久化**: 使用 Jdbi SQL Object + SQLite，DAO 已经固定为注解 SQL 方式；除初始化 schema 外，不建议引入另一套 ORM。
- **JSON 约定**: 统一通过共享 `ObjectMapper` 输出 `snake_case` JSON。
- **日志框架**: 统一使用 SLF4J + Logback，根日志级别为 `INFO`。
- **测试框架**: `JUnit Jupiter` 已声明，但当前仓库未发现测试代码。

## 3. 目录结构

agent-cloud-harness/
├── src/
│   └── main/
│       ├── java/com/agentcloud/
│       │   ├── cli/                 # 启动入口与组件装配
│       │   ├── server/              # HTTP Server 与各资源 Handler
│       │   ├── engine/              # 任务控制图、会话服务、技能服务、巩固层
│       │   ├── engine/memory/       # Resume/Handoff 上下文构建与重建
│       │   ├── engine/router/       # Worker 注册与路由
│       │   ├── model/               # 领域记录类型
│       │   └── store/               # DB 初始化、Mapper、DAO
│       └── resources/
│           ├── schema.sql           # SQLite 表结构与索引
│           ├── logback.xml          # 日志配置
│           └── web/
│               ├── console/         # Web Console 前端
│               └── dialogue/        # Dialogue 前端
├── target/                          # Maven 构建产物
├── docs/                            # 维护文档
├── pom.xml                          # Maven 构建与依赖配置
├── dependency-reduced-pom.xml       # Shade 产物缩减 POM
├── server.out.log                   # 服务标准输出日志样本
└── server.err.log                   # 服务错误输出日志样本

## 4. 系统架构

### 4.1 架构总览

图: 系统架构

    +-------------------+      +-----------------------+
    | HTTP Clients      | ---> | NioHttpServer         |
    | curl / SDK / UI   |      | Task/Session/... API  |
    +-------------------+      +-----------------------+
                                        |
                                        v
                              +-----------------------+
                              | Service / Engine      |
                              | TaskService           |
                              | SessionService        |
                              | RuntimeJudgmentService|
                              | SkillRegistry         |
                              +-----------------------+
                                        |
                         +--------------+---------------+
                         |                              |
                         v                              v
              +-----------------------+      +-----------------------+
              | ControlNodeGraph      | ---> | WorkerRouter          |
              | intake/scheduler/...  |      | WorkerRegistry        |
              +-----------------------+      +-----------------------+
                         |
                         v
              +-----------------------+      +-----------------------+
              | PacketBuilder         | ---> | ConsolidationService  |
              | Resume/Handoff Packet |      | Checkpoint 生成       |
              +-----------------------+      +-----------------------+
                         |                              |
                         +--------------+---------------+
                                        v
                              +-----------------------+
                              | Jdbi DAO + SQLite     |
                              | sessions/tasks/...    |
                              +-----------------------+

图例: `-->` 同步调用，底部方框表示持久化存储。

### 4.2 分层结构

| 层级 | 目录/命名空间 | 职责 | 典型类/文件 |
|------|-------------|------|------------|
| 接入层 | `src/main/java/com/agentcloud/server` | 暴露 HTTP API、解析请求、序列化响应、静态资源服务 | `NioHttpServer`, `TaskHandler`, `WebConsoleHandler` |
| 应用层 | `src/main/java/com/agentcloud/engine` | 编排任务生命周期、会话管理、技能注册、实验矩阵 | `TaskService`, `SessionService`, `ControlNodeGraph`, `ExperimentMatrixService` |
| 运行时与判断层 | `src/main/java/com/agentcloud/runtime`, `src/main/java/com/agentcloud/judgment` | 组装单轮执行上下文、Active Context 构建、执行/完成判断 | `TaskRuntimeContextBuilder`, `ActiveContextBuilder`, `PromptBasedJudgmentService` |
| 路由与记忆层 | `src/main/java/com/agentcloud/engine/router`, `engine/memory` | Worker 选择（含 learning memory）、续跑包构建、上下文重建 | `WorkerRouter`, `PacketBuilder`, `LearningMemoryService` |
| 工具执行层 | `src/main/java/com/agentcloud/worker`, `src/main/java/com/agentcloud/tool` | 统一 worker 执行入口、tool-aware 多步执行器、受控本地文件工具 | `WorkerExecutorRouter`, `ToolAwareWorkerExecutor`, `ToolPolicy` |
| LLM 适配层 | `src/main/java/com/agentcloud/llm` | 统一 LLM 调用客户端与配置 | `OpenAiCompatibleClient`, `LlmConfig` |
| 数据层 | `src/main/java/com/agentcloud/store` | 初始化数据库、DAO 查询与写入、行映射 | `DatabaseManager`, `TaskDao`, `LearningMemoryDao` |
| 领域模型层 | `src/main/java/com/agentcloud/model` | 定义 API 与存储共享的数据结构 | `Task`, `Session`, `Checkpoint`, `LearningMemory` |

## 5. 模块清单

### 5.1 启动与装配模块

- **目录**: `src/main/java/com/agentcloud/cli`
- **职责**: 该模块是应用进程入口，负责把数据库、DAO、服务层、路由器与 HTTP 服务装配成完整运行时。它本身不承载业务规则，但决定了所有核心组件的依赖关系与启动顺序。它还定义数据库默认位置和监听端口，是部署时最先需要理解的文件。
- **入口文件**: `src/main/java/com/agentcloud/cli/Main.java`
- **核心类/函数**:
  - `Main.main` — 初始化 SQLite、DAO、Control Graph、Registry 与 HTTP Server。
- **对外提供**: JVM 进程入口。
- **依赖**: `store`, `engine`, `server`

### 5.2 HTTP 接入模块

- **目录**: `src/main/java/com/agentcloud/server`
- **职责**: 该模块把领域资源映射为 HTTP 路径，并做最薄的一层参数解析和错误包装。它没有复杂中间件体系，所有路由都是手工匹配 URL 和请求方法。它直接决定外部系统如何使用 control plane，因此是接口契约的主来源。
- **入口文件**: `src/main/java/com/agentcloud/server/NioHttpServer.java`
- **核心类/函数**:
  - `NioHttpServer.start` — 注册 `/api/v1/*` 上下文并启用虚拟线程执行器。
  - `TaskHandler.handle` — 管理任务创建、状态变更、暂停/恢复/移交等接口。
  - `SessionHandler.handle` — 管理会话创建、查询、关闭及任务列表接口。
  - `WorkerHandler.handle` / `SkillHandler.handle` — 暴露 worker 与 skill 注册查询能力。
- **对外提供**: HTTP JSON API。
- **依赖**: `engine`, `engine/router`, `model`

### 5.3 任务编排模块

- **目录**: `src/main/java/com/agentcloud/engine`
- **职责**: 该模块是业务核心，负责接收任务请求、驱动任务状态流转、触发暂停/恢复/移交/升级等控制动作。它通过 `ControlNodeGraph` 把任务生命周期拆成多个控制节点，用显式状态机替代分散在各处的条件分支。它还负责记录事件和会话当前任务，从而维持整个会话视角的一致性。
- **补充说明**: 最新实现增加了一个最小 `RuntimeJudgmentService`，专门在 `continue` 前做规则式迁移判断，用来承接设计稿里的 judgment layer。
- **入口文件**: `src/main/java/com/agentcloud/engine/TaskService.java`
- **核心类/函数**:
  - `TaskService.createTask` — 自动建会话、写入任务与事件，并进入控制图。
  - `RuntimeJudgmentService.judge` — 在继续推进前判断下一状态迁移。
  - `ControlNodeGraph.enter` — 根据 `control_node` 派发到具体节点逻辑。
  - `ConsolidationService.consolidate` — 在关键切换点生成 checkpoint 与摘要。
  - `SessionService` — 提供会话基础 CRUD 与当前任务维护。
- **对外提供**: 任务编排服务、会话服务、技能注册服务。
- **依赖**: `store`, `engine/router`, `engine/memory`, `model`

### 5.4 记忆与路由模块

- **目录**: `src/main/java/com/agentcloud/engine/memory`, `src/main/java/com/agentcloud/engine/router`
- **职责**: 该模块分别处理“任务应交给谁”和“任务停下来后如何恢复”两类问题。`WorkerRegistry` 保存可选 worker 列表与 readiness 状态，`WorkerRouter` 依据任务类型做简单匹配。`PacketBuilder` 与 `ContextReconstructor` 则从历史决策、产物、事件中提炼续跑上下文，支撑 pause/resume/handoff 场景。
- **入口文件**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **核心类/函数**:
  - `WorkerRegistry` — 预注册内置 worker 并检查依赖是否满足。
  - `WorkerRouter.selectWorker` — 按 capability 与 readiness 选择 worker。
  - `PacketBuilder.buildResumePacket` — 汇总决策和产物，生成 resume packet。
  - `ContextReconstructor.reconstruct` — 重建共享/局部上下文视图。
- **对外提供**: worker 路由、resume/handoff 包构建、上下文恢复。
- **依赖**: `store`, `model`

### 5.5 Worker 执行与工具模块

- **目录**: `src/main/java/com/agentcloud/worker`, `src/main/java/com/agentcloud/tool`
- **职责**: 该模块负责把“选中 worker 后如何执行一轮”正式收口。`WorkerExecutorRouter` 会根据 worker 合同在普通执行器和 tool-aware 执行器之间分流；`ToolAwareWorkerExecutor` 当前已支持最小多步工具链，在单轮内执行最多 3 步 `planning -> invoke -> ... -> finalization`，并带 `repeated_tool_guard` 与 `no_progress_guard` 终止保护。`ToolPolicy` 和本地文件工具则负责把副作用限制在声明式 scope 内，并把工具调用沉淀到 `tool_invocations`。
- **核心类/函数**:
  - `WorkerExecutorRouter.executeOneRound` — 按 `suggest_only` 与 `tool_capabilities` 选择执行路径。
  - `ToolAwareWorkerExecutor.executeOneRound` — 执行最多 3 步的 `planning -> invoke` 工具链，并在收敛后生成最终结果。
  - `ToolPolicy.resolveAllowedPath` — 校验访问路径必须落在 worker 声明的 scope 内。
  - `ListFilesTool` / `SearchTextTool` / `ReadFileTool` / `WriteFileTool` — 第一版受控本地文件工具。
- **对外提供**: 单轮 worker 执行门面、最小工具执行能力、工具调用轨迹。
- **依赖**: `llm`, `engine/router`, `store`, `model`

### 5.6 运行时上下文与 Judgment 模块

- **目录**: `src/main/java/com/agentcloud/runtime`, `src/main/java/com/agentcloud/judgment`
- **职责**: `runtime` 负责把任务当前可见的 event、decision、artifact、packet、checkpoint、learning memory 组装成单轮执行所需的 `TaskRuntimeContext`，并从中提炼 `ActiveContext`（工作记忆）。`judgment` 则在此基础上做执行中判断（execution judgment）和完成后判断（completion judgment），输出下一步推荐动作。
- **入口文件**: `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java`, `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java`
- **核心类/函数**:
  - `TaskRuntimeContextBuilder.build` — 汇总多表数据生成运行时上下文。
  - `ActiveContextBuilder.build` — 从运行时上下文中提取关键决策、产物、阻塞项、开放问题等。
  - `PromptBasedJudgmentService.judgeExecution/judgeCompletion` — 基于 LLM 的判断实现。
- **对外提供**: 运行时上下文构建、Active Context、执行/完成判断。
- **依赖**: `store`, `model`, `llm`

### 5.7 LLM 适配模块

- **目录**: `src/main/java/com/agentcloud/llm`
- **职责**: 为上层提供统一的 LLM 调用接口，当前实现为 OpenAI 兼容协议。配置优先从环境变量 / 系统属性读取，未配置时以 `available=false` 降级运行。
- **入口文件**: `src/main/java/com/agentcloud/llm/OpenAiCompatibleClient.java`
- **核心类/函数**:
  - `OpenAiCompatibleClient.complete` — 发起 chat completion 请求。
  - `LlmConfig` — 读取 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` 等配置。
- **对外提供**: LLM 调用能力。
- **依赖**: 仅 Jackson / JDK HTTP Client

### 5.8 实验与评估模块

- **目录**: `src/main/java/com/agentcloud/engine`
- **职责**: 为 tool-aware execution 和 orchestration 提供可量化的 baseline 比较能力。`ExperimentRunService` 在任务生命周期中自动收集指标并落盘；`ExperimentMatrixService` 支持按内置 case catalog 批量创建三种模式（`strong_only` / `small_only` / `orchestrated`）的可比较 run。
- **入口文件**: `src/main/java/com/agentcloud/engine/ExperimentMatrixService.java`
- **核心类/函数**:
  - `ExperimentRunService.createOrUpdateRun` — 任务推进时自动写 experiment_runs。
  - `ExperimentMatrixService.createMatrixRuns` — 按 case + mode 批量创建任务。
- **对外提供**: 实验指标持久化、矩阵批量运行、汇总查询。
- **依赖**: `store`, `model`, `engine`

### 5.9 存储模块

- **目录**: `src/main/java/com/agentcloud/store`, `src/main/resources`
- **职责**: 该模块负责数据库初始化、类型映射、SQL Object DAO 定义和 schema 管理。它为上层提供稳定的会话、任务、决策、产物、事件、关系、技能、checkpoint、learning memory、tool invocation、experiment run、session message 持久化接口。项目当前所有状态都落在单个本地 SQLite 文件中。
- **入口文件**: `src/main/java/com/agentcloud/store/DatabaseManager.java`
- **核心类/函数**:
  - `DatabaseManager` — 创建数据源、注册 mapper、执行 `schema.sql`。
  - `*Dao` — 针对每个实体提供注解 SQL 查询与写入（15+ 个 DAO）。
  - `Mappers` / `JsonMapper` / `InstantArgumentFactory` — 处理数据库类型转换。
- **对外提供**: SQLite 持久化与表结构。
- **依赖**: SQLite JDBC, Jdbi, HikariCP

## 6. 模块依赖与数据流

### 6.1 模块间依赖

图: 模块间依赖

    cli/Main
       |
       +----> server ----> engine ----> store
                       |      |
                       |      +----> engine/router
                       |      |
                       |      +----> engine/memory ----> store
                       |      |
                       |      +----> runtime ----> store
                       |      |
                       |      +----> judgment ----> llm
                       |      |
                       |      +----> worker ----> tool ----> llm
                       |      |
                       |      +----> model
                       |
                       +----> llm

    图例: `---->` 表示编译期/运行期依赖方向。

### 6.2 核心数据流

图: 核心数据流

    [HTTP 请求]
        |
        v
    TaskHandler --> TaskService --> ControlNodeGraph --> WorkerRouter
        |                |                 |                |
        |                |                 v                |
        |                |     TaskRuntimeContextBuilder    |
        |                |                 |                |
        |                |          PacketBuilder           |
        |                |                 |                |
        |                |          JudgmentService         |
        |                |                 |                |
        |                +------------> ConsolidationService
        |                                  |
        +----------------------------------v
         [SQLite sessions/tasks/events/tool_invocations/learning_memories/...]
        ^
        |
    [JSON 响应 / 静态页面]

## 7. 构建与部署

### 7.1 环境要求

- Java: 21
- Maven: 3.9+ 为宜
- 数据库: 无需外部数据库，运行时自动创建本地 SQLite 文件
- 其他依赖: 写权限到 `${user.home}/.agentcloud/`

### 7.2 本地开发

```bash
# 步骤 1: 编译并打包
mvn package

# 步骤 2: 启动服务
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

### 7.3 构建与部署

- 构建命令: `mvn package`
- 产物位置: `target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`
- 部署方式: 直接以单进程 Java 服务运行

### 7.4 配置说明

| 配置项 | 文件/环境变量 | 说明 | 默认值 |
|--------|-------------|------|--------|
| `server.port` | JVM System Property | HTTP 监听端口 | `8080` |
| `user.home` | JVM/System 环境 | 决定 SQLite 文件落点 | 当前用户主目录 |
| `schema.sql` | `src/main/resources/schema.sql` | 首次启动时初始化表结构 | 内置资源 |
| 日志级别 | `src/main/resources/logback.xml` | 控制应用和依赖日志输出 | root=`INFO` |

## 8. 代码规模概要

| 指标 | 数值 |
|------|------|
| 源代码文件数 | 103（Java） |
| 主要语言分布 | Java: 103, XML: 1, SQL: 1, JS/CSS/HTML: 4 |
| 测试文件数 | 27（JUnit 5） |
| 最近活跃度 | feat: add continuity runtime, tool-aware execution, and dialogue workspace |
