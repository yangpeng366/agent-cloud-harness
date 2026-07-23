# HARDNESS_PHASE1_IMPLEMENTATION_ROADMAP

> 历史材料说明：本文保留 hardness phase-1 的阶段性路线图，便于回看当时建议的对象链和切入顺序。当前不作为直接开工入口；相关稳定结论已优先回收到 `docs/ARCHITECTURE.md`，真实执行验证请看 `docs/LIVE_FLOW_RUNBOOK.md`，当前排障口径请看 `docs/TROUBLESHOOT.md`。

## 1. 目的

本文档用于把当前 `agent-cloud-harness` 的 hardness phase-1 主线，从“已经对齐代码的设计判断”继续压到“可直接开工的实现路线图”。

它不再主要回答为什么，而是回答：

1. phase-1 目标对象分别应该落在现有代码哪里
2. 哪些对象应该新增，哪些应该扩展现有类
3. 谁生产这些对象，谁消费这些对象，哪些需要持久化
4. 第一轮最小改造应该先改哪几个点，才能把 contract 收硬而不重写架构

---

## 2. 总体策略

当前最自然的推进方式，不是重写 control plane，也不是推翻现有 worker / tool / runtime / judgment / checkpoint 分层。

更合理的方式是：

> 保留现有 skeleton，在 `worker -> tools -> runtime -> judgment -> checkpoint` 之间插入更硬的 contract / assembler / trace layer。

也就是说：

- 尽量复用现有类
- 优先做“对象升级”而不是“体系重构”
- 优先做 machine-readable first contract
- 先把外层 object chain 收紧，再决定是否继续扩大行为能力

---

## 3. phase-1 目标对象与代码落点总表

| 目标对象 | 当前最近邻 | 建议动作 | 生产者 | 消费者 | 是否持久化 |
|---|---|---|---|---|---|
| `WorkerExecutionEnvelope` | `worker/WorkerExecutionResult` | 新增 envelope，包住现有 result | `WorkerExecutorRouter` / `DefaultWorkerExecutor` / `ToolAwareWorkerExecutor` | `judgment`, `runtime`, `live_flow`, `checkpoint`, `experiment` | 部分持久化，至少 trace / summary 层 |
| `ToolInvocationRecord` | `model/ToolInvocationRecord` | 增强现有对象 | `ToolAwareWorkerExecutor` | `tool_trace`, `RuntimeFactSet`, `judgment`, `checkpoint`, `harness_trace` | 是 |
| `RuntimeFactSet` | `TaskRuntimeContext` + 多个 trace/view 聚合 | 新增统一聚合对象 | `runtime` / `TaskService` | `judgment`, `checkpoint`, `harness_trace`, `continue/resume` | 可先不单独落库 |
| `ResumeCheckpoint` | `model/Checkpoint` + `ResumePacket` | 增强 checkpoint 语义，或新增 runtime-facing model | `checkpoint/consolidation`、control flow 结束点 | `resume`, `handoff`, `live_flow`, `runtime_context` | 是 |
| `JudgmentInput` | `judgment/JudgmentContext` | 扩展或包裹现有 context | `TaskRuntimeContextBuilder` / `RuntimeFactSetAssembler` | `PromptBasedJudgmentService`, `RuntimeJudgmentService` | 否 |
| `ContinuationAction` | `RuntimeJudgmentService.TaskDecision` | 提升为统一 model / enum | `judgment` / `runtime judgment` | `ControlNodeGraph`, `TaskService`, trace views | 建议以 trace 方式持久化 |

---

## 4. 分对象实现路线

## 4.1 `WorkerExecutionEnvelope`

### 当前最近邻
- `src/main/java/com/agentcloud/worker/WorkerExecutionResult.java`

### 判断
当前不建议直接推翻 `WorkerExecutionResult`。
最自然的方式是新增 envelope，把现有 result 作为 payload 核心部分保留下来。

### 建议落点
- 新增：`src/main/java/com/agentcloud/worker/model/WorkerExecutionEnvelope.java`

### 最小字段建议
- `executionId`
- `sessionId`
- `taskId`
- `workerId`
- `startedAt`
- `finishedAt`
- `durationMs`
- `executionStatus`
- `result` (`WorkerExecutionResult`)
- `toolInvocationIds`
- `metadata`

### 生产者
- `WorkerExecutorRouter`
- `DefaultWorkerExecutor`
- `ToolAwareWorkerExecutor`

### 消费者
- `PromptBasedJudgmentService`
- `RuntimeJudgmentService`
- `TaskLiveFlowView` / `HarnessTraceView`
- checkpoint / packet builder
- experiment metrics recorder

### 第一轮最小实现建议
不要要求所有地方立刻全面切换。
可以先：

1. 新增 envelope class
2. 由 `WorkerExecutorRouter` 负责包装 execution result
3. judgment trace / harness trace 先消费 envelope 的摘要字段
4. 后续再逐步替换散落 metadata 的读取逻辑

