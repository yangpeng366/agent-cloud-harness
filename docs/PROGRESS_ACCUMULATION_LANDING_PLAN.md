# 阶段性进展累积式构建能力落地方案

<!-- 更新时间：2026-06-03 -->
<!-- 适用项目：agent-cloud-harness -->
<!-- 目标：把“阶段性进展进行累积式构建”从理念落到 runtime contract、数据结构、控制流与验收 gate。 -->

## 1. 一句话结论

`agent-cloud-harness` 当前已经具备累积式构建的关键雏形：

```text
Task / Session
  -> Event / Decision / Artifact / ToolInvocation
  -> ActiveContext / MountedContextView
  -> Judgment
  -> ResumePacket / HandoffPacket / Checkpoint
  -> Continue / Pause / Resume / Handoff
```

但它们现在更像一组彼此相邻的 continuity 资产，还没有被正式收束成一条稳定的“阶段性进展 -> 固化 -> 再进入 -> 继续构建”的 runtime contract 链。

下一步不应优先扩更多 worker 或更多功能，而应先把这条链钉死：

```text
RoundOutput
  -> RuntimeFactSet
  -> ProgressDelta
  -> AccumulationCheckpoint
  -> MountedWorkingSet
  -> ContinuationDecision
  -> NextRoundInput
```

核心目标：让 AI 不再每轮从零开始跳，而是每轮都站在已经固化的阶段性进展上继续推进。

---

## 2. 背景：为什么这件事重要

陶哲轩对当前 AI 的一个关键判断是：AI 缺乏“基于阶段性进展进行累积式构建”的能力。

映射到 agent runtime，就是下面这个问题：

```text
坏模式：
第 N 轮执行 -> 生成一段文本 -> 下一轮重新读一堆上下文 -> 再猜一次状态

好模式：
第 N 轮执行 -> 抽取事实/决策/产物/风险/未决项 -> 固化为 checkpoint
          -> 下一轮 mounted working set 只加载该继续的工作面
          -> judgment 基于稳定事实决定 continue / pause / handoff / done
```

`agent-cloud-harness` 的主叙事本来就是 continuity-first orchestration harness，因此“累积式构建”不是额外功能，而是项目核心价值的内核。

---

## 3. 当前项目已有基础

基于当前仓库文档与代码，已有基础如下。

### 3.1 已有数据面

当前 `schema.sql` 已具备以下表：

- `sessions`
- `tasks`
- `session_messages`
- `events`
- `decisions`
- `artifacts`
- `resume_packets`
- `checkpoints`
- `learning_memories`
- `tool_invocations`
- `experiment_runs`

这些表已经覆盖了累积式构建需要的主要材料：

```text
事实流：events / tool_invocations
判断流：decisions / judgment trace metadata
产物流：artifacts
恢复边界：resume_packets / checkpoints
经验沉淀：learning_memories
评估闭环：experiment_runs
```

### 3.2 已有 runtime 面

关键类已经存在：

- `TaskRuntimeContext`
  - 聚合 task、latest packet、latest checkpoint、events、decisions、artifacts、tool traces、messages、active context、mounted context。
- `ActiveContext`
  - 已表达当前一轮执行与判断共享的活动工作面。
- `MountedContextView`
  - 已作为 mounted working-memory surface 进入 worker execution 与 judgment。
- `ResumePacket`
  - 已包含 `taskIdentity`、`currentObjective`、`currentStatus`、`currentNode`、`assignedWorker`、`latestSummary`、`blockers`、`recentArtifacts`、`recentDecisions`、`machineReadableFirst`。
- `HandoffPacket`
  - 已表达移交时的 from/to worker、why handoff、what done、what remaining、cautions、resume hint。
- `Checkpoint`
  - 已表达 checkpoint type、consolidation summary、refined packet、world model delta。
- `WorkerExecutionResult`
  - 已含 summary、artifact、suggested next step、evidence refs、unfinished items、proposed actions、context requests、risk flags、execution outcome。
- `ContinuationAction / ContinuationDecision`
  - 已有 continue/halt/pause/handoff/escalate/retry 的最小动作枚举。

### 3.3 已有控制流

文档显示当前主流程已形成：

```text
create task
  -> intake
  -> scheduler
  -> worker execution
  -> tool-aware execution trace
  -> execution/completion judgment
  -> runtime judgment
  -> checkpoint / packet / human_gate / handoff / done
```

