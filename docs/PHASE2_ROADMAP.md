# Phase 2 Roadmap

## 1. 结论

当前 `agent-cloud-harness` 已经不是“只有控制平面骨架”的状态。

仓库里已经具备：

- `ControlNodeGraph` 驱动的最小 runtime loop
- `WorkerExecutor` 执行一轮 worker work
- `TaskRuntimeContextBuilder` 组装运行时上下文
- `PromptBasedJudgmentService` 做最小 judgment
- `OpenAiCompatibleClient` 作为最小 LLM adapter

因此下一阶段的重点，不应再停留在“补上 judgment / llm / worker”这一层，而应转向：

**把当前已存在的 runtime 雏形，收敛成更稳定的认知控制层。**

---

## 2. 当前状态判断

### 2.1 已经落地的能力

当前代码已经形成这条最小闭环：

```text
create task
  -> enter intake
  -> scheduler route worker
  -> execute one round
  -> persist event / artifact
  -> continue node judgment
  -> migrate to scheduler / packet / human_gate / handoff / done
```

关键落点：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java`
- `src/main/java/com/agentcloud/worker/DefaultWorkerExecutor.java`
- `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java`
- `src/main/java/com/agentcloud/llm/OpenAiCompatibleClient.java`

### 2.2 当前真正的缺口

下一阶段的缺口不再是“有没有 loop”，而是“这个 loop 稳不稳定、是否足够结构化”。

当前主要缺口：

1. `working memory` 还没有独立层。
2. worker 输出仍以自由文本为主，协议不稳定。
3. `judgeCompletion()` 已存在，但还没有真正进入主状态迁移闭环。
4. consolidation 仍偏 checkpoint 摘要器，还没有明显反哺 active context / judgment policy。
5. 尚未开始沉淀 `operational learning memory`。

---

## 3. Phase 2 目标

Phase 2 的目标不是扩更多 worker，也不是引入完整多 agent swarm，而是完成以下 3 个收敛：

### 3.1 收敛一：Working Memory 独立成层

把“当前工作面”从 `packet + recent logs` 的隐式拼接，提升为显式层。

目标：

- 区分 `continuity packet` 与 `active working memory`
- 明确 inclusion / exclusion / retention / budget policy
- 让 worker execution 与 judgment 依赖同一份 active context

建议新增：

```text
src/main/java/com/agentcloud/runtime/
  ActiveContext.java
  ActiveContextBuilder.java
  policy/
    ActiveContextPolicy.java
    RetentionPolicy.java
    ExclusionPolicy.java
```

最小要求：

- 输入：`task + latest packet + recent events + recent decisions + recent artifacts`
- 输出：一份预算受控、可直接喂给 worker/judgment 的 active context

### 3.2 收敛二：统一结构化输出协议

把 worker execution、execution judgment、completion judgment 的输出收成统一 JSON 协议。

当前问题：

- `DefaultWorkerExecutor` 返回的是 `outputText`
- `judgeExecution()` 目前基本只消费动作词
- `judgeCompletion()` 虽能返回结构，但未接入主迁移判断

建议统一三类对象：

1. `WorkerExecutionPayload`
2. `ExecutionDecision`
3. `CompletionDecision`

建议最小 worker 输出：

```json
{
  "summary": "string",
  "output_text": "string",
  "produced_artifact": false,
  "artifact_title": "",
  "artifact_content": "",
  "suggested_next_step": "string",
  "confidence": "high|medium|low"
}
```

建议最小 judgment 输出：

```json
{
  "action": "continue|wait|checkpoint|handoff|escalate|done",
  "reason": "string",
  "next_step": "string",
  "needs_checkpoint": false,
  "needs_human": false,
  "target_worker": ""
}
```

以及：

```json
{
  "status": "done|partially_done|misaligned|needs_clarification",
  "alignment_level": "high|medium|low",
  "reason": "string",
  "suggested_next_action": "string"
}
```

### 3.3 收敛三：引入 Operational Learning Memory

把运行过程中逐渐出现的偏好、启发式、失败模式，从临时经验里提出来。

第一版不要直接做“自动改策略”，而是先做：

- 候选经验记录
- 置信度累积
- 人工可读与可审计

建议第一批只存 4 类：

1. routing preferences
2. context retention hints
3. completion / failure patterns
4. tool / worker usage heuristics

建议新增对象：

```text
src/main/java/com/agentcloud/model/
  LearningMemory.java

src/main/java/com/agentcloud/store/
  LearningMemoryDao.java
```

状态建议：

- `candidate`
- `reinforced`
- `stable_hint`

---

## 4. 推荐开工顺序

### Step 1. 先做 Active Context

先把 `working memory` 从 `TaskRuntimeContext` 中独立出来。

原因：

- 这是 worker execution 和 judgment 的共同上游
- 不先抽出来，后面所有 prompt / protocol 都会继续散落

优先改动：

- `TaskRuntimeContext`
- `TaskRuntimeContextBuilder`
- `DefaultWorkerExecutor`
- `PromptBasedJudgmentService`

### Step 2. 再做结构化输出协议

让 worker 和 judgment 都返回可稳定解析的 JSON，而不是继续依赖自由文本。

优先改动：

- `WorkerExecutionResult`
- `DefaultWorkerExecutor`
- `JudgmentContext`
- `PromptBasedJudgmentService`
- `ControlNodeGraph.continueNode()`

明确要求：

- `judgeCompletion()` 必须真正参与迁移决策
- `Task.nextStep` 应由结构化输出驱动，而不是长期保持空洞

### Step 3. 最后接 learning memory

在 execution / judgment / consolidation 相对稳定后，再把候选经验沉淀下来。

原因：

- 如果前面协议不稳定，learning memory 只会积累噪声
- 这一层应建立在更稳定的 execution/judgment trace 之上

---

## 5. 代码级改造建议

### 5.1 runtime

- 让 `TaskRuntimeContext` 持有 `activeContext`
- 新增 `ActiveContextBuilder`
- 将 active context budget、retention、exclusion 变成显式策略

### 5.2 worker

- 将 `DefaultWorkerExecutor` 从“返回文本”升级成“返回结构化 execution payload”
- 将 artifact 产生与否从隐式规则改成显式字段

### 5.3 judgment

- 扩充 `JudgmentContext`，不要只传 `task + workerOutput`
- execution judgment 与 completion judgment 使用同一份 active context
- `ControlNodeGraph.continueNode()` 中串联 execution + completion，而不是只看 execution action

### 5.4 consolidation

- 在 checkpoint 中补充更稳定的：
  - key constraints
  - open questions
  - next candidates
  - repeated failure hints
- 后续允许 consolidation 为 working memory / learning memory 提供输入

### 5.5 storage

- 为 learning memory 新建表
- 为后续 policy hint / routing preference 保留结构化字段
- 继续保持 schema-first，不急着引入复杂 distributed 设计

---

## 6. 非目标

Phase 2 不建议同时展开以下内容：

- 完整多租户
- 完整 marketplace
- self-modification
- self-deployment
- 大规模 distributed runtime
- dream-like integration

这些方向可以保留接口或文档主线，但不应干扰当前收敛目标。

---

## 7. 一句话判断

当前项目最需要的，不是再补一个“大功能层”，而是把：

`packet continuity -> working memory -> worker execution -> judgment -> consolidation`

这条链条真正收成一套稳定、结构化、可积累经验的 runtime 内核。
