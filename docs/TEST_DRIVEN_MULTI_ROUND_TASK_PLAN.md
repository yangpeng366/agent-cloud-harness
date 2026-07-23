# TEST_DRIVEN_MULTI_ROUND_TASK_PLAN

## 1. 目的

本文档把 `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md` 里的多轮任务，进一步收束成一套测试驱动执行计划。

重点不是再列一次任务，而是回答下面四个问题：

1. 每类任务先写什么测试或探针
2. 每一轮应该看哪些证据面
3. 失败后优先从哪里调试
4. 什么时候可以继续自动推进，什么时候应该 pause、handoff 或进入 human gate

建议与以下文档配合使用：

- `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- `EVAL_SCENARIOS.md`
- `LIVE_FLOW_RUNBOOK.md`
- `LOCAL_DOC_WORKER_PILOT.md`
- `TROUBLESHOOT.md`
- `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
- `MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE.md`

## 2. 测试驱动原则

### 2.1 先证据，后改动

每个任务默认按以下顺序推进：

```text
Round 1: 先定位现有测试、脚本、观测接口
Round 2: 写失败测试或失败探针，确保问题可稳定复现
Round 3: 实施最小修复或最小设计收口
Round 4: 跑 focused tests / smoke probes
Round 5: 必要时补文档、补验收记录、补实验矩阵 case
```

### 2.2 优先最小回归保护

本项目已经有不少 focused test，新增工作优先挂到现有测试类，而不是先造新的大而全测试套件。

推荐顺序：

1. 单元/组件测试
2. Handler/HTTP contract 测试
3. smoke 脚本或 acceptance probe
4. live flow / experiment matrix 级别验证

### 2.3 自动验证优先级

优先使用以下验证层级：

1. `src/test/java` 里的 focused JUnit 测试
2. `scripts/*.ps1` / `scripts/*.js` 探针
3. `live_flow` / `judgment_trace` / `tool_trace` / `experiment_run`
4. 人工审阅文档或 UI

如果前 3 层还没有证据，不应只用“看起来没问题”作为完成标准。

## 3. 任务到测试资源的映射

| 任务 | 首选测试类 | 首选脚本/探针 | 首选观测面 |
|------|------------|---------------|------------|
| D01 Worker priority 覆盖 | `WorkerRegistryPriorityOverrideTest`、`WorkerRouterRouteTraceTest`（必要时补 `WorkerRegistryDynamicProviderTest`） | 可补最小 YAML smoke | `/api/v1/workers`、`/workers/{id}/readiness` |
| D02 Provider readiness 一致性 | `WorkerRegistryDynamicProviderTest` | `scripts/provider-discovery-smoke.js` | `/api/v1/agents`、`/api/v1/workers`、`/workers/{id}/readiness` |
| D03 Chat Facade SSE | `ChatFacadeHandlerHttpTest` | `scripts/Run-ChatFacadeAcceptanceProbe.ps1` | `/v1/chat/completions`、`/v1/responses` |
| D04 Pause/Resume 连续性 | `TaskServicePacketContractTest`、`ConsolidationServiceProtocolTest` | `scripts/Run-TaskRecoveryAcceptanceProbe.ps1` | `/packet`、`/checkpoints/{taskId}`、`/live_flow` |
| M01 baseline_matrix_v2 | `ExperimentMatrixServiceTest` | `scripts/Run-BaselineMatrixGateProbe.ps1` | `/experiment_matrix/cases`、`/summary` |
| M02 Packet schema 固化 | `PacketBuilderProtocolTest`、`TaskServicePacketContractTest` | 可复用 recovery probe | `/packet`、`/handoff_packet` |
| M03 GET 控制入口退役 | `TaskHandlerControlActionHttpTest`、`ControlActionHttpRouteTest` | 可补最小 HTTP matrix probe | `/api/v1/tasks/{id}/pause` 等 |
| M04 生命周期投影补齐 | `TaskServiceMessageReceiptTest`、`SessionServiceLifecycleProjectionTest` | `scripts/Run-DialogueBrowserAcceptanceProbe.ps1` | `/sessions/{id}/messages`、Dialogue UI |
| O01 ControlNodeGraph 拆分 | `ControlNodeGraphOrchestrationFlowTest`、`ControlNodeGraphActionResolutionTest` | focused `mvn -Dtest=... test` | `/live_flow`、`/judgment_trace` |
| O02 TaskService 聚合瘦身 | `TaskServiceLiveFlowViewTest`、`RuntimeFactSetAssemblerTest` | focused `mvn -Dtest=... test` | `/live_flow`、`/harness_trace` |
| O03 acceptance gate | `ExperimentMatrixServiceTest`、`ExperimentRunServiceTest` | `scripts/Run-BaselineMatrixGateProbe.ps1` | `/experiment_run`、`/experiment_matrix/summary` |
| O04 长任务收口质量 | `ControlNodeGraphOrchestrationFlowTest`、`TaskServiceMessageReceiptTest` | `scripts/Run-CodexPartialTimeoutSmoke.ps1` | `/judgment_trace`、`/harness_trace`、`/live_flow` |

