# task_3809507edbbe4231 长任务成功率改进设计

## 1. 背景

真实失败任务：

- `session_695854b5c95b4a62`
- `task_3809507edbbe4231`

用户意图：

- 根据文档  
  `D:\gitAll\agent-cloud-harness\docs\XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md`
- 修改本地代码仓库  
  `D:\gitAll\articleeditor\`

这是一个典型的：

- 本地仓库改动
- 多轮阅读 + 代码实现
- 长任务 / 代码任务

而不是普通 `message/continuation`。

---

## 2. 真实证据

### 2.1 live task 状态

`GET /api/v1/tasks/task_3809507edbbe4231/live_flow` 与 SQLite 均显示：

- `status=waiting_human`
- `control_node=human_gate`
- `assigned_worker=claude`
- `task.metadata.task_type=continuation`
- `recovery_stage=human_gate_required`
- `auto_same_worker_retry_count=1`
- `auto_handoff_count=1`
- `auto_handoff_target=openclaw-native`

### 2.2 执行链

按 events / artifacts / decisions 还原：

1. 第一轮 `codex`
   - `route_source=ready_fallback`
   - `task_type=continuation`
   - `selected_worker=codex`
   - `durationMs=151092`
   - `summary` 和 `output_text` 非常长，带 `thread not found`
   - `execution_status=timeout`
   - judgment 先给了 `same-worker retry`

2. 第二轮 `codex`
   - `durationMs=151301`
   - `outputLength=1487238`
   - 仍然是巨大噪声输出，且仍带 `thread not found`
   - judgment 触发 `auto_handoff`
   - `target_worker=claude`

3. 第三轮 `claude`
   - `durationMs=2359`
   - `outputLength=0`
   - `summary=worker claude failed: thread not found (23524)`
   - 自动恢复预算耗尽，进入 `waiting_human / human_gate`

### 2.3 额外日志事实

当前 `8080` 实例是旧代码，数据库路径：

- `.tmp/agent_cloud_new.db`

说明旧行为仍在线上：

- 任务创建时仍落成 `task_type=continuation`
- route preview 被 `assigned_worker=claude` 钉成 `task_pinned/preassigned`
- auto handoff 目标仍可能落到不合适的 worker

---

## 3. 根因拆解

这次不是单点故障，而是三层问题叠加。

### 3.1 入口判型过宽

chat façade 把“修改本地代码仓库”的请求默认建成 `continuation`。

后果：

- `WorkerRouter` 无法基于 `coding` 语义稳定选 worker
- learning memory 也会继续强化错误的 `continuation` 路由经验

### 3.2 orchestration planner 输出门控太松

当前 orchestrated planner 阶段，只要轮次结束，就可能继续向后流转。  
但这条真实 case 里，`codex` 实际返回的是：

- 巨大噪声输出
- 带 `thread not found`
- 不像可执行 delegation brief

旧行为仍把它作为后续恢复 / handoff 的上游输入，导致：

- 长输出污染 artifact / summary
- handoff 判断被脏 planner 输出带偏

### 3.3 恢复换 worker 没保住代码语义

`thread not found` 后的 auto handoff 旧逻辑没有把“当前任务本质上是 coding”作为强约束。

后果：

- 代码任务可能换去 `openclaw-native` 这类 `browser/doc/message/search` worker
- 或者在 provider-native cli 之间盲切，缺少更稳的优先级

---

## 4. 目标行为

### 4.1 创建任务时

如果 chat 请求明显是：

- 修改本地 repo
- 修改 `.java/.js/.ts/.py/.xml` 等代码文件
- 带“修改/修复/实现/补测试/fix/patch/refactor”等动作

则默认建模成：

- `task_type=coding`

### 4.2 planner 阶段结束时

只有当 planner 输出满足“可委派执行 brief”最低合同，才允许转入 `execution_pending` 或后续 handoff。

最低合同至少包括：

- 非失败态
- 非空输出
- 不包含明显 provider/runtime failure
- 输出长度在合理范围内
- 至少有简明 `summary` 或 `suggested_next_step`

如果 planner 输出是：

- `thread not found`
- `provider unavailable`
- 巨量噪声 / 日志型输出
- 明显不是 delegation brief

则不应把它当成正常 planner 成果继续下传。

### 4.3 recovery 阶段

如果当前任务是 `coding`：

- auto handoff 必须优先保住代码语义
- 优先选 coding-capable worker
- `openclaw-native` 不能在代码任务上抢到高优先级

---

## 5. 拟改动

### 5.1 Layer A：ChatFacade 任务判型

文件：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`

改动：

- 当显式 `task_type` 缺失，或仅是通用 `continuation` 时
- 根据 `intent/goal/title/workspace/repo_path/working_directory/target_path` 做轻量推断
- 识别“本地代码仓库改动”并提升到 `coding`

当前结论：

- `ChatFacadeService` 已通过 `TaskTypeHeuristics.effectiveTaskType(...)` 对缺失或通用 `continuation` 的 task type 做轻量推断。
- 输入包含本地仓库路径、Java/POM/src/test 等代码信号时，新建任务会提升为 `coding`。
- 输入包含多个本地 workspace 时，不再把多个仓库混成一个 cwd；会创建父任务和每个 workspace 对应的子任务。

验收入口：

- `ChatFacadeHandlerHttpTest.postChatCompletionInfersCodingTaskTypeForRepoModificationRequests`
- `ChatFacadeHandlerHttpTest.postChatCompletionSplitsMultipleLocalWorkspacesIntoChildTasks`

#### 2026-05-18 实施验证

- 已通过 `ChatFacadeHandlerHttpTest.postChatCompletionInfersCodingTaskTypeForRepoModificationRequests`，锁定“根据文档修改 `D:\gitAll\articleeditor` 里的 Java 服务”这类请求会被创建为 `task_type=coding`。
- 已通过 `ChatFacadeHandlerHttpTest.postChatCompletionSplitsMultipleLocalWorkspacesIntoChildTasks`，锁定同时出现 `D:\gitAll\articleeditor` 与 `D:\gitAll\agent-cloud-harness` 时会创建父任务与两个子任务，子任务分别携带自己的 workspace/cwd。

### 5.2 Layer B：Planner 输出门控

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

新增一层 planner-delegation guard，作用于 orchestrated planner 阶段：

- 如果本轮 worker 是 planner / strong planner_executor
- 且输出包含 runtime/provider failure 信号
- 或输出长度明显异常大
- 或缺少可执行 brief

则：

- 不进入正常 delegation handoff
- 直接走 failure recovery
- 或回到 same-worker cold retry
- 或进入 human gate

建议先收一个保守门槛：

- `output_text.length > 12000` 且包含 `thread not found / provider unavailable / failed to start / timeout`
- 视为 planner output invalid for delegation

当前结论：

- planner 阶段输出如果是 runtime/provider failure 噪声，不会被当成 executor delegation brief 继续下发。
- 命中 `thread not found` 与超大输出噪声时，会写入 `planner_delegation_gate=rejected`，并转入 recovery。

验收入口：

- `ControlNodeGraphOrchestrationFlowTest.plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery`

#### 2026-05-18 实施验证

- 已通过 `ControlNodeGraphOrchestrationFlowTest.plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery`，锁定 `thread not found` 加 12K+ planner 噪声不会进入 delegation；任务会先 same-worker cold retry，再 auto handoff，最终在预算耗尽时进入 human gate。
- 该回归同时锁定 persisted metadata 包含 `planner_delegation_gate=rejected / planner_delegation_gate_reason=runtime_failure_signal`。

### 5.3 Layer C：Recovery 代码语义保留

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

改动：

- `coding` 任务在 `thread not found` / provider transient failure 时
- handoff 候选优先：
  - `preferred_worker`
  - `codex`
  - `cursor`
  - `copilot`
  - `opencode`
  - `codebuddy`
  - `trae`
  - `deepseek`
  - `claude`
- `openclaw-native` 不作为代码任务恢复的一线候选

当前结论：

- coding 任务发生 transient/runtime failure 后，recovery 选目标会优先保留代码能力 worker。
- `openclaw-native` 不再作为 coding 任务 auto handoff 的一线候选。
- 这层已和 planner-output gate 联动：planner 噪声被拒绝后会进入同一条 recovery 代码语义保留链。

验收入口：

- `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryPrefersCodingWorkerOverOpenclawForCodingTask`
- `ControlNodeGraphOrchestrationFlowTest.plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery`

#### 2026-05-18 实施验证

- 已通过 `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryPrefersCodingWorkerOverOpenclawForCodingTask`，锁定 coding 任务 auto handoff 会选 `codex`，不会切到 `openclaw-native`。
- 已通过 `ControlNodeGraphOrchestrationFlowTest.plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery`，锁定 planner 噪声进入 recovery 后仍沿 coding worker 链 handoff。

### 5.4 Layer D：Route 诊断可视化

文件：

- `TaskHandler` / `live_flow` 聚合链

改动方向：

- 当前 `/select_worker` 只看到“现在被 pin 到谁”
- 对排障不够

建议增加两个概念：

- `current_pinned_route`
- `recovery_unpinned_recommendation`

这样页面上能直接看出：

- 现在为什么是 `claude`
- 如果解除 pin，系统真正建议换谁

当前已落地到返回结构：

- `GET /api/v1/tasks/{id}/select_worker`
- `GET /api/v1/tasks/{id}/live_flow` 的 `route_preview`

新增字段：

- `current_pinned_route`
- `recovery_unpinned_recommendation`

含义：

- `current_pinned_route`：解释当前为什么被 `assigned_worker / task_pinned` 钉住
- `recovery_unpinned_recommendation`：去掉 pin 之后，按当前 `task_type / model_mode / readiness` 系统真正建议的 worker

验收入口：

- `TaskHandlerControlActionHttpTest.selectWorkerIncludesPinnedAndUnpinnedRecoveryDiagnostics`
- `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewIncludesPinnedAndRecoveryRouteDiagnostics`

#### 2026-05-18 实施验证

- 已通过 `TaskHandlerControlActionHttpTest.selectWorkerIncludesPinnedAndUnpinnedRecoveryDiagnostics`，锁定 `/select_worker` 同时返回当前 pinned route 和 recovery unpinned recommendation。
- 已通过 `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewIncludesPinnedAndRecoveryRouteDiagnostics`，锁定 `/live_flow.route_preview` 同步投影两类 route 诊断，operator 不需要只看日志反推“为什么现在是某 worker、如果恢复会建议谁”。

### 5.5 Layer E：运行期统一 task_type 归一化

文件：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`

问题：

- Layer A 只覆盖“chat 新创建任务”的判型
- 但真实线上仍存在大量已经落库的 `task_type=continuation` 存量任务
- 这些任务即使内容明显是“根据文档修改本地仓库 / 改某个 `.java` 文件 / 补测试”
- 在 `select_worker / live_flow.route_preview / recovery handoff` 上，仍会继续按 `continuation` 语义路由

真实证据：

- `task_3809507edbbe4231` 当前已进入 `waiting_human / human_gate`
- 但 task metadata 仍是 `task_type=continuation`
- 这使得 route / recovery 仍可能保留过宽语义

目标：

- 保留持久化原始 `task.metadata.task_type`
- 但新增一个统一的“有效 task_type”推断口径
- 当显式 task_type 缺失，或仍是通用 `continuation` 时
- 允许系统基于：
  - `title`
  - `goal`
  - `intent`
  - `workspace / repo_path / working_directory / target_path`
  - 已有 failure / route metadata
- 在运行期把有效类型提升为 `coding`

约束：

- 只做轻量推断，不修改非代码任务语义
- GET 型观测接口不应偷偷持久化 task_type
- 页面上最好同时保留：
  - 原始 `task_type`
  - route 实际采用的 `effective task_type`

最低落地：

- `WorkerRouter.selectWorker(...)` 不再只盲信 `task.metadata.task_type`
- `select_worker` 与 `live_flow.route_preview` 对存量 continuation 代码任务，也能给出 `coding` 语义的推荐
- recovery handoff 的 unpinned 预览不再被旧 `continuation` 语义拖偏

当前结论：

- `TaskTypeHeuristics.effectiveTaskType(...)` 已成为 ChatFacade、WorkerRouter、route preview、live flow 等路径的统一有效类型口径。
- 存量 `task_type=continuation` 但内容指向本地 repo/代码修改时，观测与路由会按 effective `coding` 处理，同时不偷偷改写原始持久化 metadata。

验收入口：

- `WorkerRouterRouteTraceTest.continuationRepoModificationTaskIsRoutedUsingEffectiveCodingType`
- `TaskHandlerControlActionHttpTest.selectWorkerPromotesContinuationRepoModificationTaskToEffectiveCodingType`
- `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewPromotesContinuationRepoModificationTaskToEffectiveCodingType`

#### 2026-05-18 实施验证

- 已通过 `WorkerRouterRouteTraceTest.continuationRepoModificationTaskIsRoutedUsingEffectiveCodingType`，锁定 continuation 存量代码任务会以 `taskType=coding` 路由到 coding worker。
- 已通过 `TaskHandlerControlActionHttpTest.selectWorkerPromotesContinuationRepoModificationTaskToEffectiveCodingType`，锁定 `/select_worker` 对 repo 修改类 continuation 任务投影 effective `coding`。
- 已通过 `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewPromotesContinuationRepoModificationTaskToEffectiveCodingType`，锁定 `/live_flow.route_preview` 同样按 effective `coding` 解释和推荐 worker。

### 5.6 Layer F：provider 级恢复降级策略

文件：

- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`

问题：

- 当前恢复链只会把失败的 worker 标记成 temporarily unavailable
- 但 `thread not found / session expired / provider unavailable` 这类错误，很多时候是 provider/session 级故障，不一定等于“这个 worker 永久不行”
- 现有 harness 已经记录了 `agent_runs`，并能统计：
  - provider failure rate
  - provider runtime stats
- 但这些统计还没有接入恢复选路

真实风险：

- 同一个 provider 下面的多个 worker 可能共享同一类会话态故障
- 只按 worker 名称切换，可能出现：
  - 明明是 provider 级故障，却在同 provider 内盲切
  - 或者反过来，因为某个 worker 一次 thread failure 就整台拉黑，过早丢掉可用的同 provider 冷启动机会

目标：

- 对 `thread not found / session expired / provider unavailable` 这类 provider/runtime transient failure
- `same-worker retry` 仍保留，用于冷启动重试
- 但进入 `auto_handoff` 选目标时
- 如果当前 provider 最近窗口内已连续出现 transient/provider failure
- 则优先避开同 provider 的其他 worker
- 在同等 coding 能力下，优先切到不同 provider

第一版落地范围：

- 不改 schema
- 不新增 provider 熔断表
- 直接复用 `agent_runs` 最近窗口
- 只对 `worker_runtime_transient` 恢复启用
- 判定尽量保守：
  - 最近 3 次里至少 2 次明确是 `thread not found / provider unavailable / session expired / timeout`
  - 才视为当前 provider 进入热失败窗口

这层不是替代 `WorkerRouter`，而是避免低价值自动切换链：

- `thread not found`
- `same provider another worker`
- `再次 thread not found`

当前结论：

- `AgentRunService` 已能基于最近 provider runs 识别 provider 热失败窗口。
- `ControlNodeGraph` 在 transient failure 的 auto handoff 阶段会读取该诊断，若存在不同 provider 的可用 coding worker，会优先避开热失败 provider。
- Layer H 已进一步把 provider 避让原因投影到 `/select_worker`、`/live_flow`、`/provider_selection` 和 `/runtime_health`。

验收入口：

- `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryAvoidsHotFailingProviderWhenAlternateProviderExists`
- `TaskHandlerControlActionHttpTest.selectWorkerProjectsTopLevelRecoveryProviderDeprioritizationHints`
- `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewExplainsProviderDeprioritizationForRecoveryRecommendation`

#### 2026-05-18 实施验证

