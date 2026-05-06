# Multica 对标分析与 agent-cloud-harness 借鉴方案

## 1. 文档目标

本文档不是泛泛讨论 Multica，而是把 **Multica 的产品骨架** 与当前 `agent-cloud-harness` 的现状做对比，回答三个问题：

1. `agent-cloud-harness` 现在已经做到哪了
2. Multica 哪些能力值得借鉴，哪些其实本项目已经具备雏形
3. 下一阶段应该如何贴着当前代码与架构落地，而不是另起炉灶重做一套

---

## 2. 一句话结论

**agent-cloud-harness 已经不是一个空白项目，它已经具备“continuity-first control plane”的核心骨架；真正值得从 Multica 借鉴的，不是重做任务/会话/路由基础，而是补齐“多 Agent 统一接入层 + 本地 Agent Runtime/Daemon 抽象 + 面向 Agent 管理的可观察性”这三块产品化能力。**

换句话说：

- `agent-cloud-harness` 强在 **任务控制、续跑、packet、runtime context、tool-aware 执行、实验评估骨架**
- Multica 强在 **managed agents product shape**，也就是“把多个 coding agent 当成统一可管理队友”的产品壳

所以最合理的方向不是推翻当前项目，而是：

> 在 `agent-cloud-harness` 现有 control plane 之上，增加一层 **Managed Agent Runtime / Provider Adapter**，把它从 continuity harness 推进成多 Agent 管理平台。

---

## 3. Multica 的核心借鉴点

从公开描述和产品定位看，Multica 最值得借鉴的骨架有 5 个：

### 3.1 统一 Agent SDK / Adapter
核心思想：
- Claude Code、Codex、OpenClaw、OpenCode 等底层差异不暴露给上层
- UI、任务层、编排层只面对统一接口

### 3.2 Daemon / 本地运行时
核心思想：
- 本机 agent CLI 的发现、登录态、版本、可执行性、启动与日志，都由本地 daemon 接管
- 产品层不直接和零散 CLI 耦合

### 3.3 Task-centric 而不是 chat-centric
核心思想：
- 用户不是单纯“聊天”，而是在“派任务、追进度、收结果”
- task / run / artifact / status 才是一等对象

### 3.4 Compound skills / 复合技能
核心思想：
- agent 不是一次性调用，而是能沉淀复用的能力系统
- 技能、工具、路由经验是可积累的资产

### 3.5 可观察性优先的产品结构
核心思想：
- 用户要看得见谁在跑、为什么被选中、卡在哪、产出了什么、失败原因是什么

---

## 4. agent-cloud-harness 当前现状判断

结合 `README.md`、`docs/ARCHITECTURE.md`、`docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 以及当前代码，可以把项目现状概括为：

### 4.1 已经具备的强项

#### A. Control Plane 核心骨架已经存在
当前项目已经有：
- `TaskService`
- `SessionService`
- `ControlNodeGraph`
- `PacketBuilder`
- `ContextReconstructor`
- `TaskRuntimeContextBuilder`
- `ExperimentRunService`
- `ExperimentMatrixService`

这说明它并不是一个简单的调用脚本，而是已经具备 **任务生命周期 + 控制图 + 续跑协议 + 运行轨迹** 的控制平面雏形。

#### B. Task-centric 模型已经很明确
项目当前天然就是围绕这些对象组织的：
- session
- task
- event
- decision
- artifact
- checkpoint
- resume packet
- experiment run
- session message

这点其实已经比很多 agent 产品成熟。Multica 值得借鉴的 task-centric 视角，在这里并不是缺失项，而是已有优势。

#### C. Continuity / Resume / Handoff 是本项目特色优势
Multica 更像 managed agents platform，
而 `agent-cloud-harness` 当前非常突出的地方是：
- checkpoint
- resume packet
- handoff packet
- context reconstruction
- runtime working memory

这是一个更偏 orchestration runtime 的强项，也是差异化资产，不应该被 Multica 风格稀释掉。

#### D. Tool-aware execution 已有较深雏形
从 `ToolAwareWorkerExecutor.java` 看，项目已经有：
- 多轮工具执行上限
- tool planning
- grounding guard
- repeated tool guard
- no progress guard
- tool trace

这意味着工具链执行不是概念，而是已经进入工程实现层。

#### E. 实验评估意识已经内建
已有：
- `ExperimentRunService`
- `ExperimentMatrixService`
- `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- 多类测试覆盖 runtime / packet / tool / experiment / route trace

