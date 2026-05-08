# Architecture

<!-- 更新时间：2026-05-07 -->
<!-- 分析依据：当前工作区源码、测试、运行时 contract 与最新 roadmap 文档 -->

## 1. 项目简介

Agent Cloud Harness 是一个面向多轮、长时程、可恢复任务执行的轻量 runtime harness。

它当前最准确的定位，不是“通用 agent 平台大全”，也不是“更厚的 coding shell”，而是一个：

## **continuity-first runtime substrate**

也就是，它的核心职责不是只帮助模型完成某一次更好的回答，而是让任务在时间维度上保持连续、可观察、可恢复、可移交、可再进入。

当前项目主线应理解为：

- 为 task identity over time 提供控制面
- 为多轮执行提供显式 working-memory surface
- 为 execution / judgment / handoff 提供共享 runtime cognition seam
- 为 pause / resume / checkpoint / handoff / recovery 提供 continuity semantics
- 为 tool trace、artifact、decision、learning hint 提供可追踪 evidence surface
- 为未来 loop / routine / async / background execution 提供演进基础

当前形态仍是单机 harness，不是分布式 control plane。
但它的演进方向已经不只是“最小 loop”，而是在收敛成一个 continuity-first orchestration/runtime skeleton。

建议联读：

- `docs/PHASE2_ROADMAP.md`
- `docs/HARDNESS_PHASE1_ALIGNMENT.md`
- `docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `C:\Users\47037\.openclaw\workspace\docs\AGENT_CLOUD_HARNESS_POSITIONING_DRAFT_2026-05.md`
- `C:\Users\47037\.openclaw\workspace\docs\AGENT_CLOUD_HARNESS_ROADMAP_V1_2026-05.md`

## 2. 技术栈与约束

### 2.1 技术栈全景

| 类别 | 技术 | 版本 | 备注 |
|------|------|------|------|
| 后端语言 | Java | 21 | `pom.xml` 启用 `--enable-preview` |
| HTTP 接入 | JDK `HttpServer` | JDK 自带 | `com.sun.net.httpserver.HttpServer` + 虚拟线程 |
| 前端 | Vanilla JS + CSS | N/A | 内置 `/console/` 与 `/dialogue/` |
| JSON | Jackson | 2.17.2 | 统一 `snake_case` 输出 |
| 数据库 | SQLite | 3.46.0.0 驱动 | 本地文件，零外部依赖 |
| DAO | Jdbi SQL Object | 3.45.1 | 注解式 SQL，无 ORM |
| 连接池 | HikariCP | 5.1.0 | 轻量连接管理 |
| LLM 适配 | OpenAI-compatible client | N/A | `llm/OpenAiCompatibleClient` |
| 工具层 | 内置 Tool Registry | N/A | 受 `ToolPolicy` 约束的受控本地工具，含文件工具与命令工具；`git/shell/powershell/cmd` 都按宿主机真实可执行性探测动态暴露，其中 `powershell/cmd` 仍仅 Windows 宿主可用 |
| 日志 | SLF4J + Logback | 2.0.13 / 1.5.6 | root=`INFO` |
| 测试 | JUnit Jupiter | 5.11.0 | 已覆盖 packet、orchestration、judgment、tool-aware execution、experiment、message projection、mounted-context seam 等方向 |

### 2.2 技术栈约束规则

- **语言版本**: Java 21，且必须带 preview 编译参数。
- **包管理**: Maven 单模块结构，不要擅自拆成多模块。
- **Web 框架**: 继续沿用 `HttpHandler` 手写路由，不引入 Spring/Jersey。
- **持久化**: 继续沿用 SQLite + Jdbi SQL Object；schema 由 `schema.sql` 初始化。
- **JSON 契约**: 统一共享 `ObjectMapper`，输出 `snake_case`。
- **前端形态**: 当前前端是 resources 下的静态页面，不存在额外 SPA 构建链。
- **演进原则**: 优先收紧 runtime contract、trace、evidence、continuity semantics，不优先扩表层功能数量。

## 3. 目录结构

```text
agent-cloud-harness/
├── src/
│   ├── main/
│   │   ├── java/com/agentcloud/
│   │   │   ├── cli/                 # Main.java，手工装配全部依赖
│   │   │   ├── engine/              # Task/Session/Control Graph/Experiment/Learning Memory
│   │   │   ├── engine/memory/       # Resume/Handoff packet 构建与重建
│   │   │   ├── engine/router/       # Worker 注册与路由
│   │   │   ├── judgment/            # Prompt-based execution/completion judgment
│   │   │   ├── llm/                 # OpenAI-compatible LLM client 与配置
│   │   │   ├── runtime/             # Active Context / Runtime Context / mounted view 组装
│   │   │   ├── runtime/context/     # MountedContextView / renderer / panel 模型 / render mode
│   │   │   ├── server/              # HttpServer 与各资源 Handler
│   │   │   ├── store/               # DB 初始化、Mapper、DAO
│   │   │   ├── tool/                # 受控本地工具与 ToolPolicy
│   │   │   ├── worker/              # 默认执行器、tool-aware 执行器、执行分流
│   │   │   └── model/               # Record DTO / View / Packet / Trace
│   │   └── resources/
│   │       ├── schema.sql           # SQLite schema
│   │       ├── logback.xml          # 日志配置
│   │       └── web/
│   │           ├── console/         # Web Console 静态前端
│   │           └── dialogue/        # Dialogue 静态前端
│   └── test/java/com/agentcloud/    # 回归测试与 seam 验证
├── docs/                            # 架构、规格、契约、排查文档
├── scripts/                         # Java 21 构建/测试/运行脚本
├── pom.xml
└── dependency-reduced-pom.xml
```

## 4. 系统架构

### 4.1 架构总览

```text
Browser / curl / SDK
    |                       +-----------------------------+
    +---------------------> | NioHttpServer              |
                            | Task/Session/... Handlers  |
                            | /console/ /dialogue/       |
                            +-------------+---------------+
                                          |
                                          v
                            +-----------------------------+
                            | TaskService / SessionService|
                            | ExperimentRun/Matrix        |
                            +-------------+---------------+
                                          |
                                          v
                            +-----------------------------+
                            | ControlNodeGraph            |
                            | intake -> scheduler -> ...  |
                            +------+------+------+--------+
                                   |      |      |
                                   |      |      +--> PacketBuilder / Consolidation
                                   |      |
                                   |      +--> TaskRuntimeContextBuilder
                                   |             + ActiveContextBuilder
                                   |             + ContextViewBuilder
                                   |
                                   +--> WorkerRouter
                                          + LearningMemoryService
                                          + WorkerExecutorRouter
                                                |
                                                +--> DefaultWorkerExecutor
                                                |      + MountedContextPromptRenderer
                                                |
                                                +--> ToolAwareWorkerExecutor
                                                       + MountedContextPromptRenderer
                                                       + ToolRegistry/ToolPolicy
                                                       + ToolInvocation trace
                                                       + LLM Client

                           +--------------------------------------+
                           | PromptBasedJudgmentService           |
                           | execution/completion judgment        |
                           | shares runtime cognition seam        |
                           +--------------------------------------+

