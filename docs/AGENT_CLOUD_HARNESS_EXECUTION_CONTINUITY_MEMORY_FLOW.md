# AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW

## 1. 目的

本文档用于压缩说明 `agent-cloud-harness` 当前“执行后输出 -> 事实提炼 -> 下一次上下文”的连续性工作机制。

它回答四个问题：

1. 一轮执行后到底产出什么
2. 这些产出如何被提炼成结构化 runtime facts
3. 下一次执行如何利用这些结构化产物恢复上下文
4. 当前机制的边界、优点与缺口是什么

---

## 2. 一句话定义

`agent-cloud-harness` 当前的“记忆”机制，本质上不是长期知识库型 memory，而是一个 **task-centered continuity loop**：

```text
execution trace
  -> fact extraction
  -> continuity packet / checkpoint
  -> next-round context reconstruction
```

它依赖的不是“把所有 chat history 原样塞回 prompt”，而是：

- 把单轮执行落成结构化痕迹
- 把这些痕迹提炼成统一 runtime facts
- 再压缩成 resume / handoff / live-flow / judgment surfaces
- 在下一轮执行时用这些结构化 surface 重建上下文

---

## 3. 总体流程图

```text
[TaskService.createTask / continueTask]
        |
        v
[ControlNodeGraph.enter]
        |
        +--> intake
        +--> scheduler
                |
                v
        [TaskRuntimeContextBuilder.build]
                |
                v
        [WorkerExecutorRouter.executeOneRound]
                |
                +--> ToolAwareWorkerExecutor / default executor / provider executor
                |
                v
        [WorkerExecutionResult + execution envelope metadata]
                |
                +--> artifacts
                +--> events
                +--> decisions
                +--> tool_invocations
                +--> checkpoints / packets
                |
                v
        [RuntimeFactSetAssembler.assemble]
                |
                +--> executionBoundary
                +--> routePreview
                +--> latestOutput
                +--> recommendedAction / nextStep
                +--> runtimeContext
                |
                v
        +------------------------------+
        | continuity surfaces          |
        | - ResumePacket               |
        | - HandoffPacket              |
        | - JudgmentTraceView          |
        | - TaskLiveFlowView           |
        +------------------------------+
                |
                v
        [下一次 TaskRuntimeContextBuilder.build]
                |
                v
        使用 packet / checkpoint / recent artifacts / decisions / tool traces
        重建下一轮上下文
```

---

## 4. 执行后输出：四层产物

## 4.1 原始执行层输出

核心对象：

- `src/main/java/com/agentcloud/worker/WorkerExecutionResult.java`
- `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java`

`WorkerExecutionResult` 提供单轮执行的基础产出：

- `summary`
- `outputText`
- `artifactTitle`
- `artifactContent`
- `suggestedNextStep`
- `executionStatus`
- `durationMs`
- `metadata`

而 `WorkerExecutorRouter.executeOneRound(...)` 会额外补上 execution envelope metadata：

- `execution_id`
- `execution_started_at`
- `execution_finished_at`
- `execution_duration_ms`
- `execution_status`
- `tool_invocation_ids`

这一步的意义是：

> 把“一轮 worker 执行”从普通文本输出提升成可追踪的 execution boundary。

---

## 4.2 持久化痕迹层输出

核心表定义位于：

- `src/main/resources/schema.sql`

当前一轮执行后的主要落点包括：

- `tasks`
- `events`
- `artifacts`
- `decisions`
- `resume_packets`
- `checkpoints`
- `tool_invocations`
- `session_messages`

因此系统保存的并不只是“最终答案”，而是一串可恢复、可审计、可提炼的执行痕迹：

- 发生了什么：`events`
- 产出了什么：`artifacts`
- 做了什么判断：`decisions`
- 调用了什么工具：`tool_invocations`
- 当前恢复包是什么：`resume_packets`
- 某个压缩时刻的检查点是什么：`checkpoints`

