# Continuity Progress

## 当前状态

- 2026-07-27: worker round 超时从 120s 硬编码改为可配 + tier-aware（ControlNodeGraph.executeOneRoundWithTimeout）。dialogue 真实任务 task_6fe50128734948ba 暴露 codex 被 120s 砍掉；agent_runs 历史显示 codex p95≈331s（49% 轮次 >120s）。新增 effectiveWorkerTimeoutSeconds(workerId)：显式覆盖（-Dharness.worker.timeout.seconds / HARNESS_WORKER_TIMEOUT_SECONDS，>=30s，绝对优先）否则 strong tier=600s / 其余=300s。WorkerExecutionTimeoutConfigTest 8 场景 + 既有 timeout/loop 回归全绿。运行时验证 codex 实拿 600s。
- 2026-07-27: 单轮预算超时（worker_budget_exhausted）与瞬态故障（worker_runtime_transient）分类分离。codex 600s 预算超时曾被误分类为瞬态故障，触发无意义跨 sibling codex lane auto_handoff。新增 classifyFailureClass 分支：looksLikeRoundBudgetTimeout（匹配 TIMEOUT pattern 规范形 "worker X failed: timeout"）在 transient 检查前返回 worker_budget_exhausted；looksLikeTransientWorkerRuntimeFailure 去掉 timeout 关键词。maybePlanFailureRecovery：worker_budget_exhausted 首次同 worker retry，二次直接 human_gate + 可操作原因（"raise HARNESS_WORKER_TIMEOUT_SECONDS or decompose the task"），不跨 sibling lane handoff。selectLatestWorkerMetadata 只补白名单 failure_summary_readable，不上浮 output_text/artifact_content，避免 planner delegation gate 从 runtime_failure_signal 漂到 oversized_runtime_failure_output。WorkerBudgetExhaustedRecoveryTest 3 场景 + ControlNodeGraphOrchestrationFlowTest 全量 + router/timeout/goal 回归合计 121/0。
- 2026-07-27: 运行时复核 task_6fe50128734948ba：重启最终 JAR 后 codex 一轮在 600s 内完成调查，没有再触发预算超时；judgment 输出指出核心修复已在 articleeditor-tmp 工作树存在，仍缺 BasicInfo/CustomField 两处 fallback 和部署/浏览器复验。当前 task 保持 waiting_human/execution_pending，是任务自身需要执行补丁/复验，不是 harness 超时分类问题。

- 2026-07-21 方向调整：Codex provider 差异已由本机 CCX 网关收敛，harness 聚焦切换到 Loop 主闭环（goal -> plan -> execute -> judge -> decide，HTTP 超时不污染 task 级状态）与 Goal 目标合同（goal / subgoals / subgoal_status / acceptance_criteria / progress_summary）。新方向主入口为 ../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md，packet 字段集将正式写进 ../API_CONTRACTS.md / ../SPEC.md。
- `continuity/` 已正式升级为 `README.md + PROGRESS.md` 的轻量工作区，并已启用 `runs/README.md` 作为 control-plane execution evidence 聚合入口。
- 当前活跃推进主要集中在四条线：packet schema 固化、legacy GET control route 退役准备、live flow/runtime cognition 读面、multi-round/control graph 回归闭环。
- 现阶段仍不启用 `tasks/`、`archive/`；`runs/README.md` 只负责聚合 root-level dated 执行证据入口，不搬动文档本体，`PROGRESS.md` 只负责把当前活跃主线串起来。

