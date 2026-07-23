# PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK

## 1. 目的

本文档设计一组围绕 `agent-cloud-harness` 自身演进的多轮任务包，用于在真实仓库里调试、修改和优化项目。

它与 `EVAL_SCENARIOS.md` 的区别是：

- `EVAL_SCENARIOS.md` 主要验证 harness 控制面能力。
- 本文档主要提供可投喂给 harness 的真实工程任务，用这些任务反过来推进和验证项目。

建议用途：

- 作为 `baseline_matrix_v2` 的候选真实任务集。
- 作为 `strong_only / small_only / orchestrated` 三模式对比的任务输入。
- 作为 Dialogue 里多轮任务、暂停恢复、移交、人工确认的演示任务。
- 作为项目后续调试、修改、优化的可验收工作清单。

## 2. 任务包总览

| 编号 | 类型 | 场景 | 目标 |
|------|------|------|------|
| D01 | 调试 | Worker priority 覆盖不生效 | 复现配置覆盖失效，修复并补回归测试 |
| D02 | 调试 | Provider readiness 与 worker list 不一致 | 找出 readiness 投影不一致或缓存误导，并补探针 |
| D03 | 调试 | Chat Facade SSE 流式兼容 | 验证 `/v1/chat/completions` 与 `/v1/responses` 流式输出契约 |
| D04 | 调试 | Pause/Resume packet 连续性 | 在暂停恢复后确认 packet、checkpoint、next_step 未断裂 |
| M01 | 修改 | baseline_matrix_v2 真实任务集 | 把本文档任务沉淀为可复跑 case catalog |
| M02 | 修改 | Packet schema 固化 | 把 resume/handoff packet 最小字段写成稳定 contract |
| M03 | 修改 | GET 控制入口退役准备 | 为旧 GET pause/resume/continue/escalate 增加迁移与观测策略 |
| M04 | 修改 | Chat/Dialogue 生命周期投影补齐 | 收口 task_progress、task_result、human_gate 的消息映射 |
| O01 | 优化 | ControlNodeGraph 拆分 | 拆出失败分类、节点执行和 trace 写入，降低大文件风险 |
| O02 | 优化 | TaskService 观测面聚合瘦身 | 让 live_flow、judgment_trace、harness_trace 复用更清晰的聚合边界 |
| O03 | 优化 | Experiment matrix acceptance gate | 从 HTTP smoke 升级到真实 artifact quality/cost gate |
| O04 | 优化 | 长任务收口质量 | 明确 auto-continue、done、waiting_human 的终态边界 |

## 3. 推荐运行方式

### 3.1 单任务多轮

适合调试和小范围修改：

```text
创建任务
  -> 第一轮：定位证据和复现路径
  -> 第二轮：设计最小修复
  -> 第三轮：实施或产出 patch plan
  -> 第四轮：跑测试 / 生成验收记录
  -> 必要时 pause/resume 或 handoff
```

### 3.2 三模式对比

适合实验矩阵：

```text
同一任务输入
  -> strong_only
  -> small_only
  -> orchestrated
  -> 比较完成率、成本、handoff、resume、human_gate、acceptance_result
```

### 3.3 建议 metadata

每个任务建议带以下 metadata，方便后续进入 `experiment_run` 和 `experiment_summary`：

```json
{
  "task_pack": "project_evolution_v1",
  "task_case_key": "D01",
  "task_family": "debug",
  "task_length_bucket": "short",
  "model_mode": "orchestrated",
  "acceptance_gate": "manual_plus_regression_test",
  "requires_pause_resume": false,
  "requires_handoff": false
}
```

## 4. 调试类任务

## D01. Worker priority 覆盖不生效

### 2026-06-14 结果

- 已修复：`WorkerRegistry.register()` 现在会保存 `applyPriorityOverride(...)` 返回的 worker，不再把覆盖后的 metadata 写回默认值。
- 已补回归：新增 `WorkerRegistryPriorityOverrideTest`，分别覆盖 metadata 覆盖和同 capability worker 的路由顺序变化；`WorkerRegistryDynamicProviderTest` 继续保留动态 provider 注册面的附测价值。
- 已补测试隔离：`WorkerRegistry` 支持显式传入 priority config 路径；Surefire 默认设置 `agentcloud.worker.priority.config.enabled=false`，避免仓库根目录或用户目录下的本地 `workers.yml` 污染测试集。
- 顺带收口：`WorkerRouter` 在存在 learning-memory hint 时，会继续探测到 hinted worker 的 dispatch readiness，而不是只看第一个 ready worker；这修复了 `routing:coding:deepseek` 这类 hint 无法生效的回归。
- 验证命令：
  `& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=WorkerRegistryPriorityOverrideTest,WorkerRegistryDynamicProviderTest,WorkerRouterRouteTraceTest' test }`
