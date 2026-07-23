## 方向调整 (2026-07-21)

Codex 不同 provider 的协议差异已经由本机 CCX 网关统一收敛，harness 不再负责 provider 差异收敛。下一阶段 5 个工程优先级调整为：

1. **Loop 主闭环 + 执行中状态判断**：`goal -> plan -> execute -> judge -> decide`；HTTP `/continue` 超时不把 active 任务判成 `failed`。
2. **Goal 目标合同**：`goal / subgoals / subgoal_status / acceptance_criteria / progress_summary` 最小可度量合同。
3. **上下交接文档 / handoff packet**：Resume / Handoff packet 最小字段集正式写进 `API_CONTRACTS.md` / `SPEC.md`。
4. **UI 页面展示结果 / 返回 + 执行中状态判断**：`active / running / waiting_human / failed / partial / done` 状态语义与 `/dialogue/` `/console/` 展示。
5. **Provider 专项（降级）**：codex profile lane、codebuddy/deveco CLI 接入只在接入新协议或读面诊断时推进；provider 差异由 CCX 收敛。

下方原 P1–P5 描述保留作为上下文，但排序口径已被本段取代，以 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 为准。原 P2 baseline matrix 的真实 worker smoke 证据继续有效，作为 loop 验证的真实执行证据来源。

### 完成状态 (2026-07-22)

上述 5 个方向已全部完成 P1/P2/P3 落地并验证：

1. Loop 主闭环：验收标准 #1/#2/#3 全部 done（`ControlNodeGraphOrchestrationFlowTest` / `ControlNodeGraphDecideGoalProgressPriorityTest` / `LoopContinueTimeoutInvariantTest` + P2 e2e smoke）
2. Goal 目标合同：`autoUpdateSubgoalStatus` + `resolveAction` goal progress priority 优先judgment done
3. 交接 packet：`API_CONTRACTS.md` / `SPEC.md` 字段集写入 + `TaskServicePacketContractTest` done
4. UI 状态展示：`partial` 独立 tone + `waiting_human` 显示人工动作入口 + `active` 不渲染为 `failed` + loop activity detector done
5. Provider 专项：由 CCX 网关收敛，harness 侧降级确认 done

详细推进记录见 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 验收标准段标注和 `NEXT_EVOLUTION_PLAN.md`。

---
# NEXT_5_ENGINEERING_PRIORITIES

## 1. 目的

本文档用于把当前 `agent-cloud-harness` 下一阶段最值得推进的 5 个工程点正式落成项目优先级清单。

排序原则不是“概念上最完整”，而是：

- 最能推进项目主叙事
- 最能形成可验证闭环
- 最能降低后续架构漂移风险
- 最能把已有 continuity 基础转成真正可运行的 orchestration runtime

当前项目最核心的主叙事应明确为：

> 让强模型调度小模型完成更长周期、更低成本、可恢复可交接的任务执行。

因此，以下优先级都围绕这一主线展开。

但这条主线的前提表述也应保持稳定：

> 当前项目首先是一个 continuity-first control plane / orchestration harness，然后才是围绕该底座去证明 strong-to-small model orchestration 的近端价值。

建议与以下文档对照阅读，避免 roadmap、当前态和评测叙事继续分叉：