这说明项目不是只做功能，也在试图证明 orchestration 的价值。

---

## 5. 对比后真正的差距在哪里

真正的差距，不在 task/session/control graph 这些底层，而在“多 Agent 管理产品化”这层。

### 5.1 当前项目更像 control plane，不像 managed agents product
现在的核心对象仍是：
- task
- worker
- control node
- packet
- judgment

但如果要更接近 Multica 的“managed agents platform”，还需要一个更明确的对象层：
- agent provider
- agent runtime
- agent session
- agent run
- provider capability
- provider readiness

也就是说，当前 `worker` 更偏抽象执行单元，
但还没完整承接 “Claude Code / Codex / OpenClaw / OpenCode 这些真实 agent 实体” 的产品建模。

### 5.2 当前缺少统一 Provider Adapter 层
虽然项目里已经有：
- `WorkerRegistry`
- `WorkerRouter`
- `WorkerExecutorRouter`

但它们当前更像“内部 worker 选择与执行分流”，不是“外部 agent provider 标准接入层”。

差距体现在：
- 没有统一的 provider detect / auth / capabilities / createSession / runTask 抽象
- 没有针对 Codex / Claude Code / OpenClaw / OpenCode 的适配器边界
- 还没有“接入一个新 agent”的产品化入口

### 5.3 当前缺少 Daemon / Runtime Supervisor 明确层
现在 `Main.java` 里直接装配：
- DB
- registry
- router
- tool registry
- executor
- server

这对单机原型是合理的，但如果往 Multica 那种 managed agents 平台走，会有几个问题：
- 本地 CLI 发现逻辑没有独立层
- provider 登录态和版本探测没有独立层
- 外部 agent 进程的生命周期管理没有单独 supervisor
- 运行日志、stderr、exit code、health 状态还没有形成明确 runtime abstraction

### 5.4 当前 UI 偏“任务控制与诊断”，还不够“Agent 管理工作台”
已有 `/console/` 和 `/dialogue/`，这很好。
但如果对标 Multica，还欠这类视图：
- Agent inventory
- Provider readiness/status
- Installed CLI detection
- Auth required / unavailable reason
- Agent run timeline
- Agent artifacts by provider

也就是现在更像“任务运维控制台”，还不是“多 Agent 管理台”。

### 5.5 Worker 和 Agent Provider 语义尚未完全分开
当前的 `Worker` 更像：
- 能力节点
- 执行节点
- 路由目标

但未来如果接入真实 agent 产品，需要分两层：

1. **Agent Provider**
   - codex
   - claude-code
   - openclaw
   - opencode

2. **Worker Persona / Execution Role**
   - planner
   - executor
   - reviewer
   - consolidator

这样才能支持：
- 一个 provider 承担多个角色
- 一个角色可路由到不同 provider
- orchestration 策略与具体 provider 解耦

---

## 6. 对标结论：哪些要借鉴，哪些不要重做

## 6.1 应该借鉴的

### A. 增加统一 Agent Provider Adapter 层
这是当前最值得补的。

### B. 增加本地 Agent Runtime / Daemon 视角
把“发现、检测、可用性、启动、健康、日志”从 worker execution 中拆出来。

### C. 增加 Agent 管理型 UI
让用户不只看 task，也能看 agent inventory 和 provider 状态。

### D. 把任务、运行、产物与 provider 绑定得更清楚
让每个 run 都能回答：
- 是谁执行的
- 为什么选它
- 它属于哪个 provider
- 它当时的 readiness / model tier / role 是什么

## 6.2 不建议重做的

### A. 不要推翻 task / session / packet / checkpoint 骨架
这些恰恰是当前项目的资产。

### B. 不要把产品重心退化成“多 chat 窗口”
Multica 看起来像 managed agents platform，但 `agent-cloud-harness` 的差异化优势在 control plane 与 continuity，不该退化成聊天器。

### C. 不要把 worker router 直接替换成 provider switcher
应该是 **Provider Adapter -> Runtime -> Worker/Role Router -> Task Control Graph** 的叠加，而不是互相覆盖。