---

## 4.2 `ToolInvocationRecord`

### 当前最近邻
- `src/main/java/com/agentcloud/model/ToolInvocationRecord.java`
- `src/main/resources/schema.sql` 的 `tool_invocations`

### 判断
这里不需要新造同义对象，直接增强现有对象最合适。

### 建议增强字段
- `executionId`
- `status` (`planned|running|succeeded|failed|blocked|skipped`)
- `touchedPaths`
- `sideEffectLevel` (`none|local_read|local_write|bounded_exec`)
- `roundIndex`
- `stepReason`

### 生产者
- `ToolAwareWorkerExecutor`
- 后续如果有 agent/provider 侧 tool bridge，也应复用此对象

### 消费者
- `GET /tool_trace`
- `RuntimeFactSetAssembler`
- `PromptBasedJudgmentService`
- checkpoint / handoff packet builder
- `HarnessTraceView`

### 第一轮最小实现建议
先补最有价值的三个：

1. `executionId`
2. `status`
3. `touchedPaths`

因为这三个最直接提升：
- trace 可解释性
- execution 与 tool chain 关联性
- side-effect grounding 能力

---

## 4.3 `RuntimeFactSet`

### 当前最近邻
- `TaskRuntimeContext`
- `JudgmentTraceView`
- `TaskLiveFlowView`
- `HarnessTraceView`
- `TaskService` 内部多处聚合逻辑

### 判断
这是当前最值得新增的对象之一。
因为当前“事实”已经存在，但散在多个 view / service / trace 拼装过程里。

### 建议落点
- 新增：`src/main/java/com/agentcloud/runtime/model/RuntimeFactSet.java`
- 新增：`src/main/java/com/agentcloud/runtime/RuntimeFactSetAssembler.java`

### 最小字段建议
- `taskId`
- `sessionId`
- `controlNode`
- `assignedWorker`
- `latestExecution`
- `toolInvocations`
- `latestCheckpoint`
- `latestPacket`
- `latestDecisionSummary`
- `latestArtifactSummary`
- `openQuestions`
- `blockers`
- `recommendedAction`
- `recommendedNextStep`
- `metadata`

### 生产者
- `RuntimeFactSetAssembler`
- 调用时机可先挂在 `TaskService` 的 live flow / judgment / checkpoint 相关路径

### 消费者
- `PromptBasedJudgmentService`
- `RuntimeJudgmentService`
- `CheckpointService` / packet builder
- `HarnessTraceView`
- 后续的 continue / resume / handoff 决策

### 第一轮最小实现建议
第一轮不要强求替换所有现有 view。
先做：

1. assembler
2. judgment 可选消费
3. harness trace / live flow 增量带出 fact set 摘要

这样风险最低，而且能尽快验证对象是否好用。

---

## 4.4 `ResumeCheckpoint`

### 当前最近邻
- `src/main/java/com/agentcloud/model/Checkpoint.java`
- `ResumePacket`
- `PacketBuilder`

### 判断
当前 checkpoint 已存在，但更偏 consolidation / packet refine 存档。
phase-1 需要的是更显式的恢复入口对象。

### 建议落点
两种都可以，但建议先轻量：

- 先新增 runtime-facing model：`src/main/java/com/agentcloud/runtime/model/ResumeCheckpoint.java`
- 由现有 `Checkpoint + ResumePacket + RuntimeFactSet` 组装得出

### 最小字段建议
- `checkpointId`
- `taskId`
- `sessionId`
- `currentStatus`
- `currentNode`
- `assignedWorker`
- `latestExecutionId`
- `latestSummary`
- `nextStep`
- `blockers`
- `openQuestions`
- `resumeHints`
- `createdAt`

### 生产者
- checkpoint builder / consolidation 结束点
- pause / handoff / session end 等显式控制点

### 消费者
- `GET /packet`
- `GET /live_flow`
- `resume`
- `handoff`
- active context builder

### 第一轮最小实现建议
先不要改底层表结构。
第一轮可以先：

1. 新增 runtime-facing model
2. 用现有 checkpoint + packet 拼装
3. 在 API / trace 视图中先带出来

等对象稳定后，再决定是否单独落更硬 schema。

---

## 4.5 `JudgmentInput`

### 当前最近邻
- `src/main/java/com/agentcloud/judgment/JudgmentContext.java`

### 判断
这里更适合扩展现有对象，而不是平行新增完全重复的一套上下文。

### 建议落点
两种可选：

- 方案 A: 扩 `JudgmentContext`
- 方案 B: 新增 `JudgmentInput`，内部持有 `JudgmentContext + RuntimeFactSet + WorkerExecutionEnvelope`

当前更推荐 B，因为它更利于把 hardness 主线对象链写清楚。

