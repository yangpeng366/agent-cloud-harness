# Hardness Phase-1 与当前代码对齐说明

> 历史材料说明：本文保留 2026-05-03 这轮 hardness 对齐判断，主要用于回看当时如何把 blueprint 压回代码现实。当前不作为直接开工入口；如需查看现状，优先看 `docs/ARCHITECTURE.md`，如需看当前执行/验证链路，优先看 `docs/LIVE_FLOW_RUNBOOK.md` 与 `docs/TROUBLESHOOT.md`。

更新时间：2026-05-03

## 1. 目的

本文档用于把当前 `agent-cloud-harness` 代码和外部 `agent-cloud-architecture` 里的 hardness phase-1 方案重新对齐，回答三个更直接的问题：

1. 方案里提到的对象和 runtime contract，哪些当前代码已经有落点
2. 哪些部分已经不只是设计，而是已有真实实现雏形
3. 哪些 gap 仍然存在，下一步应该补代码还是补文档

它不是重复 hardness 方案全文，而是把方案压回当前仓库现实。

---

## 2. 总体判断

如果压成一句话，当前项目的状态是：

> `agent-cloud-harness` 已经不只是“准备做 hardness”，而是已经拥有一部分 hardness phase-1 的代码落点，尤其是 tool policy、tool trace、tool-aware execution、runtime context、judgment trace 和 checkpoint / packet 持久化；但这些能力还没有完全被收束成统一的 runtime contract object chain。

这意味着：

- 外部方案文档并没有跑偏
- 但也不能再把项目写成“这些对象都还不存在”
- 更准确的说法是：**已经有若干局部 contract 和持久化表面，只是还没统一封装成你前面那套 phase-1 hardness 主线对象链**

---

## 3. 和 hardness phase-1 对应的当前代码落点

## 3.1 `WorkerExecutionEnvelope`

### 方案中的角色
统一描述一次 worker round 的标准化结果。

### 当前最接近的代码
- `src/main/java/com/agentcloud/worker/WorkerExecutionResult.java`
- `src/main/java/com/agentcloud/worker/DefaultWorkerExecutor.java`
- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

### 当前状态判断
**partial**

### 为什么是 partial
当前已经有统一的 `WorkerExecutionResult`，字段包括：
- `summary`
- `outputText`
- `producedArtifact`
- `artifactTitle`
- `artifactContent`
- `suggestedNextStep`
- `confidence`
- `executionStatus`
- `evidenceRefs`
- `unfinishedItems`
- `tokenUsage`
- `durationMs`
- `metadata`

这已经非常接近 phase-1 里想要的 execution envelope。

但还缺几个 hardness 视角下的重要点：
- 没有显式 `execution_id`
- 没有显式 `session_id/task_id/worker_id` 直接绑定在对象本身上
- 没有显式 `tool_invocation_refs`
- 没有显式 `started_at/finished_at`
- `status` 语义还偏轻，更多是结果字段而不是状态迁移驱动对象

### 最自然的下一步
不是重新发明对象，而是：

- 以 `WorkerExecutionResult` 为近邻
- 增厚成更完整的 `WorkerExecutionEnvelope`
- 或新增 envelope 包住现有 result

---

## 3.2 `ToolInvocationRecord`

### 方案中的角色
记录工具调用 trace，作为可解释、可审计、可恢复的一部分。

### 当前最接近的代码
- `src/main/java/com/agentcloud/model/ToolInvocationRecord.java`
- `src/main/java/com/agentcloud/store/ToolInvocationDao.java`
- `src/main/resources/schema.sql` 中的 `tool_invocations`
- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

### 当前状态判断
**exists，但未完全升级为 phase-1 目标形态**

### 为什么可以算 exists
这里已经不是“只有想法”，而是有完整代码表面：
- model 已存在
- DAO 已存在
- schema 已存在
- execution 中已真实写 trace