这说明项目已经越过“只有概念”的阶段。

真正缺口是：这些对象还没有形成一个更硬的、跨轮稳定的 progress accumulation protocol。

---

## 4. 当前缺口诊断

### 4.1 缺口一：阶段性进展没有统一对象

现在一次 worker round 会产生：

- `WorkerExecutionResult`
- `ToolInvocationRecord`
- event
- artifact
- decision / judgment metadata
- checkpoint / packet

但缺少一个明确对象说明：

> 这一轮到底为任务世界增加了什么？哪些事实被确认？哪些假设被推翻？哪些中间产物可以作为下一轮地基？

建议新增统一对象：`ProgressDelta`。

### 4.2 缺口二：checkpoint 更像 summary，不够像构建基座

当前 `Checkpoint` 有 `consolidationSummary`、`refinedPacket`、`worldModelDelta`。

方向是对的，但下一步要明确 checkpoint 的职责：

- 不只是“摘要一下发生了什么”
- 而是“冻结当前可复用的中间状态”

也就是 checkpoint 应该能回答：

```text
下一轮从哪里继续？
哪些东西已经不用重新验证？
哪些路径已经被排除？
哪些证据支持当前判断？
哪些风险还没有解除？
```

建议把现有 checkpoint 升级为 `AccumulationCheckpoint` 语义。

### 4.3 缺口三：mounted context 缺少明确晋升/降级策略

`MountedContextView` 已经存在，但需要更明确的 policy：

- 哪些 facts 必须进入 mounted working set？
- 哪些只留在 archive/history？
- 哪些 evidence refs 只保留引用，不保留全文？
- 哪些 blocker/risk 每轮都要保留？
- 哪些失败路径要作为 negative memory 保留？

否则 mounted context 容易退化为“最近日志拼接”。

### 4.4 缺口四：judgment 输入事实面还不够硬

现在 judgment 已经存在，但应避免只读自由文本 summary。

下一步应让 judgment 显式消费：

- confirmed facts
- open blockers
- produced artifacts
- failed attempts
- tool side effects
- evidence refs
- acceptance criteria
- next candidate actions

建议新增 `RuntimeFactSet` 或同等聚合对象。

### 4.5 缺口五：学习记忆还没有稳定进入策略闭环

`learning_memories` 已有表，但应先避免“自动改策略”。

更稳的路线是：

```text
candidate -> reinforced -> stable_hint
```

并先用于：

- routing hints
- context retention hints
- completion/failure patterns
- worker/tool usage heuristics

---

## 5. 目标架构：Progress Accumulation Loop

建议把累积式构建明确成项目的一条一级 runtime loop。

### 5.1 总体链路