## 4. 执行阶段模板

## 4.1 Phase A: 建立失败证据

目标是先把“问题存在”固定下来。

建议动作：

- 找最接近的测试类，先补一个失败断言。
- 如果问题更偏集成面，先补一个 smoke probe 或 HTTP acceptance probe。
- 在提交修复前，保留失败输出或失败断言描述。

最低产物：

- 一个失败测试名，或
- 一个失败脚本报告路径，或
- 一个失败的 `live_flow` / `judgment_trace` 证据路径

## 4.2 Phase B: 最小修复或最小收口

目标是让问题最小范围闭环，不在同一轮混入额外重构。

建议动作：

- 只修与失败证据直接对应的路径。
- 优先保外部 contract 不变。
- 如任务是设计收口，优先补 contract 文档与测试，再考虑扩展实现。

不建议：

- 在 D 类任务里顺带做大重构
- 在 O 类任务第一轮就改 API shape
- 在没有测试保护前同时改 runtime、UI、文档三层

## 4.3 Phase C: focused 回归

每个任务修复后至少做 1 组 focused 回归：

```powershell
.\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=<FocusedTestClass>"
```

如果是 HTTP/脚本 contract，再补一个 probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\<probe>.ps1 ...
```

## 4.4 Phase D: 观测面复核

如果任务影响运行时行为，还需要至少看一类观测面：

- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/judgment_trace`
- `/api/v1/tasks/{id}/tool_trace`
- `/api/v1/tasks/{id}/experiment_run`
- `/api/v1/tasks/{id}/harness_trace`

目标是确认：

- 不只是测试过了
- 运行时投影也没有断

## 5. 调试计划

## 5.1 D01 Worker priority 覆盖不生效

### 测试优先级

1. `WorkerRegistryPriorityOverrideTest`
2. `WorkerRouterRouteTraceTest`
3. 如仍不足，再补一个最小 `workers.yml` 读取测试

### 当前状态

- 2026-06-15：`WorkerRegistryPriorityOverrideTest` 已通过 `Test-WithJava21.ps1 -QuietMaven -Dtest=WorkerRegistryPriorityOverrideTest` 跑通。
- `Test-WithJava21.ps1` 对裸 `-Dtest` 的透传已验证生效，但文档主示例仍优先保留 `-MavenArgs "-Dtest=..."` 这种更明确的写法。

### 多轮调试步骤

```text
Round 1:
  - 读 WorkerRegistry.register/applyPriorityOverride
  - 确认当前覆盖对象是否被后续 workers.put 覆盖
Round 2:
  - 新增失败测试：断言 selection_priority 被配置覆盖
Round 3:
  - 修复对象返回或写入顺序
Round 4:
  - 验证 metadata 保留 selection_priority_original / overridden
Round 5:
  - 若路由行为受影响，再补 WorkerRouter 断言
```

### 失败时先看

- `WorkerRegistry.register()`
- `applyPriorityOverride()`
- worker metadata 是否在 `register()` 末尾被旧对象覆盖

### 完成证据

- focused test 通过
- worker metadata 中能看到覆盖标志

## 5.2 D02 Provider readiness 与 worker list 不一致

### 测试优先级

1. `WorkerRegistryDynamicProviderTest`
2. `scripts/provider-discovery-smoke.js`

### 多轮调试步骤

```text
Round 1:
  - 用临时 providers.yaml 启动 harness
Round 2:
  - 对比 /agents、/workers、/workers/{id}/readiness 三者输出
Round 3:
  - 检查 dispatch preflight cache 和 temporary unavailable 逻辑
Round 4:
  - 修复静态 ready 与运行时 ready 不一致问题
Round 5:
  - 更新 smoke probe 断言
```

### 失败时先看

- `provider-discovery-smoke/report.json`
- `WorkerRegistry.readinessReason(...)`
- dispatch preflight cache 是否过期