- `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `GOAL_ORIENTED_EVAL_PLAN.md`
- `EVAL_SCENARIOS.md`
- `HARDNESS_PHASE1_ALIGNMENT.md`

---

## 2. 当前优先级总览

### P1. 做通“强模型调小模型”的最小闭环
### P2. 建立 baseline experiment matrix 与统一指标落盘
### P3. 把已有 tool/runtime/judgment/checkpoint 能力收束成 hardness phase-1 runtime contract 链
### P4. 固化 checkpoint / handoff packet 协议
### P5. 正式化状态变更接口与消息投影

---

## 3. P1. 做通“强模型调小模型”的最小闭环

## 为什么优先级最高

当前项目已经具备不少 continuity / orchestration 基础：

- continuity control layer
- routing
- handoff
- live flow
- dialogue message layer
- tool-aware worker 雏形

但主叙事还没有真正闭环证明：

- 强模型负责规划、判断、路由、验收
- 小模型负责执行子任务
- 系统实际上按这种分工在稳定运行

如果这一点不成立，项目更像一个有连续性能力的 runtime，而不是一个真正的 strong-to-small model orchestration runtime。

## 当前缺口

当前已经不再是完全缺少这条 trace。代码层已经有最小 runtime 证据：

- planner 阶段会写入 `planner_worker / planner_model_tier`
- executor 阶段会写入 `executor_worker / executor_model_tier`
- completion judgment 会写入 `evaluator_role / evaluator_model_tier / evaluation_result`
- experiment run / matrix summary 已能统计 `orchestration_closed_loop_observed` 与 strong-small-strong loop

当前仍缺少的是更硬的真实运行证据：

- provider-backed 的 `short-001 / medium-001 / long-001` 三模式复跑
- 从真实 worker artifact 到 completion judgment 的质量验收
- 成本口径与 `strong_only / small_only` 的可比较结果
- 至少一条真实 fallback / escalation 样本的可解释原因链

## 开发目标

先把已有最小 runtime 场景接到 baseline matrix，不要一开始泛化：

- 强模型负责 task breakdown / judgment / acceptance
- 小模型负责子任务执行
- `orchestrated` run 必须在 trace 中留下 planner / executor / evaluator 三段证据
- 同一 case 必须能和 `strong_only / small_only` 比较

这些字段已经是必须保留的 runtime contract：

- `selected_model_tier`
- `selected_worker`
- `why_selected`
- `fallback_reason`
- `evaluation_result`
- `planner_model_tier`
- `executor_model_tier`
- `evaluator_model_tier`
- `orchestration_closed_loop_observed`

## 验收标准

以下至少满足：

1. 一条任务链中，能够看到 planner / executor / evaluator 的角色切换
2. trace 中能够明确看到强模型与小模型的使用分工
3. 失败时能解释为什么 fallback / escalate
4. 输出结果能区分“规划失败”与“执行失败”

## 风险

- 过早泛化到所有任务类型，导致闭环迟迟做不完
- 只做文档层角色命名，没有真正进入 runtime trace
- 执行链过弱，导致 orchestration 看起来存在，但没有真实收益

## 推荐策略

只做一个代表性场景先跑通，例如：

- 强模型拆解任务
- 小模型执行局部步骤
- 强模型做结果判断与下一步决策

---

## 4. P2. 建立 baseline experiment matrix 与统一指标落盘

## 为什么排第二

项目现在的问题不是完全没有方向，而是：

> 还缺少把“感觉更好”转成“可比较结果”的实验骨架。

如果没有 baseline matrix，就无法稳定回答：

- 强模型单跑效果如何
- 小模型单跑效果如何
- 强模型调小模型是否真的更优
- continuity / handoff 是否真的带来收益

## 当前缺口

当前缺少：

- 完整 `3+3+3` 的真实 worker matrix 结果（当前仅有 `short-001` 三模式 smoke）
- 真实 worker artifact quality gate 的内容级标定
- 成本 threshold 的真实 worker 标定与 release gate 接入
- 把 baseline matrix HTTP gate 提升为 release gate
- 复跑已修复 app-server 参数后的 `short-001`，把当前 `waiting_human` 失败样本推进成可比较的成功/失败基线

## 开发目标

代码层已经支持三种实验模式：

- `mode=strong_only`
- `mode=small_only`
- `mode=orchestrated`

每次 run 统一记录：

- `completion_status`
- `acceptance_result`
- `total_cost`
- `handoff_count`
- `resume_count`
- `human_gate_count`
- `failure_reason`

内置 `baseline_matrix_v1` 已经有最小 3+3+3 case catalog：

- 3 个短任务
- 3 个中任务
- 3 个长任务

当前还应继续补：

- 用 `scripts/Run-BaselineMatrixGateProbe.ps1` 做 HTTP 合同 gate，并检查 `acceptanceGateResultCounts` / `artifactQualityGateStatusCounts` / `costGateStatusCounts`
- 重新 build 后复跑 `short-001` real worker smoke；确认不再卡在 initialize 后，再扩到 `medium-001 / long-001` 和完整 `3+3+3`
- 把 `experiment_summary`、失败 case 的 `live_flow / judgment_trace / tool_trace` 纳入 release gate 证据

2026-07-21 最新进展：`Run-BaselineMatrixRealWorkerSmoke.ps1` 已在 `http://localhost:18082` 跑出 `short-001 x 3 mode` 的 provider-backed smoke，`terminal_run_count=3`、`evaluated_run_count=3`；随后已从 `.tmp\provider-runs\codex\...\events.jsonl` 定位到失败根因是 `codex app-server --no-alt-screen --listen stdio://` 被 Codex CLI `0.144.4` 拒绝，并已完成 app-server 参数兼容修复。P2 的阻塞点已从“没有真实 worker evidence”收敛为“修复后复跑与 release gate 标定”。

