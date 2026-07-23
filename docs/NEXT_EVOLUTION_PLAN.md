# 下一阶段演进计划：从协议注册到端到端可观测闭环

> 本文档承接 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 和 `CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md` 的已完成项，定义 2026-07-22 之后的工程推进方向。

## 1. 当前已收口

| 切片 | 状态 | 验证入口 |
|------|------|----------|
| Pi protocol 注册 (P1) | done | `PiProtocolTest` |
| Advisory handoff 语义 (P2) | done | `AdvisoryHandoffTest` |
| Trae protocol 注册 (P3) | done | `TraeProtocolTest` |
| Loop 验收 #1: plan->execute->judge->decide 证据链 | done | `ControlNodeGraphOrchestrationFlowTest` |
| Loop 验收 #3: continue 超时不污染 task 级状态 | done | `LoopContinueTimeoutInvariantTest` |
| Goal contract 初始化 | done | `TaskServiceGoalContractTest` |
| Runtime judgment 消费 subgoal_status | done | `RuntimeJudgmentServiceTest` |
| Goal-progress 切片接入控制图 | done | `ControlNodeGraphActionResolutionTest` |
| 交接 packet typed continuity 字段 | done | `TaskServicePacketContractTest` |
| UI 状态 tone 分层 (task / worker run) | done | `console-status-tone-plan.test.mjs` |
| UI subgoal progress 展示 | done | `dialogue-task-subgoal-progress-plan.test.mjs` |
| UI recovery action hint | done | `dialogue-recovery-action-hint-plan.test.mjs` |

## 2. 未收口的缺口

### G1: Loop 验收 #2 — decide 消费 goal 进度（done）

`ControlNodeGraph.continueNode` 当前在 judge 阶段已经调用了 `RuntimeJudgmentService.judge()`，后者消费了 `subgoal_status`。但 `continueNode` 在 orchestration judgment 之后、进入 recovery directive 解析时，recovery directive 仍主要基于单轮 execution result（`executionDecision` / `completionDecision`）而非 goal progress 全貌来决定 continue/handoff/human_gate。

**缺口**：当 orchestration judgment 返回 "continue" 但 subgoal_status 显示有 blocked 时，当前行为取决于 recovery directive 优先级，而非 goal progress 优先。

### G2: Goal progress auto-update 已落地（done）

`RuntimeJudgmentService.judge()` 是只读判断，不产生 subgoal_status 更新。当前 subgoal_status 只在 task 创建时由 `initializeGoalContract` 初始化一次，之后没有自动更新机制。

**缺口**：loop 运行过程中 subgoal 状态不会自动从 pending -> in_progress -> done/blocked 迁移，需要外部（worker 执行结果 / human input）手动更新。

### G3: 真实端到端集成已验证（done）

P1/P2/P3 都是协议注册和语义测试，没有真实的 Pi/Trae worker 被调度执行过。baseline matrix smoke 跑到 `waiting_human` 后卡在 provider auth/LLM 可用性。

### G4: UI Loop Activity 已落地（done）

前端 tone/status plan 模块已写好，但它们消费的是 task metadata 的快照。当 loop 持续运行时（多轮 continue），metadata 的更新频率和前端轮询频率之间没有合同保证。

## 3. 下一阶段推进方向

### 方向 A: Loop Decide 消费 Goal Progress（收口 G1 + G2）

这是 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 验收标准 #2 的落地。

**目标**：`continueNode` 在 decide 阶段，goal progress 判断优先于单轮 execution result 判断。

**具体动作**：

1. **在 `ControlNodeGraph.continueNode` 的 decide 阶段增加 goal progress 优先判断**
   - 当前：recovery directive 基于 `executionDecision` + `completionDecision`
   - 改为：先检查 `RuntimeJudgmentService.judge()` 的 goal progress 判断，如果返回 HALT（all done）或 ESCALATE（blocked），优先走 goal 决策路径，不再只看单轮 result
   - 测试：新增 `ControlNodeGraphDecideGoalProgressPriorityTest`

