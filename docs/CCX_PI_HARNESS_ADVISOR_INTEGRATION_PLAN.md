# CCX + Pi + Harness + Advisor 集成落地计划

> 本文档是产品决策而非架构构想；所有判断基于当前 harness 实际代码能力，不基于假设。

## 1. 当前事实基线

### harness 已具备的能力

- **Provider 注册**：14 个内置 provider（codex/openclaw, claude, copilot, deepseek, opencode, hermes, gemini, **pi**, cursor, kimi, kiro, codebuddy, **trae**, reasonix, deveco）
- **Protocol 解析**：`ProviderProtocolRegistry` 支持 per-provider output parser，已注册 codex/copilot 协议
- **Worker 执行**：`ProviderCliWorkerExecutor` 统一 CLI 调用，`ToolAwareWorkerExecutor` 支持最多 3 步 tool chain
- **任务连续性**：session / task / checkpoint / resume packet / handoff packet 已落地
- **控制图**：intake -> scheduler -> continue -> packet -> human_gate -> handoff，goal-progress 已接入
- **Goal 合同**：`initializeGoalContract` 在 task 创建时自动补齐 goal/subgoals/subgoal_status/progress_summary
- **Runtime judgment**：`RuntimeJudgmentService` 消费 subgoal_status，输出 HALT/CONTINUE/ESCALATE
- **UI 状态展示**：dialogue 的 pinned outcome 卡展示 subgoal progress，console 的 task/run badge 分层

### CCX 的角色（已收敛）

CCX 网关已正式负责：
- Responses API ↔ Chat Completions 转换
- 多 provider 路由、429 冷却、Key 轮换
- 模型名混淆兜底（`fuzzyModeEnabled + modelMapping`）

harness 只消费 CCX 归一化后的 provider run metadata/status/events，不再负责 provider 差异收敛。

### Pi 的角色（已注册但未激活）

Pi 在 `BuiltinAgentProviders.defaults()` 中已注册为 `model_tier: small` provider。当前未注册 protocol，实际使用取决于 `MULTICA_PI_PATH` 环境变量是否指向可用 CLI。

### Trae 的角色（已注册但无 protocol）

Trae 在 `BuiltinAgentProviders` 中注册为 `model_tier: strong`，但 `ProviderProtocolRegistry.defaultRegistry()` 中无 trae protocol。当前只能作为 `manual_window` 位使用，不能自动 route 或 auto-handoff。

## 2. 产品决策：四层集成的最小可行切面

### 决策 1：不另建 Pi 执行层

harness 的 `ToolAwareWorkerExecutor` 已实现 tool chain 执行（最多 3 步），与 Pi 的 parallel tool execution 是重叠能力。集成路径不是"Pi 替代 harness 执行"，而是：

**Pi 作为 harness 的一个 worker**，通过 `ProviderCliWorkerExecutor` 被调度。harness 控制 task 生命周期和 handoff，Pi 负责单轮 agent loop。

落地动作：
1. 确认 `MULTICA_PI_PATH` 指向 Pi CLI
2. 为 Pi 注册 `ProviderProtocol`，解析其事件流输出为 `WorkerExecutionResult`
3. 在 `WorkerRouter` 中让 Pi 成为 `small` tier 的可调度 worker

### 决策 2：Advisor 是模型间咨询，不是人在环

更正 Obsidian 文档中的定位：Trae Advisor 不是人类评审者，而是"低能力模型向高能力模型请教思路"的咨询通道。

落地形态：
- 不引入 `AdvisorService` / `ReviewResult` 等独立服务
- 而是把 Advisor 调用建模为 harness 的 **handoff 的一种语义**：当前 worker（如 Pi/CodeBuddy）遇到需要更强推理的判断点时，handoff 给 high-tier worker（如 Codex/Claude/Reasonix）做 advisory 判断，然后 handoff 回原 worker 继续执行
- 这条路径已有基础设施：`handoffTask` + `HandoffPacket` + cross-worker stability contract

落地动作：
1. 在 `ControlNodeGraph.continueNode` 中增加 advisory handoff 判断条件（当前 worker 为 small tier 且 judgment 为 ESCALATE 时，优先 handoff 给 strong tier advisory worker 而不是直接 human_gate）
2. handoff packet 中区分 `why_handoff = advisory_consult` 和 `why_handoff = worker_failure`
3. advisory 完成后自动 resume 原任务，不需要人工介入

### 决策 3：CCX 保持当前边界

CCX 已收敛为 provider 接入层，harness 不复制其路由逻辑。当前边界不变。

### 决策 4：不引入新的进程间通信协议

Pi 和 harness 在同一进程内通过 `ProviderCliWorkerExecutor` 的 CLI 调用交互。不引入 gRPC / WebSocket / event bus 等额外通信层。Pi 的事件流通过 protocol parser 转为 harness 的 `WorkerExecutionResult`。

## 3. 落地优先级

| 优先级 | 切片 | 验收标准 | 预计改动面 |
|--------|------|----------|------------|
| P1 | Pi protocol 注册 | Pi 作为 small-tier worker 被 WorkerRouter 调度，单轮执行结果进入 WorkerExecutionResult | ProviderProtocolRegistry + 1 个 PiProtocol |
| P2 | Advisory handoff 语义 | small-tier worker ESCALATE 时自动 handoff 给 strong-tier advisory，advisory 完成后 auto-resume 原任务 | ControlNodeGraph + HandoffPacket why_handoff 字段 |
| P3 | Trae protocol 注册 | Trae 从 manual_window 升级为可自动调度的 strong-tier worker | ProviderProtocolRegistry + 1 个 TraeProtocol |

## 4. 不做的事

- 不为 Advisor 引入独立服务层（`AdvisorService` / `ReviewResult`）
- 不引入 Pi 的事件总线作为 harness 的新通信层
- 不让 Pi 替代 harness 的 `ControlNodeGraph` / `TaskService` 编排逻辑
- 不在 harness 内复制 CCX 的路由/Key 轮换逻辑

## 5. 与现有文档的关系

- 取代 Obsidian 46 号文档中的"四层集成架构探索"，把构想收成可执行的产品决策
- 与 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 的 Loop/Goal 主线对齐：advisory handoff 是 loop 中 decide 节点的一种新分支
- 与 `API_CONTRACTS.md` / `SPEC.md` 对齐：advisory handoff 的 packet 字段将补入 HandoffPacket 最小字段集

## 6. 落地状态

| 优先级 | 状态 | 说明 |
|--------|------|------|
| P1 | done | Pi protocol 注册已完成，PiProtocol + PiProtocolTest 全绿 |
| P2 | done | Advisory handoff 语义已完成，ControlNodeGraph + AdvisoryHandoffTest 全绿 |
| P3 | done | Trae protocol 注册已完成，TraeProtocol + TraeProtocolTest 全绿 |