## 验收标准

以下至少满足：

1. 同一任务可以在三种模式下复跑
2. run 结果能结构化落盘
3. 可以直接比较成本、完成率、恢复次数、人工介入次数
4. 能发现 orchestration 模式是否真优于 strong_only 或 small_only

## 风险

- 任务集过大，导致实验框架迟迟不落地
- 指标定义不稳定，后续结果不可比
- acceptance / artifact quality / cost gate 已有最小字段，但还缺真实 worker artifact 内容级验收和 release gate 标定

## 推荐策略

先用少量但可复现的任务，优先证明：

> orchestration 是否在“成本 / 成功率 / 长任务连续性”上有任何明确优势。

---

## 5. P3. 把已有 tool/runtime/judgment/checkpoint 能力收束成 hardness phase-1 runtime contract 链

## 为什么排第三

对照当前代码后，最值得回收的一点是：
项目已经不是“还没有 tool-aware execution / tool trace / judgment / checkpoint”。

相反，当前仓库已经有：

- `WorkerExecutionResult`
- `ToolAwareWorkerExecutor`
- `ToolInvocationRecord` + `tool_invocations`
- `ToolPolicy`
- `TaskRuntimeContext`
- `JudgmentContext` / `PromptBasedJudgmentService` / `RuntimeJudgmentService`
- `Checkpoint` / `ResumePacket` / `HandoffPacket`

这意味着当前最大的工程缺口，已经不再是“先把这些能力从零造出来”，而是：

> 把这些已经存在的能力收束成统一、可解释、可恢复、可续跑的 hardness phase-1 runtime contract 链。

## 当前缺口

当前仍缺少：

- 从 `WorkerExecutionResult` 进一步收硬成更显式 execution envelope
- 给 `ToolInvocationRecord` 补更适合 continuation / judgment 使用的字段
- 一个统一的 `RuntimeFactSet` 聚合对象
- 更明确的 `ContinuationAction`
- 把 checkpoint 从 consolidation 主线推进成更硬的 resume contract

## 开发目标

优先把当前已有能力沿同一条 object chain 收住：

- `WorkerExecutionResult -> WorkerExecutionEnvelope`
- `ToolInvocationRecord` 增强为更硬的 trace contract
- `RuntimeFactSet`
- `ResumeCheckpoint`
- `JudgmentInput / ContinuationAction`

## 验收标准

以下至少满足：

1. worker round 有统一 execution contract，而不只是自由返回 result 字段
2. tool trace 能更直接进入 runtime/judgment/continuation 聚合
3. checkpoint 不只表达 consolidation，而且能表达恢复入口
4. judgment 输入能显式消费 execution / tool / side-effect facts
5. continue / halt / handoff / retry 能在 trace 中明确表达

## 风险