当前字段包括：
- `id`
- `sessionId`
- `taskId`
- `workerId`
- `toolName`
- `arguments`
- `resultSummary`
- `success`
- `elapsedMs`
- `createdAt`
- `metadata`

### 与方案相比还差什么
- 没有显式 `execution_id`
- 没有单独 `status` enum，仍主要靠 `success` boolean
- 没有显式 `touched_paths` 主字段，当前更可能散在 metadata 或工具结果里
- 还没有被明确提升为 phase-1 主 runtime contract 对象

### 最自然的下一步
不是新增一套重复对象，而是：
- 直接增强当前 `ToolInvocationRecord`
- 补 `execution_id` / `status` / `touched_paths`
- 把它从“工具日志”升级成“runtime hardness trace object”

---

## 3.3 `ToolPolicy` / `ToolScope`

### 方案中的角色
声明式放开 worker 工具能力，并约束目录边界、命令边界和副作用边界。

### 当前最接近的代码
- `src/main/java/com/agentcloud/tool/ToolPolicy.java`
- `src/main/java/com/agentcloud/model/Worker.java`
- `src/main/java/com/agentcloud/tool/ToolRegistry.java`
- 各种 `ReadFileTool` / `WriteFileTool` / `PatchFileTool` / `SearchTextTool`

### 当前状态判断
**exists / partial 之间，更接近 exists**

### 依据
当前代码已经真实具备：
- `suggestOnly` worker 限制
- `toolCapabilities` 校验
- `toolScope` 路径边界校验
- 命令超时与输出长度限制
- dangerous command 拦截
- git 子命令 allowlist

这已经是很明确的 runtime enforcement，而不是只有文档原则。

### 与方案相比还差什么
- side-effect level 还没显式抽成统一 enum
- policy deny trace 还没完全上升为统一 continuation/judgment 输入
- `ToolPolicy` 仍更像 enforcement helper，还不是完整的 tool runtime policy surface

---

## 3.4 `ResumeCheckpoint`

### 方案中的角色
把当前轮压成显式恢复入口。

### 当前最接近的代码
- `src/main/java/com/agentcloud/model/Checkpoint.java`
- `src/main/java/com/agentcloud/store/CheckpointDao.java`
- `src/main/resources/schema.sql` 中的 `checkpoints`
- `PacketBuilder`, `ConsolidationService`

### 当前状态判断
**partial**

### 依据
当前项目已经有：
- checkpoint model
- checkpoint 表
- checkpoint query handler
- pause / handoff / consolidation 过程中的 checkpoint 写入

这说明 checkpoint 不是空白。

### 为什么仍是 partial
它更偏：
- consolidation checkpoint
- packet / world model delta 存档

还没有完全变成 phase-1 方案里那种：
- 明确以 `control_node + latest execution + fact set + continuation` 为恢复入口的 resume checkpoint

也就是说：
**checkpoint 已存在，但 resume contract 还没被收硬。**

---

## 3.5 `RuntimeFactSet`

### 方案中的角色
作为 judgment、checkpoint、continuation 共用的执行事实聚合对象。

### 当前最接近的代码
- `TaskRuntimeContext`
- `TaskRuntimeContextBuilder`
- `TaskLiveFlowView`
- `HarnessTraceView`
- `JudgmentTraceView`
- `TaskService.getLiveFlow()` / `getJudgmentTrace()` / `getToolTrace()` 聚合逻辑

### 当前状态判断
**missing as object, partial as capability**

### 解释
当前系统已经能聚合很多 runtime facts：
- route trace
- judgment trace
- tool trace
- runtime context
- artifact / event / decision / checkpoint

所以“事实聚合能力”并不缺。

但它还没有被正式压成一个单独对象：
- `RuntimeFactSet`

这意味着：
- 现在是多个 view/service 各自拼
- 还不是统一的 judgment-friendly / checkpoint-friendly fact aggregate

这正好是外部方案最该往代码里收的一刀。

---