---

## 4.3 事实提炼层输出

核心对象：

- `src/main/java/com/agentcloud/runtime/RuntimeFactSetAssembler.java`
- `src/main/java/com/agentcloud/runtime/model/RuntimeFactSet.java`

`RuntimeFactSetAssembler.assemble(...)` 会把分散状态提炼成统一 `RuntimeFactSet`，主要包含：

- `latestOutput`
- `recommendedAction`
- `recommendedNextStep`
- `executionJudgment`
- `completionJudgment`
- `toolInvocations`
- `executionBoundary`
- `routePreview`
- `metadata`
- `runtimeContext`
- `latestPacket`
- `latestCheckpoint`

其中最关键的是 `executionBoundary`，这是单轮执行边界的结构化压缩：

- `executionId`
- `executionStatus`
- `startedAt`
- `finishedAt`
- `durationMs`
- `workerId`
- `toolInvocationIds`
- `toolInvocationCount`
- `traceSummary`

这层不是简单“写摘要”，而是：

> 把执行事实转成统一、可复用、可观测的 runtime fact surface。

---

## 4.4 面向连续性的对外 surface

在 `RuntimeFactSet` 之上，系统继续构造几种 continuity surface：

### A. ResumePacket

位置：

- `src/main/java/com/agentcloud/engine/memory/PacketBuilder.java`

`buildResumePacket(...)` 会压缩出：

- `task_identity`
- `current_objective`
- `current_status`
- `current_node`
- `assigned_worker`
- `latest_summary`
- `next_step`
- `blockers`
- `open_questions`
- `recent_artifacts`
- `recent_decisions`
- `active_goal`
- runtime fact surface

这是下一次“恢复继续执行”的主包。

### B. HandoffPacket

同样在 `PacketBuilder` 中，由 `buildHandoffPacket(...)` 负责。

它用于：

- worker 交接
- 子任务/切换执行单元
- continuity handoff

### C. JudgmentTraceView

位置：

- `TaskService.getJudgmentTrace(...)`
- `src/main/java/com/agentcloud/model/JudgmentTraceView.java`

它暴露：

- latest output
- execution judgment
- completion judgment
- execution boundary
- runtime facts
- runtime context

### D. TaskLiveFlowView

位置：

- `TaskService.getLiveFlow(...)`
- `src/main/java/com/agentcloud/model/TaskLiveFlowView.java`

这是最完整的一站式 live inspection surface，当前已聚合：

- `task`
- `latestPacket`
- `routePreview`
- `runtimeContext`
- `judgmentTrace`
- `runtimeFacts`
- `runtimeCognitionSurface`
- `runtimeCognitionTimeline`
- `checkpoints`
- `learningMemories`
- `toolInvocations`
- `executionBoundary`
- `relatedMessages`
- `experimentRun`
- `providerSelection`
- `agentRun`
- `agentRunEvents`
- `agentArtifacts`

---

## 5. 下一次上下文是如何恢复的

## 5.1 上下文重建入口：TaskRuntimeContextBuilder

核心文件：

- `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java`
- `src/main/java/com/agentcloud/runtime/TaskRuntimeContext.java`

每次执行前，系统不会简单重放上一次对话，而是通过 `TaskRuntimeContextBuilder.build(task)` 从存储层重新拼装上下文。

当前 builder 会加载：

- recent `events`
- recent `decisions`
- recent `artifacts`
- recent `tool_invocations`
- recent task/session `messages`
- latest `resume_packet`
- latest `checkpoint`
- learned hints
- `activeContext`
- `mountedContextView`

也就是说，下一轮上下文的生成机制是：

```text
persistent state
  -> TaskRuntimeContextBuilder
  -> TaskRuntimeContext
  -> executor / judgment prompt inputs
```

这就是当前 harness 的“工作记忆”核心。

---

## 5.2 为什么 packet / checkpoint 很关键