- 继续只扩功能，不先收紧 contract，会让 runtime 越来越难解释
- 工具 trace、judgment、checkpoint 各自存在，但彼此语义不统一
- 文档和代码会再次开始漂移

## 推荐策略

不要重写架构。

最自然的推进方式是：
- 保留现有 control plane skeleton
- 沿 `worker -> tools -> runtime -> judgment -> checkpoint` 插入更硬的 contract / assembler / trace layer

---

## 6. P4. 固化 checkpoint / handoff packet 协议

## 为什么排第三

checkpoint / handoff packet 是整个 continuity 设计的地基。

如果协议长期处于半隐式状态，后续会直接影响：

- resume
- handoff
- audit
- model delegation
- replayability

越晚钉死，后面越容易结构漂移。

## 当前缺口

当前仍缺少一份更正式的协议定义，明确：

- resume packet 与 handoff packet 的边界
- 哪些字段是恢复必须项
- 哪些字段是增强项
- 哪些是 machine-readable first
- 哪些只是 human-facing summary

## 开发目标

建议先固定最小字段集。

### Resume Packet 最小字段

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

### Handoff Packet 最小字段

- `from_worker`
- `to_worker`
- `why_handoff`
- `what_done`
- `what_remaining`
- `cautions`
- `resume_hint`

协议要求建议明确：

- machine-readable first
- human-readable summary second

## 验收标准

以下至少满足：

1. packet schema 有文档定义
2. runtime 输出与 schema 对齐
3. packet 可用于 resume / handoff 的稳定输入
4. 不同 worker / different run path 下 packet 仍保持字段一致性

## 风险

- 过度追求完美 schema，导致迟迟不冻结
- human summary 先行，机器字段反而不稳定
- resume 与 handoff 混成一个泛 packet，导致职责不清

## 推荐策略

先钉死最小字段，再允许扩展字段。

不要一开始把 packet 设计成“万能记忆对象”。

---

## 6. P4. 把 tool-aware execution 升级到最小多步工具链

## 为什么排第四

当前 tool-aware execution 仍偏早期，更像：

- planning
- invoke one tool
- finalization

但真实任务往往至少需要 2 到 3 步工具链。
如果执行层过弱，上层 orchestration 即使设计得很好，也难以证明价值。

## 当前缺口

当前仍缺少：

- 多步工具调用链
- step-to-step reasoning trace
- 基本终止保护
- no-progress guard

## 开发目标

先做可控的最小多步版本，不直接做成完全开放式 agent loop。

建议支持：

- 最多 2 到 3 步工具链
- 常见模式如 `search -> read -> write`

每一步记录：

- `selected_tool`
- `args`
- `result_summary`
- `why_next_step`

加上终止保护：

- `max_tool_rounds = 3`
- `repeated_tool_guard`
- `no_progress_guard`

## 验收标准

以下至少满足：

1. 单次任务中可稳定完成 2-3 步工具链
2. 每一步都有 trace 可回放
3. 出现重复调用或无进展时能自动终止
4. tool-aware execution 的成功率高于单工具单轮版本

## 风险

- 一上来做成全功能 autonomous loop，复杂度失控
- 没有 guard，工具链容易卡死
- trace 太弱，问题难以定位

## 推荐策略

明确这是“最小多步版”，不是“一步做完通用 Agent Tool Loop”。

---

## 7. P5. 正式化状态变更接口与消息投影

## 为什么排第五

这一步不最先决定核心能力，但会显著提升：

- 一致性
- 可维护性
- 产品感
- 可回放性

当前系统明显还带有过渡态特征：

- `pause/resume/continue/escalate` 已正式切到 POST 写接口，但旧 GET 兼容入口仍存在
- 关键 lifecycle message 已有稳定投影字段，但覆盖面和浏览器级验收还需要继续收紧
- runtime layer 与 message layer 的核心状态语义已对齐，剩余风险主要在长尾 subtype 和 UI 映射一致性

## 当前缺口

当前仍缺少：