### 完成证据

- smoke probe 通过
- `/workers` 与 `/readiness` 一致

## 5.3 D03 Chat Facade SSE 流式兼容

### 测试优先级

1. `ChatFacadeHandlerHttpTest`
2. `scripts/Run-ChatFacadeAcceptanceProbe.ps1`

### 当前状态

- 2026-06-15：`ChatFacadeHandlerHttpTest` 已通过 `Test-WithJava21.ps1 -QuietMaven -Dtest=ChatFacadeHandlerHttpTest` 跑通。
- 2026-06-15：`Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port 18080 -KeepServerLogs` 已返回 `chat_completions` / `responses` 双 surface 的结构化 probe 结果。

### 多轮调试步骤

```text
Round 1:
  - 先补 chat completions stream 的断言
Round 2:
  - 再补 responses stream 的事件顺序断言
Round 3:
  - 验证非法 JSON、method not allowed、client disconnect
Round 4:
  - 如前端有消费差异，再跑 facade acceptance probe
Round 5:
  - 必要时更新 Dialogue 验收 runbook
```

### 失败时先看

- `ChatFacadeHandler.sendCompletionStream`
- `ChatFacadeHandler.sendResponsesStream`
- `ChatFacadeHandlerHttpTest` 的 SSE body 断言

### 完成证据

- HTTP test 通过
- acceptance probe 能返回有效 `task_id` / `reply_type`

## 5.4 D04 Pause/Resume packet 连续性

### 测试优先级

1. `TaskServicePacketContractTest`
2. `ConsolidationServiceProtocolTest`
3. `scripts/Run-TaskRecoveryAcceptanceProbe.ps1`

### 多轮调试步骤

```text
Round 1:
  - 先确认 pause 是否持久化 packet
Round 2:
  - 检查 checkpoint 是否含最小恢复面
Round 3:
  - resume 后比对 next_step、assigned_worker、recent_artifacts
Round 4:
  - 若恢复链断裂，补 focused regression test
Round 5:
  - 用 live_flow 再核一次恢复后的观测面
```

### 失败时先看

- `TaskService.pauseTask` / `resumeTask`
- packet DAO / checkpoint DAO
- `/api/v1/tasks/{id}/packet`
- `/api/v1/checkpoints/{taskId}`

### 完成证据

- packet contract 测试通过
- recovery probe 通过

## 6. 修改类任务的测试驱动策略

## 6.1 M01 baseline_matrix_v2

先补 `ExperimentMatrixServiceTest`，再动 catalog 代码。

建议顺序：

1. 新增失败断言：v2 case 存在且 metadata 合同完整
2. 修改 `ExperimentMatrixService`
3. 运行 `Run-BaselineMatrixGateProbe.ps1`
4. 记录 report 路径

### 当前状态

- 2026-06-15：`ExperimentMatrixServiceTest` 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest"` 跑通。
- 2026-06-15：`Run-BaselineMatrixGateProbe.ps1` 已通过，report 写入 `.tmp\baseline-matrix-gate-20260615.json`，`created_run_count=9`、`summary_total_runs=9`。

## 6.2 M02 Packet schema 固化

先补协议测试或文档契约断言，再动 runtime。

建议顺序：

1. `PacketBuilderProtocolTest`
2. `TaskServicePacketContractTest`
3. `ConsolidationServiceProtocolTest`
4. 再更新 `API_CONTRACTS.md`

### 当前状态

- 2026-06-30：先按 `PacketBuilderProtocolTest`、`TaskServicePacketContractTest`、`ConsolidationServiceProtocolTest`、`API_CONTRACTS.md` 对照了一轮实现与协议，确认 builder / service / checkpoint 的 typed schema 已经对齐，文档中的 `machine-readable first` 口径也已落在 `packet` / `handoff_packet` / `refined_packet` 三条面上。
- 2026-06-30：补了 `/api/v1/tasks/{id}/refresh_packet` 与 `/api/v1/tasks/{id}/packet` 的 HTTP 合同回归：`TaskHandlerControlActionHttpTest#getAndRefreshResumePacketReturnTypedMachineReadableSchema`，把 `packet_version=1.1`、`task_identity`、`current_status`、`current_node`、`assigned_worker`、`latest_summary`、`next_step`、`blockers`、`open_questions`、`recent_artifacts`、`recent_decisions` 以及 payload 中的 continuity alias 一次性锁进接口层。
- 2026-06-30：focused suite 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,TaskHandlerControlActionHttpTest"` 跑通；当前 M02 的最小证据链已覆盖 builder、service、checkpoint 与 HTTP API 四层。