```text
┌─────────────────────────────────────────────────────────────┐
│                         Task Identity                        │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ MountedWorkingSet                                            │
│ - current objective                                          │
│ - confirmed facts                                            │
│ - active constraints                                         │
│ - open blockers                                              │
│ - accepted artifacts                                         │
│ - next candidates                                            │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ Worker Round                                                 │
│ - execution result                                           │
│ - tool invocations                                           │
│ - produced artifacts                                         │
│ - proposed actions                                           │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ RuntimeFactSet                                               │
│ - facts                                                      │
│ - evidence refs                                              │
│ - side effects                                               │
│ - failures / dead ends                                       │
│ - unresolved questions                                       │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ ProgressDelta                                                │
│ - what changed                                               │
│ - what became stable                                         │
│ - what should carry forward                                  │
│ - what should be archived                                    │
│ - what needs human / handoff / retry                         │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ AccumulationCheckpoint                                       │
│ - resumable state                                            │
│ - accepted intermediate result                               │
│ - next entrypoint                                            │
│ - evidence boundary                                          │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               v
┌─────────────────────────────────────────────────────────────┐
│ ContinuationDecision                                         │
│ continue | retry | pause | handoff | escalate | done          │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 设计原则

1. **任务身份稳定**
   - 每一轮都必须绑定 `task_id / session_id / current_objective`。

2. **世界状态丰富，执行工作面紧凑**
   - 全量历史可以丰富。
   - 下一轮执行面必须压缩、稳定、可解释。

3. **checkpoint 是构建基座，不是聊天摘要**
   - checkpoint 必须包含可恢复入口、证据边界、已确认事实、未决事项。

4. **judgment 读事实，不只读文本**
   - judgment 输入必须结构化。

5. **失败也要累积**
   - 无效路径、失败工具调用、被否定假设应进入 negative progress，避免重复撞墙。

---

## 6. 建议新增/正式化的数据对象

### 6.1 RuntimeFactSet

用途：把一轮执行后的事实面统一给 judgment / checkpoint / mounted context 使用。

建议位置：

```text
src/main/java/com/agentcloud/runtime/RuntimeFactSet.java
```

建议字段：

```java
public record RuntimeFactSet(
    String sessionId,
    String taskId,
    String executionId,
    List<String> confirmedFacts,
    List<String> rejectedAssumptions,
    List<String> producedArtifactRefs,
    List<String> toolSideEffects,
    List<String> evidenceRefs,
    List<String> openQuestions,
    List<String> blockers,
    List<String> riskFlags,
    List<String> nextCandidates,
    Map<String, Object> metadata
) {}
```

生成来源：

```text
WorkerExecutionResult
+ ToolInvocationRecord[]
+ TaskRuntimeContext
+ latest judgment metadata
```

### 6.2 ProgressDelta

用途：表达“这一轮相对上一轮推进了什么”。

建议位置：

```text
src/main/java/com/agentcloud/runtime/ProgressDelta.java
```

建议字段：

```java
public record ProgressDelta(
    String sessionId,
    String taskId,
    String fromCheckpointId,
    String executionId,
    List<String> newlyConfirmedFacts,
    List<String> newlyProducedArtifacts,
    List<String> resolvedQuestions,
    List<String> newOpenQuestions,
    List<String> newBlockers,
    List<String> failedAttempts,
    List<String> carryForwardItems,
    List<String> archiveOnlyItems,
    String suggestedNextStep,
    boolean checkpointRecommended,
    String checkpointReason,
    Map<String, Object> metadata
) {}
```

### 6.3 AccumulationCheckpoint

不一定第一步新增表，可以先映射到现有 `checkpoints`：

```text
checkpoints.refined_packet_json      -> resumable state
checkpoints.world_model_delta_json   -> progress delta / fact delta
checkpoints.metadata_json            -> evidence boundary / policy trace
```

建议在代码层新增语义对象：

```text
src/main/java/com/agentcloud/runtime/AccumulationCheckpoint.java
```

建议字段：

```java
public record AccumulationCheckpoint(
    String checkpointId,
    String sessionId,
    String taskId,
    String checkpointType,
    String baseCheckpointId,
    String objectiveSnapshot,
    List<String> stableFacts,
    List<String> acceptedArtifacts,
    List<String> activeBlockers,
    List<String> negativeFindings,
    List<String> carryForwardContext,
    String nextEntrypoint,
    List<String> evidenceRefs,
    Map<String, Object> mountedContextSeed,
    Map<String, Object> metadata
) {}
```

### 6.4 MountedContextPolicy

用途：决定哪些内容进入下一轮工作面。

建议位置：

```text
src/main/java/com/agentcloud/runtime/context/MountedContextPolicy.java
```

最小策略：

```text
必须保留：
- current objective
- next step
- active blockers
- open questions
- acceptance criteria
- latest stable facts
- latest accepted artifact refs
- last failed attempt if relevant

引用保留：
- 大 artifact content
- 长 tool output
- 历史 events

默认降级：
- 重复日志
- 低价值中间文本
- 已解决且无复用价值的问题
```

---

## 7. 控制流改造方案

### 7.1 当前流程增强点

现有流程：

```text
worker execution
  -> judgment
  -> checkpoint / continue
```

建议增强为：

```text
worker execution
  -> collect RuntimeFactSet
  -> compute ProgressDelta
  -> judge with facts
  -> maybe write AccumulationCheckpoint
  -> rebuild MountedContextView
  -> continue / pause / handoff / done