- 2026-06-15 补充：`Test-WithJava21.ps1` 已验证支持直接窄跑 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=WorkerRegistryPriorityOverrideTest`，运行日志明确打印 `Running: ... mvn.cmd -q test -Dtest=WorkerRegistryPriorityOverrideTest`。

### 目标

验证 `workers.yml` / `workers.yaml` 里的 `selection_priority` 覆盖是否真正影响 worker metadata，并在发现缺陷后修复。

### 背景证据

当前 `WorkerRegistry.register()` 里先调用 `applyPriorityOverride(enriched)`，随后又执行 `workers.put(enriched.workerId(), enriched)`。这条路径值得重点排查，因为覆盖后的对象可能被原对象覆盖回去。

### 多轮流程

```text
Round 1: 阅读 WorkerRegistry 和相关测试，确认 priority override 设计意图
Round 2: 写一个最小复现测试，构造 workers.yml 覆盖 codex 或 kimi priority
Round 3: 修复 register/applyPriorityOverride 的对象返回或写入顺序
Round 4: 跑 WorkerRegistry 相关测试，确认 metadata 中保留 selection_priority_overridden=true
Round 5: 如果影响路由，追加 WorkerRouter 路由优先级断言
```

### 验收标准

- 覆盖配置被加载后，目标 worker 的 `selection_priority` 是配置值。
- metadata 保留 `selection_priority_original` 与 `selection_priority_overridden=true`。
- `listReady()` 或路由预览不会丢失该覆盖结果。
- 回归测试能在无真实 provider binary 的环境下运行。

### 建议模式

- `strong_only`：用于先做代码审查和缺陷定位。
- `orchestrated`：强模型规划测试，较小执行器改最小代码。
- `small_only`：用于观察小模型是否容易漏掉“写入后又覆盖”的时序问题。

### 示例任务输入

```json
{
  "title": "D01 调试 Worker priority 覆盖不生效",
  "task_type": "coding",
  "source": "eval",
  "priority": "high",
  "intent": "复现并修复 workers.yml selection_priority 覆盖不生效的问题",
  "goal": "补一个稳定回归测试，并让 WorkerRegistry 注册后的 worker metadata 保留覆盖结果",
  "metadata": {
    "task_pack": "project_evolution_v1",
    "task_case_key": "D01",
    "task_family": "debug",
    "task_length_bucket": "short",
    "model_mode": "orchestrated",
    "acceptance_gate": "regression_test"
  }
}
```

## D02. Provider readiness 与 worker list 不一致

### 目标

调试 `/api/v1/workers` 列表、`/api/v1/workers/{id}/readiness`、provider registry 三者之间 readiness 结果不一致的问题。

### 多轮流程

```text
Round 1: 阅读 WorkerHandler、WorkerRegistry、AgentProviderRegistry 的 readiness 路径
Round 2: 用缺失 binary 的临时 providers.yaml 复现 ready=false
Round 3: 对比 worker list 与 readiness endpoint 的 reason / mode / dispatch_preflight 字段
Round 4: 修复缺失字段、缓存过期或静态 ready 覆盖运行时 ready 的问题
Round 5: 将探针沉淀到 scripts 或现有 precheck 文档
```

### 验收标准

- 缺失 binary 时 worker list 与 readiness endpoint 都返回 `ready=false`。
- reason 能说明 `binary not found`、dispatch preflight 失败或 provider 未注册。
- readiness 缓存不会让已失败 provider 继续参与自动路由。
- 探针能在无真实第三方 CLI 的环境下稳定复跑。

### 建议模式

- `orchestrated`：强模型规划检查面，小模型执行脚本和断言。
- 插入一次 `handoff`：如果执行器缺少本机 CLI 能力，移交给能跑 PowerShell/Node 探针的 worker。

## D03. Chat Facade SSE 流式兼容

### 目标

验证 OpenAI-compatible `/v1/chat/completions` 与 `/v1/responses` 在 `stream=true` 时的 SSE 输出是否稳定，避免前端或 SDK 消费失败。

### 多轮流程

```text
Round 1: 阅读 ChatFacadeHandler 和 ChatFacadeHandlerHttpTest
Round 2: 构造 chat completions stream 请求，检查 data chunk 与 [DONE]
Round 3: 构造 responses stream 请求，检查 response.created / output_text.delta / completed 顺序
Round 4: 补充断开连接、非法 JSON、非 POST 方法的错误响应测试
Round 5: 如有前端消费问题，同步更新 Dialogue 验收 runbook
```

### 验收标准

- SSE `Content-Type` 为 `text/event-stream; charset=UTF-8`。
- 每个 event 使用 `data: ...\n\n`。
- 正常流以 `data: [DONE]` 结束。
- 客户端断开不会打出内部错误响应。
- 非法 JSON 返回稳定错误体，不暴露内部异常。

### 建议模式

- `small_only`：适合跑接口契约检查。
- `strong_only`：适合判断是否与 OpenAI SDK 兼容。

### 2026-06-15 结果

- 已补 focused regression：`ChatFacadeHandlerHttpTest` 通过 `.\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=ChatFacadeHandlerHttpTest` 跑通。
- 已补 acceptance probe：`Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port 18080 -KeepServerLogs` 成功返回结构化结果，`chat_completions` 与 `responses` 两条 surface 都返回 `task_receipt`，`live_flow_available=true`。
- 已补脚本输出收口：`Use-Java21.ps1 -Quiet` 不再输出前缀提示，`Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 现在可稳定产出纯 JSON 结果。