All runtime traces / packets / decisions / artifacts / tool calls
                    |
                    v
         +-----------------------------+
         | Jdbi DAO + SQLite           |
         | sessions/tasks/events/...   |
         +-----------------------------+
```

### 4.2 当前最关键的架构判断

这个项目的核心链条，不该只理解为：

`task -> worker -> text result`

而应该理解为：

`packet continuity -> working memory -> worker execution -> judgment -> consolidation -> next round continuity`

这条链条里的每个环节都正在从“隐式文本拼接”收敛到“更显式的 runtime contract”。

### 4.3 分层结构

| 层级 | 目录/命名空间 | 职责 | 典型类/文件 |
|------|-------------|------|------------|
| 前端层 | `src/main/resources/web` | 本地观测与交互 UI | `console/app.js`, `dialogue/app.js` |
| 接入层 | `src/main/java/com/agentcloud/server` | HTTP API、静态资源服务、错误包装 | `NioHttpServer`, `TaskHandler`, `SessionHandler`, `WebConsoleHandler` |
| 应用层 | `src/main/java/com/agentcloud/engine` | 任务/会话生命周期、实验、学习记忆、控制动作编排 | `TaskService`, `SessionService`, `ControlNodeGraph`, `ExperimentRunService` |
| 运行时层 | `src/main/java/com/agentcloud/runtime` | Active Context 与 TaskRuntimeContext 组装 | `ActiveContextBuilder`, `TaskRuntimeContextBuilder`, `TaskRuntimeContext` |
| mounted context 层 | `src/main/java/com/agentcloud/runtime/context` | mounted working-memory view、panel 化表示、prompt renderer、mode seam | `MountedContextView`, `ContextViewBuilder`, `MountedContextPromptRenderer`, `PromptRenderingMode` |
| 执行层 | `src/main/java/com/agentcloud/worker` | worker 一轮执行、工具调用、结果结构化、执行元数据 | `DefaultWorkerExecutor`, `ToolAwareWorkerExecutor`, `WorkerExecutionResult`, `WorkerExecutionEnvelope` |
| judgment 层 | `src/main/java/com/agentcloud/judgment` | execution/completion judgment，驱动 continue / wait / checkpoint / handoff / done | `PromptBasedJudgmentService`, `JudgmentContext` |
| 工具层 | `src/main/java/com/agentcloud/tool` | 受控工具注册、权限、调用结果 | `ToolRegistry`, `ToolPolicy`, `ToolResult` |
| 持久化层 | `src/main/java/com/agentcloud/store` | schema、DAO、数据库访问 | `TaskDao`, `EventDao`, `ArtifactDao`, `ToolInvocationDao` |
| 模型层 | `src/main/java/com/agentcloud/model` | task/packet/decision/artifact/trace/eval DTO | `Task`, `ResumePacket`, `Checkpoint`, `Decision`, `ToolInvocationRecord` |

## 5. Continuity-first 设计主轴

### 5.1 任务身份优先于单轮回答

项目最值得坚持的设计取向是：

- 任务要能跨轮存在
- 执行要有中间边界
- 中断后要可恢复
- 移交要有 machine-readable packet
- 判断要能解释为什么继续/暂停/结束

所以这个项目的核心价值，不在单次回答质量本身，而在 **任务随时间保持连贯**。

### 5.2 mounted context 是 Phase 2 working-memory spine

当前架构中最重要的新 seam 是 mounted context。

它的意义不是“更好看的 prompt 拼装”，而是：

- 给 runtime 一个 task-local working-memory surface
- 把 context engineering 从经验做法推进成 runtime contract
- 给 worker execution 和 judgment 提供共享认知面
- 为未来 demotion / reload / archive reopen 提供策略插口

当前代码里已经有：

- `MountedContextView`
- `ContextViewBuilder`
- `MountedContextPromptRenderer`
- `PromptRenderingMode`
- `TaskRuntimeContext.mountedContextView`

并且 mounted seam 已进入：

- `DefaultWorkerExecutor`
- `ToolAwareWorkerExecutor`
- `PromptBasedJudgmentService`

这意味着项目当前的重点不是“再提出 mounted context 概念”，而是把它收敛成稳定、可测、可 rollout 的 working-memory contract。

### 5.3 ActiveContext 仍然有用，但不再是最终抽象

`ActiveContext` 仍然是有价值的上游综合面。

但从长期看，它更适合作为：

- compatibility projection，或
- synthesis layer

而 mounted context 更适合成为 runtime-facing working set abstraction。

### 5.4 packet / checkpoint 不是旧包袱，而是 continuity 资产

项目里已有的：

- `ResumePacket`
- `Checkpoint`
- `HandoffPacket` 方向
- `TaskRuntimeContext`

不是应该被“更新潮的 memory 概念”替换掉的旧设计。
相反，它们是 continuity harness 的硬资产，因为它们承载：

- resumability
- recoverability
- auditable continuity boundary
- structured handoff
- lifecycle semantics

## 6. 运行时主链路

### 6.1 最小闭环

当前最小运行闭环仍是：

```text
create task
  -> intake
  -> scheduler select worker
  -> build task runtime context
  -> execute one round
  -> persist event / artifact / tool trace
  -> execution + completion judgment
  -> continue / wait / checkpoint / handoff / done