- 已通过 `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryAvoidsHotFailingProviderWhenAlternateProviderExists`，锁定 provider 热失败窗口存在时 auto handoff 会避开当前 provider，选择不同 provider 的 coding worker。
- 已通过 `TaskHandlerControlActionHttpTest.selectWorkerProjectsTopLevelRecoveryProviderDeprioritizationHints` 与 `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewExplainsProviderDeprioritizationForRecoveryRecommendation`，锁定 provider 降权原因已经进入观测面，不只停在内部 recovery 决策。

### 5.7 Layer G：cold-start recovery 清理 provider continuation

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/worker/CodexAppServerWorkerExecutor.java`
- `src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java`

问题：

- `thread not found`、provider timeout、大输出中断后，如果恢复轮继续携带旧 `provider_session_id / provider_thread_id / codex_thread_id`，就可能把任务再次发回已经失效的 provider continuation。
- same-worker retry 与 auto handoff 都必须明确是 fresh session / fresh thread，而不是普通 resume。

目标：

- cold-start recovery 调度时清理旧 provider continuation metadata。
- 执行器看到 cold-start recovery stage 时，不再使用旧 thread/session resume 参数。
- runtime/message/UI 侧可以通过 `recovery_execution_mode=fresh_session` 看出这是冷启动恢复。

第一版边界：

- 不删除历史 artifact / packet / message。
- 不引入新的 provider session 表。
- 只在恢复轮 metadata 和执行器 resume 参数决策上收口。

最小口径：

- same-worker retry：继续保留，但明确视为“清 continuation 元数据后的冷启动重试”
- auto handoff：优先避开最近短窗口内 failure rate 明显过高的 provider
- 若当前失败 worker 属于某 provider，且该 provider 最近连续失败明显，则优先切到不同 provider 的同类 coding worker

当前结论：

- 上述“cold-start retry”已经进入运行时合同，不再只停在设计语义。
- 当恢复链进入：
  - `same_worker_retry_scheduled`
  - `auto_handoff_scheduled`
- `ControlNodeGraph.applyRecoveryDirective(...)` 会先清掉上一轮 continuation/thread 线索：
  - `provider_session_id`
  - `provider_thread_id`
  - `codex_thread_id`
  - `resume_provider_session_id`
- 同时写入：
  - `recovery_execution_mode=fresh_session`
- 控制图流转级回归已证明 recovery 后下一轮 executor 看到的 `TaskRuntimeContext.task().metadata()` 不再包含旧 provider thread/session id。
- 执行器本身也已经显式遵守这条语义：
  - 当 `recovery_stage=same_worker_retry_scheduled|auto_handoff_scheduled`
  - `CodexAppServerWorkerExecutor.resumeThreadId(...)` 返回 `null`，不再走 `thread/resume`
  - `ProviderCliWorkerExecutor.resumeId(...)` 返回 `null`，不再默认用旧 `--resume/--session`
  - 恢复轮明确视为 fresh session / fresh thread

验收入口：

- `ControlNodeGraphActionResolutionTest.applyRecoveryDirectiveClearsProviderContinuationMetadataDuringSameWorkerRetry`
- `ControlNodeGraphActionResolutionTest.applyRecoveryDirectiveClearsProviderContinuationMetadataDuringAutoHandoff`
- `ControlNodeGraphOrchestrationFlowTest.sameWorkerRetryColdStartClearsProviderContinuationMetadataBeforeNextRoundExecution`
- `CodexAppServerWorkerExecutorTest.resumeThreadIdReturnsNullDuringRecoveryColdStartStages`
- `ProviderCliWorkerExecutorTest.resumeIdUsesContinuationMetadataInsteadOfSessionIdAndSkipsRecoveryColdStart`

#### 2026-05-18 实施验证

- 已通过 `ControlNodeGraphActionResolutionTest.applyRecoveryDirectiveClearsProviderContinuationMetadataDuringSameWorkerRetry`，锁定 same-worker cold retry 会移除 `provider_session_id / provider_thread_id / codex_thread_id / resume_provider_session_id` 并写入 `recovery_execution_mode=fresh_session`。
- 已通过 `ControlNodeGraphActionResolutionTest.applyRecoveryDirectiveClearsProviderContinuationMetadataDuringAutoHandoff`，锁定 auto handoff 恢复同样清理旧 continuation，并写入 fresh-session 恢复模式。
- 已通过 `ControlNodeGraphOrchestrationFlowTest.sameWorkerRetryColdStartClearsProviderContinuationMetadataBeforeNextRoundExecution`，锁定恢复后的下一轮 executor 看到的 `TaskRuntimeContext.task().metadata()` 不再包含旧 provider thread/session id。
- 已通过 `CodexAppServerWorkerExecutorTest.resumeThreadIdReturnsNullDuringRecoveryColdStartStages`，锁定 Codex app-server executor 在 recovery cold-start stage 不会返回旧 resume thread id。
- 已通过 `ProviderCliWorkerExecutorTest.resumeIdUsesContinuationMetadataInsteadOfSessionIdAndSkipsRecoveryColdStart`，锁定 provider-native CLI executor 不会在 recovery cold-start stage 复用旧 `--resume/--session`。

约束：

- 不做重型调度器，不引入外部 scoring service
- 只用 harness 里已经存在的 `agent_runs` 和 provider readiness
- 先做保守降级，不改最终 completion 判定

最低落地：

- `ControlNodeGraph` 恢复选路时可以拿到“当前 worker 对应 provider”
- 对候选 worker 做 provider-aware 排序，避免优先落到最近失败过热的 provider
- 页面解释已由 Layer H 收口：
  - 当前选中的 worker/provider
  - 是否因为 provider failure 触发了降级

### 5.8 Layer H：provider 避让原因显式投影到 route/provider 观测面

文件：

- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`

问题：

- Layer F 已经能在恢复链里根据最近 `agent_runs` 判定“当前 provider 处于热失败窗口”
- 但这层信息现在主要只存在：
  - `ControlNodeGraph` 日志
  - 内部的 recovery handoff 判断
- 页面上的 `/select_worker`、`live_flow.route_preview`、`/provider_selection` 还看不出：
  - 是否因为 provider 热失败而避开了某个 provider
  - 被避开的 provider 是谁
  - 证据来自什么

真实影响：

- operator 能看到结果“为什么选了 codex / 为什么没继续选 claude”
- 但很难区分这是：
  - 普通 capability match
  - task pinned
  - 还是 provider failure 驱动的保守降级

目标：

- 在现有 route/provider 观测面上显式投影 provider 避让原因
- 至少覆盖：
  - `GET /api/v1/tasks/{id}/select_worker`
  - `GET /api/v1/tasks/{id}/live_flow` 的 `route_preview`
  - `GET /api/v1/tasks/{id}/provider_selection`

建议新增最小信号：

- `provider_deprioritized=true|false`
- `deprioritized_provider=<provider-id>`
- `deprioritization_reason=recent transient provider failures`

第一版边界：

- 不要求在普通 route 上真的改变 worker 选择算法
- 只要求：
  - 对 `recovery_unpinned_recommendation` 这类恢复视角预览
  - 或 provider 观测面
  - 能解释“如果现在要恢复，系统会尽量避开哪个 provider，为什么”
- 不引入新的数据库表
- 直接复用现有 `AgentRunService.shouldDeprioritizeProvider(...)` 与 provider failure 统计

最低落地：

- `RouteDiagnostic` 能携带 provider 避让解释
- `recovery_unpinned_recommendation` 在命中热失败 provider 时带上这组字段
- `provider_selection.metadata` 同步投影这组字段
- `/select_worker` 主返回体也应直接带：
  - `recovery_provider_deprioritized`
  - `recovery_deprioritized_provider`
  - `recovery_deprioritization_reason`
- `/runtime_health` 也应同步投影：
  - `metadata.deprioritized_providers`
  - `provider_stats[].metadata.provider_deprioritized`
  - `provider_stats[].metadata.deprioritization_reason`
- 这些 provider 恢复降级信号不应只停在 API：

当前结论：

- `/select_worker` 主返回体已经投影：
  - `recovery_provider_deprioritized`
  - `recovery_deprioritized_provider`
  - `recovery_deprioritization_reason`
- `/live_flow.route_preview.recovery_unpinned_recommendation` 已投影：
  - `provider_deprioritized`
  - `deprioritized_provider`
  - `deprioritization_reason`
- `/provider_selection.metadata` 已同步投影 provider 避让字段。
- `/runtime_health` 已投影：
  - `metadata.deprioritized_providers`
  - `provider_stats[].metadata.provider_deprioritized`
  - `provider_stats[].metadata.deprioritization_reason`
- `/dialogue/` route box 会把 recovery unpinned recommendation 渲染为：
  - `recovery避开 <provider>`
  - `恢复阶段会优先避开 <provider>`
- `/console/` runtime health 会渲染：
  - `当前恢复降级窗口：<provider>`
  - provider comparison 行内恢复避让说明

验收入口：

- `TaskHandlerControlActionHttpTest.selectWorkerProjectsTopLevelRecoveryProviderDeprioritizationHints`
- `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewExplainsProviderDeprioritizationForRecoveryRecommendation`
- `TaskHandlerProviderSelectionHttpTest.providerSelectionProjectsProviderDeprioritizationMetadataFromRecoveryDiagnostics`
- `TaskHandlerProviderSelectionHttpTest.runtimeHealthSummarizesProviderAndRecentRunStatus`
- `provider-deprioritization-plan.test.mjs`

#### 2026-05-18 实施验证

- 已通过 `TaskHandlerControlActionHttpTest.selectWorkerProjectsTopLevelRecoveryProviderDeprioritizationHints`，锁定 `/select_worker` 顶层返回 `recovery_provider_deprioritized / recovery_deprioritized_provider / recovery_deprioritization_reason`。
- 已通过 `TaskHandlerLiveFlowHttpTest.liveFlowRoutePreviewExplainsProviderDeprioritizationForRecoveryRecommendation`，锁定 `/live_flow.route_preview.recovery_unpinned_recommendation` 投影 `provider_deprioritized / deprioritized_provider / deprioritization_reason`。
- 已通过 `TaskHandlerProviderSelectionHttpTest.providerSelectionProjectsProviderDeprioritizationMetadataFromRecoveryDiagnostics`，锁定 `/provider_selection.metadata` 同步投影 provider 避让字段。
- 已通过 `TaskHandlerProviderSelectionHttpTest.runtimeHealthSummarizesProviderAndRecentRunStatus`，锁定 `/runtime_health` 投影 `metadata.deprioritized_providers` 与 `provider_stats[].metadata.provider_deprioritized / deprioritization_reason`。
- 已通过 `provider-deprioritization-plan.test.mjs`，锁定前端 helper 能把 recent transient provider failure 人类可读化，并能识别 `/select_worker` 顶层 recovery 字段。
- 额外执行过一次 Maven 全量测试，`Tests run: 379, Failures: 0, Errors: 0, Skipped: 0`；该次是命令参数未收窄导致，结果可作为补充健康信号，后续仍应优先使用单进程窄回归。

### 5.9 Layer I：cold-start recovery 显式投影到第一页消息与 route 说明

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/resources/web/dialogue/app.js`

问题：

- 当前 recovery 相关第一屏信号已经有：
  - `failure_class`
  - `recovery_stage`
  - `auto_same_worker_retry_count`
  - `auto_handoff_count`
  - `auto_handoff_target`
- 但 operator 仍需要自己推断：
  - 这一轮到底是不是冷启动恢复
  - 旧 provider session/thread 是否已经被清掉
- 这会让 `same_worker_retry_scheduled / auto_handoff_scheduled` 看起来和普通续跑轮差别不大。

目标：

- 对 cold-start recovery 增加一条最小、稳定、可投影的执行模式信号
- 让消息卡和 route box 第一屏就能看见：
  - 这轮恢复会走 fresh session / fresh thread
  - 不是沿用旧 continuation 的普通续跑

建议最小合同：

- task / artifact / session message metadata 新增：
  - `recovery_execution_mode=fresh_session`
- 第一版只在以下 recovery stage 写入：
  - `same_worker_retry_scheduled`
  - `auto_handoff_scheduled`
- `human_gate_required` 不写这个字段，避免把“等待人工确认”误说成仍在冷启动执行。

边界：

- `recovery_execution_mode` 是执行模式，不是新的 `recovery_stage`
- 不引入第二套状态机
- 不要求额外建表；直接复用 task metadata、worker artifact metadata 和 session message metadata 投影

最低落地：

- `ControlNodeGraph.applyRecoveryDirective(...)` 在 cold-start recovery 时写入：
  - `recovery_execution_mode=fresh_session`
- `TaskService.appendRuntimeFactMessageMetadata(...)` 把该字段带进：
  - `task_progress`
  - `task_result`
- `/dialogue/` 的 `messageCardRecoveryDetail(...)` 直接补一条短文案：
  - `fresh session`
- route box 的 drawer chip 也补一条：
  - `recovery: fresh session`
  - `/console/` 的 `runtime_health` 需要直接显示“当前恢复降级窗口”与受影响 provider
  - `/console/` 与 `/dialogue/` 的 route box 需要直接解释“恢复阶段会优先避开 <provider>”
  - operator 不应再只靠 raw JSON / live_flow / 日志反推 provider 避让原因
- 这条 UI 合同现在已收口到可复用渲染计划与页面级 probe：
  - `buildRouteBoxPlan(...)` 会把 `providerDeprioritization` 计入 drawer detail，确保恢复避让说明不会因为缺少 candidate/chip/timeline 而被折叠掉。
  - `scripts/recovery-job-ui-probe.js` 负责用页面级 fetch fixture 验证 `/dialogue/` 或 `/console/` 上的 recovery job 可见性。
  - `scripts/console-provider-window-probe.js` 负责用页面级 fetch fixture 覆盖 `runtime_health + live_flow/provider_selection`，并断言 `/console/` 渲染出恢复降级窗口、provider 行内避让说明和 route box 避让说明。
  - 如果后续要扩 `/dialogue/` 同类 browser 验收，应沿这个 probe 的 fixture 方式补 dialogue surface，而不是只看 raw JSON。
- 至少要有 HTTP 回归锁住：
  - 有热失败 provider 样本时，`live_flow.route_preview.recovery_unpinned_recommendation` 能看到避让原因
  - `/provider_selection` 能看到对应 metadata
  - `/select_worker` 不展开子对象时也能直接看到恢复视角的 provider 避让提示
  - `/runtime_health` 能解释当前哪些 provider 处于恢复降级窗口

当前结论：

- `ControlNodeGraph` 在 `same_worker_retry_scheduled / auto_handoff_scheduled` 写入 `recovery_execution_mode=fresh_session`。
- worker artifact latest metadata、session message metadata、`TaskService` runtime fact message metadata 都会保留该字段。
- `/dialogue/` 消息卡显示 `recovery · fresh session`。
- `/dialogue/` route box drawer chip 显示 `recovery: fresh session`。
- `/console/` 和 `/dialogue/` 的恢复按钮默认走 `recover?async=true`，不会把浏览器请求绑定到长 worker 执行。

验收入口：

- `ControlNodeGraphActionResolutionTest` 覆盖 recovery directive 与 continuation/thread 清理。
- `ControlNodeGraphOrchestrationFlowTest` 覆盖控制图恢复流转。
- `TaskServiceMessageReceiptTest` 覆盖 `task_progress / task_result` 消息投影。
- `dialogue-task-thread-preview-regression.test.mjs` 覆盖消息卡 `fresh session` 文案。
- `dialogue-route-box-plan.test.mjs` 覆盖 route box `recovery: fresh session` chip，以及 provider 避让说明会撑开 route drawer。
- `dialogue-recovery-job-plan.test.mjs` 覆盖异步 recovery job 面板。
- `scripts/Run-ConsoleProviderWindowProbe.ps1` / `scripts/console-provider-window-probe.js` 覆盖 `/console/` browser 级 provider 恢复降级窗口可见性。

#### 2026-05-18 实施验证

- 已通过 `TaskServiceMessageReceiptTest.continueWritesAssistantProgressMessageWithRecoveryMetadata`，锁定 `task_progress` 消息 metadata 和 `full_content` 都包含 `recovery_execution_mode=fresh_session / Recovery Mode / fresh session`。
- 已通过 `dialogue-task-thread-preview-regression.test.mjs`，锁定 `/dialogue/` 任务消息卡会把 cold-start recovery 渲染为 `fresh session` 提示。
- 已通过 `dialogue-route-box-plan.test.mjs`，锁定 route box 会把 `recovery: fresh session` 作为 drawer detail chip，并且 provider 避让说明也会撑开 drawer，不会只藏在 raw JSON。
- 已通过页面级 Console provider window 探针：`scripts/Run-ConsoleProviderWindowProbe.ps1 -BaseUrl http://localhost:8080 -ReportPath .tmp\console-provider-window-probe-20260518.json -ScreenshotPath .tmp\console-provider-window-probe-20260518.png`。
- 该探针确认 `/console/` 真实浏览器渲染出 `当前恢复降级窗口：claude`、provider comparison 行内 `恢复阶段会优先避开 claude`，以及 route box `recovery避开 claude`。
- 探针证据文件：`.tmp/console-provider-window-probe-20260518.json`、`.tmp/console-provider-window-probe-20260518.png`。

