# Free-First + LLM-Judged Escalation 设计

> 文档类型：技术设计（`*_DESIGN.md`）。
> 关联：`FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`（cost-class 路由基线）、`FREE_MODEL_WORKER_LANE_PLAN.md`（codex-free lane）、`E2_CODEX_FREE_E2E_SMOKE_EXECUTION_RECORD_2026-07-29.md`（真机证据）。

## 1. 问题与结论

### 问题

`free_first_routing` 默认关闭。当前路由决策是不是由 LLM/agent 做的？能不能做成"先由 codex-free 跑一轮，跑完再由 LLM/agent 判断要不要升级到 codex"？

### 结论

**当前路由是静态配置，不是 LLM 决策；但执行后的判断是 LLM 的，升级是确定性的 tier 规则。两者没有连成"先免费跑 -> LLM 判 -> 不行再升级"的闭环。**

具体分三层：

| 层 | 当前机制 | LLM/agent 参与？ |
|----|----------|-------------------|
| 路由（pre-execution） | `provider_routing_policy=free_first` 静态 task metadata flag；未设则按 selection_priority 选 codex(100) > codex-free(70) | 否，纯配置 |
| 执行 | 选中的 worker 跑一轮 | 否 |
| 判断 + 升级（post-execution） | `PromptBasedJudgmentService` LLM 判 execution action + completion status；`resolveAction` 映射；advisory handoff 在 escalate/wait/human_gate 时 small-tier -> strong-tier | 判断是 LLM；升级是确定性 tier 规则 |

## 2. 当前架构核对

### 2.1 路由层（WorkerRouter）

- `prefersFreeFirstRouting(task)` 读 `task.metadata["provider_routing_policy"]`，等于 `"free_first"` 才启用。
- 启用时：先选 `free_auto` worker（codex-free），额度耗尽 fallback 到 `paid_auto`（codex）。
- 未启用（默认）：按 `routeComparator`（selection_priority）选，codex(100) 胜出。
- `provider_routing_policy` 在 Java 代码中**只读不写**，不在 harness-config.yml 中，只能 per-task 通过 API metadata 设置。

### 2.2 判断层（PromptBasedJudgmentService）

- `judgeExecution`：LLM prompt 要求输出 `action (continue|wait|checkpoint|handoff|escalate|done)`。LLM **可以**说 escalate，但默认 fallback 是 continue。
- `judgeCompletion`：LLM prompt 要求输出 `status (done|partially_done|misaligned|needs_clarification)`。默认 fallback 是 partially_done。

### 2.3 升级层（ControlNodeGraph）

- `resolveAction(executionAction, completionStatus, ...)` 映射 LLM 判断到 resolved action：
  - blocked subgoal -> human_gate
  - executionAction=done + completion=done + alignment!=low -> done
  - executionAction=continue + completion=done + alignment!=low -> done
  - executionAction=continue + misaligned -> checkpoint
  - **其他（含 continue + partially_done）-> `return executionAction`（typically continue）**
- advisory handoff（`resolveAdvisoryHandoff`）：resolvedAction 为 escalate/wait/human_gate **且** 当前 worker 是 small tier **且** 有 ready strong-tier worker 时，handoff 到 strong-tier。
- **partially_done 不触发 advisory handoff** -- 它 fall through 到 continue，继续用同一个（small-tier）worker 跑。

## 3. Gap 分析

E2 真机证据（task_c0601e1ced394407）实测：

1. free_first_routing 默认关闭 -> router 选 codex（strong），不是 codex-free。
2. 手动 handoff 到 codex-free 后，codex-free init 超时 -> 恢复链 advisory handoff codex-free -> codex（这是**故障恢复**触发的升级，不是 LLM 判断输出质量触发的）。
3. codex 跑完产出 README 摘要，LLM 判断 execution=continue + completion=partially_done。
4. `resolveAction` fall through 到 continue，但 goal progress（0/1 done, 1 blocked）覆盖为 human_gate。

**两个断点：**

- **断点 A**：free-first 默认关闭，codex-free 不会先跑。
- **断点 B**：即使 codex-free 先跑了且产出 partially_done，`resolveAction` 不会产出 escalate，advisory handoff 不触发，任务继续用同一个 small-tier worker 或落到 human_gate。