---

## 7. 建议的落地架构

建议在现有架构上新增三层能力，而不是大改既有层。

## 7.1 新增 Agent Provider Layer
建议新增包：

```text
src/main/java/com/agentcloud/agent/
  AgentProvider.java
  AgentProviderRegistry.java
  AgentCapability.java
  AgentReadiness.java
  AgentSessionRef.java
  AgentRunRef.java
  providers/
    CodexProvider.java
    ClaudeCodeProvider.java
    OpenClawProvider.java
    OpenCodeProvider.java
```

建议接口：

```ts
interface AgentProvider {
  String providerId();
  DetectResult detect();
  AuthStatus authStatus();
  CapabilitySet capabilities();
  AgentSessionRef createSession(CreateSessionRequest req);
  AgentRunRef runTask(RunTaskRequest req);
  void interruptRun(String runId);
  List<AgentArtifact> listArtifacts(String runId);
}
```

这层的作用：
- 统一接入真实 agent provider
- 屏蔽 CLI / API 差异
- 给上层提供稳定 contract

---

## 7.2 新增 Agent Runtime Supervisor
建议新增包：

```text
src/main/java/com/agentcloud/runtime/agent/
  AgentDiscoveryService.java
  AgentRuntimeSupervisor.java
  AgentProcessRecord.java
  AgentHealthService.java
  AgentLogService.java
```

职责：
- 探测本机是否安装 codex / claude / openclaw / opencode
- 检测版本、执行可用性、登录态
- 管理子进程生命周期
- 统一采集 stdout/stderr/exit code
- 对外提供 health/readiness/status

这层相当于把 Multica 的 daemon 思路，贴到当前 harness 里。

---

## 7.3 在现有 WorkerRouter 之上增加 Provider-aware 路由
当前 `WorkerRouter` 已经有：
- taskType
- learning memory hint
- model tier
- fallback reason
- route trace

这是很好的基础，不该废弃。

建议演进为两段式：

### 第一步：Provider Selection
先决定：
- 选哪个 provider
- 为什么选
- provider readiness 是否可用
- 是否需要 fallback

### 第二步：Worker Role Selection
再决定：
- 这个 provider 承担 planner / executor / reviewer 的哪个角色
- 进入哪个 control node

这样保留现有 route trace 优势，同时让路由结果更贴近真实多 Agent 管理。

---

## 8. 与当前代码结构的贴合点

### 8.1 `Main.java` 适合成为 Provider Layer 装配入口
当前所有核心依赖都在 `Main.java` 手工装配。

这意味着新增 provider / runtime supervisor 最顺的接入点就是这里。

建议在 `Main.java` 中新增装配顺序：
1. AgentDiscoveryService
2. AgentProviderRegistry
3. AgentRuntimeSupervisor
4. WorkerRegistry / WorkerRouter
5. TaskService / SessionService
6. HTTP Server

---

### 8.2 `WorkerRouter.java` 已经天然适合承接 provider-aware trace
当前它已经返回：
- `selectedWorker`
- `fallbackWorkers`
- `routeReason`
- `routeSource`
- `selectedModelTier`
- `selectedExecutionRole`
- `whySelected`
- `fallbackReason`

这很强。

下一步只要扩展为再包含：
- `selectedProvider`
- `providerReadiness`
- `providerVersion`
- `providerAuthStatus`

就可以很自然升级成 Multica 风格的路由解释层。

---

### 8.3 `TaskService` 适合新增 run/provider 投影
当前 `TaskService` 已经承担：
- createTask
- updateTaskState
- refreshResumePacket
- selectWorker
- runtime context
- judgment trace
- tool trace
- experiment run record

建议继续扩展：
- `getAgentRunTrace(taskId)`
- `getProviderSelection(taskId)`
- `getTaskExecutionInventory(taskId)`

让任务详情不只显示 worker/control graph，也显示 provider/run 维度。

---

### 8.4 `ToolAwareWorkerExecutor` 保持不动或轻改
这块已经比较深入。

建议不要重写成 provider layer 的一部分，而是继续保留为：
- 某类 worker role 的执行器
- 未来可由 provider capability 决定是否启用 tool-aware path

也就是说：
- provider layer 决定“谁来做”
- tool-aware executor 决定“怎么做”