### 5.10 Layer J：dispatch preflight readiness

问题：

- 现有 `/workers/{id}/readiness` 主要是 passive readiness：
  - worker dependencies
  - host tool availability
  - provider detect/status
  - 最近运行时失败产生的 temporary unavailable
- 这些信号可以说明“配置上看起来能用”，但不能证明“现在分发一条任务时，这个 worker/provider 真能接受新轮次”。
- 对长任务来说，这个缺口会放大两类失败：
  - 首轮就把任务发给一个 provider/session 已经失效的 worker
  - `thread not found` 后虽然 recovery 想切 worker，但下一个候选其实也处在不可分发状态

目标：

- 把 worker 可用性拆成两层：
  - `passive`：默认 readiness，保持轻量，不主动发测试轮次
  - `dispatch`：分发前 readiness，允许做短超时主动验活，并把失败短期缓存成不可分发
- scheduler 在真正执行 worker 前必须使用 `dispatch` 口径。
- operator 可以通过 `GET /api/v1/workers/{id}/readiness?mode=dispatch` 手动查看分发前验活结果。

第一版最小合同：

- `ReadinessCheck` 增加稳定字段：
  - `mode=passive|dispatch`
  - `dispatch_preflight_ready`
  - `dispatch_preflight_reason`
  - `dispatch_preflight_cached`
- `dispatch` 模式会复用 passive readiness 作为前置条件：
  - passive 不 ready 时，不再额外启动 provider
  - passive ready 时，才做 provider-backed worker 的主动 preflight
- preflight 失败时：
  - readiness 返回 `ready=false`
  - `checks.dispatch_preflight=false`
  - worker 被短期标记为 temporarily unavailable，路由和恢复链会跳过它
- preflight 成功时：
  - 短期缓存结果，避免每一次路由预览都启动 provider

实现边界：

- 第一版只在 `mode=dispatch` 或 scheduler 真分发前触发主动验活。
- 普通 `/readiness` 和普通 route preview 不触发重操作。
- 主动验活可以先落成 provider 层的轻量 handshake；后续再为 Codex app-server / provider-native CLI 接入真实 “READY” 测试轮次。
- 如果 provider 暂时不支持主动测试，必须显式返回 `dispatch preflight not supported`，不能把它伪装成已验活。

最低回归：

- `GET /workers/{id}/readiness` 仍保持 passive 语义。
- `GET /workers/{id}/readiness?mode=dispatch` 会返回 dispatch 字段。
- dispatch preflight 失败后，worker readiness 被拉低，router 不再选它。
- scheduler 执行前如果当前 assigned worker dispatch preflight 失败，会清掉该 worker 并重新路由。

当前结论：

- `WorkerRegistry.checkReadiness(workerId)` 默认仍是 `mode=passive`，不会调用 provider dispatch preflight，也不会返回 `dispatch_preflight_*` 字段。
- `WorkerRegistry.checkReadiness(workerId, "dispatch")` 会在 passive ready 后调用 `AgentProviderRegistry.dispatchPreflight(providerId)`。
- dispatch preflight 失败时：
  - 返回 `ready=false`
  - `checks.dispatch_preflight=false`
  - `dispatch_preflight_ready=false`
  - `dispatch_preflight_reason=<provider reason>`
  - worker 会被短期标记为 runtime unavailable
- `/api/v1/workers/{id}/readiness?mode=dispatch` 已投影上述字段；未知 mode 返回 `400`，避免拼错参数时误以为做了 dispatch 验活。
- `WorkerRouter` 会在候选筛选、pinned worker、learning memory hint 三条路径使用 dispatch readiness，失败时保留原始 preflight reason。
- `ControlNodeGraph.ensureDispatchReadyBeforeExecution(...)` 会在 scheduler 真正调用 executor 前再次检查 assigned worker；如果失败，会清掉 assigned worker、写入 `dispatch_preflight_failed_worker / dispatch_preflight_reason`，再重新路由。

验收入口：

- `AgentProviderSupportTest.dispatchReadinessRunsProviderPreflightAndMarksWorkerTemporarilyUnavailableOnFailure`
- `ApiErrorContractHttpTest.workerReadinessDispatchModeProjectsPreflightFields`
- `ApiErrorContractHttpTest.workerReadinessRejectsUnknownMode`
- `WorkerRouterRouteTraceTest.routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses`
- `WorkerRouterRouteTraceTest.pinnedWorkerDispatchFailureKeepsOriginalPreflightReason`
- `WorkerRouterRouteTraceTest.learningMemoryHintDispatchFailureKeepsOriginalPreflightReason`
- `ControlNodeGraphOrchestrationFlowTest.schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight`

#### 2026-05-18 实施验证

- 已通过 `AgentProviderSupportTest.dispatchReadinessRunsProviderPreflightAndMarksWorkerTemporarilyUnavailableOnFailure`，锁定 passive readiness 不主动 preflight，`mode=dispatch` 会调用 provider preflight，失败后 worker 会被短期标记为 temporarily unavailable。
- 已通过 `ApiErrorContractHttpTest.workerReadinessDispatchModeProjectsPreflightFields`，锁定 `GET /api/v1/workers/{id}/readiness?mode=dispatch` 投影 `mode=dispatch / dispatch_preflight_ready=false / dispatch_preflight_reason=<provider reason>`。
- 已通过 `ApiErrorContractHttpTest.workerReadinessRejectsUnknownMode`，锁定未知 readiness mode 返回 `400`，避免拼错参数时误以为做了 dispatch 验活。
- 已通过 `ControlNodeGraphOrchestrationFlowTest.schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight`，锁定 scheduler 真执行前会复检 assigned worker；preflight 失败时写入 `dispatch_preflight_failed_worker / dispatch_preflight_reason`，清掉原 worker 并重新路由到可分发 worker。
- Layer P 已进一步验证普通候选、pinned worker、learning memory hint 三条 router 入口都会保留原始 preflight failure reason。

### 5.11 Layer K：最近失败任务恢复入口

问题：

- `resume / continue / handoff` 已经能单独推进任务，但 operator 需要先判断：
  - 哪些最近任务可恢复
  - 应该继续原 worker，还是切 worker
  - 是否需要清掉旧 provider session / thread 后 cold-start
- 对 `thread not found`、超大输出导致的 provider runtime failure、长任务中断这类场景，只靠人工点多个低层动作，容易把任务继续发回坏 continuation。

目标：

- 暴露一个统一恢复入口，把最近失败/等待任务转成可执行恢复动作。
- 默认恢复语义是 `fresh_session`：清理旧 provider continuation metadata，再重新进入 scheduler。
- 如果任务或请求里给出 `target_worker / auto_handoff_target`，优先走 handoff 后继续。
- 对认证失败、provider 未安装、环境不可用这类不可自动恢复问题，明确返回不可恢复原因，不自动重试。

最小 API 合同：

- `GET /api/v1/tasks/recoverable?limit=10`
  - 返回最近可恢复候选和不可自动恢复原因。
  - 第一版候选状态：`waiting_human / failed / paused / waiting`，以及 `control_node=human_gate` 的任务。
- `POST /api/v1/tasks/{id}/recover`
  - Body 可选字段：
    - `mode=auto|resume|continue|handoff`
    - `target_worker`
    - `reason`
  - `auto` 模式按恢复计划执行：
    - 有目标 worker 且不同于当前 worker：`handoff`
    - 否则：`resume`，并强制 `recovery_execution_mode=fresh_session`

响应最小字段：

- `plan.recoverable`
- `plan.recommended_action`
- `plan.reason`
- `plan.target_worker`
- `plan.failure_class`
- `plan.provider_failure_class`
- `plan.failure_evidence_source`
- `plan.failure_evidence`
- `plan.recovery_execution_mode`
- `control_result` 或 `handoff_result`

实现边界：

- 不新增第二套状态机；恢复入口只是 `resume / continue / handoff` 的薄编排层。
- 第一版不做浏览器自动操作模拟；人工验收用 API/console 入口触发，浏览器测试只验证按钮和响应可见。
- 不直接删除历史 artifact / packet / message；只在本轮恢复 metadata 中标注 `manual_recovery_requested=true` 与 `fresh_session`。
- 对 `thread not found` 这类 provider transient failure，恢复入口必须避免沿用旧 `provider_session_id / provider_thread_id / codex_thread_id`。
- 恢复计划的 provider failure 分类证据不应只依赖 latest agent run；还要读取 task `waiting_reason / summary / next_step`，否则失败只落在任务状态面时，`GET /tasks/recoverable` 会缺少可解释原因。

最低回归：

- `GET /tasks/recoverable` 不会被 `/tasks/{id}` 路由误吃。
- `POST /tasks/{id}/recover` 能把 `waiting_human + provider_runtime_transient` 任务恢复到 scheduler 路径，并写入 `recovery_execution_mode=fresh_session`。
- `mode=handoff` 或 `target_worker` 能走 handoff，并在响应里返回 `handoff_result`。
- `provider_auth_failed / provider_not_installed` 不会自动恢复。
- 只有 `waiting_reason / summary / next_step` 带 `thread not found / timeout / provider unavailable` 时，恢复计划也能返回 `provider_failure_class=provider_runtime_transient` 和对应 evidence source。
- `target_worker` 触发的 `auto` 恢复不能退化成普通 `resume`；响应必须包含 `handoff_result`，持久化任务的 `assigned_worker` 应切到目标 worker。
- `waiting_reason / summary / next_step` 或 provider run summary 命中输出过大、输出超过限制、输出被 provider 截断这类失败时，也应归类为 `provider_runtime_transient`；恢复入口应允许 fresh-session 继续，而不是把“超大输出导致的 provider 轮次失败”当作不可恢复的最终失败。

本地验收入口：

- `scripts/Run-TaskRecoveryAcceptanceProbe.ps1`
  - 默认只用 HTTP API 构造最小失败任务，并验证：
    - `GET /tasks/recoverable` 能识别 `provider_runtime_transient`
    - `mode=auto + auto_handoff_target` 会返回 `handoff_result`
    - `provider_auth_failed` 会被拒绝自动恢复
  - 默认不触发同 worker `resume` 真实执行，避免验收脚本在本机 worker/provider 配置不稳定时启动重任务。
  - 如需同时验证 fresh-session 异步恢复触发链，可显式加 `-IncludeResumeExecution`。
  - 带 `-IncludeResumeExecution` 时，验收目标是确认恢复入口返回 `202 accepted`，并带出 `request_id / status_url / recovery_execution_mode=fresh_session`；它不等待 worker 完成。
  - 2026-05-17 本地实测：`http://localhost:18090` 上默认合同场景通过；旧的同步附加 resume smoke 已能进入 `[Trigger] resume` / `[Scheduler]` 并路由到 `codex`，但会受 worker 时长影响。当前主验收改为异步 accepted 合同。

#### 2026-05-18 实施验证

- 已确认 `TaskHandler` 先匹配 `/api/v1/tasks/recoverable`，不会被 `/api/v1/tasks/{id}` 泛路由误吃。
- 已通过 `TaskHandlerControlActionHttpTest.getRecoverableTasksListsRecentInterruptedTasksBeforeGenericTaskRoute`，锁定最近可恢复任务能从统一入口返回，且带出 `recoverable=true / recommended_action / recovery_execution_mode=fresh_session`。
- 现有回归已覆盖 waiting reason、latest agent run、oversized output 三类 provider runtime transient 证据来源；Layer T 进一步锁定 `provider_error` 优先作为 `failure_evidence`。
- 同步 `POST /tasks/{id}/recover` 和 `target_worker` handoff 语义已有 `postRecoverResumesWithFreshSessionMetadataAndClearsProviderContinuation`、`postRecoverAutoHandoffUsesTargetWorkerAndReturnsHandoffResult` 覆盖。
- 已通过本地 HTTP 验收探针：`scripts/Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -ReportPath .tmp\task-recovery-acceptance-probe-20260518.json`。
- 探针默认场景确认：`thread not found` 可恢复候选返回 `provider_failure_class=provider_runtime_transient / recommended_action=resume / recovery_execution_mode=fresh_session`；`mode=auto + auto_handoff_target` 返回 `recommended_action=handoff / assigned_worker=claude`；`provider_auth_failed` 自动恢复被 HTTP `400` 拒绝。
- 已通过 `TaskHandlerControlActionHttpTest.postRecoverAsyncAcceptsOversizedOutputFailureAsFreshSessionRecovery`，锁定 `output too large / maximum output exceeded` 这类超大输出失败不仅能进入 recoverable 列表，也能通过 `recover?async=true` 进入 fresh-session 恢复并写入 succeeded recovery job。
- 已通过附加异步触发合同：`scripts/Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -ReportPath .tmp\task-recovery-acceptance-probe-include-resume-20260518.json -IncludeResumeExecution`。
- 附加场景确认 fresh-session 异步恢复返回 `accepted=true / async=true / request_id=recovery_* / status_url=/api/v1/tasks/{id}/live_flow / recovery_execution_mode=fresh_session`，并能通过 `/recovery_jobs` 查到 matching job。
- 探针证据文件：`.tmp/task-recovery-acceptance-probe-20260518.json`、`.tmp/task-recovery-acceptance-probe-include-resume-20260518.json`。

### 5.12 Layer L：recover 异步触发，避免 HTTP 绑定长 worker 执行

问题：

- `POST /api/v1/tasks/{id}/recover` 当前会同步调用 `resume / continue / handoff`。
- 当恢复动作进入 scheduler 并调用真实 provider worker 时，HTTP 请求会一直等到 worker 返回。
- 对 Codex app-server、provider CLI、大输出或慢任务，这会造成：
  - 前端按钮长期 pending，看起来像恢复入口失败
  - 验收脚本需要自己做客户端超时兜底
  - worker 实际已经继续跑，但 operator 拿不到“恢复已触发”的稳定回执

目标：

- 保留现有同步合同，避免破坏旧调用方。
- 增加显式异步恢复触发：调用方传 `async=true` 或 `wait=false` 时，HTTP 立即返回接受结果。
- 异步结果只承诺“恢复动作已排队/已触发”，不承诺 worker 已完成。
- 后续状态继续通过 `/tasks/{id}`、`/live_flow`、`/messages`、agent run 或 `/recovery_jobs` 观测。

最小 API 合同：

- `POST /api/v1/tasks/{id}/recover?async=true`
- 或 body 中传：
  - `async=true`
  - `wait=false`
- 响应：
  - HTTP `202`
  - `TaskRecoveryResult.accepted=true`
  - `TaskRecoveryResult.async=true`
  - `TaskRecoveryResult.request_id=<recovery id>`
  - `TaskRecoveryResult.status_url=/api/v1/tasks/{id}/live_flow`
  - `control_result / handoff_result` 为空