```

### 6.2 TaskRuntimeContext 的角色

`TaskRuntimeContext` 是当前运行时共享事实面的核心容器，聚合：

- `task`
- `latestPacket`
- `latestCheckpoint`
- `recentEvents`
- `recentDecisions`
- `recentArtifacts`
- `recentMessages`
- `activeContext`
- `mountedContextView`

它的重要性在于：

- execution 与 judgment 不再只依赖 task title + raw text
- continuity artifacts 开始以结构化方式进入运行面
- mounted context 能成为行为路径的一部分，而不是附属说明

### 6.3 Worker execution

当前有两条主要执行路径：

#### `DefaultWorkerExecutor`
- 构建基础 system/user prompt
- 解析 LLM 输出
- 根据 `PromptRenderingMode` 注入 mounted context 或 shadow render
- 产出 `WorkerExecutionResult`

#### `ToolAwareWorkerExecutor`
- 采用多轮 tool-aware 协议
- 具备工具选择、调用、trace 记录、二次收敛能力
- 管理 auto-write / grounded output / image input / visual brief 等更复杂执行场景
- 将工具调用与执行 trace 通过 metadata、`tool_invocation_id` 等字段相连

### 6.4 Execution envelope 与 trace

执行结果已经不应只被视为一段 output text。

当前更重要的是把单轮执行稳定收敛成一个可追踪 execution envelope，包括：

- `execution_id`
- `started_at`
- `finished_at`
- `duration_ms`
- `execution_status`
- `tool_invocation_ids`
- result metadata

这条线的重要意义在于：

- 单轮执行有明确边界
- worker round 可和 tool trace 关联
- 后续 experiment/live flow/harness evolution 才有稳定证据面

### 6.5 Judgment

`PromptBasedJudgmentService` 当前承担两类判断：

- `judgeExecution()`
- `judgeCompletion()`

长期重点不是“再多写几个动作词”，而是让 judgment 逐渐依赖同一份 runtime cognition surface：

- mounted context
- active context
- latest worker metadata
- artifacts / tool evidence
- continuity packet

从而让 continue / checkpoint / handoff / done 不只是基于自由文本印象做决定。

## 7. Evidence 与 memory 方向

### 7.1 Evidence-first 而不是 transcript flood

长时程 agent runtime 的关键限制，往往不是模型不会回答，而是上下文债务太大。

因此系统长期应坚持：

- 原始工具输出可保留
- prompt 中只放 bounded preview / structured evidence
- summary 不是 memory 本体，只是进入证据的入口
- judgment / execution 应优先消费高价值结构化证据，而不是长文本堆叠

### 7.2 热温冷分层

mounted context 的长期方向应该支撑热温冷分层：

- hot: 当前 mounted active working set
- warm: bounded summaries + handles
- cold: raw traces / raw artifacts / historical packets
- reopen: 按需把冷数据重新拉回热区

### 7.3 Retrieval / reopen 是后续 Phase，不是现在跳题

项目长期应支持：

- archive reopen
- evidence handle rehydration
- context demotion / reload
- retrieval during reasoning

但前提是先把 mounted working-memory seam 和 lifecycle semantics 收稳。

## 8. Harness 演化方向

项目的长期潜力，不只在于执行任务，也在于逐步演化 harness 自身。

### 8.1 Harness self-evolution 的最小闭环

未来应逐步形成：

1. 记录 runtime trace
2. 压缩为结构化 failure/success evidence
3. 形成 change hypothesis
4. 在小范围验证
5. 根据结果 keep / adjust / rollback

### 8.2 需要的一等工件

这要求以下对象逐步变成一等工件：

- component change contract
- execution/judgment evidence
- eval scenarios
- learning memory candidates
- rollback boundary

因此以下文档方向是合理的：

- `docs/HARNESS_EVOLUTION.md`
- `docs/HARNESS_CHANGE_CONTRACT.md`

长期还可以继续补：

- trace debugging schema
- hard-case eval matrix
- harness evolution ledger

### 8.3 自主改善边界

这个项目可以朝“自主改善”演进，但不应朝“无边界自改主线代码”演进。

更准确的方向是：

**evidence-driven, rollback-aware, controlled self-evolution**

也就是：
- 允许系统基于 runtime trace 识别改进机会
- 允许系统生成 change hypothesis 与 patch candidate
- 允许系统在隔离分支或 sandbox 中做小范围验证
- 只有在评测、验收、回滚边界都明确时，才允许变更被提升

不应发生的是：
- 没有 trace/eval 证据就直接改主线
- 把频繁 prompt 改动误当作学习
- 单次失败就触发大范围架构重写
- 无法说明假设、收益、风险、回滚条件的自改行为

### 8.4 建议的成熟度阶梯

#### Level 0: observe only
- 记录失败模式、连续性断裂、执行/判断偏差
- 不直接提出代码变更

#### Level 1: suggest only
- 生成 change hypothesis
- 标出影响组件、预期收益、验证计划、回滚条件

#### Level 2: patch with approval
- 自动生成 patch candidate
- 自动跑 targeted tests / eval
- 仍需人工批准后才能合并

#### Level 3: sandboxed closed-loop improvement
- 仅在 trace 质量、eval 质量、rollback discipline 足够稳定后
- 才允许系统在隔离 branch / sandbox 中自动闭环迭代
- 即便如此，进入 shared/default runtime 行为仍应有明确 acceptance gate

### 8.5 先从哪些面开始自优化

更安全的早期自优化面包括：
- routing heuristics
- context retention / demotion policy
- evidence budgeting policy
- judgment / evaluator prompt policy
- capability metadata
- benchmark adapter / diagnostic surface

高风险面应更晚开放：
- lifecycle semantics
- persistence schema
- checkpoint / resume contract
- handoff packet structure
- broad cross-module refactor

### 8.6 一条总规则

建议把这条原则写死：

**没有证据，不做自优化；没有评测，不做提升；没有回滚，不做默认推广。**

这能保证项目继续保持 continuity-first identity，而不是滑向 noisy self-editing system。

## 9. 当前最重要的工程优先级

如果对照最新代码现状，项目的最优先顺序应是：

### Priority 1. 完成 mounted context Phase 2A convergence
- renderer boundedness
- mode rollout discipline
- worker/judgment compatibility
- tests and observability

### Priority 2. 收紧 execution/judgment shared cognition surface
- 减少 execution 与 judgment 读不同事实面的情况
- 更明确地让结构化 metadata、artifact、tool evidence 进入 judgment

### Priority 3. 生命周期加固
- pause / resume / checkpoint / handoff / reopen 语义继续收硬

### Priority 4. evidence reopen 与 memory discipline
- 不是先做更大 retrieval，而是先做 reopen policy 和 evidence hierarchy

### Priority 5. adaptive harness evolution
- 在 trace、eval、contract 稳定后，再推进 harness policy 自演化

## 10. 非目标与边界

当前阶段不应让这些方向抢走主线：

- 单纯拼 UI 表层能力
- 更厚的本地 coding shell 便利封装竞赛
- 仅以 agent 数量为卖点的多 agent demo
- 在 continuity semantics 未稳前就激进做 retrieval-first memory 平台
- 在 trace/eval 不稳前就推进高风险自改系统

## 11. 一句话总结

Agent Cloud Harness 当前最准确的架构理解，不是“一个能跑 agent 的小控制平面”，而是：

**一个正在从最小 runtime loop 收敛为 continuity-first runtime substrate 的 agent harness，其核心主线是 working memory、shared runtime cognition、lifecycle semantics、evidence discipline 与可演化的 harness contract。**