```

### 7.2 新增服务建议

#### RuntimeFactSetBuilder

```text
src/main/java/com/agentcloud/runtime/RuntimeFactSetBuilder.java
```

职责：

- 从 `WorkerExecutionResult` 提取事实、产物、风险、未完成项。
- 从 `ToolInvocationRecord` 提取工具副作用、触达路径、失败原因。
- 从 `TaskRuntimeContext` 带入当前目标、约束、验收标准。

#### ProgressDeltaService

```text
src/main/java/com/agentcloud/runtime/ProgressDeltaService.java
```

职责：

- 比较 latest checkpoint 与 RuntimeFactSet。
- 输出新增事实、已解决问题、新 blocker、失败路径。
- 判断是否需要 checkpoint。

#### AccumulationCheckpointService

可以先扩展现有 `ConsolidationService`，也可以新增 façade。

职责：

- 把 ProgressDelta 写入现有 `checkpoints.world_model_delta_json`。
- 把 mounted seed 写入 `checkpoints.refined_packet_json`。
- 同步刷新 `resume_packets.payload_json`。

#### MountedContextRefreshService

职责：

- 根据 latest checkpoint + policy 重建下一轮 mounted context。
- 产出 selection trace，方便解释为什么保留/丢弃某些上下文。

---

## 8. API 与观测面建议

### 8.1 新增或增强 API

不必一开始新增太多端点，建议先增强现有 live flow。

#### 方案 A：增强 `/api/v1/tasks/{id}/live_flow`

新增字段：

```json
{
  "runtime_fact_set": {},
  "progress_delta": {},
  "latest_accumulation_checkpoint": {},
  "mounted_context_selection_trace": []
}
```

优点：前端改动集中，符合当前 live flow 聚合面方向。

#### 方案 B：新增只读调试端点

```text
GET /api/v1/tasks/{id}/runtime_facts
GET /api/v1/tasks/{id}/progress_delta
GET /api/v1/tasks/{id}/accumulation_checkpoint
```

优点：调试清晰。
缺点：API 面变宽。

推荐：先做方案 A，等对象稳定后再拆端点。

### 8.2 Console / Dialogue 展示建议

在任务详情里增加一个 `Accumulation` 面板：

```text
Accumulation
├── Stable Facts
├── Accepted Artifacts
├── Open Questions
├── Active Blockers
├── Failed Attempts / Negative Findings
├── Next Entrypoint
└── Why these items are mounted
```

这个面板对项目定位很关键：它能直接展示“系统不是每轮重来，而是在累积”。

---

## 9. 分阶段落地计划

## Phase A：不改 schema，先收 contract

目标：最小侵入，把对象链跑通。

### A1. 新增 RuntimeFactSet / ProgressDelta record

新增：

```text
src/main/java/com/agentcloud/runtime/RuntimeFactSet.java
src/main/java/com/agentcloud/runtime/ProgressDelta.java
src/main/java/com/agentcloud/runtime/RuntimeFactSetBuilder.java
src/main/java/com/agentcloud/runtime/ProgressDeltaService.java
```

不新增表，先通过 metadata / checkpoint json 持久化。

### A2. 在 worker round 后构建 RuntimeFactSet

接入点建议：

```text
ControlNodeGraph.continueNode 或 worker execution 返回后的统一处理位置
```

要求：每轮至少能得到：

- execution id
- summary
- evidence refs
- produced artifacts
- unfinished items
- risk flags
- tool invocation ids

### A3. checkpoint 写入 ProgressDelta

映射：

```text
ProgressDelta -> checkpoints.world_model_delta_json
Mounted seed   -> checkpoints.refined_packet_json
```

### A4. live flow 暴露 accumulation section

让前端/接口能看到这条链。

### Phase A 验收

至少新增测试：

```text
RuntimeFactSetBuilderTest
ProgressDeltaServiceTest
ConsolidationServiceAccumulationTest
TaskLiveFlowAccumulationProjectionTest
```

验收标准：

1. worker result + tool trace 可以生成 RuntimeFactSet。
2. RuntimeFactSet 可以生成 ProgressDelta。
3. ProgressDelta 能进入 checkpoint。
4. live flow 能展示 latest accumulation 信息。
5. 不破坏现有 task lifecycle 测试。

---

## Phase B：让 mounted context 真正消费 checkpoint

目标：下一轮不再主要依赖最近日志，而是依赖累积后的 mounted seed。

### B1. 新增 MountedContextPolicy

策略先写死也可以，重点是把选择逻辑显式化。

### B2. ContextViewBuilder 消费 AccumulationCheckpoint

优先级建议：

```text
current task objective
> latest accumulation checkpoint mounted seed
> latest resume packet
> recent critical events/decisions/artifacts
> fallback recent messages
```

### B3. 增加 selection trace

每条进入 mounted context 的 item 标记来源：

```json
{
  "item": "xxx",
  "source": "checkpoint.world_model_delta",
  "reason": "active_blocker",
  "retention": "must_keep"
}
```

### Phase B 验收

新增测试：

```text
MountedContextPolicyTest
ContextViewBuilderAccumulationSeedTest
PromptBasedJudgmentMountedFactsTest
```

验收标准：

1. checkpoint 里的 stable facts 会进入下一轮 mounted context。
2. archive-only items 不进入 prompt，但保留引用。
3. blocker/open question 每轮保留，直到被 resolved。
4. selection trace 可解释。

---

## Phase C：judgment 改为事实驱动

目标：completion / continuation judgment 明确消费 RuntimeFactSet，而不是只消费文本。

### C1. JudgmentContext 增加 fact set

建议字段：

```java
RuntimeFactSet runtimeFactSet;
ProgressDelta progressDelta;
AccumulationCheckpoint latestAccumulationCheckpoint;
```

### C2. PromptBasedJudgmentService prompt 改造

判断维度：

```text
- 是否满足 acceptance criteria
- 是否存在 unresolved blocker
- 是否有 stable progress worth checkpointing
- 是否重复失败，需要 retry/handoff/escalate
- 是否已经 done
```

### C3. ContinuationAction 扩展 done/checkpoint 语义

当前 enum 有：

```text
CONTINUE / HALT / PAUSE / HANDOFF / ESCALATE / RETRY
```

建议评估是否补：

```text
DONE / CHECKPOINT
```

如果暂不扩 enum，也要在 `ContinuationDecision.metadata` 或 judgment decision 里明确表达。

### Phase C 验收

新增测试：

```text
PromptBasedJudgmentFactDrivenTest
ControlNodeGraphAccumulationDecisionTest
RepeatedFailureEscalationTest
DoneWhenAcceptanceFactsSatisfiedTest
```

验收标准：

1. 有 blocker 时不会误判 done。
2. acceptance facts 满足时能进入 done。
3. 重复失败时能 retry/handoff/escalate，而不是无限 continue。
4. 有阶段性稳定进展时会 checkpoint。

---

## Phase D：把 learning memory 接入累积策略

目标：让系统开始学习“什么应该保留、什么容易失败、什么 worker/tool 适合什么任务”。

### D1. 从 ProgressDelta 生成 learning candidate

候选来源：

- repeated failed attempts
- successful routing
- context item repeatedly useful
- tool usage success/failure pattern
- completion misjudgment correction

### D2. learning memory 不直接自动改策略

第一版只做提示：

```text
candidate -> reinforced -> stable_hint
```

只有 `stable_hint` 才能影响 MountedContextPolicy / WorkerRouter。

### D3. live flow 展示 learning hint 来源

避免黑箱策略漂移。

### Phase D 验收

新增测试：

```text
LearningMemoryFromProgressDeltaTest
MountedContextPolicyStableHintTest
WorkerRouterLearningHintTraceTest
```

验收标准：

1. 失败模式可以生成 candidate memory。
2. 多次强化后变为 stable_hint。
3. stable_hint 影响策略时有 trace。
4. 用户/测试可以解释策略为什么变化。

---

## 10. 最小代码改动清单

### 10.1 第一批建议新增文件

```text
src/main/java/com/agentcloud/runtime/RuntimeFactSet.java
src/main/java/com/agentcloud/runtime/ProgressDelta.java
src/main/java/com/agentcloud/runtime/RuntimeFactSetBuilder.java
src/main/java/com/agentcloud/runtime/ProgressDeltaService.java
src/main/java/com/agentcloud/runtime/AccumulationCheckpoint.java
src/main/java/com/agentcloud/runtime/context/MountedContextPolicy.java
```

### 10.2 第一批建议修改文件

```text
src/main/java/com/agentcloud/engine/ControlNodeGraph.java
src/main/java/com/agentcloud/engine/ConsolidationService.java
src/main/java/com/agentcloud/runtime/TaskRuntimeContext.java
src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java
src/main/java/com/agentcloud/runtime/context/ContextViewBuilder.java
src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java
src/main/java/com/agentcloud/server/TaskHandler.java
src/main/java/com/agentcloud/engine/TaskService.java
```

### 10.3 第一批测试文件

```text
src/test/java/com/agentcloud/runtime/RuntimeFactSetBuilderTest.java
src/test/java/com/agentcloud/runtime/ProgressDeltaServiceTest.java
src/test/java/com/agentcloud/runtime/context/MountedContextPolicyTest.java
src/test/java/com/agentcloud/engine/ConsolidationServiceAccumulationTest.java
src/test/java/com/agentcloud/engine/TaskLiveFlowAccumulationProjectionTest.java
src/test/java/com/agentcloud/judgment/PromptBasedJudgmentFactDrivenTest.java
```

---

## 11. 推荐的第一个 PR 范围

第一个 PR 不要贪大。

建议标题：

```text
Add accumulation runtime facts and progress delta contract
```

范围：

1. 新增 `RuntimeFactSet`。
2. 新增 `ProgressDelta`。
3. 新增 builder/service。
4. 从 `WorkerExecutionResult + recentToolInvocations` 生成 fact set。
5. 生成 progress delta。
6. 把 progress delta 写进 checkpoint metadata/world model delta。
7. live flow 增加只读展示。
8. 补 3-4 个单元测试。

明确不做：

- 不新增数据库表。
- 不重写 ControlNodeGraph。
- 不改 provider 架构。
- 不自动启用 learning memory 策略影响。
- 不做复杂 UI，只先暴露 JSON。

---

## 12. 验收 Gate

### 12.1 单任务累积 gate

构造一个三轮任务：

```text
Round 1: 搜索/读取，得到事实 A、未决问题 Q1
Round 2: 解决 Q1，产出 artifact B，但出现 blocker C
Round 3: 根据 blocker C handoff/escalate 或继续解决
```

验证：

- Round 2 mounted context 包含事实 A 和 Q1。
- Round 3 mounted context 包含 artifact B 和 blocker C。
- checkpoint 记录每轮新增 delta。
- judgment 不重复要求验证已确认事实 A。

### 12.2 失败累积 gate

构造一个工具失败场景：

```text
同一 tool / same args 连续失败 2 次
```

验证：

- failed attempt 进入 ProgressDelta。
- 下一轮 mounted context 包含 negative finding。
- judgment 选择 retry with changed args / handoff / escalate，而不是重复同样调用。

### 12.3 resume gate

暂停后恢复：

验证：

- resume 不靠全量历史重放。
- latest checkpoint + resume packet 足够重建 mounted context。
- next entrypoint 与 pause 前一致。

### 12.4 handoff gate

worker A 移交 worker B：

验证：

- handoff packet 包含 what done / what remaining / cautions / resume hint。
- worker B 的 mounted context 含稳定事实和未完成项。
- 不需要 worker B 从原始日志重新推理任务状态。

---

## 13. 风险与防偏

### 风险一：把 checkpoint 做成更长摘要

防偏：checkpoint 必须结构化，必须有 stable facts / blockers / next entrypoint / evidence refs。

### 风险二：mounted context 重新退化为 recent logs

防偏：所有进入 mounted context 的 item 都要有 source + reason + retention type。

### 风险三：learning memory 太早自动改策略

防偏：第一阶段只记录 candidate，不自动影响执行；stable_hint 才能进入策略，并必须留 trace。

### 风险四：对象太多但控制流没接上

防偏：第一 PR 必须把 RuntimeFactSet -> ProgressDelta -> Checkpoint -> LiveFlow 跑通。

### 风险五：过度追求通用 schema

防偏：先服务当前 continuity-first harness，不抽象成万能 agent memory 标准。

---

## 14. 与当前项目路线的关系

这份方案与现有文档关系如下：

- 对 `ARCHITECTURE.md`：把 continuity-first runtime substrate 进一步具体化为 progress accumulation loop。
- 对 `PHASE2_ROADMAP.md`：把 working memory、结构化输出、learning memory 三条线用 RuntimeFactSet / ProgressDelta 串起来。
- 对 `NEXT_5_ENGINEERING_PRIORITIES.md`：直接服务 P3 hardness phase-1 runtime contract 链，也支撑 P1/P2 的 orchestration 价值证明。
- 对 `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`：补的是“已有能力尚未收束成统一 runtime contract 链”的核心缺口。

---

## 15. 最终目标状态

完成后，`agent-cloud-harness` 应能稳定回答：

1. 当前任务已经确认了什么？
2. 哪些中间结果已经成为下一轮地基？
3. 哪些问题还没解决？
4. 哪些路径已经失败，不该重复？
5. 下一轮为什么加载这些上下文，而不是那些上下文？
6. 为什么继续、暂停、移交、升级或结束？
7. 中断后能否从 checkpoint 恢复，而不是重放全部历史？

当这些问题都能被 runtime 对象和测试回答时，项目就真正具备了“基于阶段性进展进行累积式构建”的能力。