- `request_id` 同时作为 Layer M 的 `TaskRecoveryJob.id`，可通过 `/api/v1/tasks/{id}/recovery_jobs` 查回。
- 同步默认行为保持 HTTP `200` 与原响应形态。

实现边界：

- 第一版用 harness 进程内虚拟线程执行恢复动作，不新增队列表。
- 异步任务失败先写日志；任务状态仍由恢复动作自身更新。
- 异步路径在排队前仍要同步生成 `TaskRecoveryPlan` 并拒绝不可恢复任务，避免把明显错误任务放进后台。
- 强可观测 job 状态已由 Layer M 的 `TaskRecoveryJob` 提供；Layer L 只负责 accepted 响应、入队前 plan 校验和不等待 worker 完成。

最低回归：

- 默认 `/recover` 仍返回同步 `control_result / handoff_result`。
- `?async=true` 返回 `202`，包含 `accepted=true / async=true / request_id / status_url`。
- 异步路径对 `provider_auth_failed` 仍同步返回 `400`，不能把不可恢复任务排进后台。
- 异步路径不能在响应前执行 worker；HTTP 响应只依赖 plan 和入队。

#### 2026-05-18 实施验证

- 已确认 `TaskHandler.isAsyncRecoveryRequested(...)` 支持 query `async=true/background=true/wait=false` 与 body `async=true/wait=false`。
- 已通过 `TaskHandlerControlActionHttpTest.postRecoverAsyncReturnsAcceptedWithoutWaitingForControlResult`，锁定 `recover?async=true` 返回 HTTP `202`，响应包含 `accepted=true / async=true / request_id / status_url`，且不等待 `control_result`。
- 已通过 `TaskHandlerControlActionHttpTest.postRecoverAsyncStillRejectsProviderEnvironmentBlockedFailures`，锁定 `provider_auth_failed` 这类环境阻断失败在入队前同步拒绝，不创建后台恢复执行。
- 已通过真实 HTTP 探针 `scripts/Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -ReportPath .tmp\task-recovery-acceptance-probe-include-resume-20260518.json -IncludeResumeExecution`，锁定 fresh-session 异步恢复返回 `accepted=true / async=true / request_id=recovery_* / status_url=/api/v1/tasks/{id}/live_flow / recovery_execution_mode=fresh_session`，且不等待 worker 完成。
- 验证注意：不要并行启动多个 Maven/Surefire 进程跑同一模块测试。2026-05-18 并行跑同一测试类时出现过 JUnit discovery 阶段 `NoClassDefFoundError`，dump 显示根因是多个 Maven 进程同时改写 `target/classes`，顺序复跑同一用例通过。

### 5.13 Layer M：RecoveryJob 持久化观测

问题：

- Layer L 让 `recover?async=true` 可以快速返回 `request_id`。
- 但如果 `request_id` 只存在于响应和日志里，刷新页面或换 operator 后就很难回答：
  - 这次异步恢复是否已经开始
  - 是否失败
  - 失败原因是什么
  - 后台线程最终推进到了哪个动作

目标：

- 把每次异步恢复触发落成 `TaskRecoveryJob`。
- `request_id` 即 job id，响应、event、后续查询共用同一个标识。
- 最小状态：`accepted -> running -> succeeded|failed|interrupted`。
- 不把它做成通用任务队列；第一版只服务 recovery 可观测性。

最小 API 合同：

- `GET /api/v1/tasks/{id}/recovery_jobs?limit=10`
  - 返回该 task 最近 recovery job。
- `TaskRecoveryJob` 字段：
  - `id`
  - `task_id`
  - `session_id`
  - `status`
  - `mode`
  - `recommended_action`
  - `target_worker`
  - `recovery_execution_mode`
  - `failure_class`
  - `provider_failure_class`
  - `status_url`
  - `accepted_at`
  - `started_at`
  - `completed_at`
  - `error_message`
  - `metadata`

实现边界：

- 不新增 worker 队列调度器；仍由进程内虚拟线程执行。
- 进程崩溃或 harness 重启时，启动 reconciler 会把遗留的 `accepted/running` job 标记为 `interrupted`，并写入短错误摘要；它不自动重放恢复动作，避免重启后重复执行有副作用的任务。
- 同步 `/recover` 第一版不落 job，避免改变旧合同。
- `GET /recovery_jobs` 是观测接口，不触发恢复动作。

当前结论：

- `recover?async=true` 已落 `TaskRecoveryJob`，`request_id` 与 job id 复用。
- `GET /api/v1/tasks/{id}/recovery_jobs?limit=10` 已是只读观测接口，不触发恢复动作。
- 不可恢复的环境阻断类失败会在入队前同步拒绝，不创建后台 job。
- 启动时会调用 `TaskRecoveryJobDao.markActiveJobsInterrupted(...)`，把上一次进程留下的 `accepted/running` job 收束成 `interrupted`，避免最近恢复 job 永久显示“还在跑”。
- 默认 UI 走异步恢复，因此 `target_worker / auto_handoff_target` 的 handoff 语义也必须在 async 路径成立：job 要记录 `recommended_action=handoff / target_worker=<worker>`，后台恢复完成后任务的 `assigned_worker` 应切到目标 worker。
- 回归测试不能只断言 `202 accepted` 后立即关闭 fixture；即使业务语义是“HTTP 不等待 worker”，测试也应通过 `/recovery_jobs` 等待后台 job 进入终态，避免把真实的后台更新竞态隐藏成偶发日志噪声。

最低回归：

- `recover?async=true` 返回的 `request_id` 可以在 `/recovery_jobs` 里查到。
- 后台恢复成功时 job 状态最终为 `succeeded`。
- 后台恢复抛异常时 job 状态最终为 `failed` 且保留脱敏错误摘要，不能把 provider 原始长输出或敏感异常完整落库。
- 环境阻断类不可恢复任务不会创建 job。
- 重启恢复时，遗留 `accepted/running` job 会变成 `interrupted`，`completed_at` 被填充，`error_message` 说明进程重启导致后台恢复中断。
- `recover?async=true` 携带 `target_worker` 或任务 metadata 带 `auto_handoff_target` 时，不能退化成普通 resume；job 与最终任务状态都要体现 handoff。

#### 2026-05-18 实施验证

- 已确认 `TaskRecoveryJob`、`TaskRecoveryJobDao`、`schema.sql.task_recovery_jobs` 与 `DatabaseManager` RowMapper 已落地；`Main` 启动时调用 `markActiveJobsInterrupted(...)` 收束遗留 `accepted/running` job。
- 已通过 `TaskHandlerControlActionHttpTest.postRecoverAsyncAutoHandoffUsesTargetWorkerAndRecordsJob`，锁定 async handoff 会写入 job，job 最终进入 `succeeded`，并保留 `recommended_action=handoff / target_worker=<worker>`。
- 已通过 `TaskHandlerControlActionHttpTest.postRecoverAsyncRecordsFailedJobWithSanitizedErrorSummary`，锁定后台恢复异常会写入 `failed` job，错误摘要会截断并脱敏，不能把 provider 原始长输出完整落库。
- 已通过 `TaskHandlerControlActionHttpTest.recoveryJobDaoMarksActiveJobsInterruptedOnStartupReconcile`，锁定启动收束会把残留 active job 标记为 `interrupted` 并填充 `completed_at / error_message`，且 `/api/v1/tasks/{id}/recovery_jobs` 能直接读回 interrupted 状态和错误摘要。
- 已通过真实 HTTP 探针 `scripts/Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -ReportPath .tmp\task-recovery-acceptance-probe-include-resume-20260518.json -IncludeResumeExecution`，锁定返回的 `request_id` 可通过 `/api/v1/tasks/{id}/recovery_jobs?limit=5` 查回 matching job，报告中的 `job_status=running` 证明 job 已持久化并进入执行态。

### 5.14 Layer N：RecoveryJob 前端投影与默认异步恢复

#### 背景

Layer L/M 已经让 `recover?async=true` 快速返回 `request_id`，并把后台恢复状态落到 `TaskRecoveryJob`。但如果 Console / Dialogue 仍然调用同步 `/recover`，或者任务详情页不显示最近 job，operator 还是会遇到两个问题：

- 点击“自动恢复”后浏览器等待真实 worker，体感像卡住。
- 已返回 `request_id` 后，页面上看不到这次恢复是否进入 `accepted / running / succeeded / failed / interrupted`。

#### 设计结论

- `/console/` 与 `/dialogue/` 的 `recover` 操作默认走 `POST /api/v1/tasks/{id}/recover?async=true`。
- 点击后 toast 显示 `request_id`，同时刷新 task live flow 与最近 recovery jobs。
- 任务详情页加载时额外读取 `GET /api/v1/tasks/{id}/recovery_jobs?limit=5`，只读展示最近 job，不触发恢复动作。
- 展示信息保持短平：最近 job 的 status、request id、recommended action、execution mode、target worker、failure class、accepted/started/completed 时间与脱敏错误摘要。

#### 验收入口

- 有最近异步恢复 job 的任务，在 `/console/` 与 `/dialogue/` 详情页都能看到 `Recovery Job` 概览卡。
- UI 点击“自动恢复”时，Network 应请求 `recover?async=true`，不可退回同步 recover。
- `request_id` 能在 `/api/v1/tasks/{id}/recovery_jobs?limit=5` 中查回。
- 当异步恢复 job 是 handoff 时，`/console/` 与 `/dialogue/` 共享的 Recovery Job plan 必须直接显示 `Action=handoff` 和目标 worker chip，避免 operator 只能从 raw JSON 判断是否真的切 worker。

#### 2026-05-18 实施验证

- 已确认 `/console/` 与 `/dialogue/` 的 recover 操作都会把动作路径改写为 `recover?async=true`，不会再把按钮请求绑定到真实 worker 长执行。
- 已确认两个前端都会在任务详情加载时读取 `/api/v1/tasks/{id}/recovery_jobs?limit=5`，并复用 `buildRecoveryJobPlan(...)` 渲染 `Recovery Job` 概览。
- 已通过 `dialogue-task-action-plan.test.mjs`，锁定 `waiting_human / human_gate` 与 failed interrupted 任务的 primary action 都是 `recover`。
- 已通过 `dialogue-recovery-job-plan.test.mjs`，锁定最近异步 job、失败错误摘要、handoff 目标 worker、interrupted 手工关注态都能进入共享 plan。
- 已通过真实浏览器页面级 Dialogue 探针：`node --max-old-space-size=512 scripts\recovery-job-ui-probe.js --base-url http://localhost:8080 --surface dialogue --report .tmp\recovery-job-ui-probe-dialogue-20260518.json --screenshot .tmp\recovery-job-ui-probe-dialogue-20260518.png`。
- 已通过真实浏览器页面级 Console 探针：`node --max-old-space-size=512 scripts\recovery-job-ui-probe.js --base-url http://localhost:8080 --surface console --report .tmp\recovery-job-ui-probe-console-20260518.json --screenshot .tmp\recovery-job-ui-probe-console-20260518.png`。
- 两个 surface 都确认 recover 请求走 `/recover?async=true`，body 为 `{"mode":"auto",...}`，并能渲染 `Recovery Job running / fresh_session_resume recovery_probe_request`。
- 探针证据文件：`.tmp/recovery-job-ui-probe-dialogue-20260518.json`、`.tmp/recovery-job-ui-probe-dialogue-20260518.png`、`.tmp/recovery-job-ui-probe-console-20260518.json`、`.tmp/recovery-job-ui-probe-console-20260518.png`。

### 5.15 Layer O：Tool trace 字段合同收硬

#### 背景

长任务要判断“有没有继续执行、执行到哪一轮、改了哪些文件”，不能只看 worker 自由文本。`ToolInvocationRecord` 当前已经有真实落库字段：

- `execution_id`
- `status`
- `touched_paths`

这些字段能把一个 worker round、具体工具调用和被触达路径串起来，是后续 runtime judgment 判断“是否仍需继续 / 是否需要人工验收”的基础证据。

#### 当前结论

- `tool_invocations` schema 已包含 `execution_id / status / touched_paths_json`。
- `ToolInvocationRecord` record、DAO、RowMapper 已按这些字段读写。
- `ToolAwareWorkerExecutor` 已为工具调用生成 `execution_id`，并从 tool arguments / trace metadata 提取 `touched_paths`。
- `RuntimeFactSetAssembler` 已能从最近 tool invocations 推导 execution boundary。

#### 当前结论

- `/api/v1/tasks/{id}/tool_trace` 已稳定返回 `execution_id / status / touched_paths`，这些信息不再只存在于 `metadata`。
- `TaskHandlerControlActionHttpTest.toolTraceExposesExecutionStatusAndTouchedPaths` 已覆盖 HTTP 层字段合同。
- `RuntimeFactSetAssembler` 已用这些字段推导 execution boundary，experiment evidence 也会指向 `tool_execution_ids / tool_trace_path`。
- `/dialogue/` 与 `/console/` 的 details tool trace 摘要已优先显示 `status / execution_id / touched_paths`，再补充 `result_summary`，避免从自由文本里猜执行轮次和路径。
- 前端验证不应只用正则扫源码；`toolTraceSummary(...) / toolTraceStatusLabel(...)` 应抽成共享 plan，通过行为级 Node 测试证明 structured fields 的输出顺序和截断规则。

#### 验收入口

- `TaskHandlerControlActionHttpTest.toolTraceExposesExecutionStatusAndTouchedPaths`
  - `execution_id = exec_tool_trace_contract`
  - `status = succeeded`
  - `touched_paths = [docs/output.md]`
- `node --test src/test/js/tool-trace-details-plan.test.mjs`
  - `buildToolTraceSummary(...)` 优先拼出 `exec <id>` 与 `paths <path>`
  - 最多展示 3 条路径并用 `+N` 标记剩余路径
  - `buildToolTraceStatusLabel(...)` 优先使用结构化 `status`
  - `/dialogue/` 与 `/console/` details 摘要都调用共享 helper

#### 2026-05-18 实施验证

- 已通过 `TaskHandlerControlActionHttpTest.toolTraceExposesExecutionStatusAndTouchedPaths`，锁定 `/api/v1/tasks/{id}/tool_trace` 直接返回 `execution_id=exec_tool_trace_contract`、`status=succeeded`、`touched_paths=["docs/output.md"]`。
- 已通过 `tool-trace-details-plan.test.mjs`，锁定前端摘要优先使用结构化 `execution_id / touched_paths / result_summary`，并限制 noisy path list 为 3 条加 `+N`。
- 已确认 `/dialogue/` 与 `/console/` 都从共享 `tool-trace-plan.js` 导入 `buildToolTraceSummary(...)` 与 `buildToolTraceStatusLabel(...)`，避免两个页面分别拼字段导致合同漂移。

### 5.16 Layer P：Dispatch readiness 诊断不退化

#### 背景

分发前 readiness 已经支持 `mode=dispatch`，可以在真正把任务交给 provider 前做一次轻量验活。这个机制对长任务很关键：如果 provider 已经出现 `thread not found`、无法启动 fresh turn、超时等问题，router 应该在分发前跳过它。

当前实现已经会跳过 dispatch preflight 失败的 worker，但诊断链路容易退化：筛选阶段先调用 dispatch readiness，失败后 worker 被标记为 temporary unavailable；后续解释 skipped worker 时如果重新查 passive readiness，原因就可能只剩 `temporarily unavailable`，而不是原始 dispatch preflight 失败原因。

#### 设计结论

- `WorkerRouter` 在候选筛选时应保留每个候选 worker 的 dispatch readiness 结果。
- `fallback_reason` 应直接使用同一轮 dispatch readiness 的 `reason / dispatch_preflight_reason`，不要在解释阶段重新查询 passive readiness。
- 当强模型 tier 因 dispatch preflight 失败而降级到其他 tier 时，`fallback_reason` 需要同时说明：
  - 哪个 worker 被跳过
  - 原始 preflight 失败原因
  - 是否发生了 tier fallback