## D04. Pause/Resume packet 连续性

### 目标

验证任务暂停后生成 packet、恢复后能延续 `next_step`、artifact、route/judgment trace，而不是从头开始。

### 多轮流程

```text
Round 1: 创建一个需要多轮执行的 coding 任务
Round 2: 执行一次 continue 后调用 pause
Round 3: 读取 /packet、/checkpoints/{taskId}、/live_flow
Round 4: 调用 resume，再执行 continue
Round 5: 对比恢复前后的 next_step、assigned_worker、recent_artifacts
```

### 验收标准

- pause 后持久化 resume packet。
- checkpoint 中能看到 pause_before 或等价恢复快照。
- resume 后 task identity 不变。
- 恢复后不会丢失最新 worker artifact 与 judgment trace。
- 如果恢复信息不足，应进入 `waiting_human` 而不是静默重跑。

### 建议模式

- `orchestrated`：最适合验证 planner/executor/evaluator 三段证据是否跨 resume 保留。
- 必须插入一次真实 `pause/resume`。

## 5. 修改类任务

## M01. baseline_matrix_v2 真实任务集

### 目标

把本文档中的 D/M/O 任务整理为 `baseline_matrix_v2` 候选 case，使实验矩阵不再只有抽象任务标题。

### 多轮流程

```text
Round 1: 选择 D01、D03、M02、O03 作为首批 short/medium/long case
Round 2: 为每个 case 定义 workspace_preconditions、acceptance_criteria、expected_artifacts、recovery_policy
Round 3: 修改 ExperimentMatrixService 或新增 case provider，保留 baseline_matrix_v1 兼容
Round 4: 更新 ExperimentMatrixServiceTest，验证 catalog 和 metadata 落盘
Round 5: 跑 Run-BaselineMatrixGateProbe.ps1，生成 v2 smoke 证据
```

### 验收标准

- v1 case 不被破坏。
- v2 case key 稳定，例如 `debug-worker-priority-001`。
- 创建 task 时 metadata 带 `task_pack=project_evolution_v1` 或 `baseline_matrix_v2`。
- 每个 case 都有明确 acceptance gate。
- summary 能按 case 和 model mode 聚合。

### 建议模式

- `strong_only`：先做 case 设计。
- `orchestrated`：落代码和测试。

### 2026-06-15 结果

