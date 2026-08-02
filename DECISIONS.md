# DECISIONS 稳定设计决策

> 本文件只放跨多主题、不轻易回退的稳定决策。短期的探索性选择仍在 docs/<topic>/PROGRESS.md 与代码注释里。

## D01 — 文档结构合同

仓库采用 docs/<topic>/ 工作区划分（continuity / provider / dialogue / evaluation / release / meta），活跃主题额外带 PROGRESS.md；uns/、	asks/、rchive/ 按需启用。详细规则见 docs/DOCS_GOVERNANCE.md（待补），根入口在 AGENTS.md、WAKE.md。

**不退理由**：与 AGENTS.md 的入口约束绑定，被多个 agent 协作依赖；改结构会牵动所有主题入口。

## D02 — 不引入额外 memory/、state/ 目录树

仓库继续使用轻量写回面（STATE.md + docs/<topic>/PROGRESS.md），不采用 rticleeditor 的 memory/、state/ 树。

**不退理由**：AGENTS.md 已明确划线，本仓库面向轻量持久化；引入额外目录树会与入口契约冲突。

## D03 — API 契约改动先改 docs/API_CONTRACTS.md 再改代码

任何 API 字段、表结构、JSON 形状变更必须先在 docs/API_CONTRACTS.md 留痕，再改代码，最后回填 src/test/java/。

**不退理由**：是 §3 控制面回归测试的入口；改动不写文档会断探针任务的可重复性。

## D04 — 状态机改动先改 docs/SPEC.md 再改代码

控制图节点（intake / scheduler / continue / packet / human_gate / handoff / end）的状态转换规则，先在 docs/SPEC.md 描述清楚，再改 ControlNodeGraph 实现。

**不退理由**：§4 验证线全部基于 docs/SPEC.md 的状态机语义，状态机改动不写文档会断评估。

## D05 — utoUpdateSubgoalStatus execution_pending 守卫

§4.1 #5 修复：在 ControlNodeGraph.autoUpdateSubgoalStatus 中加入守卫，当 model_mode=orchestrated 且 orchestration_stage=execution_pending 时，跳过 subgoal 自动完成，避免 esolveAction 把 ction=handoff 短路成 done。

**不退理由**：已通过 ControlNodeGraphOrchestrationFlowTest 17/0、GoalProgressAutoUpdateTest 11/0、AdvisoryHandoffTest 12/0 三套单测，并在真实探针（	ask_6886b7bacc1c4ace）上验证 cf dispatch 真实发生。

## D06 — Probe task 必须带 xperiment_name 与 intent

POST /api/v1/tasks 提交的探针任务必须带 xperiment_name 与 intent 字段，否则不算评估证据。

**不退理由**：是 uns/ 索引与 PROGRESS.md 摘录的唯一依据；缺字段会让 trace 链路断掉。

## D07 — 暂停链路先落 packet 再持久化 checkpoint

TaskService.pauseTask 必须在更新 pause checkpoint 之前先 persistTransitionPacket(pause_before)，并保证 pause → packet 路径最新 packet 可被 GET /api/v1/tasks/{id}/packet 取回。

**不退理由**：回归保护在 TaskServicePacketContractTest.pauseTaskPersistsResumePacketAndPauseCheckpoint()；不持久化 packet 会让 resume 接口拿不到 packet，从而 deferred。

## D08 — Consolidation 查询按 	ask.sessionId() + 	ask.id() 顺序

ConsolidationService 查询 artifact 时按 session 优先、再 task id 顺序，避免 key_artifacts 跟 checkpoint/refined packet 对不齐。

**不退理由**：回归保护在 ConsolidationServiceProtocolTest.consolidateProducesCheckpointProtocolPayload()。

## D09 — 控制面错误响应脱敏

所有 handler 通过 NioHttpServer 返回稳定错误体；500 固定为 internal error，不直接回传异常 .getMessage()。日志里可保留详情。

**不退理由**：回归保护在 ControlActionHttpRouteTest.postPauseHidesInternalFailureDetails()、ApiErrorContractHttpTest；响应层回传内部异常细节会泄服务端栈。

## 待结清项（非已落地决策）

- **§4.1 #6**：ccx-free 模型在 reading 任务上的输出质量，不属于 control flow 范围，单独走 provider/decision。
- **§4.1 #7**：探针任务 fixture 文档面（本仓库）被清理过；当前已补回 docs/README.md、docs/evaluation/README.md、docs/evaluation/PROGRESS.md、STATE.md、DECISIONS.md，但 src/main/java 源码仍未回到仓库，下次开工前需要先恢复源码。
- **handoff-loop circuit breaker**：当 task goal 显式要求 	arget_worker，且 scalate_from_small_tier 触发时，是否直接 human_gate 而非 escalate，仍未决策。