- 同时 `route_reason` 不能继续声称“按原 preferred tier 选中”，否则前端会把实际降级选择展示成强模型命中。
- 当前普通候选筛选、pinned worker 和 learning memory hint 三条入口都已经按这条口径实现并测试：
  - `WorkerRouter` 会保留本轮 `dispatchReadinessByWorker`。
  - `explainDispatchReadinessFallback(...)` 优先使用 `dispatch_preflight_reason`。
  - pinned worker fallback 会使用 dispatch readiness，而不是 passive readiness。
  - learning memory hint fallback 会先检查 dispatch readiness，并优先使用 `dispatchPreflightReason()`。
- 因此 `thread not found during dispatch preflight` 不会退化成泛化的 `temporarily unavailable` 或 `not ready`。

#### 验收入口

- `WorkerRouterRouteTraceTest.routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses`
  - `selected_worker` 不再是失败 worker
  - `fallback_reason` 包含 `dispatch readiness skipped worker(s)`
  - `fallback_reason` 包含原始 `thread not found during dispatch preflight`
  - `fallback_reason` 包含 `no dispatch-ready worker matched preferred model tier=strong`
  - `route_reason` 不包含 `model tier preference (strong)`
- `WorkerRouterRouteTraceTest.pinnedWorkerDispatchFailureKeepsOriginalPreflightReason`
- `WorkerRouterRouteTraceTest.learningMemoryHintDispatchFailureKeepsOriginalPreflightReason`

#### 2026-05-18 实施验证

- 已通过 `WorkerRouterRouteTraceTest.routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses`，锁定普通候选筛选会跳过 dispatch preflight 失败的 `codex`，选择 `kimi`，并保留原始 `thread not found during dispatch preflight`。
- 已通过 `WorkerRouterRouteTraceTest.pinnedWorkerDispatchFailureKeepsOriginalPreflightReason`，锁定 pinned worker 失败时不会把原始 preflight reason 退化成泛化 `temporarily unavailable`。
- 已通过 `WorkerRouterRouteTraceTest.learningMemoryHintDispatchFailureKeepsOriginalPreflightReason`，锁定 learning memory hint 指向的 worker dispatch 失败时，hint 不会被应用，fallback reason 仍保留原始 preflight 失败文本。
- 验证注意：Surefire 的 `-Dtest=Class#methodA+Class#methodB` 在当前环境只实际执行了 1 个方法；涉及多个关键方法时应拆成独立命令，不能用一次组合选择结果作为多用例通过证据。

### 5.17 Layer Q：本地工作区访问拒绝不能停在 active/scheduler

#### 背景

2026-05-18 继续调试 `session_2b11c93d9dcd439c / task_82707e4f80214ad5` 时，现场证据显示：

- task 已正确归一化为 `task_type=coding`，不再是旧的 `continuation`。
- 第一阶段 `codex` 仍出现 `thread not found (27316)`，恢复链切到 `deepseek`，并写入 `recovery_execution_mode=fresh_session`。
- `deepseek` 返回 `completed`，但内容是“无法直接访问本地文件或路径，请补充/粘贴文档内容”。
- planner gate 记录 `planner_delegation_gate=rejected / missing_compact_brief`，但任务最终停在 `active / scheduler`，没有进一步 worker round，也没有进入 human gate。

这说明已有 planner gate 只能阻止坏输出被当作 delegation brief，却没有把“worker 明确不具备本地工作区访问能力”的 completed 文本转成恢复状态。对本地代码仓库长任务来说，这不是正常完成，也不是可等待的 active 状态。

#### 设计结论

- `completed` 只代表 provider 进程正常返回，不等价于任务语义成功。
- 当任务是 `coding` 或上下文明显指向本地 repo，且 worker 输出包含以下信号时，应视为 `worker_backend_deterministic`：
  - `无法直接访问本地文件或路径`
  - `无法访问本地`
  - `请粘贴文档内容`
  - `cannot access local files/path/workspace/repository`
  - `paste the document/content`
- 该类输出应触发 recovery decision：
  - 若自动 handoff 预算仍有剩余，切到下一个可本地执行的 coding worker。
  - 若预算已耗尽，进入 `waiting_human / human_gate`，而不是保留 `active / scheduler`。
- 这类失败摘要必须保持短文本投影，不能把 provider 原始长输出完整塞进 live_flow/message metadata，避免大输出继续放大 JVM 内存压力。

#### 验收入口

- `ControlNodeGraphOrchestrationFlowTest.localWorkspaceAccessRefusalDoesNotLeaveTaskActiveScheduler`
  - worker 返回 `completed` 但拒绝访问本地路径。
  - `planner_delegation_gate=rejected`
  - `planner_delegation_gate_reason=local_workspace_access_refusal`
  - 自动恢复预算耗尽时，最终状态必须是 `waiting_human / human_gate`。

#### 2026-05-18 实施验证

- 已修正 `ControlNodeGraph.maybePlanFailureRecovery(...)`：即使 worker `execution_status=completed`，只要当前输出符合本地工作区访问拒绝信号，也会进入 recovery 分类，而不是只等 planner gate 兜底。
- 已调整 `classifyFailureClass(...)` 优先级：本地工作区访问拒绝优先归为 `worker_backend_deterministic`，不会被历史/合并 metadata 里的 `provider_runtime_transient` 覆盖。
- 已新增并通过 `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoverySchedulesAutoHandoffForLocalWorkspaceAccessRefusal`，锁定“无法直接访问本地文件或路径”会触发 deterministic backend failure 和 auto handoff。
- 已加强并通过 `ControlNodeGraphOrchestrationFlowTest.localWorkspaceAccessRefusalDoesNotLeaveTaskActiveScheduler`，锁定最终进入 `waiting_human / human_gate`，并保留 `failure_class=worker_backend_deterministic` 与 `planner_delegation_gate_reason=local_workspace_access_refusal`。

### 5.18 Layer R：本地工作区任务的 worker 资格与 Codex thread 诊断

#### 现场结论

2026-05-18 对 `session_2b11c93d9dcd439c / task_82707e4f80214ad5` 继续排查，窄口径证据来自：

- `GET /api/v1/tasks/task_82707e4f80214ad5/agent_run`
- `GET /api/v1/agent_runs?task_id=task_82707e4f80214ad5&limit=20`
- `GET /api/v1/agent_runs/arun_e598a6bfa2ba4f36`

结论：

- 明确返回“无法直接访问本地文件或路径”的 worker 是 `deepseek`。
- 该 run 为 `arun_b2ac66c131ca4dab`，`provider_id=deepseek`，`execution_backend=provider_native_cli`，`cli_binary=C:\nvm4w\nodejs\deepseek.cmd`，`cli_cwd=D:\gitAll\agent-cloud-harness`。
- `F:\github\DeepSeek-TUI` 的 README 与 `deepseek doctor` 均显示 DeepSeek TUI 具备本地 workspace 读写能力；`doctor` 输出的 workspace 取自当前进程工作目录。
- 因此这次失败不是 `deepseek` 天生不能读本地文件，而是 harness 当时把 provider-native CLI 启在 `D:\gitAll\agent-cloud-harness`，没有把目标仓库 `D:\gitAll\articleeditor` 作为 cwd/workspace 传进去。
- 当前调用形态是 `deepseek --provider deepseek exec <prompt>`，缺少 `--skip-onboarding / --yolo` 等非交互执行保护参数；如果进入审批/首次启动/错误 cwd，上层只会拿到自由文本失败。

Codex 侧结论：

- `codex` 的 run `arun_e598a6bfa2ba4f36` 并非一开始没有 thread；metadata 中已有 `provider_thread_id=019e39cb-21f3-7963-8cbf-cb2441abaf52`，protocol trace 包含 `thread/started`、`turn/started` 和大量 `item/commandExecution/outputDelta`。
- 该 run 真实终态是 `timeout`，`provider_error=codex turn completion timed out`，`provider_turn_status=timeout`，`tool_invocation_count=24`。
- 摘要里的 `thread not found (19340/27316)` 是 Codex 输出流或命令输出中的错误文本被并入 worker artifact / failure summary，不是 harness 在分发第一步就无法找到 `.codex` 线程。
- 用户在 Codex 端能看到 thread，但 harness 仍收到或展示 `thread not found`，合理解释是：Codex app-server 的运行时 thread/turn 生命周期与 `.codex` 持久化历史不是同一层；app-server 超时/进程被 harness 终止后，后续 resume/输出清理可能引用了已经不在当前 app-server 运行时里的短线程标识。

#### 设计结论

- Worker 的 `coding` capability 不能等价为“可访问本地文件”。
- 对目标文本中包含本地路径、本地仓库、`D:\gitAll\...`、`src/main`、`pom.xml` 等信号的 coding 任务，router 应额外要求 worker 声明 `local_workspace_access=true`。
- `deepseek` 应声明 `local_workspace_access=true / workspace_access_mode=native_cli_cwd`，但前提是 executor 必须把 cwd 设成目标 repo。
- Learning Memory hint 不能覆盖本地工作区访问资格；如果 hint 指向无本地能力 worker，应写入 `fallback_reason`，并继续选择具备本地工作区能力的 worker。
- ChatFacade 创建任务时，如果用户输入或 metadata 中出现本地文件路径，应推导并写入 `workspace_root / workspace / working_directory / cwd`，让 Codex 与支持 workspace 参数的 CLI 在目标仓库执行，而不是默认落在 harness 自身仓库。
- Provider-native executor 也需要兜底从 task goal/title/intent 中提取 `D:\gitAll\<repo>`，避免历史任务缺少 `workspace_root` 时仍回退到 harness cwd。
- DeepSeek TUI 的非交互调用应在 `exec` 前传入全局参数 `--skip-onboarding --yolo --provider deepseek`，并通过 `ProcessBuilder.directory(cwd)` 进入目标 repo。
- 对 Codex 长输出超时，不应只把 `thread not found` 这种被截断/乱码的输出文本当作根因；根因应优先取 provider metadata 中的 `provider_error / provider_failure_reason / provider_turn_status`。
- 如果用户输入或 metadata 中出现多个本地 workspace，不能把第一个 workspace 简单写成全局 `cwd` 后直接执行：
  - ChatFacade 应提取所有可能本地目标，写入 `workspace_roots`。
  - 多 workspace 时创建一个父任务作为编排锚点，父任务标记 `split_parent=true / split_reason=multiple_local_workspaces`，默认不直接跑 worker。
  - 每个 workspace 创建一个子任务，子任务分别写入自己的 `workspace_root / workspace / working_directory / cwd`。
  - 子任务写入 `split_child=true / split_parent_task_id / task_scope_index / task_scope_total`，并沿用原始 `auto_start`。
  - 这样 provider-native CLI 每次只在一个确定 cwd 下执行，避免跨仓库误读写。
- 本地代码任务识别不能只依赖“修改/实现/重构”这类强动作词；“检查仓库/排查 src/补测试/验证工程”同样会启动本地文件读取与代码验证，也应在出现 repo path、`src`、`pom.xml/package.json`、测试等代码信号时提升为 `coding`。

#### 验收入口

- 本地路径 coding 任务中，`deepseek` 的 learning memory hint 可以被应用，但必须在 metadata/exec 侧确认 cwd 指向目标 repo。
- 默认 `codex` worker metadata 应声明 `local_workspace_access=true`，`deepseek` 应声明 `local_workspace_access=true / workspace_access_mode=native_cli_cwd`。
- ChatFacade 输入包含 `D:\gitAll\articleeditor\docs\xxx.md` 时，新建 task metadata 应包含 `workspace_root=D:\gitAll\articleeditor`。
- ChatFacade 输入同时包含 `D:\gitAll\articleeditor` 与 `D:\gitAll\agent-cloud-harness` 时，应创建 1 个父任务和 2 个子任务；两个子任务的 `cwd` 分别指向自己的 repo。
- ChatFacade 输入“检查 `D:\gitAll\<repo>\src` 并补测试”时，父子任务都应按 `coding` task type 建立。
- Provider-native executor 在缺少 workspace metadata 但 goal 包含 `D:\gitAll\articleeditor\docs\xxx.md` 时，应把 `cli_cwd` 解析成 `D:\gitAll\articleeditor`。
- Codex 超时 run 的诊断应展示 `provider_error=codex turn completion timed out`，不要把输出流里的 `thread not found` 误当成唯一根因。

#### 2026-05-18 实施验证

- 已确认 `WorkerRegistry` 默认声明：`codex.local_workspace_access=true`，`deepseek.local_workspace_access=true / workspace_access_mode=native_cli_cwd`。
- 已确认 `ChatFacadeService` 会从 request metadata、用户输入、goal/intent/title 中抽取全部 Windows 本地路径，归一化为 `workspace_roots`；多 workspace 时创建父任务和按 workspace 拆分的子任务。
- 已确认 `ProviderCliWorkerExecutor` 会优先使用 task metadata 中的 `cwd/workspace/working_directory/workspace_root`，缺失时从 task goal/title/summary/metadata 中兜底推导 `D:\gitAll\<repo>`。
- 已确认 DeepSeek facade binary 调用会包含 `--skip-onboarding --yolo --provider deepseek exec <prompt>`，并通过 `ProcessBuilder.directory(cwd)` 进入目标 repo。
- 已补充负向路由合同：本地 workspace coding 任务中，如果 learning memory hint 指向 `local_workspace_access=false` 的 worker，hint 不能覆盖本地 workspace 资格，fallback reason 必须解释该 worker 不在当前候选集。
- 已通过以下窄回归：
  - `ChatFacadeHandlerHttpTest.postChatCompletionInfersCodingTaskTypeForRepoModificationRequests`
  - `ChatFacadeHandlerHttpTest.postChatCompletionSplitsMultipleLocalWorkspacesIntoChildTasks`
  - `ProviderCliWorkerExecutorTest.deepSeekPlanUsesFacadeProviderFlagsAndModelOverride`
  - `ProviderCliWorkerExecutorTest.deepSeekResolvesWorkingDirectoryFromGoalPathWhenWorkspaceMetadataMissing`
  - `WorkerRouterRouteTraceTest.localWorkspaceCodingTaskCanUseDeepseekWhenLearningHintHasWorkspaceAccess`
  - `WorkerRouterRouteTraceTest.defaultWorkersDeclareObservedLocalWorkspaceAccessBoundary`
  - `WorkerRouterRouteTraceTest.localWorkspaceCodingTaskRejectsLearningHintWithoutWorkspaceAccess`

### 5.19 Layer S：AgentRun 失败诊断优先级

#### 背景

Layer R 的现场排查已经确认：Codex app-server 长任务里，`thread not found (...)` 可能只是命令输出流或超时后的残留文本被并入 worker artifact / summary，并不一定代表 harness 分发时找不到 `.codex` thread。更可靠的根因来自 worker metadata 中的结构化字段：

- `provider_error`
- `provider_turn_status`
- `provider_failure_class`
- `provider_failure_reason`

如果 `agent_runs.summary` 仍优先取 `WorkerExecutionResult.summary/outputText`，operator 在 `/agent_runs`、`/tasks/{id}/agent_run`、runtime health 最近失败列表里会先看到输出流噪声，而不是 provider 层真实错误。

#### 设计结论

- `AgentRunService.recordWorkerRun(...)` 应把 `provider_error / provider_turn_status` 从 `WorkerExecutionResult.metadata` 上提到 `AgentRunRecord.metadata` 顶层，和已有 `provider_failure_class / provider_failure_reason` 保持同一观测层级。
- 对 provider failure 状态（`failed / timeout / blocked / empty / unknown`）的 run，`AgentRunRecord.summary` 应优先使用：
  - `provider_error`
  - `provider_failure_reason`
  - 原 `result.summary`
  - `result.outputText`
  - `result.artifactContent`
- 对 completed run 不改变现有摘要优先级，避免正常产物摘要被 provider metadata 抢占。
- 摘要仍保留 500 字符截断，避免大输出继续进入列表接口和 UI 首屏。

#### 验收入口