只依赖 recent artifacts / decisions 仍然会有噪声，因此系统又引入：

- `resume_packets`
- `checkpoints`

它们的作用不是保存原始历史，而是保存“压缩过的连续性状态”：

- 当前 objective 是什么
- 当前 status/node 是什么
- 最近总结是什么
- next step 是什么
- blocker / open question 是什么
- 当前 runtime facts 是什么

因此下一次恢复时，系统不必从长链原始历史重新理解任务，而可以优先读取：

> **已经压缩好的 continuity artifact**

这也是当前 repo 的核心方向：

> 不信原始 history 回放，优先信结构化连续性 surface。

---

## 6. 主代码流程

## 6.1 创建任务 / 启动任务

```text
TaskService.createTask
  -> taskDao.insert(tasks)
  -> eventDao.insert(task_created)
  -> controlGraph.enter(task)
```

相关文件：

- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

说明：

- `TaskService.createTask(...)` 负责落 task 与初始 event
- 默认 `autoStart=true` 时直接进入 `ControlNodeGraph`

---

## 6.2 执行一轮

```text
ControlNodeGraph.schedulerNode
  -> runtimeContextBuilder.build(task)
  -> workerExecutor.executeOneRound(ctx, workerId)
  -> persist artifact / event / decision / tool traces
  -> move control node
```

这是主要执行主链。

其中 `schedulerNode(...)` 完成：

- worker route / preflight
- runtime context build
- executor invoke
- round result metadata merge
- agent run / event / artifact / state 记录

---

## 6.3 执行封装：execution envelope

```text
WorkerExecutorRouter.executeOneRound
  -> selectExecutor
  -> executor.executeOneRound
  -> create WorkerExecutionEnvelope
  -> WorkerExecutionResult.withEnvelope(...)
```

相关文件：

- `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java`
- `src/main/java/com/agentcloud/worker/model/WorkerExecutionEnvelope.java`
- `src/main/java/com/agentcloud/worker/WorkerExecutionResult.java`

这一步让 execution boundary 成为统一、稳定、可追踪的事实面。

---

## 6.4 工具感知执行

```text
ToolAwareWorkerExecutor.executeOneRound
  -> render mounted prompt if needed
  -> decide whether tools are needed
  -> invoke tools
  -> collect tool trace metadata
  -> return WorkerExecutionResult
```

相关文件：

- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

它的价值是：

- 工具调用不是黑盒
- `tool_invocation_ids` 能回链到持久化记录
- grounded write / multi-step tool trace 会进入 metadata
- prompt mode / mounted context usage 也会被记录

---

## 6.5 事实提炼

```text
RuntimeFactSetAssembler.assemble
  -> build runtime context
  -> read latest packet / judgments / tool invocations
  -> merge latest worker metadata
  -> buildExecutionBoundary
  -> build routePreview
  -> emit RuntimeFactSet
```

相关文件：

- `src/main/java/com/agentcloud/runtime/RuntimeFactSetAssembler.java`

这是“输出 -> 提炼”的主枢纽。

---

## 6.6 构造下一次上下文包

```text
TaskService.refreshResumePacket
  -> packetBuilder.buildResumePacket(task, session)
  -> packetDao.insert(packet)
```

相关文件：

- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/memory/PacketBuilder.java`

这一步把 task continuity 压缩成下一次恢复所需的结构化 resume artifact。

---

## 6.7 面向操作者的 live-flow / judgment views

```text
TaskService.getJudgmentTrace
  -> RuntimeFactSetAssembler.assemble
  -> buildJudgmentTraceView

TaskService.getLiveFlow
  -> RuntimeFactSetAssembler.assemble
  -> aggregate packets / checkpoints / memories / tool traces
  -> return TaskLiveFlowView