2. **在 `ControlNodeGraph` 的 judge 后增加 subgoal_status 回写**
   - 当 worker 执行结果包含可识别的 subgoal 完成信号时（如 tool 返回 "done" / "complete"），自动更新对应 subgoal 的 status
   - 初版规则：如果 worker execution result 是 `completed` 且无 error，将当前 `in_progress` 的 subgoal 标为 `done`；如果 worker execution result 是 `failed`，将当前 `in_progress` 的 subgoal 标为 `blocked`
   - 测试：新增 `GoalProgressAutoUpdateTest`

### 方向 B: 端到端集成验证（收口 G3）

**目标**：至少完成一次从 harness 调度到 CCX 路由到 worker 执行到 loop judge 到 decide 的端到端闭环。

**具体动作**：

1. **创建 CCX 端到端集成 precheck**
   - 验证 CCX 网关可用性（`http://127.0.0.1:4243/health`）
   - 验证至少一个 model 可用（`/v1/models` 非空）
   - 验证 provider auth（发起一个最小 completion 请求）
   - 脚本：`scripts/Run-CcxIntegrationPrecheck.ps1`

2. **创建 Pi 端到端集成 precheck**
   - 验证 `MULTICA_PI_PATH` 指向可用 CLI
   - 验证 Pi 能通过 CCX 完成 agent loop
   - 验证事件流输出可被 `PiProtocol` 解析

3. **端到端 smoke 场景**
   - 前提：CCX + 至少一个 LLM provider 可用
   - 场景：创建 task -> harness 调度 Pi worker -> Pi 通过 CCX 调用 LLM -> 事件流回 harness -> loop judge -> decide
   - 验收：至少拿到一个 `done` 状态的 task

### 方向 C: UI Loop 状态同步合同（收口 G4）

**目标**：当前端展示"执行中"状态时，该状态与 loop 实际状态一致。

**具体动作**：

1. **定义 task metadata 更新触发合同**
   - 每次 `continueNode` 完成后必须更新 `task.metadata.last_loop_tick`（ISO timestamp）
   - 前端用 `last_loop_tick` 判断"loop 是否仍在活跃运转"
   - 如果 `last_loop_tick` 超过阈值（如 30s），前端展示 "loop stall" 而非 "running"

2. **前端 loop 活跃度检测**
   - 新增 `loop-activity-detector-plan.js`：基于 `last_loop_tick` 与当前时间的差值判断 loop 活跃度
   - 测试：新增 `loop-activity-detector-plan.test.mjs`

3. **waiting_human 与 human_gate 的 UI 合同**
   - `waiting_human` 时页面必须展示：reason（为什么等待）、action（人工可以做什么）、hint（建议的操作）
   - 当前 `recovery-action-hint-plan.js` 已覆盖 failure_class -> action 映射，需补充 `goal_progress_blocked` 场景的 hint

## 4. 优先级排序

| 优先级 | 方向 | 依赖 | 状态 | 验证入口 |
|--------|------|------|------|----------|
| P1 | A: Loop Decide 消费 Goal Progress | 无 | done | ControlNodeGraphDecideGoalProgressPriorityTest + GoalProgressAutoUpdateTest |
| P2 | B: 端到端集成验证 | CCX 可用 | done | CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md + P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md |
| P3 | C: UI Loop 状态同步合同 | P1 | done | dialogue-loop-activity-detector-plan.test.mjs |

P1/P2/P3 均已完成。P2 端到端验证证明了 harness -> CCX -> LLM -> loop judge -> decide -> subgoal auto-update -> last_loop_tick 完整闭环在真实 LLM 调用上跑通。

## 5. 不做的事

- 不引入 `AdvisorService`（已由 advisory handoff 覆盖）
- 不在 harness 内复制 CCX 路由逻辑
- 不引入 gRPC/WebSocket/event bus
- 不修改 Pi/Trae 的 CLI 接口
- 不为 goal progress 自动更新引入 LLM 判断（初版用规则，后续再考虑 LLM-assisted subgoal update）

## 6. 写回顺序

- 本计划为主入口
- loop / goal 变化写 `continuity/PROGRESS.md`
- 端到端集成证据写 `evaluation/runs/README.md` 和 dated execution record
- UI 状态同步写 `dialogue/PROGRESS.md` 和 `WEB_CONSOLE.md`
- 稳定取舍写 `DECISIONS.md`
- 跨主题短摘要写 `STATE.md`