- 已验证 `ExperimentMatrixServiceTest,ExperimentRunServiceTest` 可通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest"` 跑通。
- 调用时要保留引号；在 PowerShell 里直接裸写逗号分隔的 `-Dtest=...,...` 会先被解析成参数错误，而不是脚本或 Maven 失败。
- 已补 gate probe：`Run-BaselineMatrixGateProbe.ps1` 通过，生成 `.tmp\baseline-matrix-gate-20260615.json`，`created_run_count=9`、`summary_total_runs=9`。

## M02. Packet schema 固化

### 目标

把 resume packet 与 handoff packet 的最小字段写成可依赖 contract，并让 API、测试、文档对齐。

### 多轮流程

```text
Round 1: 对照 PacketBuilderProtocolTest、ConsolidationServiceProtocolTest 和 API_CONTRACTS
Round 2: 列出 resume/handoff packet 当前真实字段与缺失字段
Round 3: 更新 docs/API_CONTRACTS.md 或新增 contract 小节
Round 4: 如 runtime 输出缺字段，补最小代码和 mapper/test
Round 5: 用 pause/resume 与 handoff 路径各跑一个回归测试
```

### 验收标准

- resume packet 至少包含 task_identity、current_status、current_node、assigned_worker、latest_summary、next_step、recent_artifacts、recent_decisions。
- handoff packet 至少包含 from_worker、to_worker、why_handoff、what_done、what_remaining、cautions、resume_hint。
- 字段命名与 JSON snake_case 一致。
- 文档写明 machine-readable 字段优先，human summary 只是辅助。

### 建议模式

- `orchestrated`：强模型负责 schema 判断，小模型负责字段对齐和测试。
- 需要一次 `handoff` 验证。

### 2026-06-30 结果

- 先按 `PacketBuilderProtocolTest`、`TaskServicePacketContractTest`、`ConsolidationServiceProtocolTest`、`API_CONTRACTS.md` 回看了一轮，确认 resume / handoff / refined packet 的最小 typed schema 在 builder、service 与 consolidation 路径上已经一致。
- `handoff_packet` 的 HTTP typed schema 之前已经有 `TaskHandlerControlActionHttpTest` 覆盖；本轮真正补齐的是 `refresh_packet` 与 `packet` 两条 resume packet API 的 HTTP 合同缺口。
- 新增 `TaskHandlerControlActionHttpTest#getAndRefreshResumePacketReturnTypedMachineReadableSchema` 后，当前已能在接口层直接断言 `packet_version=1.1`、`task_identity`、`current_status/current_node/assigned_worker`、`latest_summary/next_step`、`blockers/open_questions`、`recent_artifacts/recent_decisions` 以及 payload 中的 continuity alias。
- focused suite 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,TaskHandlerControlActionHttpTest"` 跑通；dated 证据记录见 `M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`。

## M03. GET 控制入口退役准备

### 目标

为旧 `GET /pause|resume|continue|escalate` 兼容入口制定退役计划，并补充观测字段，避免缓存或预取误触发继续存在。

### 多轮流程

```text
Round 1: 阅读 TaskHandlerControlActionHttpTest 和 API_CONTRACTS 中控制动作说明
Round 2: 确认 GET 兼容入口当前审计字段和事件投影
Round 3: 设计 deprecation metadata、日志和文档提示
Round 4: 如代码缺字段，补充 legacy_route_used 或 equivalent audit marker
Round 5: 更新测试，确保 POST 是正式路径，GET 仅兼容且可观测
```

### 验收标准

- 新接入文档只推荐 POST。
- GET 兼容调用有可查询审计痕迹。
- GET 不新增功能，不成为新客户端依赖。
- 下线条件和迁移窗口写入文档。

### 建议模式

- `strong_only`：适合 contract 和兼容策略设计。
- `small_only`：适合简单测试补充。

### 2026-06-30 结果

- 当前实现层已明确：`POST` 是正式控制路径，历史 `GET /pause|resume|continue|escalate` 与 `GET /sessions/{id}/close` 只保留兼容。
- 兼容 `GET` 现在会稳定返回 `Deprecation: true`、`Sunset: Thu, 31 Dec 2026 23:59:59 GMT`、替代 `POST` 的 `Link/Warning/X-AgentCloud-Replacement-Method`，且同一请求会在消息与事件投影里留下 `request_method=GET`、`request_path`、`legacy_control_route=true`。
- 本轮真正补齐的是测试层：`TaskHandlerControlActionHttpTest` 现已覆盖 `legacy GET resume / continue / escalate` 的 handler-level 审计元数据，`ControlActionHttpRouteTest` 现已覆盖 `legacy GET pause / resume / continue / escalate` 的 route-level deprecation headers 与投影契约，`SessionHandlerLifecycleHttpTest` 继续覆盖 `legacy GET close session`。
- focused suite 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskHandlerControlActionHttpTest,ControlActionHttpRouteTest,SessionHandlerLifecycleHttpTest"` 跑通；dated 证据记录见 `M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`。