- 旧 GET 控制入口的退役策略、外部调用方迁移说明和最终下线验收
- `task_receipt / task_action / task_state / task_progress / task_result` 之外的长尾 runtime subtype 投影边界
- task state 与 dialogue state 的浏览器级一致映射验收矩阵

## 开发目标

继续把控制动作固定为 POST 写接口，并把 GET 仅作为带审计标记的历史兼容入口：

- `POST /api/v1/tasks/{id}/pause`
- `POST /api/v1/tasks/{id}/resume`
- `POST /api/v1/tasks/{id}/continue`
- `POST /api/v1/tasks/{id}/escalate`
- `POST /api/v1/tasks/{id}/handoff`
- `POST /api/v1/tasks/{id}/state`

当前已经明确的稳定 runtime/message 投影面包括：

- `task_receipt`
- `task_action`
- `task_state`
- `task_progress`
- `task_result`

后续目标不是重新发明 message layer，而是把这组投影继续作为稳定回放面，补齐长尾 subtype、UI 映射和浏览器级验收。

## 验收标准

当前已经满足并有测试证据的部分：

1. 控制动作使用语义正确的写接口：`TaskHandlerControlActionHttpTest.postPauseUsesFormalWriteRouteAndReasonBody()`
2. 旧 GET 入口仍可用但会进入审计标记：`TaskHandlerControlActionHttpTest.legacyGetPauseStillWorksAndIsMarkedForAudit()`
3. task state change 同时写一致事件流和消息流：`TaskServiceControlActionProjectionTest.updateTaskStateWritesConsistentLifecycleEventAndMessage()`
4. task control action 有稳定消息投影字段：`TaskServiceControlActionProjectionTest.pauseTaskWritesControlActionProjectionWithStableMetadata()`
5. continue 运行过程能投影 `task_progress / task_result`：`TaskServiceMessageReceiptTest.continueWritesAssistantProgressMessageWithExperimentRouteMetadata()`、`TaskServiceMessageReceiptTest.continueWritesAssistantResultMessageForTerminalTask()`

仍需继续补强的验收：

1. `/dialogue/` 真实浏览器路径能稳定反映 task lifecycle，并能区分正式 POST 动作与历史 GET 兼容动作
2. 长尾 runtime subtype 进入消息层前有明确白名单、字段合同和回归测试
3. GET 兼容入口下线前有迁移窗口、日志观测和失败回退策略

## 风险

- 外部调用方继续依赖旧 GET，导致缓存/预取误触发风险长期存在
- 新增 runtime subtype 绕过稳定 message contract，重新让 UI 与 runtime 脱节
- 只看单元测试而缺少 `/dialogue/` 浏览器级生命周期验收，导致投影字段存在但页面状态不一致

## 推荐策略

保持当前策略：关键生命周期事件先收口，长尾 subtype 只在有明确字段合同和页面消费路径时再进入 message layer。GET 兼容入口继续保留短期安全垫，但所有新接入必须使用 POST。

---

## 8. 推荐本周执行顺序

如果按本周开发顺序排，我建议：

1. P1 强模型调小模型最小闭环
2. P2 baseline experiment matrix + 指标落盘
3. P3 先把已有 tool/runtime/judgment/checkpoint 收束成 hardness runtime contract
4. P4 packet spec 固化并对齐 runtime 输出
5. P5 控制动作接口正式化 + 消息投影收口

这个顺序的逻辑是：

- 先把主叙事跑通
- 再把验证体系建起来
- 然后先把已有 runtime contract 收硬，避免能力继续散落
- 再钉死 continuity packet 地基
- 最后收口产品化接口与消息面

---

## 9. 总结

当前项目最不该做的是继续扩更多抽象概念。

当前项目最该做的是：

> 把“强模型调小模型完成长任务”从架构叙事，变成第一条真正可运行、可对比、可验证、可复盘的系统主线。

上述五项优先级，正是围绕这条主线给出的最小而关键的推进路径。