- `AgentRunServiceTest.recordWorkerRunPrefersProviderErrorForFailedDiagnostics`
  - worker output 中包含 `thread not found: 27316`
  - metadata 中包含 `provider_error=codex turn completion timed out`
  - 最终 `AgentRunRecord.summary` 必须是 `codex turn completion timed out`
  - 顶层 metadata 必须包含 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason`
- `TaskHandlerProviderSelectionHttpTest.agentRunEndpointSurfacesProviderErrorDiagnostics`
  - 通过 `AgentRunService.recordCompletedWorkerRun(...)` 生成 timeout run
  - `GET /api/v1/tasks/{id}/agent_run` 返回的 `summary` 必须优先展示 `provider_error`
  - HTTP 返回的 `metadata.provider_error / provider_turn_status` 必须在顶层可见

#### 2026-05-18 实施验证

- 已确认 `AgentRunService.recordWorkerRun(...)` 会把 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason` 从 worker result metadata 提升到 `AgentRunRecord.metadata` 顶层。
- 已确认 provider failure 状态的 `summary` 优先级为 `provider_error -> provider_failure_reason -> result.summary -> result.outputText -> result.artifactContent`，且保持 500 字符截断。
- 已通过 `AgentRunServiceTest.recordWorkerRunPrefersProviderErrorForFailedDiagnostics`，锁定 worker output 包含 `thread not found: 27316` 时，失败 run 的 summary 仍优先显示 `codex turn completion timed out`。
- 已通过 `TaskHandlerProviderSelectionHttpTest.agentRunEndpointSurfacesProviderErrorDiagnostics`，锁定 `GET /api/v1/tasks/{id}/agent_run` 返回的 `summary` 与顶层 `metadata.provider_error / provider_turn_status` 都优先展示结构化 provider 根因。

### 5.20 Layer T：RecoveryPlan 证据优先使用结构化 provider error

#### 背景

Layer S 已经让 `agent_runs` 和 `/tasks/{id}/agent_run` 优先展示 `provider_error`。但恢复计划本身仍存在一个解释缺口：`TaskService.buildRecoveryPlan(...)` 读取 `provider_failure_class` 后，如果该 class 已存在，当前 `failure_evidence_source / failure_evidence` 可能只显示 class 名本身，而不是结构化 provider 根因。

这会让 `/api/v1/tasks/recoverable` 和 `recover?async=true` job metadata 虽然知道“可恢复”，但 operator 仍看不到更直接的原因，例如 `codex turn completion timed out`。

#### 设计结论

- `resolveProviderFailureEvidence(...)` 在命中 `provider_failure_class` 时，也应优先查找同一来源的结构化原因：
  - request: `provider_error / provider_failure_reason`
  - task metadata: `provider_error / provider_failure_reason`
  - latest agent run metadata: `provider_error / provider_failure_reason`
- source 应精确到具体字段，例如 `agent_run.metadata.provider_error`，避免只显示 `agent_run.metadata.provider_failure_class`。
- 如果没有结构化原因，才回退为 class 值本身，保持旧合同兼容。
- 后续 `TaskRecoveryJob.metadata.failure_evidence` 会自然继承这条计划证据，不需要新增 job 字段。

#### 验收入口

- `TaskHandlerControlActionHttpTest.getRecoverableTasksUsesAgentRunProviderErrorAsFailureEvidence`
  - latest agent run metadata 同时有 `provider_failure_class=provider_runtime_transient` 和 `provider_error=codex turn completion timed out`
  - `GET /api/v1/tasks/recoverable` 返回：
    - `provider_failure_class=provider_runtime_transient`
    - `failure_evidence_source=agent_run.metadata.provider_error`
    - `failure_evidence=codex turn completion timed out`

#### 2026-05-18 实施验证

- 已修改 `TaskService.resolveProviderFailureEvidence(...)`：当 request/task metadata/latest agent run metadata 中存在 `provider_failure_class` 时，先用同源 `provider_error`，再用同源 `provider_failure_reason`，最后才回退到 `provider_failure_class`。
- 已新增并通过 `TaskHandlerControlActionHttpTest.getRecoverableTasksUsesAgentRunProviderErrorAsFailureEvidence`，锁定 `/api/v1/tasks/recoverable` 暴露 `agent_run.metadata.provider_error`。
- 已复跑 `TaskHandlerControlActionHttpTest.getRecoverableTasksClassifiesProviderFailureFromWaitingReasonEvidence`，确认旧的 waiting reason 文本分类路径仍可用。
- 已复跑 `AgentRunServiceTest`，确认 Layer S 的 AgentRun provider error 顶层投影仍可用。

### 5.21 Layer U：dispatch preflight 必须区分主动探测与被动复用

#### 背景

Layer J/P 已经把分发前 readiness 串进 router 和 scheduler，但当前 `AgentProvider.dispatchPreflight()` 默认实现仍是 `refreshStatus()`。这会带来一个隐藏风险：

- `--version` / `doctor` / binary exists 只能证明 CLI 存在或配置基本可读。
- 它不能证明 provider 现在能接受 fresh turn、不会卡 onboarding、不会因会话状态返回 `thread not found`、不会立即超时。
- 如果观测面只看到 `dispatch_preflight_ready=true`，operator 会误以为“已经发过测试语句/真实验活”。

用户提出的“分发任务前 worker 可用状态需要准确判断，建议发条测试语句，或者其他方式机制”不能一步到位地默认对所有 provider 发真实 LLM 请求：真实测试语句可能付费、耗时、写入 provider 历史，甚至触发审批/工具权限。因此第一阶段必须先把 preflight 的强弱口径暴露出来，并给严格分发留开关。

#### 设计结论

- `AgentProvider.dispatchPreflight()` 的结果必须在 metadata 中声明：
  - `dispatch_preflight_mode=active_probe`：provider 实现了主动 fresh-turn/handshake 探测。
  - `dispatch_preflight_mode=passive_status`：只是复用 detect/refresh 状态，不能等价为真实可分发。
- `WorkerRegistry.ReadinessCheck` 与 `/workers/{id}/readiness?mode=dispatch` 必须投影 `dispatch_preflight_mode`。
- Router / scheduler 默认仍允许 `passive_status` 通过，保持兼容。
- 增加 JVM 开关 `-Dagentcloud.dispatch.preflight.require_active_probe=true`：
  - 开启后，provider-backed worker 只有 `dispatch_preflight_mode=active_probe` 且 ready 才能分发。
  - passive fallback 必须返回不可分发，reason 明确为 `dispatch preflight active probe required but provider returned passive_status`。
- 后续为 Codex app-server / provider-native CLI 实现真实测试语句时，只需要在对应 provider 覆盖 `dispatchPreflight()` 并标记 `active_probe`；严格模式会自动接入。

#### 验收入口

- `AgentProviderSupportTest.dispatchReadinessProjectsPassiveFallbackProbeMode`
  - 未覆盖 `dispatchPreflight()` 的 provider 在 dispatch readiness 下返回 `dispatch_preflight_mode=passive_status`。
- `AgentProviderSupportTest.dispatchReadinessStrictModeRejectsPassiveFallbackProbe`
  - 开启 `agentcloud.dispatch.preflight.require_active_probe=true` 后，passive fallback 不可分发。
  - reason 必须说明 active probe required。
- `AgentProviderSupportTest.dispatchReadinessStrictModeAllowsActiveProbe`
  - 覆盖 `dispatchPreflight()` 且 metadata 声明 `active_probe` 的 provider 在严格模式下可分发。

#### 2026-05-18 实施验证

- 已修改 `AgentProvider.dispatchPreflight()` 默认实现：未覆盖主动探测的 provider 会复用 `refreshStatus()`，但 metadata 显式标记 `dispatch_preflight_mode=passive_status / dispatch_preflight_note=provider did not implement active dispatch probe`，避免 operator 误以为已经发过真实测试语句。
- 已修改 `WorkerRegistry.ReadinessCheck`：`GET /api/v1/workers/{id}/readiness?mode=dispatch` 会投影 `dispatch_preflight_mode`。
- 已新增 JVM 开关 `-Dagentcloud.dispatch.preflight.require_active_probe=true`：严格模式下 provider-backed worker 如果只返回 `passive_status`，会被标记不可分发，reason 为 `dispatch preflight active probe required but provider returned passive_status`。
- 已通过 `AgentProviderSupportTest.dispatchReadinessProjectsPassiveFallbackProbeMode`、`dispatchReadinessStrictModeRejectsPassiveFallbackProbe`、`dispatchReadinessStrictModeAllowsActiveProbe`，锁定 passive fallback、严格拒绝和 active probe 放行三条核心语义。
- 已通过 `ApiErrorContractHttpTest.workerReadinessDispatchModeProjectsPreflightFields` 与 `workerReadinessDispatchModeProjectsPassiveFallbackProbeMode`，锁定 HTTP 层同时返回 `dispatch_preflight_ready / dispatch_preflight_reason / dispatch_preflight_mode`。
- 2026-05-18 复跑 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=AgentProviderSupportTest` 与 `-Dtest=ApiErrorContractHttpTest` 时，脚本实际执行 Maven 全量测试；最终分别显示 `Tests run: 382` 与 `Tests run: 383`，均 `Failures: 0, Errors: 0, Skipped: 0`。后续如需真正窄跑，应直接确认脚本是否透传 `-Dtest` 参数。

### 5.22 Layer V：Java 21 测试脚本必须真实透传 Surefire 窄跑参数

#### 背景

Layer U 验证时使用了：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=AgentProviderSupportTest`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=ApiErrorContractHttpTest`

但脚本实际执行的是 `mvn test`，没有把 `-Dtest=...` 传给 Maven，导致两次都跑成全量测试。这个问题本身不会让代码错误，但会影响长任务调试效率和证据可信度：

- 文档写“窄回归”，实际证据却是全量测试。
- 调试某一层时每次都跑 1 分多钟，降低迭代速度。
- 如果后续需要逐个方法复跑，脚本不透传参数会掩盖 Surefire 选择器是否真的生效。

#### 设计结论

- `Test-WithJava21.ps1` 保留原有 `-MavenArgs` 参数。
- 同时兼容直接追加 Maven/Surefire 参数，例如：
  - `.\scripts\Test-WithJava21.ps1 -Dtest=AgentProviderSupportTest`
  - `.\scripts\Test-WithJava21.ps1 -DskipTests=false -Dtest=ApiErrorContractHttpTest`
- 脚本输出的 `Running:` 行必须能看到最终 Maven 参数，方便排查是否真的透传。
- 仍不并行运行 Maven；同一模块测试必须顺序执行，避免之前出现的 `target/classes` 并发写入问题。

#### 验收入口

- `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=AgentProviderSupportTest`
  - `Running:` 行应包含 `test -Dtest=AgentProviderSupportTest`
  - Surefire 结果应只出现 `AgentProviderSupportTest` 对应测试数，不应再跑全量 380+。

#### 2026-05-18 实施验证

- 已修改 `scripts/Test-WithJava21.ps1`：新增 `PassthroughMavenArgs`，通过 `ValueFromRemainingArguments` 接住直接追加的 Maven/Surefire 参数，并和原有 `-MavenArgs` 合并后执行。
- 已复跑 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=AgentProviderSupportTest`。
- 验证输出显示 `Running: ... mvn.cmd test -Dtest=AgentProviderSupportTest`，Surefire 只运行 `com.agentcloud.agent.AgentProviderSupportTest`。
- 验证结果：`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`，不再误跑全量 380+。

### 5.23 Layer W：provider-backed worker 有继续信号时不能被 tool-aware 门槛误拦

#### 背景

`shouldAutoContinueTask()` 已支持 `next_step / unfinished_items / declared rounds / auto_multi_round` 作为自动续跑信号，但当前实现先强制要求：

- `tool_aware_executor=true`
- `tool_execution_mode=multi_tool_round`

这会导致 `CodexAppServerWorkerExecutor`、`ProviderCliWorkerExecutor`、DeepSeek TUI 这类 provider-backed worker 即使真实返回了可继续线索，也会在进入信号判断前被拒绝。表面现象就是任务停在 `scheduler/continue` 附近，Codex/DeepSeek 侧能看到 thread 或本地执行痕迹，但 harness 没有继续分发下一轮。

#### 设计结论

- 自动续跑不应只绑定 harness tool-aware 执行器。
- 对 provider-backed 轮次，只要 metadata 中有明确 backend 标识即可进入同一套继续信号判断：
  - `execution_backend=provider_app_server`
  - `execution_backend=provider_native_cli`
  - 或存在 `provider_id / provider_session_id / provider_thread_id`
- 继续条件仍沿用已有安全边界：
  - `resolvedAction` 必须是 `continue`
  - 失败恢复链路仍先由 `maybePlanFailureRecovery()` 接管
  - `repeated_tool_guard / no_progress_guard / missing_required_current_round_write` 仍拒绝
  - `grounded_output_present=true` 且没有后续声明轮次时仍拒绝
  - `auto_continue_burst_count` 仍按现有预算限制
- provider-backed 的默认 burst limit 不扩大；没有声明轮次、没有 `auto_multi_round`、没有输出要求时最多自动补一轮，避免无限循环。

#### 验收入口

- 新增控制图窄测：provider-backed 第一轮返回 `execution_backend=provider_app_server` 与 `suggested_next_step`，即使没有 `tool_aware_executor/multi_tool_round`，也应自动进入第二轮。
- 第二轮返回 `grounded_output_present=true` 后，任务应进入 `done/end`，并保留一次 `auto_continue_burst_count`。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.shouldAutoContinueTask()`：自动续跑入口不再只认 `tool_aware_executor + multi_tool_round`，新增识别 `provider_app_server / provider_native_cli / provider_id / provider_session_id / provider_thread_id`。
- 已新增 `ControlNodeGraphOrchestrationFlowTest.providerBackedRoundAutoContinuesWhenNextStepIsPresent()`。
- 已复跑：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=ControlNodeGraphOrchestrationFlowTest#providerBackedRoundAutoContinuesWhenNextStepIsPresent`
  - 结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- 验证日志中可见 provider-backed 第一轮进入 `[AutoContinue] ... has_next_step=true ... can_continue=true ... burst_limit=1`，随后执行第二轮并进入 `done/end`。

### 5.24 Layer X：provider-backed 执行证据必须进入 runtime cognition surface

#### 背景

Layer W 已经允许 provider-backed worker 根据 `next_step / unfinished_items / provider_thread_id` 自动续跑。但如果 provider backend/thread/error 证据只留在 raw artifact 的 `latest_worker_metadata`，operator 仍需要翻原始 JSON 才能解释：

- 这一轮到底是 harness tool-aware、Codex app-server，还是 provider-native CLI。
- 自动续跑是基于哪个 provider thread/session。
- `thread not found` 类噪声和 `provider_error/provider_turn_status` 哪个才是结构化根因。

`live_flow.runtime_facts.execution_boundary` 与 `runtime_cognition_surface.execution` 是更稳定的诊断入口，应直接投影这些字段，避免 Layer W 的行为只停在日志和 raw metadata。

#### 设计结论

- `ControlNodeGraph.buildExecutionBoundary(...)` 应把 provider-backed 执行字段纳入 execution boundary metadata：
  - `execution_backend`
  - `provider_id`
  - `provider_session_id`
  - `provider_thread_id`
  - `resume_provider_session_id`
  - `provider_error`
  - `provider_turn_status`
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
- `RuntimeCognitionSurfaceView.ExecutionSurface` 增加同名 provider 字段，前端和 API 客户端不用再读散落 metadata。
- tool-aware 字段保持原状，provider 字段为空时不影响旧响应。

#### 验收入口

- `ControlNodeGraphActionResolutionTest.buildExecutionBoundaryKeepsProviderBackendFields`
  - 构造 provider-backed metadata。
  - 断言 `ExecutionBoundary.metadata` 保留 provider backend/thread/error。