## M04. Chat/Dialogue 生命周期投影补齐

### 目标

补齐 Dialogue 消息层中 task lifecycle 的稳定投影，让 `task_progress`、`task_result`、`human_gate`、recovery 状态更容易回放。

### 多轮流程

```text
Round 1: 阅读 SessionService、TaskServiceMessageReceiptTest、dialogue/app.js
Round 2: 找出 runtime event 到 session message 的现有映射
Round 3: 定义新增 subtype 的白名单和字段合同
Round 4: 更新服务端投影或前端渲染
Round 5: 跑后端消息测试和浏览器级 Dialogue 验收探针
```

### 验收标准

- 终态任务稳定产生 `task_result`。
- 继续执行中间态稳定产生 `task_progress`。
- `waiting_human` 有明确 pending question 或 reason。
- 前端不会把 provider failure、human gate、terminal result 混成同一种消息。

### 建议模式

- `orchestrated`：规划 contract，执行局部前后端改动，强模型验收。
- 适合插入一次 `pause/resume`。

## 6. 优化类任务

## O01. ControlNodeGraph 拆分

### 目标

降低 `ControlNodeGraph.java` 的大文件风险，把失败分类、节点执行、trace 写入拆成更小组件。

### 多轮流程

```text
Round 1: 标记 ControlNodeGraph 内部职责边界，不做行为修改
Round 2: 优先抽出 FailureClassifier，保留旧测试全绿
Round 3: 再抽出 worker round trace 写入辅助类
Round 4: 最后考虑节点执行器拆分
Round 5: 每拆一步都跑 focused engine tests
```

### 验收标准

- 第一阶段不改变 public API。
- 每次拆分都可以单独回滚。
- 失败分类测试能独立覆盖常见 provider failure。
- `ControlNodeGraph` 行数和认知复杂度下降。
- live_flow、judgment_trace、experiment_run 字段不回退。

### 建议模式

- `strong_only`：适合边界设计和风险评估。
- `orchestrated`：适合按阶段执行拆分。
- 需要人工 gate：如果 diff 超过预期范围，应停下确认。

## O02. TaskService 观测面聚合瘦身

### 目标

让 `TaskService` 中 live_flow、judgment_trace、harness_trace、experiment_run 的聚合逻辑继续向 `RuntimeFactSetAssembler` 或更清晰的 assembler 边界收束。

### 多轮流程

```text
Round 1: 找出 TaskService 中重复读取 event/decision/artifact/tool trace 的方法
Round 2: 画出 RuntimeFactSetAssembler 已覆盖和未覆盖的事实集
Round 3: 抽一个低风险查询路径先复用 assembler
Round 4: 补 TaskServiceLiveFlowViewTest 或 RuntimeFactSetAssemblerTest
Round 5: 对比 JSON shape，确认外部 API 不变
```

### 验收标准

- 外部 API shape 不变。
- 重复 DAO 查询或重复组装逻辑减少。
- RuntimeFactSet 字段来源更明确。
- 测试覆盖至少一个被迁移的观测面。

### 建议模式

- `orchestrated`：强模型判断 API 兼容，小模型执行局部重构。

## O03. Experiment matrix acceptance gate

### 目标

把 baseline matrix 从 HTTP 合同 smoke 升级到质量验收 gate，使实验结果能判断 artifact 是否可接受。

### 多轮流程

```text
Round 1: 阅读 ExperimentRunService、ExperimentMatrixService、Run-BaselineMatrixGateProbe.ps1
Round 2: 定义 acceptance_result 的最小自动判定规则
Round 3: 为 short case 增加 artifact 类型和断言规则
Round 4: 将 gate 结果写入 experiment_run metadata
Round 5: summary 增加 accepted/rejected/needs_followup 对比证据
```