## 3.6 `JudgmentInput` / fact-aware judgment

### 方案中的角色
把 judgment 从“吃一坨上下文”推进到“吃结构化执行事实”。

### 当前最接近的代码
- `src/main/java/com/agentcloud/judgment/JudgmentContext.java`
- `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java`
- `src/main/java/com/agentcloud/engine/RuntimeJudgmentService.java`

### 当前状态判断
**partial**

### 依据
当前 judgment 已经不是空白：
- `PromptBasedJudgmentService` 明确存在
- `JudgmentContext` 明确存在
- `RuntimeJudgmentService` 已经在做一层规则判断

### 为什么还是 partial
当前 judgment 层是显式的，但还没有完全进入你外部方案里说的那种：
- fact-aware judgment input
- continuation action 显式化
- 统一读取 execution/tool/side-effect/retry facts

也就是说：
**judgment layer 已存在，但 judgment contract 还没完全做硬。**

---

## 4. 当前代码比外部方案更往前的地方
这是这次最值得回收进 docs 的部分。

有几块当前代码其实已经比外部方案初稿默认假设的状态更往前：

### 4.1 工具层不是纯设想，而是完整可运行层
当前仓库里已经有：
- 真实 tool classes
- registry
- policy
- DAO
- schema
- tool-aware multi-step executor
- guard 机制

所以不能再把项目写成“工具能力只是待设计边界”。
更准确的是：

> 工具边界和工具执行层都已经存在，当前缺的是把它们进一步收束成 hardness 主线下的统一 contract。

### 4.2 tool trace 已有持久化表面
`tool_invocations` 已经落库，这很关键。
这意味着 phase-1 里的“ToolInvocationRecord 应优先持久化”其实已经被代码验证了。

### 4.3 judgment 已经是显式模块
`judgment/` 和 `RuntimeJudgmentService` 都已存在，说明项目已经不需要从零发明 judgment layer。
真正该做的是把它升级，而不是先创建。

### 4.4 checkpoint / packet / runtime context 已经不是概念层
当前 continuation、packet、checkpoint、runtime context 都已有模型、builder、DAO 或 view。
所以外部文档里要避免把这些写得像纯蓝图。

---

## 5. 当前最值得更新的 docs 方向
如果对照当前代码继续推进 docs，我建议优先补这三类：

### 5.1 更新 capability gap 文档
把“tool-aware 最小多步执行”从 25%-35% 适度上调，
因为现在已经有：
- `ToolAwareWorkerExecutor`
- 多步执行测试
- 工具 trace 持久化
- grounded write guard

更准确的表述应变成：
- 已有真实多步工具执行雏形
- 当前 gap 在统一 execution contract、fact aggregation、continuation alignment

### 5.2 在 architecture/spec 中单列 hardness 对齐章节
明确写：
- 当前已有 `WorkerExecutionResult`
- 当前已有 `ToolInvocationRecord`
- 当前已有 `ToolPolicy`
- 当前已有 `Checkpoint`
- 当前已有 `JudgmentContext`
- 但仍缺统一 `RuntimeFactSet` / `ContinuationAction` / 更硬的 resume contract

### 5.3 新增一篇“hardness roadmap vs current code”
把外部 40-45 这套设计包和仓库现状直接映射，减少未来文档漂移。

---

## 6. 一句话结论
对照 `d:/gitAll/agent-cloud-harness` 当前代码后，最准确的说法不是：

- hardness phase-1 还只是方案

而是：

> `agent-cloud-harness` 已经拥有 phase-1 hardness 的若干真实代码落点，尤其是 tool policy、tool-aware execution、tool trace 持久化、checkpoint、judgment 和 runtime context；当前真正缺的不是这些能力从零开始，而是把它们进一步收束成统一的 `WorkerExecutionEnvelope -> ToolInvocationRecord -> RuntimeFactSet -> ResumeCheckpoint -> JudgmentInput / ContinuationAction` runtime contract 链。