- `TaskServiceLiveFlowViewTest.liveFlowProjectsProviderExecutionSurface`
  - 构造带 provider metadata 的 worker artifact / judgment。
  - 断言 `/live_flow.runtime_cognition_surface.execution` 投影 `execution_backend/provider_id/provider_thread_id/provider_error/provider_turn_status`。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.buildExecutionBoundary(...)`：控制图 judgment/runtime facts 的 execution boundary 会保留 provider backend/thread/error 字段。
- 已修改 `RuntimeFactSetAssembler`：live_flow/judgment trace 从 artifact 或 tool invocation 重建 runtime facts 时，同样保留 provider backend/thread/error 字段，避免只在控制图即时路径可见。
- 已修改 `RuntimeCognitionSurfaceView.ExecutionSurface`、`RuntimeCognitionSurfaceAssembler` 与 `RuntimeFactSurfaceExporter`：`runtime_cognition_surface.execution` 和导出 map 都直接投影 provider 字段。
- 已通过 `ControlNodeGraphActionResolutionTest.buildExecutionBoundaryKeepsProviderBackendFields`。
- 已通过 `TaskServiceLiveFlowViewTest.liveFlowProjectsProviderExecutionSurface`；该测试先暴露出 `RuntimeFactSetAssembler` 没有复制 `provider_error/provider_turn_status` 的读模型缺口，修正后通过。

### 5.25 Layer Y：latest worker metadata 过滤器不能丢 provider error

#### 背景

Layer X 已让 `buildExecutionBoundary(...)`、`RuntimeFactSetAssembler` 与 `runtime_cognition_surface.execution` 支持 provider-backed 证据。但控制图即时判断路径还有一个更早的过滤点：`ControlNodeGraph.selectLatestWorkerMetadata(...)` 会从 worker artifact / execution result metadata 中挑选允许进入 judgment/runtime facts 的字段。

当前该过滤器已经保留 `provider_failure_class / provider_failure_reason / provider_retryable`，但漏掉了 `provider_error / provider_turn_status`。结果是：

- `buildExecutionBoundary(...)` 虽然会复制 `provider_error / provider_turn_status`，但上游过滤后字段已经丢失。
- recovery / judgment 可以看到 failure class，却看不到更直接的结构化根因。
- `thread not found` 类原始噪声可能继续盖过 provider app-server 返回的规范错误，例如 `codex turn completion timed out`。

#### 设计结论

- `ControlNodeGraph.selectLatestWorkerMetadata(...)` 必须把 `provider_error / provider_turn_status` 纳入白名单。
- 该修复只扩大 provider diagnostic metadata 的可观测面，不改变路由、恢复预算或自动续跑条件。
- 过滤器层需要单独测试，不能只测 `buildExecutionBoundary(...)`，否则会漏掉“下游支持但上游已丢字段”的回归。

#### 验收入口

- `ControlNodeGraphActionResolutionTest.selectLatestWorkerMetadataKeepsProviderErrorDiagnostics`
  - 输入 metadata 同时包含 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason / provider_retryable`。
  - 输出 selected metadata 必须全部保留这些 provider diagnostic 字段。
  - 任意无关字段仍不得泄漏。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.selectLatestWorkerMetadata(...)`：provider-backed worker metadata 白名单补入 `provider_error / provider_turn_status`。
- 已新增并通过 `ControlNodeGraphActionResolutionTest.selectLatestWorkerMetadataKeepsProviderErrorDiagnostics`，锁定 provider error/status 不会在进入 judgment/runtime facts 前被过滤掉。
- 已复跑 `ControlNodeGraphActionResolutionTest` 类级回归：`Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`。

### 5.26 Layer Z：recovery failure summary 优先使用 provider 结构化根因

#### 背景

Layer S/T/Y 已经让 agent run、recoverable plan、runtime facts 都能看到 `provider_error / provider_failure_reason`。但控制图内部真正生成 recovery directive 时，`resolveRecoveryFailureText(...)` 的优先级仍是：

- `failure_summary_readable`
- `output_text`
- `artifact_content`
- latest output
- `tool_summary`
- `tool_plan_reason`

这会导致 Codex app-server 已经返回 `provider_error=codex turn completion timed out` 时，recovery 日志、`failure_summary_readable`、human gate 理由仍可能显示被输出流污染的 `worker codex failed: thread not found (...)`。这不是分类错误，但会让 operator 继续把噪声当根因。

#### 设计结论

- `ControlNodeGraph.resolveRecoveryFailureText(...)` 应优先读取结构化 provider 失败原因：
  - `provider_error`
  - `provider_failure_reason`
  - `provider_turn_status`
- 只有这些字段为空时，才回退到旧的 readable summary / output / artifact / latest output。
- `provider_turn_status` 单独存在时应进入可读摘要，避免只有 `timeout` 这类状态时被完全丢弃。
- 输出仍经过 `sanitizeReadableFailureSummary(...)`，保留既有 worker 前缀归一化和长度控制。

#### 验收入口

- `ControlNodeGraphActionResolutionTest.resolveRecoveryFailureTextPrefersProviderErrorOverThreadNoise`
  - 输入同时包含 `provider_error=codex turn completion timed out` 与 `failure_summary_readable=worker codex failed: thread not found (...)`。
  - 输出必须优先包含 `codex turn completion timed out`。
  - 输出不得再包含 `thread not found`。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.resolveRecoveryFailureText(...)`：恢复失败摘要优先读取 `provider_error -> provider_failure_reason -> provider_turn_status`，再回退到旧的 readable/output/artifact/latest output。
- 已新增结构化 provider 摘要清洗路径，避免 `provider_error=codex turn completion timed out` 被通用 known-failure 规则压缩为 `timeout`。
- 已新增并通过 `ControlNodeGraphActionResolutionTest.resolveRecoveryFailureTextPrefersProviderErrorOverThreadNoise`，锁定 `thread not found` 噪声不会覆盖 provider 结构化根因。

### 5.27 Layer AA：current round metadata 补强不能丢 provider error

#### 背景

Layer Y 修复了 `selectLatestWorkerMetadata(...)` 白名单，Layer Z 修复了 recovery 摘要优先级。但控制图当前轮执行还有一个补强节点：`augmentLatestWorkerMetadata(...)` 会把 `WorkerExecutionResult` 上的执行状态、duration、evidence、failure class 等字段补回 selected metadata。

当前该方法会补：

- `provider_failure_class`
- `provider_failure_reason`
- `provider_retryable`

但没有补：

- `provider_error`
- `provider_turn_status`

如果上游 selected metadata 因历史 artifact、旧测试 fixture 或中间兼容路径没有携带这两个字段，补强阶段仍不能从原始 `WorkerExecutionResult.metadata()` 把结构化根因补回来。这样 Layer Z 的 `resolveRecoveryFailureText(...)` 即使优先读 provider error，也可能拿不到字段。

#### 设计结论

- `ControlNodeGraph.augmentLatestWorkerMetadata(...)` 必须补齐 `provider_error / provider_turn_status`。
- 这属于 current-round metadata completeness 修复，不改变 artifact 持久化格式，也不改变 provider executor 输出格式。
- 应单测补强函数本身，确保即使输入 selected metadata 为空，也能从 `WorkerExecutionResult.metadata()` 恢复 provider diagnostics。

#### 验收入口

- `ControlNodeGraphActionResolutionTest.augmentLatestWorkerMetadataKeepsProviderErrorDiagnostics`
  - 输入空 selected metadata。
  - `WorkerExecutionResult.metadata()` 包含 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason / provider_retryable`。
  - 输出 metadata 必须保留全部 provider diagnostic 字段。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.augmentLatestWorkerMetadata(...)`：current round metadata 补强阶段会从 `WorkerExecutionResult.metadata()` 补入 `provider_error / provider_turn_status`。
- 已新增并通过 `ControlNodeGraphActionResolutionTest.augmentLatestWorkerMetadataKeepsProviderErrorDiagnostics`，锁定即使输入 selected metadata 为空，也能保留 provider diagnostics。

### 5.28 Layer AB：会话消息投影必须带 provider diagnostics

#### 背景

Layer X/Y/Z/AA 已经把 provider 结构化根因打通到 runtime facts、execution boundary、current round metadata 与 recovery failure summary。但 Dialogue/Console 第一屏最常被用户看到的是 session message，而 `TaskService.appendRuntimeFactMessageMetadata(...)` 当前只投影：

- `failure_class`
- `failure_summary_readable`
- `recovery_*`
- route/tool/execution 基础字段

它没有投影：

- `provider_error`
- `provider_turn_status`
- `provider_failure_class`
- `provider_failure_reason`
- `provider_retryable`

结果是 API 和 live_flow 已经能解释 “Codex turn completion timed out”，但消息卡 metadata 仍可能只能显示 `thread not found` 或泛化 failure summary。长任务调试时，operator 往往先看消息流，因此这条观测链也必须闭合。

#### 设计结论

- `TaskService.appendRuntimeFactMessageMetadata(...)` 应从三个来源投影 provider diagnostics：
  - `task.metadata()`
  - `facts.metadata()`
  - `latestArtifact.metadata()`
- 投影字段保持与 runtime surface 一致：
  - `provider_error`
  - `provider_turn_status`
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
- 不改变消息正文渲染逻辑；第一版只保证 metadata 可用，避免前端需要反查 raw artifact 或 live_flow。

#### 验收入口

- `TaskServiceMessageReceiptTest.continueWritesAssistantProgressMessageWithProviderDiagnostics`
  - 构造带 provider diagnostics 的 worker artifact / runtime facts。
  - 触发 continue 后产生 assistant progress message。
  - 断言 message metadata 包含 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason / provider_retryable`。

#### 2026-05-18 实施验证

- 已修改 `TaskService.appendRuntimeFactMessageMetadata(...)`：会从 `task.metadata()`、`facts.metadata()`、`latestArtifact.metadata()` 三个来源投影 provider diagnostics 到 session message metadata。
- 已新增 `copyProviderDiagnostics(...)`，统一投影 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason / provider_retryable`。
- 已新增并通过 `TaskServiceMessageReceiptTest.continueWritesAssistantProgressMessageWithProviderDiagnostics`，锁定真实 `continueTask` 消息写入路径能保留 provider diagnostics。

### 5.29 Layer AC：Dialogue expanded message 必须展示 provider diagnostics

#### 背景

Layer AB 已经把 provider diagnostics 写入 session message metadata，但 `/dialogue/` 的 expanded message 仍只读取：

- `full_content`
- `failure_summary_readable`
- `output_text`
- `artifact_content`
- `next_step`

如果消息 metadata 已经有 `provider_error=codex turn completion timed out`，用户点开消息仍可能只看到旧的 Failure Summary 或 worker output，而看不到结构化 provider 根因。这会让 Layer AB 只停在 API raw metadata，没有进入实际人工排查界面。

#### 设计结论

- `message-expansion-plan.js` 的 task outcome full content 应增加 `Provider Diagnostics` 区块。
- 区块内容从 metadata 中读取：
  - `provider_error`
  - `provider_turn_status`
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
- 展示顺序应靠近 Failure Summary，早于 Worker Output / Artifact Content。
- `hasExpandedTaskOutcomeContent(...)` 也应把 provider diagnostics 视为可展开内容来源。
- 不改变 message collapsed body，避免列表首屏变重；只在 expanded content 中显示。

#### 验收入口

- `dialogue-message-expansion-plan.test.mjs`
  - `task_progress` message metadata 只有 provider diagnostics 时，仍可展开。
  - expanded full content 包含 `Provider Diagnostics`、`codex turn completion timed out`、`provider_runtime_transient`、`retryable: true`。

#### 2026-05-18 实施验证

- 已修改 `message-expansion-plan.js`：task progress/result expanded content 会在 Failure Summary 之后、Worker Output 之前展示 `Provider Diagnostics` 区块。
- `Provider Diagnostics` 已投影 `provider_error / provider_turn_status / provider_failure_class / provider_failure_reason / provider_retryable`。
- `hasExpandedTaskOutcomeContent(...)` 已把 provider diagnostics 视为可展开内容来源。
- 已新增并通过 `dialogue-message-expansion-plan.test.mjs` 中的 provider diagnostics 用例，锁定只有 provider diagnostics 时消息仍可展开。

### 5.30 Layer AD：Dialogue message signal 必须在折叠态提示 provider 根因

#### 背景

Layer AC 让用户点开 message 后能看到 `Provider Diagnostics`，但折叠态 message card 和 role summary 的 signal 仍只读取 lifecycle/route/tool/model/hint。实际排查长任务时，用户不一定会逐条展开消息；如果折叠态只显示 `trigger · continue` 或 `route · kimi`，仍无法第一眼知道根因是 `codex turn completion timed out`。

#### 设计结论

- `message-card-plan.js` 应增加一个高优先级 `provider` signal。
- signal 来源优先级：
  - `provider_error`
  - `provider_failure_reason`
  - `provider_turn_status`
  - `provider_failure_class`
- provider signal 应排在 route/tool 之前，但不抢在 trigger/event/completion/acceptance/action 之前，避免正常生命周期信号被完全挤掉。
- compact summary 也应复用该 signal，这样 message role summary 卡片能看到 provider 根因。
- 仍保留最多 3 个 signal 的现有限制，避免首屏变重。

#### 验收入口

- `dialogue-message-card-plan.test.mjs`
  - metadata 包含 `provider_error=codex turn completion timed out` 时，signal texts 包含 `provider · codex turn completion timed…`。
  - provider signal 应出现在 route signal 前。
- `dialogue-message-summary-plan.test.mjs`
  - 最新 assistant task progress 带 provider error 时，role summary 的 `primarySignal` 应能显示 provider 根因。

#### 2026-05-18 实施验证

- 已修改 `message-card-plan.js`：message signal 增加 `provider` 高优先级信号，来源优先级为 `provider_error -> provider_failure_reason -> provider_turn_status -> provider_failure_class`。
- provider signal 排在 route/tools 前，但仍排在 trigger/event/completion/acceptance/action 后，避免正常生命周期信号被完全挤掉。
- 已新增并通过 `dialogue-message-card-plan.test.mjs` provider signal 用例，锁定折叠态 message card 能显示 provider 根因。
- 已新增并通过 `dialogue-message-summary-plan.test.mjs` provider summary 用例，锁定 role summary 的 primary signal 能显示 provider 根因。

### 5.31 Layer AE：Recovery Job 面板必须展示 provider failure evidence

#### 背景

Layer T 已经把恢复计划的结构化根因写成 `failure_evidence_source / failure_evidence`，`TaskService.insertRecoveryJob(...)` 也会把这两个字段放进 `TaskRecoveryJob.metadata`。但 `/console/` 与 `/dialogue/` 共享的 `recovery-job-plan.js` 仍只显示：

- recovery status
- request id
- recommended action
- execution mode
- target worker
- `provider_failure_class`

这会造成 operator 看到 `failure provider_runtime_transient`，但看不到真正推动恢复判断的证据，例如 `codex turn completion timed out`。这类信息已经在 job metadata 中，不需要新增 API 字段；缺口在 UI plan 没有读取和投影。

#### 设计结论

- `buildRecoveryJobPlan(...)` 应读取并返回：
  - `failureEvidence`
  - `failureEvidenceSource`
- 读取优先级：
  - 显式 provider error：`provider_error / providerError / metadata.provider_error / metadata.providerError`
  - provider failure reason：`provider_failure_reason / providerFailureReason / metadata.provider_failure_reason / metadata.providerFailureReason`
  - 恢复计划证据：`failure_evidence / failureEvidence / metadata.failure_evidence / metadata.failureEvidence`
- 如果存在 `failureEvidence`，Recovery Job cards 应新增 `Failure Evidence` 行。
- chips 可增加短证据 chip，例如 `evidence codex turn completion timed out`，用于折叠概览直接看到根因。
- 现有 `error_message` 仍只表示 recovery job 执行错误，不要把 provider failure evidence 塞进 `error` 字段，避免混淆“原始 provider 失败”和“恢复 job 自身失败”。

#### 验收入口