## 已完成
- 2026-07-23: Phase 2 P3 Loop Handoff Recovery 增强。新增 MAX_HANDOFF_DEPTH=3 + handoffDepth() 方法。advisory handoff 前检查 depth >= 3 时直接 human_gate。triggerHandoff 递增 handoff_depth。HandoffDepthLimitTest 5 场景。
- 2026-07-23: Phase 2 P2 LLM-assisted Subgoal Update。新增 LlmSubgoalJudgmentService，ambiguous executionStatus 时调用 LLM 判断 subgoal 状态。LlmSubgoalJudgmentServiceTest 11 场景。API_CONTRACTS.md + SPEC.md 已补合同。
- 2026-07-23: Phase 2 P3 Loop Handoff Recovery 增强。新增 MAX_HANDOFF_DEPTH=3 + handoffDepth() 方法。advisory handoff 前检查 depth >= 3 时直接 human_gate。triggerHandoff 递增 handoff_depth。HandoffDepthLimitTest 5 场景。
- 2026-07-23: Phase 2 P2 LLM-assisted Subgoal Update。新增 LlmSubgoalJudgmentService，ambiguous executionStatus 时调用 LLM 判断 subgoal 状态。LlmSubgoalJudgmentServiceTest 11 场景。API_CONTRACTS.md + SPEC.md 已补合同。
- 2026-07-22: Worker execution 超时保护落地。`schedulerNode` 中 `executeOneRoundWithTimeout` 使用 `CompletableFuture.get(120s)` 给 worker 执行设置超时。worker hang 时抛出 RuntimeException 进入 failure recovery 路径，控制图线程不会被无限阻塞。新增 `WorkerExecutionTimeoutTest` 3 场景（fast worker 正常返回、hanging worker 超时抛出、failing worker 传播异常）。
- 2026-07-22: `partial` task 终态落地。`finalizeCompletedTask` 现在检查 subgoal_status：部分完成 -> `partial`（非 `done`），全部完成才 -> `done`。同时 `autoUpdateSubgoalStatus` 在 worker `running` 时将第一个 `pending` subgoal 标为 `in_progress`，补全 `pending -> in_progress -> done/blocked` 生命周期。新增 `TaskPartialStatusTest` 6 场景 + `GoalProgressAutoUpdateTest` 扩展 2 场景。
- 2026-07-22: Loop 验收标准 #2 落地。`resolveAction` 现在 goal progress 优先于单轮 execution result：blocked -> human_gate, all done -> done, open + execution done -> checkpoint。新增 `ControlNodeGraphDecideGoalProgressPriorityTest` 10 场景。同时落地 `autoUpdateSubgoalStatus`：completed -> subgoal done, failed -> subgoal blocked；新增 `GoalProgressAutoUpdateTest` 7 场景。`continueNode` 每次完成后写 `last_loop_tick`。
- 2026-07-22: Loop 验收标准 #3 落地。`LoopContinueTimeoutInvariantTest` 证明 `controlGraph.enter()` 异常时 task 保持 `active`，不变成 `failed`（3 场景：异常不标 failed、状态不变、不写 failed event）。`API_CONTRACTS.md` 已补 Loop Continue 不变量。
- 2026-07-22: P2 Advisory Handoff 语义落地（跨 continuity + provider 主题）。`ControlNodeGraph.continueNode` 在 `escalate` 分支前增加 advisory handoff 判断：small-tier worker + ready strong-tier worker 时优先 handoff（`handoff_reason=advisory_consult`），无 strong-tier 时保持 `human_gate`。新增 `resolveAdvisoryHandoff`、`AdvisoryHandoffTest` 5 场景全绿。`API_CONTRACTS.md` + `SPEC.md` 已补合同。
- 2026-07-21: P2 goal contract 初始化入口落地。`TaskService.createTask(...)` 现在会在 `ProviderTaskContractNormalizer.normalize(...)` 后统一调用 `initializeGoalContract(meta, goal)`，把最小 goal contract 前移到任务创建入口：`goal` 取 `firstNonBlank(req.goal(), req.intent())`，默认补齐 `metadata.goal`、`subgoals=[goal]`、`subgoal_status=[{title,status=pending}]`、`progress_summary=0/1 subgoals done`；显式给定的 `subgoals / subgoal_status / progress_summary / acceptance_criteria` 保持原值。新增 `TaskServiceGoalContractTest` 覆盖 goal 默认化、intent fallback、preserve explicit metadata 三类场景，并同步 `API_CONTRACTS.md` / `SPEC.md` 写明 task creation 初始化路径。Focused regression `TaskServiceGoalContractTest,TaskServiceAutoStartTest,RuntimeJudgmentServiceTest,ControlNodeGraphActionResolutionTest` 全绿（59 tests, 0 failures），docs audit 0 violation。
- 2026-07-21: P1 goal-progress human gate 解释面补齐。`ControlNodeGraph` 在 blocked `subgoal_status` 触发 `human_gate` 时会写入 `Task.waitingReason=subgoal blocked requires human gate`；新增两个 `ControlNodeGraphActionResolutionTest` 覆盖 blocked/open reason 行为，并同步 `API_CONTRACTS.md` / `SPEC.md`。Focused regression 全绿（73 tests, 0 failures），docs audit 0 violation。
- 2026-07-21: P1 控制图层 goal-progress 切片落地。`ControlNodeGraph.resolveAction` 扩展为接收 `subgoal_status`，`continueNode` 传入 `task.metadata().subgoal_status`；新增 `resolveGoalProgressAction` 让 blocked -> `human_gate`、all done -> `done`、open -> `continue` 真正进入 loop 状态迁移，证据修复动作仍优先。新增三个 `ControlNodeGraphActionResolutionTest` 反射测试覆盖该路径，并同步 `API_CONTRACTS.md` / `SPEC.md`。Focused regression 全绿（71 tests, 0 failures），docs audit 0 violation。
- 2026-07-21: P1/P2 最小 goal-progress decision 切片落地。RuntimeJudgmentService 现在消费 `Task.metadata.subgoal_status`：blocked -> `ESCALATE`/human gate，all done -> `HALT`，open -> `CONTINUE`；新增 `RuntimeJudgmentServiceTest` 覆盖六类判断，并同步 `API_CONTRACTS.md` / `SPEC.md` 的 Runtime Judgment / Goal Contract。Focused regression `RuntimeJudgmentServiceTest,ControlNodeGraphActionResolutionTest` 全绿，docs audit 0 violation。
- 2026-07-21: P3 交接 packet 切片落地。SPEC.md 的 ResumePacket / HandoffPacket 字段表升级成与 API_CONTRACTS.md 一致的 machine-readable 最小字段集；新增 cross-worker 稳定性 contract test `TaskServicePacketContractTest.crossWorkerHandoffToResumePacketPreservesTypedContinuityFields()`，验证 codex->kimi handoff 预览、handoffTask 返回的 typed HandoffPacket、以及 handoff_before checkpoint 持久化的下游 ResumePacket 都保留 typed continuity 字段（下游 assigned_worker、current_objective、open_questions、blockers、recent_artifacts、recent_decisions 不丢）。Focused regression 全绿。