## 4. 设计方案：Free-First + LLM-Judged Escalation

目标闭环：

```
codex-free 先跑 -> LLM 判断
  ├─ done -> 收工（省钱）
  ├─ partially_done -> advisory handoff 升级到 codex（strong）重跑
  └─ blocked/misaligned -> human_gate
```

### 4.1 层 1：全局 free_first_routing 配置

- 在 `harness-config.yml` 增加 `free_first_routing: true` 全局默认。
- `HarnessConfig` / `WorkerLaneConfig` 增加字段，`WorkerRegistry` 或 `TaskService` 在建任务时若 task metadata 未显式设 `provider_routing_policy`，用全局默认注入。
- 不破坏 per-task 覆盖：task metadata 显式设的优先于全局默认。

### 4.2 层 2：LLM 判断（已存在，无需改）

- `judgeExecution` + `judgeCompletion` 已经产出 action + status。
- 关键信号：`completionStatus=partially_done` 表示"有产出但不完整"。

### 4.3 层 3：partially_done + small-tier -> escalate

在 `resolveAction` 返回后、进入 switch 之前，增加一个 tier-aware 升级判断：

```
if resolvedAction == "continue"
   and currentWorker is small-tier
   and completionStatus == "partially_done"
   and resolveAdvisoryHandoff(task, "small") != null  // 有 ready strong-tier worker
then:
   resolvedAction = "escalate"  // 触发 advisory handoff 到 strong-tier
```

这样 partially_done 的 small-tier worker 会自动 advisory handoff 到 strong-tier（codex）重跑，而不是继续用 free worker 空转或直接 human_gate。

### 4.4 防止空转 / 限深

- 复用现有 `handoff_depth` 上限（`MAX_HANDOFF_DEPTH`）：升级到 strong-tier 后若仍 partially_done，不再升级（没有更高 tier），走 continue/human_gate。
- 可配 `free_first_escalation_max_rounds`（默认 1）：small-tier 最多跑 N 轮 partially_done 后才升级，给 free worker 一次自我修正机会。

## 5. 实现步骤

| 步骤 | 改动 | 文件 |
|------|------|------|
| S1 | harness-config.yml 增 `free_first_routing` 字段 + HarnessConfig record + 注入 task metadata 默认 | `HarnessConfig.java`、`HarnessConfigLoader.java`、`harness-config.example.yml`、`TaskService.java` |
| S2 | decide node 增 tier-aware 升级判断（partially_done + small-tier -> escalate） | `ControlNodeGraph.java`（resolveAction 后、switch 前） |
| S3 | 测试：free-first 路由 + partially_done 升级 + 限深 | `WorkerRouterRouteTraceTest`、`ControlNodeGraphActionResolutionTest` |
| S4 | 真机复跑：codex-free 先跑 -> partially_done -> 升级 codex -> done | E2 execution record follow-up |

## 6. 风险与取舍

- **成本 vs 质量**：free-first 会增加 codex-free 的调用频次（省钱），但 partially_done 升级到 codex 意味着同一任务可能跑两轮（free + strong）。对简单任务（reading/summarize）free worker 大概率一次 done，省一轮 strong；对复杂任务（coding）free worker 大概率 partially_done，多一轮 free 成本可接受。
- **init 超时**：codex-free 经 CCX 冷路径 init 超时（E2 实测）。free-first 开启后首次调用更可能命中冷启动。需配合 `initialize_timeout_ms` 调优或 CCX 预热。
- **partially_done 误判**：LLM 可能对简单任务也判 partially_done（E2 实测 planner delegation gate missing_compact_brief）。升级判断依赖 completionStatus 质量；若 LLM 频繁误判 partially_done，会导致不必要的升级。可加 `free_first_escalation_max_rounds` 缓冲。
- **不破坏现有合同**：free-first 保持 config-driven（不内置硬编码），per-task metadata 覆盖优先；升级判断是 additive（仅在 continue + small-tier + partially_done 时触发），不影响现有 done/checkpoint/human_gate 路径。