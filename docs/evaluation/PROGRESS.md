# Evaluation Progress

## 当前状态

- `evaluation/` 已正式升级为 `README.md + PROGRESS.md` 的轻量工作区，并已启用 `runs/README.md` 作为 dated execution evidence 聚合入口。
- 当前活跃推进主要集中在四条线：产品视角评估与 capability gap、工程优先级与路线图、多轮任务包与测试驱动执行链、matrix/dated execution evidence 汇总。
- 现阶段仍不启用 `tasks/`、`archive/`；`runs/README.md` 只负责聚合 root-level dated 执行证据入口，不搬动文档本体，`PROGRESS.md` 则继续负责把当前活跃主线串起来。

## 已完成
- 2026-07-22: P2 端到端集成验证全部 PASS。CCX precheck（health + 30 models + completion）通过；harness 启动后创建 auto_start task，codex worker 通过 CCX 路由到 glm-4-flash 完成执行，loop judge -> decide 输出 `status=done`，goal progress auto-update 生效（`1/1 subgoals done`），`last_loop_tick` 写入 metadata。证据沉淀到 `../P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md` 和 `../CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md`。
- 2026-07-22：P2 baseline matrix follow-up 复跑完成。新增 P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_FOLLOWUP_EXECUTION_RECORD_2026-07-22.md，确认 codex app-server --listen stdio:// 已能完成 JSON-RPC initialize 交互，不再被 --no-alt-screen 参数错误阻断；本轮仍未拿到 accepted/completed 样本，剩余瓶颈转为本机 provider auth / LLM 可用性与 recovery budget。
- O03 acceptance gate 已补最小代码闭环：`ExperimentRunService` 现在把 `acceptance_gate_result`、`artifact_quality_gate_status`、`cost_gate_status`、`cost_gate_threshold_units` 写入 run metadata；`ExperimentMatrixService` 为 baseline case 增加长度桶成本阈值，并在 mode summary 中聚合 `acceptanceGateResultCounts`、`artifactQualityGateStatusCounts`、`costGateStatusCounts`、`runsWithFailureReason` / `failureReasonCounts`。`ExperimentMatrixServiceTest` 与 `ExperimentRunServiceTest` 已覆盖这些字段。
- O03 HTTP gate 已收口到脚本与文档合同。`Run-BaselineMatrixGateProbe.ps1` 现在校验每个 mode 的 `acceptance_gate_result_counts / artifact_quality_gate_status_counts / cost_gate_status_counts / runs_with_failure_reason`，并把 `mode_gate_rollup` 写入 report；同日补了“重复 experiment_name 直接失败”的前置校验。`TaskHandlerExperimentSummaryHttpTest` 已断言三种 mode 的 gate counts，`docs/API_CONTRACTS.md`、`docs/SPEC.md`、`TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`、`MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md` 已同步当前口径。真实脚本验证报告写入 `.tmp\baseline-matrix-gate-20260721.json`，三种 mode 均为 `not_evaluated=3`、`within_threshold=3`、`runs_with_failure_reason=0`，并已沉淀成 `docs/O03_ACCEPTANCE_GATE_HTTP_EXECUTION_RECORD_2026-07-21.md`。
- 2026-07-21：P2 baseline matrix 已拿到第一份 provider-backed real worker smoke 证据。`Run-BaselineMatrixRealWorkerSmoke.ps1` 在 `http://localhost:18082` 上创建了 `short-001 x 3 mode` 的真实 run，report 写入 `.tmp\baseline-matrix-real-worker-smoke-20260721.json`。三种 mode 都满足 `terminal_run_count` / `evaluated_run_count` 最低门槛，并留下 `live_flow / judgment_trace / tool_trace / harness_trace` 证据；但最终全部停在 `waiting_human / human_gate`，`acceptance_result=rejected`，共同 unfinished item 为 `initialize: timed out waiting for response`。该轮结果已沉淀成 `docs/P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md`。
- 同日继续复盘 real worker smoke 的 provider run 证据，确认 `initialize` 超时根因是 `codex app-server --no-alt-screen --listen stdio://` 在 Codex CLI `0.144.4` 下直接报 `unexpected argument '--no-alt-screen' found`。当前已把 `CodexAppServerWorkerExecutor` 的 app-server plan 收成 `codex app-server --listen stdio://`，exec-json 仍保留 `--no-alt-screen`；focused suite `CodexAppServerWorkerExecutorTest,AgentProviderSupportTest,ApiErrorContractHttpTest` 已通过。
- P1 验收标准 3、4 contract test 已落地：ControlNodeGraphOrchestrationFlowTest.orchestratedLoopDecisionTraceDistinguishesPlannerFailureFromExecutorFailure。该测试验证 execution_judgment 携带 `execution_role` + `selected_model_tier`，completion_judgment 携带 `evaluator_role` + `evaluator_model_tier` + `orchestration_closed_loop_observed`，task metadata 区分 `planner_worker` + `executor_worker`。这些字段是区分"规划失败"与"执行失败"的最小可追溯链。

- `evaluation/README.md` 已从单纯专题入口升级为 `README.md -> PROGRESS.md -> 子线文档` 的工作区入口。
- `evaluation/runs/README.md` 已新增，当前 execution evidence 现在有了主题内聚合入口，不再只能从 root-level dated 文档长名单回看。
- 项目评价、优先级、任务包、matrix 与 dated execution record 这几条线已经形成“基线文档 + focused 验证入口 + 执行证据”的可追踪闭环。
- `docs/README.md`、`WAKE.md`、`AGENTS.md`、`DOCS_GOVERNANCE.md` 已同步把 evaluation 标成已启用 `PROGRESS.md` 的业务工作区。