- `dialogue-recovery-job-plan.test.mjs`
  - 构造带 `metadata.failure_evidence_source=agent_run.metadata.provider_error` 和 `metadata.failure_evidence=codex turn completion timed out` 的 recovery job。
  - 断言 plan 返回 `failureEvidence / failureEvidenceSource`。
  - 断言 cards 包含 `Failure Evidence`。
  - 断言 chips 包含短 evidence chip。

#### 2026-05-18 实施验证

- 已修改 `recovery-job-plan.js`：`buildRecoveryJobPlan(...)` 会优先读取 provider error / provider failure reason，再回退到 recovery plan 的 `failure_evidence`。
- plan 返回新增 `failureEvidence / failureEvidenceSource`，并在 cards 中增加 `Failure Evidence` 行。
- chips 新增 `evidence ...` 短提示，确保 collapsed recovery job 概览也能看到 `codex turn completion timed out` 这类根因。
- 已新增并通过 `dialogue-recovery-job-plan.test.mjs` provider failure evidence 用例。

### 5.32 Layer AF：dispatch readiness 必须显式区分 active probe 与 passive fallback

#### 背景

Layer J/P 已经把分发前 readiness 接入 scheduler 和 router，但当前还有一个容易误导 operator 的边界：`AgentProvider.dispatchPreflight()` 默认实现会复用 `detect()/refreshStatus()`，并在 metadata 中标记 `dispatch_preflight_mode=passive_status`。这能保持旧 provider 可用，但它不等价于“真的发了一条测试轮次”。

如果 UI/API 只看到 `dispatch_preflight_ready=true`，operator 会误以为 worker 已经通过主动验活；实际它只证明 provider 二进制和基础状态看起来可用，仍可能在真实 fresh turn 时卡 onboarding、超时或返回 `thread not found`。

#### 设计结论

- `ReadinessCheck` 应新增 `dispatch_preflight_active_probe`。
- `dispatch_preflight_active_probe=true` 仅当 `dispatch_preflight_mode=active_probe`。
- `passive_status / skipped / not_required / unknown` 都必须显示为 `false`，即使为了兼容仍允许 `ready=true`。
- `/api/v1/workers/{id}/readiness?mode=dispatch` 必须直接投影该字段。
- route/live_flow 后续如果展示 dispatch readiness，应以该字段区分“主动验活通过”和“被动状态 fallback”，不能只看 `dispatch_preflight_ready`。
- 严格阻断仍由 `-Dagentcloud.dispatch.preflight.require_active_probe=true` 控制；本层先把诊断事实打透，不在默认配置下一次性切断所有旧 provider。

#### 验收入口

- `AgentProviderSupportTest.dispatchReadinessProjectsPassiveFallbackProbeMode`
  - `dispatch_preflight_mode=passive_status`
  - `dispatch_preflight_ready=true`
  - `dispatch_preflight_active_probe=false`
- `AgentProviderSupportTest.dispatchReadinessStrictModeAllowsActiveProbe`
  - `dispatch_preflight_mode=active_probe`
  - `dispatch_preflight_active_probe=true`
- `ApiErrorContractHttpTest.workerReadinessDispatchModeProjectsPassiveFallbackProbeMode`
  - HTTP response 中 `dispatch_preflight_active_probe=false`。

#### 2026-05-18 实施验证

- 已修改 `WorkerRegistry.ReadinessCheck`：新增 `dispatchPreflightActiveProbe`，JSON 输出为 `dispatch_preflight_active_probe`。
- `DispatchPreflightStatus.activeProbe()` 只在 `dispatch_preflight_mode=active_probe` 时返回 true。
- passive fallback 仍可保持兼容的 `ready=true / dispatch_preflight_ready=true`，但会明确输出 `dispatch_preflight_active_probe=false`。
- 已补 `AgentProviderSupportTest` 与 `ApiErrorContractHttpTest` 对 passive fallback / active probe 两类模式的断言。
- Layer AH 已把同一口径补进 scheduler dispatch failure event 与 API 合同文档，避免 live_flow/event 消费方只看到 `dispatch_preflight_ready`。

### 5.33 Layer AG：`workspace_roots` 必须落到 provider 任务合同与单目标 cwd

#### 背景

Layer R 已经确认 `ChatFacadeService` 会把本地路径统一提取到 `workspace_roots`，并在多 workspace 时拆父子任务。但 provider 执行层还存在一个边界缺口：

- `ProviderTaskPromptBuilder` 的 `Workspaces:` 只读取 `cwd/workspace/workspace_root/working_directory/workspaces/workspace_paths`，没有读取 `workspace_roots`。
- `ProviderCliWorkerExecutor` 与 `CodexAppServerWorkerExecutor` 的 cwd 解析也只读取单值 `cwd/workspace/working_directory/workspace_root`，没有在历史任务只有 `workspace_roots=[D:\gitAll\articleeditor]` 时把它作为单目标 cwd。

这会导致一种失败链：上游已经识别出目标 repo，但 provider 任务合同和进程 cwd 仍可能回退到 harness 自身目录，进而让 DeepSeek/Codex 报“无法访问本地文件”或在错误仓库里执行。

#### 设计结论

- `workspace_roots` 是 ChatFacade 对“所有可能本地目标”的标准字段，provider prompt 必须纳入 `Workspaces:`。
- provider 执行器只有在 `workspace_roots` 归一化后恰好只有一个非空目标时，才允许把它作为 cwd。
- `workspace_roots` 存在多个目标时，执行器不能任意取第一个作为 cwd；多目标应由 ChatFacade 拆成父任务和单 workspace 子任务。
- 显式单值 cwd 字段仍优先：`cwd -> workspace -> working_directory -> workspace_root -> 单目标 workspace_roots`。
- 该规则同时适用于 provider-native CLI（DeepSeek/Kimi/Cursor 等）和 Codex app-server，避免恢复/移交时不同 worker 的 cwd 口径分裂。

#### 验收入口

- `ProviderTaskPromptBuilder` 构造 prompt 时，`metadata.workspace_roots=["D:\gitAll\articleeditor"]` 必须出现在 `Workspaces:`。
- `ProviderCliWorkerExecutor` 在缺少 `cwd/workspace/workspace_root`、但存在单元素 `workspace_roots` 时，`resolveWorkingDirectory(...)` 必须返回该 repo。
- `CodexAppServerWorkerExecutor` 同样必须支持单元素 `workspace_roots` 作为 cwd。
- 多元素 `workspace_roots` 不应被执行器折叠成第一个 cwd；应依赖上游拆分后的子任务显式写入自己的 `cwd`。

#### 2026-05-18 实施验证

- 已修改 `ProviderTaskPromptBuilder`：`Workspaces:` 会读取 `workspace_roots`，provider prompt 能看到 ChatFacade 提取出的本地目标。
- 已修改 `ProviderCliWorkerExecutor.resolveWorkingDirectory(...)`：缺少显式 cwd 字段时，单元素 `workspace_roots` 可作为 DeepSeek/native provider 执行 cwd。
- 已修改 `CodexAppServerWorkerExecutor.resolveWorkingDirectory(...)`：Codex app-server 同样支持单元素 `workspace_roots` 作为 cwd。
- 多元素 `workspace_roots` 不在执行器层折叠为第一个 cwd，继续依赖 ChatFacade 拆父子任务。
- 已复跑：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=ProviderCliWorkerExecutorTest`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=CodexAppServerWorkerExecutorTest`

### 5.34 Layer AH：dispatch preflight 失败事件必须带 active probe 口径

#### 背景

Layer AF 已让 `/api/v1/workers/{id}/readiness?mode=dispatch` 输出 `dispatch_preflight_active_probe`，但 scheduler 在真正分发前发现 worker preflight 失败时，`worker_dispatch_preflight_failed` event 只写入：

- `dispatch_preflight_ready`
- `dispatch_preflight_reason`
- `dispatch_preflight_cached`

缺少 `dispatch_preflight_mode / dispatch_preflight_active_probe`。这会造成 live_flow/event 消费方无法判断失败来自真实 active probe，还是 strict mode 下的 passive fallback 拒绝，operator 又会回到只看 `ready=false` 的模糊状态。

#### 设计结论

- `worker_dispatch_preflight_failed` event payload 必须和 readiness API 使用同一组诊断字段：
  - `dispatch_preflight_ready`
  - `dispatch_preflight_reason`
  - `dispatch_preflight_cached`
  - `dispatch_preflight_mode`
  - `dispatch_preflight_active_probe`
- `Task.metadata.dispatch_preflight_reason` 继续保留短原因，不把所有 probe metadata 写回 task 顶层，避免任务 metadata 膨胀。
- API 合同文档必须明确 `dispatch_preflight_active_probe=false` 不等价于不可用；它只说明本次 dispatch readiness 没有经过主动探测。是否阻断由 `ready` 和严格模式共同决定。

#### 验收入口

- `ControlNodeGraphOrchestrationFlowTest.schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight`
  - dispatch preflight 失败后应写入 `worker_dispatch_preflight_failed` event。
  - event payload 必须包含 `dispatch_preflight_mode=active_probe`。
  - event payload 必须包含 `dispatch_preflight_active_probe=true`。
- `docs/API_CONTRACTS.md` 的 worker readiness 合同必须列出 `dispatch_preflight_mode / dispatch_preflight_active_probe`。

#### 2026-05-18 实施验证

- 已修改 `ControlNodeGraph.ensureAssignedWorkerDispatchReady(...)`：`worker_dispatch_preflight_failed` event payload 新增 `dispatch_preflight_mode / dispatch_preflight_active_probe`。
- 已更新 `docs/API_CONTRACTS.md`：worker dispatch readiness 合同列出 `dispatch_preflight_mode / dispatch_preflight_active_probe`，并说明 `active_probe=false` 不直接等价于不可用。
- 已增强 `ControlNodeGraphOrchestrationFlowTest.schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight`，断言失败事件保留 active probe 口径。
- 已复跑 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Dtest=ControlNodeGraphOrchestrationFlowTest#schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight`，结果 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

#### 2026-05-19 补充验证

- 已静态核对 `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`：`worker_dispatch_preflight_failed` event payload 当前包含 `dispatch_preflight_mode` 与 `dispatch_preflight_active_probe`，与 readiness API 口径一致。
- 已静态核对 `docs/API_CONTRACTS.md`：`GET /api/v1/workers/{id}/readiness?mode=dispatch` 合同列出 `dispatch_preflight_ready / dispatch_preflight_reason / dispatch_preflight_cached / dispatch_preflight_mode / dispatch_preflight_active_probe / dispatch_preflight_metadata`，并明确 `dispatch_preflight_active_probe=false` 只代表非主动探测，不直接等价于不可用。
- 已复跑窄回归：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -MavenArgs '-Dtest=ControlNodeGraphOrchestrationFlowTest#schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight,ApiErrorContractHttpTest#workerReadinessDispatchModeProjectsPreflightFields+workerReadinessDispatchModeProjectsPassiveFallbackProbeMode,AgentProviderSupportTest#dispatchReadinessProjectsPassiveFallbackProbeMode+dispatchReadinessStrictModeAllowsActiveProbe'`
  - 结果：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。
- 已复跑完整 Java 回归：`powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1`，结果 `Tests run: 399, Failures: 0, Errors: 0, Skipped: 0`。这次验证覆盖新增 `src/main/resources/web/package.json` 被 Maven resource 阶段复制后的主测试面。
- 已复跑完整打包：`powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1`，Maven `package` 成功，resource 阶段复制 `47` 个资源，并产出 `target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`。
- 已用 JDK 21 `jar.exe` 直接检查 shaded JAR 内容，确认产物包含 `web/package.json`、`web/dialogue/recovery-job-plan.js` 与 `web/console/app.js`。
- 本轮补充复核：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1` 曾在 `124s` 外层命令超时，但 surefire 报告没有失败；加长超时后复跑 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven` 正常退出。
  - 复跑结果经 `target/surefire-reports/TEST-*.xml` 汇总确认：`50` 个测试报告，`Tests run: 399, Failures: 0, Errors: 0, Skipped: 0`。
  - 已复跑完整前端 plan 套件：`node --test src/test/js/*.mjs`，结果 `163` 个测试全部通过，覆盖 recovery job、provider diagnostics、route box、tool trace、timestamp normalization 等当前 UI 可见性面。
  - 已用当前 shaded JAR 做运行级 smoke：`java --enable-preview -Dserver.port=18080 -Duser.home=.tmp\jar-smoke-18080 -jar target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` 启动成功，`GET /api/v1/health` 返回 `status=up / virtual_threads=true / version=0.2.0`。
  - 已用当前 shaded JAR 在隔离端口 `18081` 复跑恢复 HTTP 验收：`scripts\Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:18081 -ReportPath .tmp\task-recovery-acceptance-probe-20260519.json -IncludeResumeExecution -RequestTimeoutSec 20`，结果通过。报告覆盖 `recoverable_provider_runtime_transient`、`auto_handoff_recovery`、`environment_blocked_recovery_rejected` 与 `fresh_session_async_resume_recovery`，其中异步恢复返回 `accepted=true / async=true / recovery_execution_mode=fresh_session`，并能通过 `request_id` 查回 `job_status=running`。
  - 已用当前 shaded JAR 在隔离端口 `18082` 复跑页面级 recovery job UI 探针：
    - `/dialogue/`：`node --max-old-space-size=512 scripts\recovery-job-ui-probe.js --base-url http://localhost:18082 --surface dialogue --report .tmp\recovery-job-ui-probe-dialogue-20260519.json --screenshot .tmp\recovery-job-ui-probe-dialogue-20260519.png`
    - `/console/`：`node --max-old-space-size=512 scripts\recovery-job-ui-probe.js --base-url http://localhost:18082 --surface console --report .tmp\recovery-job-ui-probe-console-20260519.json --screenshot .tmp\recovery-job-ui-probe-console-20260519.png`
    - 两个 surface 均确认 `recover?async=true` 请求、`mode=auto` body、`Recovery Job` 可见、`recovery_probe_request` 可见、`running` 状态可见。
  - 已用当前 shaded JAR 在隔离端口 `18083` 复跑 Console provider recovery window 探针：`scripts\Run-ConsoleProviderWindowProbe.ps1 -BaseUrl http://localhost:18083 -ReportPath .tmp\console-provider-window-probe-20260519.json -ScreenshotPath .tmp\console-provider-window-probe-20260519.png`，结果通过。报告确认 `runtime_health_window=true / provider_row_hint=true / route_box_hint=true`，页面渲染出“当前恢复降级窗口：claude”“恢复阶段会优先避开 claude”和 `recovery避开 claude`。

---

## 6. 实施顺序

1. 先文档化这条真实失败链和目标行为。
2. 再补 planner-output gate。
3. 然后把 recovery 语义和 route 诊断补齐。
4. 最后用窄回归锁住：
   - repo 修改请求会被判成 `coding`
   - planner 巨量失败输出不会继续当 delegation brief
   - `coding` 任务恢复时不会优先切去 `openclaw-native`
   - 存量 `continuation` 代码任务在 `select_worker/live_flow` 上也会按 `coding` 语义路由
   - 分发前 `dispatch readiness` 失败时不会继续把长任务发给该 worker
   - 最近失败任务可以通过统一 `recover` 入口冷启动恢复或 handoff 恢复
   - `recover?async=true` 能快速返回接受结果，避免 HTTP 请求被真实 worker 执行拖住
   - 异步 recovery 的 `request_id` 能通过 `/recovery_jobs` 查回状态
   - dispatch preflight 能区分 `active_probe` 与 `passive_status`，严格模式下不把被动状态复用误判成可分发
   - Java 21 测试脚本能真实透传 `-Dtest=...`，避免把窄回归误跑成全量测试

---

## 7. 预期收益

做完后，这类“本地代码仓库长任务”的成功率提升，主要体现在：

- 一开始就更容易选对 worker
- 中途 provider 异常时不再把坏 planner 输出继续放大
- 恢复换 worker 时不容易丢失代码语义
- 页面和 live_flow 更容易解释“为什么失败、接下来该换谁”
- 老任务和新任务的 task_type 口径不再分裂