### 建议字段
- `taskId`
- `sessionId`
- `runtimeContext`
- `factSet`
- `latestExecution`
- `latestToolInvocations`
- `latestCheckpoint`
- `judgmentMode` (`execution|completion|continuation`)
- `metadata`

### 生产者
- `TaskRuntimeContextBuilder`
- `RuntimeFactSetAssembler`
- judgment 入口 adapter

### 消费者
- `PromptBasedJudgmentService`
- `RuntimeJudgmentService`

### 第一轮最小实现建议
先做 judgment adapter，不要先大改 prompt service。
即：

1. 保留现有 `JudgmentContext`
2. 新增 `JudgmentInput`
3. 在 judgment service 入口处组装
4. 先只多消费其中几个关键字段

---

## 4.6 `ContinuationAction`

### 当前最近邻
- `RuntimeJudgmentService.TaskDecision`

### 判断
这是另一个值得尽快从匿名 decision 提升为显式对象的点。

### 建议落点
- 新增：`src/main/java/com/agentcloud/runtime/model/ContinuationAction.java`
- 新增：`src/main/java/com/agentcloud/runtime/model/ContinuationDecision.java`

### 最小 enum 建议
- `continue`
- `halt`
- `pause`
- `handoff`
- `escalate`
- `retry`

### 决策对象最小字段
- `action`
- `reason`
- `targetWorker`
- `derivedFrom`
- `metadata`

### 生产者
- `RuntimeJudgmentService`
- 后续 `PromptBasedJudgmentService` 也可输出推荐动作并映射进来

### 消费者
- `ControlNodeGraph`
- `TaskService`
- `JudgmentTraceView`
- `HarnessTraceView`

### 第一轮最小实现建议
先把 `TaskDecision` 提升为显式 model，并保持老逻辑不变。
这样改动最小，但能先把 runtime continuation 动作命名统一下来。

---

## 5. 第一轮最小改造包建议

如果只做第一轮最小改造，我建议顺序如下：

### Step 1. 新增 `ContinuationAction` / `ContinuationDecision`
理由：
- 改动最小
- 能先统一 runtime 续跑动作命名
- 对 trace / docs / control graph 都有立即收益

### Step 2. 增强 `ToolInvocationRecord`
优先补：
- `executionId`
- `status`
- `touchedPaths`

理由：
- 这是当前最成熟、最真实的 trace 面
- 增强后立刻提升 explainability
- 对 judgment / checkpoint / side-effect grounding 都有直接价值

### Step 3. 新增 `RuntimeFactSet` + assembler
理由：
- 当前事实已存在但分散
- 这是把多个 trace 聚回同一条 contract 链的关键刀

当前落地状态（2026-05-05）：
- 已新增 `src/main/java/com/agentcloud/runtime/model/RuntimeFactSet.java`
- 已新增 `src/main/java/com/agentcloud/runtime/RuntimeFactSetAssembler.java`
- `TaskService.getJudgmentTrace()` / `getLiveFlow()` / `getHarnessTrace()` 已优先复用 `RuntimeFactSetAssembler`
- `TaskService.recordAssistantProgressMessage()` 已改为基于 `RuntimeFactSet` 读取 judgment / next-step / latest-output，减少重复聚合
- 已在 JDK 21 + preview 环境下通过 compile 和相关聚焦测试

### Step 4. 新增 `WorkerExecutionEnvelope`
理由：
- 可以基于现有 `WorkerExecutionResult` 轻量包一层
- 一旦 execution id 和 tool trace 连上，整条 object chain 会明显更清楚

### Step 5. 新增 `ResumeCheckpoint`
理由：
- 这一步在 checkpoint / packet 语义已较稳定后再补更合适
- 避免过早把底层 schema 改复杂

---

## 6. 明确不建议现在做的事

当前阶段不建议：

### 6.1 不要重写 control graph
当前问题不是 control graph 不存在，而是 contract 不够硬。

### 6.2 不要先做“大一统万能对象”
比如把 packet、runtime context、judgment input、checkpoint、trace 全揉成一个超大对象。
这样只会重新制造耦合。

### 6.3 不要先追求全量数据库 schema 重构
phase-1 更适合先做：
- runtime-facing model
- trace enrichment
- assembler
- API view alignment

### 6.4 不要先把所有 view 一次性迁到新对象
先允许新旧并存，优先验证对象边界。

---

## 7. 一句话结论

如果把当前代码和 hardness phase-1 目标对齐后压成一句话，最合理的实现路线不是：

- 继续扩很多新功能
- 或重写整个 runtime

而是：

> 以现有 `WorkerExecutionResult`、`ToolInvocationRecord`、`TaskRuntimeContext`、`JudgmentContext`、`Checkpoint/ResumePacket` 为支点，逐步新增 `ContinuationAction`、`RuntimeFactSet`、`WorkerExecutionEnvelope`、`ResumeCheckpoint` 这些更硬的中间 contract，把当前已经存在但分散的 runtime 能力收束成统一 object chain。