### 验收标准

- 不只统计 created run，还能统计 accepted/rejected/needs_followup。
- acceptance gate 的失败原因可读。
- cost threshold 和 artifact quality 可以分别记录。
- 仍允许人工最终复核，但不能只靠人工备注。

### 建议模式

- `strong_only`：定义验收口径。
- `orchestrated`：实现 gate 和 summary。
- 可以从 D01 作为第一个真实 acceptance case。

## O04. 长任务收口质量

### 目标

明确长任务什么时候继续、什么时候 done、什么时候进入 human gate，减少自动续跑灰区。

### 多轮流程

```text
Round 1: 收集 task_result、worker_round、completion judgment、human_gate 的现有字段
Round 2: 定义 terminal readiness checklist
Round 3: 对 declared rounds / auto_multi_round / no_progress_guard 建立状态机规则
Round 4: 补一个长任务失败或卡住场景的回归测试
Round 5: 更新 TROUBLESHOOT 或 LIVE_FLOW_RUNBOOK 的排障入口
```

### 验收标准

- `done` 必须有 completion judgment 或等价终态证据。
- `waiting_human` 必须说明等待什么输入。
- auto-continue 有最大轮次或 no-progress guard。
- 长任务失败时能区分 provider failure、artifact 不足、用户信息不足。

### 建议模式

- `orchestrated`：最符合该场景，因为需要规划、执行、判断三段证据。
- 必须至少插入一次 `pause/resume` 或 `handoff`。

### 2026-06-15 结果

- 先用 packet/message/experiment-run 相关 focused tests 加 `ControlNodeGraphOrchestrationFlowTest` 做了一轮聚合窄跑，失败收口到 3 个 O04 场景，且共同表现为最终 `human_gate` 终态丢失 `auto_handoff_count`。
- 根因已定位为 `ControlNodeGraph.applyRecoveryDirective(...)` 在非 auto-handoff 分支误删历史 `auto_handoff_count`；修复后保留计数、继续清理 `auto_handoff_target`。
- 已通过 `Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ControlNodeGraphActionResolutionTest,ControlNodeGraphOrchestrationFlowTest"` 完成 focused regression 回归，并补了 dated execution record。

## 7. 首批推荐执行顺序

建议先跑 4 个任务，形成最小闭环：

| 顺序 | 任务 | 原因 |
|------|------|------|
| 1 | D01 Worker priority 覆盖不生效 | 范围小、疑点明确、适合快速拿到回归测试 |
| 2 | D03 Chat Facade SSE 流式兼容 | 契约清晰、容易形成 HTTP 测试 |
| 3 | M01 baseline_matrix_v2 真实任务集 | 把真实任务固化进评估面 |
| 4 | O03 Experiment matrix acceptance gate | 让实验不止能创建任务，还能判断质量 |

第一批完成后，再进入：

- D04，验证 continuity 是否能承载真实修改任务。
- M02，固化 packet contract。
- O01/O02，开始有安全网的大文件拆分。

## 8. 测试驱动入口

本文档的执行计划已经进一步落到 `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`，其中明确了：

- 每个任务先挂哪个测试类
- 失败后优先看哪个脚本或观测面
- 什么情况下该 pause / handoff / human gate
- 如何把单次修复沉淀回 baseline matrix

实际执行步骤与任务输入模板则进一步落到 `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`。

## 9. 最小验收记录模板

每次跑任务后，建议至少记录：

```text
task_id:
task_case_key:
model_mode:
worker path:
round count:
resume count:
handoff count:
human gate count:
files touched:
tests run:
acceptance_result:
failure_reason:
live_flow path:
judgment_trace path:
tool_trace path:
next action:
```

## 10. 结论

这组任务的目标不是再写一套抽象 benchmark，而是让 `agent-cloud-harness` 用自身的真实工程问题来验证自身：

- 调试类任务验证 harness 能不能定位和修复具体缺陷。
- 修改类任务验证 harness 能不能把设计收敛成代码和 contract。
- 优化类任务验证 harness 能不能承载多轮、低风险、可回滚的项目演进。

如果 D01、D03、M01、O03 能在三模式下稳定复跑，并形成可读的 `experiment_summary`，项目就具备了把 `baseline_matrix_v1` 升级为真实工程任务矩阵的基础。