## 6.3 M03 GET 控制入口退役

先补 contract 测试，再写迁移文档。

建议顺序：

1. `TaskHandlerControlActionHttpTest`
2. `ControlActionHttpRouteTest`
3. 文档标记 POST 为正式路径
4. 如有必要，再补审计字段

### 当前状态

- 2026-06-30：先按 `TaskHandler.java`、`SessionHandler.java`、`NioHttpServer.java` 与 `API_CONTRACTS.md` 复核了一轮，确认兼容 `GET /pause|resume|continue|escalate` 与 `GET /sessions/{id}/close` 当前都已进入 deprecation 路径：响应头会补 `Deprecation / Sunset / Link / Warning / X-AgentCloud-Replacement-Method`，同时生命周期投影会额外标记 `legacy_control_route=true`。
- 2026-06-30：本轮真实缺口不在实现，而在测试覆盖面。已在 `TaskHandlerControlActionHttpTest` 补上 `legacyGetResumeContinueAndEscalateStillMarkAuditMetadata`，并在 `ControlActionHttpRouteTest` 补强 `legacy GET resume / continue / escalate` 的 route-level 回归；`SessionHandlerLifecycleHttpTest` 继续负责 `GET close session` 的兼容侧验证。
- 2026-06-30：为恢复 focused suite，还顺手收口了两个阻塞编译的 provider 配置接口问题：`LocalCliAgentProvider` 恢复 `cliConfig()` 暴露但改用 `providerConfig` 存储字段，`CodexAppServerWorkerExecutor` 修复 `exec_json` profile 参数适配错位。这两处属于 pre-existing 编译阻塞，不改变 M03 的外部契约。
- 2026-06-30：focused suite 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskHandlerControlActionHttpTest,ControlActionHttpRouteTest,SessionHandlerLifecycleHttpTest"` 跑通；dated 记录见 `M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`。

## 6.4 M04 生命周期投影补齐

先补消息投影测试，再动前端。

建议顺序：

1. `TaskServiceMessageReceiptTest`
2. `SessionServiceLifecycleProjectionTest`
3. `/dialogue/` acceptance probe
4. 前端渲染修正

## 7. 优化类任务的测试驱动策略

## 7.1 O01 ControlNodeGraph 拆分

这种任务不能先改代码再补测试，必须先锁外部行为。

建议顺序：

1. 先跑：
   - `ControlNodeGraphOrchestrationFlowTest`
   - `ControlNodeGraphActionResolutionTest`
2. 再抽取内部类
3. 每拆一步都回跑 focused tests
4. 如行为变更不可避免，先改测试名称和断言语义，再改实现

## 7.2 O02 TaskService 聚合瘦身

建议先补 assembler 级测试，再做内部复用迁移。

首选测试：

- `RuntimeFactSetAssemblerTest`
- `TaskServiceLiveFlowViewTest`

## 7.3 O03 acceptance gate

建议先让 `ExperimentMatrixServiceTest` 出现失败断言，再补 summary 字段。

外部验证：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 `
  -BaseUrl http://localhost:8080 `
  -ReportPath .tmp\baseline-matrix-gate-dev.json
```

如需显式传 `-ExperimentName`，必须使用未复用名称；当前 probe 发现同名 experiment 已有 run 时会直接失败，避免把 `summary_total_runs` 静默累加成 18、27 这类假失败。

### 当前状态

- 2026-06-15：`ExperimentMatrixServiceTest,ExperimentRunServiceTest` 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest"` 跑通。
- 2026-06-15：baseline matrix gate probe 已通过，`summary` 完成 `9/9` 聚合。
- 2026-07-21：O03 HTTP gate 继续收口。`Run-BaselineMatrixGateProbe.ps1` 现在会校验 `acceptance_gate_result_counts / artifact_quality_gate_status_counts / cost_gate_status_counts / runs_with_failure_reason`，并把 `mode_gate_rollup` 写入 report。真实脚本验证报告已写入 `.tmp\baseline-matrix-gate-20260721.json`，其中三种 mode 都显示 `not_evaluated=3`、`within_threshold=3`、`runs_with_failure_reason=0`；同日还补了“重复 experiment_name 直接失败”的前置校验。
- 2026-07-21：P2 real worker smoke 已跑出第一份 provider-backed baseline evidence。`Run-BaselineMatrixRealWorkerSmoke.ps1` 在 `http://localhost:18082` 上执行 `short-001` 的三种 mode，report 写入 `.tmp\baseline-matrix-real-worker-smoke-20260721.json`；三者都满足 terminal/evaluated 最低门槛，并留下 `live_flow / judgment_trace / tool_trace / harness_trace`，但最终全部因 `initialize: timed out waiting for response` 停在 `waiting_human / human_gate`，`acceptance_result=rejected`。
- 2026-07-21：P2 real worker smoke 的 `initialize` 超时已定位到 Codex app-server 启动参数兼容问题：`codex app-server --no-alt-screen --listen stdio://` 在 Codex CLI `0.144.4` 下直接报 `unexpected argument '--no-alt-screen' found`。当前 app-server plan 已改为 `codex app-server --listen stdio://`，focused suite `CodexAppServerWorkerExecutorTest,AgentProviderSupportTest,ApiErrorContractHttpTest` 已通过；下一步是重建后复跑 real worker smoke。
- 同日确认：PowerShell 直接裸写逗号分隔 `-Dtest=...,...` 会触发参数解析错误，文档示例必须保留引号。