这个边界最好保持。

---

## 9. 产品与页面借鉴方案

## 9.1 现有页面继续保留
- `/dialogue/` 继续作为任务发起与消息交互入口
- `/console/` 继续作为任务与 live flow 诊断入口

## 9.2 新增 `/agents/` 页面
建议新增一个 agent 管理页面，展示：
- provider 名称
- 是否安装
- 版本
- auth 状态
- readiness
- capabilities
- 最近检测时间
- 最近失败原因

这样可以补上 Multica 最强的 “managed agents inventory” 体验。

## 9.3 在 task detail 中新增 Provider / Run 面板
现有任务详情建议增加：
- selected provider
- selected worker role
- selection reason
- fallback reason
- run status
- process/log reference
- artifacts by run

## 9.4 新增 Runtime / Health 面板
建议在 console 中增加 runtime 区块：
- agent runtime online/offline
- active runs
- crashed runs
- auth-needed providers
- unavailable providers

---

## 10. 分阶段落地路线

## Phase 1，补齐多 Agent 管理骨架
目标：**让项目具备 Managed Agent Runtime 雏形**

范围：
- 新增 `agent/` provider abstraction
- 新增 discovery / readiness / auth status 抽象
- 接入 2 个 provider skeleton（例如 Codex / OpenClaw）
- 为 route trace 增加 provider 维度
- 增加 `/api/v1/agents` 和 `/api/v1/agents/{id}`

成功标准：
- 系统能列出本机发现到的 agent provider
- 每个 provider 有 status / version / auth / capabilities
- task route trace 能显示 selected provider

---

## Phase 2，补齐 daemon/runtime 视角
目标：**让系统从控制平面升级到可管理运行时**

范围：
- AgentRuntimeSupervisor
- provider process/log lifecycle
- run level records
- `/agents/` 页面
- runtime health 面板

成功标准：
- 能看见某个 provider 当前是否健康
- 能回看 agent run 的日志和退出状态
- 能区分 provider 不可用、登录失效、执行失败等不同问题

---

## Phase 3，把 provider 接进 orchestration 主闭环
目标：**让强模型调小模型的叙事真正落到真实 agent provider 上**

范围：
- planner / executor / evaluator 角色与 provider 绑定
- orchestrated 模式下的 provider-aware route trace
- baseline matrix 中加入 provider 维度
- acceptance / cost / handoff 对比

成功标准：
- `strong_only / small_only / orchestrated` 不只是 model_mode，而能映射到真实 provider 组合
- 实验能回答不同 provider 组合下的效果差异

---

## 11. 优先级建议

如果只做 3 件最重要的事，我建议按这个顺序：

### Priority 1
**新增 Agent Provider 抽象层**

原因：这是从当前 harness 走向 Multica 风格 managed agents platform 的第一步。

### Priority 2
**新增 Agent Discovery + Readiness + Auth 状态接口**

原因：没有这一层，就没有真正的 daemon/product runtime 视角。

### Priority 3
**在 console 中新增 Agent Inventory / Runtime Health 面板**

原因：这样用户才能“看见系统在管理 agent”，而不只是管理 task。

---

## 12. 最终判断

### 当前项目不缺什么
`agent-cloud-harness` **不缺**：
- task/session/control graph
- continuity packet
- runtime context
- tool-aware execution
- experiment skeleton

### 当前项目真正缺什么
它真正缺的是：
- 多 Agent provider 统一接入抽象
- 本地 agent runtime/daemon 视角
- Agent inventory / readiness / auth / logs 的产品层
- 把 provider 维度写进 task/run/experiment 的完整解释链

### 所以最合理的借鉴方式是
不是“按 Multica 重做一套”，而是：

> 用 Multica 的 **managed agents product shape**，去包装并增强 `agent-cloud-harness` 已有的 continuity-first orchestration core。

这是最顺、也最有差异化价值的路线。

---

## 13. 建议的下一步文档

基于这份对标分析，建议接着补两份工程化文档：

1. **《agent provider 接入技术设计》**
   - package 设计
   - interface 草案
   - provider registry / discovery / readiness 数据结构

2. **《agent inventory 与 runtime health 页面方案》**
   - 页面信息架构
   - API contract
   - 字段与状态机定义

如果要直接推进开发，建议先写第 1 份。