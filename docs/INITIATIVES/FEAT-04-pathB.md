# FEAT-04 path B — OpenEyes MCP 13 tool e2e + runtime_facts.openeyes_tool_calls

> **状态**: 验收通过 (accepted; FEAT-05 跟进 delivery verification)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com
> **依赖**: FEAT-04 path A 已落 `OpenEyesTool` (commit: TBD)，本卡是 path A 之后的下一档。

## 立项门槛 (gating thresholds)

1. path A 验收通过（ToolRegistry 注册 OpenEyesTool，默认 enabled=false / register_as_tool=false，回滚 < 1 行）。
2. e2e 任务链: 任务描述「open Feishu → click 消息框 → type hello → screenshot」至少能通过 ToolAwareWorkerExecutor 跑通 `windows → click → type → capture` 四步，每步都进 `ToolInvocationDao`，并产出可回放的 `TaskRunEvidence`。
3. `runtime_facts.openeyes_tool_calls` 记录调用次数 / 延迟 / 成功率；`/console/` Runtime Health 透传该字段。
4. 关闭 `eyes_mcp.enabled=false` 时所有 `openeyes_tool_calls` 指标回零，UI 不再渲染 OpenEyes 卡片。

## 范围

1. 新增 `src/main/java/com/agentcloud/runtime/OpenEyesToolCallMetric.java` 与 `OpenEyesToolCallMetricDao`，记 `worker_id / subcommand / elapsed_ms / success` 进 `tool_invocation` 表(已存在，复用 metadata)。
2. `ToolAwareWorkerExecutor` 在 invoke 完成后异步落 metric；`TaskRuntimeContextBuilder` 把 `openeyes_tool_calls` 聚合到 `runtime_facts`。
3. `NioHttpServer.runtime_health` 暴露 `openeyes_tool_calls.{count,p50,p95,success_rate,last_subcommand}` 字段。
4. `/console/` Runtime Health 新增 OpenEyes 调用历史卡（最近 10 次 subcommand/状态/耗时）。
5. 端到端 smoke: `scripts/Run-OpenEyesE2ESmoke.ps1` 默认 dry-run（仅 `windows list` + `capture`），`-Go` 才真 click/type（依赖真实 Feishu 主窗口；缺窗时 fail-fast）。

## blocker

- path A 落地 + 真实 Feishu / Any VPN / VS Code 等目标窗口就绪。
- runtime_facts 字段在 `RuntimeFactSetAssembler` 已有 schema；增字段属 additive，不需要 contract 变更。

## 当前状态

2026-09-21：path B 的可观测面已先落地。`ToolInvocationDao` 聚合 openeyes 调用次数、成功率、p50/p95 与最近调用；`NioHttpServer` 通过 setter 注入 `HarnessState` 和 DAO，并在 `/api/v1/health.eyes_mcp.tool_calls` 暴露指标；Console Runtime Health 使用当前任务的 `tool_invocations` 渲染 UI assertion 卡。启动 smoke 已修复为 dry-run 预检 `eyes` 与 `eyes-mcp` 两个 binary、`-Go` 调 `eyes-mcp`。真实探针返回 `openeyes/1.26.0`、13 tools；真实 harness health 返回 `healthy`、13 tools、`tool_calls.count=0`（临时 DB 无调用）。
2026-09-22：path B e2e 闭环。`src/test/java/com/agentcloud/worker/OpenEyesToolChainE2ETest.java` 走 `ToolAwareWorkerExecutor` 实测 `windows -> click --go -> type --text hello -> capture --json` 四步；每步落 `ToolInvocationDao`，`tool_chain_step_count=4`，`tool_execution_mode=multi_tool_round`。首次 `-Go` 实测全 success（windows list 1.06s、click --go 1.10s、type --text hello 1.06s、capture --json 1.25s），DAO 4 条记录全 `success=true`，主链路门槛达成。当前 desktop 会话锁光标注入（`SetCursorPos=false`），用例 `Assumptions.assumeTrue(cursorInjectionAvailable())` 自动 skip；后续 FEAT-05 要补 `actionability preflight` / delivery verification，避免 `eyes type` 仅上报派发成功。`docs/AGENT_DESKTOP_OPENEYES_LEARNING.md` 沉淀 `lahfir/agent-desktop@main`（skeleton/drill、headless policy、BARS 风险闸门、ref stable、bounded loop）借鉴要点。Bitable recvvNWh9u3lQZ 状态推进到「验收通过」。

2026-09-22：delivery verification 最小切片已落地。新增 `scripts/Run-OpenEyesTypeVerified.ps1`，包装 `eyes type` 并在前后取 `GetForegroundWindow` title 作为 evidence。支持 `-ExpectedWindowTitle` + `-ActivateIfMismatch` 自动激活目标窗口。输出 JSON 含 `delivery_status`（DISPATCHED_FOCUSED / FOCUS_MISMATCH / FOCUS_CHANGED_DURING_TYPE / DISPATCH_FAILED）、`focus_window_before` / `focus_window_after` / `focus_matches_expected`。实测隔离 WinForms 窗口 PASS：`delivery_status=DISPATCHED_FOCUSED`、`focus_matches_expected=true`、`window_activated=true`。

## sign-off checklist

- [x] path A 已签字 (FEAT-04 立项卡)
- [x] e2e 任务链实测通过
- [x] runtime_facts.openeyes_tool_calls 字段在 /api/v1/health.eyes_mcp.tool_calls 暴露
- [x] /console/ Runtime Health 新卡验收
- [x] CONTRIBUTING.md 该子档状态从候选切换到进行中
- [x] Bitable 项目方向表 recvvNWh9u3lQZ 状态同步

## 参考

- 父立项卡: ../INITIATIVES/FEAT-04.md
- 调研依据: docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.4
- 立项表 schema: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md TBD-007
2026-09-22：delivery verification lib 化。`scripts/lib/OpenEyesDelivery.ps1` 提取 `Resolve-DeliveryStatus` 纯函数，`Run-OpenEyesTypeVerified.ps1` 重构 dot-source 调用。`tests/Test-OpenEyesDelivery.ps1` 10/10 PASS（DISPATCHED_FOCUSED / DISPATCH_FAILED / FOCUS_MISMATCH / FOCUS_CHANGED_DURING_TYPE + 优先级覆盖）。`tests/Run-JevPatrolL3Tests.ps1` 增加 openeyes-delivery 套件，aggregate pass=61。