## 7.4 O04 长任务收口质量

建议先把终态规则固化到测试里，再碰 heuristics。

首选测试：

- `ControlNodeGraphOrchestrationFlowTest`
- `TaskServiceMessageReceiptTest`
- `TaskServiceExperimentRunLifecycleTest`

外部 smoke：

- `scripts/Run-CodexPartialTimeoutSmoke.ps1`

### 当前状态

- 2026-06-15：先用 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,ControlNodeGraphOrchestrationFlowTest,TaskServiceMessageReceiptTest,TaskServiceExperimentRunLifecycleTest"` 做了一轮聚合窄跑，首次失败集中在 `ControlNodeGraphOrchestrationFlowTest` 的 3 个 O04 恢复链场景。
- 2026-06-15：失败共同表现为最终 `waiting_human / human_gate` 任务缺失 `auto_handoff_count`；根因定位到 `ControlNodeGraph.applyRecoveryDirective(...)` 在非 `autoHandoff` 分支里误删历史 `auto_handoff_count`，导致“经历过一次自动移交后落到 human gate”的终态丢失恢复链计数。
- 2026-06-15：修复后已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ControlNodeGraphActionResolutionTest,ControlNodeGraphOrchestrationFlowTest"` 回归，新增 action-resolution 断言锁定“保留 `auto_handoff_count`，但清掉 `auto_handoff_target`”的合同。

## 8. pause / handoff / human gate 使用规则

### 8.1 何时 pause

以下情况建议主动插入 `pause`：

- 任务已经拿到关键证据，但修复方案还没收敛
- 需要跨轮保留 runtime context 做 continuity 验证
- 要验证 packet 与 checkpoint 是否足够恢复

### 8.2 何时 handoff

以下情况建议主动 `handoff`：

- 当前 worker 缺少运行本地探针或脚本的能力
- 需要从强模型规划切到更低成本执行器
- 需要验证跨 worker packet 连续性

### 8.3 何时进入 human gate

以下情况不应盲目自动推进：

- diff 范围已明显超出预期
- 外部 contract 不清晰，测试无法定义通过条件
- 同一失败重试后仍无法判断是设计问题还是实现问题

## 9. 首批执行建议

为了尽快形成“测试驱动 + 多轮任务 + 调试计划”闭环，建议本周按下面顺序推进：

1. D01
   - 产出一个失败测试和一个最小修复
2. D03
   - 产出一组 SSE contract 回归断言
3. M01
   - 把 D01 / D03 变成 baseline_matrix_v2 候选 case
4. O03
   - 让矩阵结果开始有 acceptance gate

这样可以形成一条完整链：

```text
真实缺陷
  -> 失败测试
  -> 最小修复
  -> 任务化沉淀
  -> experiment matrix 复跑
  -> acceptance gate 汇总
```

## 10. 结论

如果 `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md` 解决的是“接下来做哪些真实多轮任务”，
那么本文档解决的是“这些任务如何按测试驱动方式落地”。

而 `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md` 则进一步解决“如何把首批任务真正跑起来”。

两份文档配合后的目标是：

- 任务不是只靠自然语言描述
- 调试不是只靠人工猜测
- 优化不是先大改再补救
- 每个任务都能落回测试、探针、观测面和正式文档

这才是 `agent-cloud-harness` 后续把真实工程任务纳入 experiment matrix 的可持续路径。