- `continuity/README.md` 已从单纯专题入口升级为 `README.md -> PROGRESS.md -> 子线文档` 的工作区入口。
- packet schema、legacy GET control route、live flow 诊断与多轮执行这几条线都已形成“基线文档 + focused regression + dated execution record”的可追踪闭环。
- `docs/README.md`、`WAKE.md`、`AGENTS.md`、`DOCS_GOVERNANCE.md` 已同步把 continuity 标成已启用 `PROGRESS.md` 的业务工作区。
- `continuity/runs/README.md` 已新增，当前 control-plane execution evidence 现在有了主题内聚合入口，不再只能从 root-level dated 文档长名单回看。

## 活跃子线

- packet / checkpoint / resume packet / typed schema
- control action / legacy GET route / lifecycle projection / runtime cognition
- live flow / runtime context / active context / continuity read surface
- control graph / multi-round task / pause-resume-handoff / execution record

## 下一步

- 如果 continuity 主题开始并行推进两条以上实施线，再考虑补 `tasks/` 做子线拆分。
- 如果控制面 execution/acceptance 证据持续密集新增，再考虑补 `runs/` 聚合 dated 记录。
- 如果 `runs/README.md` 后续继续膨胀到需要分批次索引，再在 `runs/` 下面补更细的二级 README，而不是直接把 root-level dated 文档物理迁走。
- 每轮状态机、packet 或 live flow 收口后，至少同步一个稳定基线文档，再把跨主题摘要写回 `STATE.md`。

## 风险

- `ARCHITECTURE.md`、`SPEC.md`、`API_CONTRACTS.md`、`LIVE_FLOW_RUNBOOK.md` 与 `PROGRESS.md` 之间仍可能发生合同或验证口径漂移。
- continuity 主题虽然已经有 `runs/README.md`，但执行证据文档本体仍在 root-level `docs/`；若入口不同步，仍可能回退成“有索引但结论没回收到基线/主线”的状态。