## 活跃子线

- capability gap / project evaluation / engineering priorities / roadmap
- goal-oriented eval / scenarios / product-goal fit
- benchmark / borrowing / go-to-market / productization
- project evolution multi-round task pack / test-driven plan / execution records

## 下一步

- 重新构建 harness 产物后复跑 `short-001` real worker smoke，确认 app-server 不再因 `--no-alt-screen` 启动参数失败。
- 在收掉 `short-001` 初始化超时后，把 smoke 扩到 `medium-001 / long-001`，再推进完整 `3+3+3` baseline release gate。
- 如果 evaluation 主题开始并行推进两条以上实施线，再考虑补 `tasks/` 做子线拆分。
- 如果评估 execution/precheck/matrix 证据继续密集新增，先维护 `runs/README.md` 分组与回收口径；只有分组膨胀时才补更细的二级 README。
- 如果 `runs/README.md` 后续继续膨胀到需要分批次索引，再在 `runs/` 下面补更细的二级 README，而不是直接把 root-level dated 文档物理迁走。
- 每轮优先级、评测方案或任务包收口后，至少同步一个稳定基线文档，再把跨主题摘要写回 `STATE.md`。

## 风险

- `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`、`PROJECT_EVALUATION_AND_NEXT_PLAN.md`、`NEXT_5_ENGINEERING_PRIORITIES.md`、`PHASE2_ROADMAP.md` 与 `PROGRESS.md` 之间仍可能发生判断口径漂移。
- evaluation 主题虽然已经有 `runs/README.md`，但 dated 文档本体仍在 root-level `docs/`；若后续入口不同步，仍可能回退成“有索引但结论没回收到主线”的状态。

## 2026-07-22 Coding E2E Smoke 执行记录归档

- 2026-07-22：Coding E2E smoke 已拿到一轮端到端闭环证据。`Run-CcxIntegrationPrecheck.ps1` health/models/completion 全 PASS 后，在 harness（`http://localhost:18082`，worker execution timeout 120s）上对真实编码任务跑通 Harness -> CCX -> LLM -> Loop -> Decide 链路。该轮结果已沉淀成 `docs/CODING_E2E_SMOKE_EXECUTION_RECORD_2026-07-22.md`，与 `P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md` 同属 evaluation 主题的 real-worker smoke 证据线。

## 2026-07-29 E2 Codex-Free Lane 真机 e2e 冒烟

- 2026-07-29：E2 codex-free lane 真机 e2e 冒烟验证完成。codex-free lane（经本地 CCX 3688 + codex app-server，provider_model_provider=ccx-free）真机执行一轮 reading 任务：codex-free init 超时（worker_runtime_transient） -> same_worker_retry_then_auto_handoff -> advisory handoff codex-free -> codex（strong tier，产出 README 摘要，~27s） -> completion partially_done（planner delegation gate missing_compact_brief）-> human_gate。长任务收口合同字段（decision_rationale / progress_detail / progress_summary）在 task metadata 与 /judgment_trace API 双通道验证 PASS；E1 #1/#2 + 可观测层 + E3 UI 验收 PASS。free_first_routing 默认关闭（codex-free selection_priority=70 < codex 100，config-driven by design）。证据沉淀到 ../E2_CODEX_FREE_E2E_SMOKE_EXECUTION_RECORD_2026-07-29.md。


## 2026-08-02 Long Stability Smoke 25200s 回归保护

- 本轮时间：2026-08-02
- 观察：仓库现有 worker timeout override seam 已支持 7h+ 预算，但此前缺少 25200s 专项回归保护与 runner。
- 动作：新增 `WorkerExecutionTimeoutConfigTest.longStabilitySmoke25200sOverrideIsAcceptedAcrossTiers()`，并新增 `scripts/Run-LongStabilitySmoke.ps1` 作为 `long-001` 25200s smoke 的可重复入口。
- 证据：`docs/LONG_STABILITY_SMOKE_25200S_EXECUTION_RECORD_2026-08-02.md`
- 未结清项：完整真实 25200s run 需要持续 7h+ 环境窗口，当前证据为“入口与 regression 已就绪”，待真实环境窗口补 run。
## 2026-08-02 预算超时恢复回归

- 本轮时间：2026-08-02
- 根因已收口：仓库 HEAD 的 prepareFreshSessionRecovery 已会在 fresh session retry 前把 subgoal_status 里 blocked 子目标重置为 pending，并强制回写 status=active / control_node=scheduler / waiting_reason=null；当前 live task 	ask_21f7c333c57e4514 的 SQLite 副本也显示其 subgoal 已是 pending。
- 新增回归覆盖：WorkerBudgetExhaustedRecoveryTest.prepareFreshSessionRecoveryClearsBlockedSubgoalsAndResetsWaitingState，直接从 waiting_human + blocked subgoal 出发验证 recovery 后状态可被清回 ctive。
- 冲突说明：工作区无 merge conflict；未提交项仅为 docs/test/doc 写回，不阻塞源码修复。
- 未结清项：运行中 JAR D:\gitAll\agent-cloud-harness\.tmp\runtime-jars\agent-cloud-harness-0.1.0-SNAPSHOT-shaded-port9091-20260731-135115.jar 仍为旧构建，所以 live API 仍显示 manual_recover_scheduled；需要重建 JAR 并热替换后，自动 retry 才会真的跑起来。