```

这层主要用于：

- runtime inspection
- debugging
- experiment analysis
- operator visibility
- continuity governance

---

## 7. 当前机制的三种“记忆”

## 7.1 短程执行记忆：TaskRuntimeContext

这是当前回合真正被执行器 / judgment 使用的工作记忆。

特点：

- 动态重建
- 面向 task
- 聚合 recent evidence 与 compressed continuity artifacts

## 7.2 连续性记忆：ResumePacket / Checkpoint

这是当前 repo 最重要的 memory 形式。

特点：

- 不是 raw history
- 是 continuity-oriented compression
- 服务于恢复、handoff、续跑

## 7.3 可观测记忆：RuntimeFactSet / LiveFlow / JudgmentTrace

这层更多是给系统和操作者用，而不是直接当成 prompt 原料。

特点：

- 可观测
- 可审计
- 可调试
- 可用于后续 governance / benchmark / experiment

---

## 8. 当前机制的主要优点

### 8.1 不依赖纯聊天回放

不是“把全部 history 塞回去”，而是用结构化 continuity surface 恢复任务状态。

### 8.2 单轮执行边界更清晰

`executionBoundary` 让“这一轮干了什么”成为第一类对象。

### 8.3 工具证据可回链

不是只看模型自述，而是能回链到 `tool_invocations`。

### 8.4 continuity 有压缩层

`resume packet / checkpoint` 减少了下一次恢复时对长链原始历史的依赖。

### 8.5 operator 可观测性强

`TaskLiveFlowView` / `JudgmentTraceView` 已经形成较完整的 runtime observation surface。

---

## 9. 当前机制的边界与缺口

## 9.1 现在更像 task continuity runtime，不是 goal continuity runtime

当前 schema 与主控对象仍然是 `task`：

- `tasks.goal` 只是文本字段
- 顶层生命周期拥有者仍是 task，不是 goal
- packet 仍然以 `task_identity` 为主键组织

所以现在系统更像：

```text
persisted task loop
  + packet / checkpoint / judgment / runtime facts
```

而不是：

```text
goal-native runtime
  -> goal owns lifecycle
  -> task is an execution unit under goal
```

## 9.2 当前 memory 偏 continuity，不偏 durable belief

它很擅长：

- 下一轮怎么继续
- 上一轮做了什么
- 当前任务有哪些 blocker / next step

但还不擅长：

- 长期稳定知识沉淀
- goal-level durable state
- cross-task belief memory

## 9.3 judgment 主要对 task 收敛

当前 judgment 更擅长回答：

- task 继续还是暂停
- handoff 还是 human gate
- 当前 next step 是什么

但还没有成熟回答：

- goal 是否真正完成
- 子任务结果如何累计回 goal progress
- goal 是否应 reopen / supersede / fork

---

## 10. 结构性判断

如果压成一句架构定义：

> `agent-cloud-harness` 当前的记忆机制，是一种 **以 task 为中心、以执行痕迹为原料、以 runtime facts 为中间层、以 packet/checkpoint 为压缩载体、以 TaskRuntimeContext 重建下一轮上下文的 continuity memory system**。

这也是为什么当前代码看起来不像“对话机器人记忆”，而更像：

- execution continuity runtime
- state compression runtime
- task recovery / handoff runtime
- operator-visible runtime cognition surface

---

## 11. 后续最自然的升级方向

在当前结构上，最自然的下一步不是推翻 task runtime，而是：

1. 保留 task 作为执行单元
2. 新增独立 goal lifecycle runtime
3. 让 packet / runtime facts / live-flow 从 task-aware 升级为 goal-aware
4. 让 completion audit / budget / reopen / follow-up 都能收口到 goal

也就是：

```text
TaskRuntime
  executes concrete work units
  emits events / packets / judgments / traces

GoalRuntime
  owns goal lifecycle
  observes task transitions
  reconciles progress / completion / reopen / budget
```

这条路线与现有代码最兼容，也最符合当前 repo 已经形成的 continuity-first 方向。
