# HTTP Provider 执行（/v1/chat/completions）现状评估与方向决策

> 背景：调研"harness 是否支持 HTTP 调用 agent provider（如 `/v1/chat/completions`）"。
> 性质：决策文档。结论：分层已有部分支持；full HTTP-as-worker-lane（`provider_http`）暂不建，先走现有 LLM-upstream + CCX+CLI lane，中期按需再补 `provider_http` 设计。
> 配套：`AGENT_PROVIDER_TECHNICAL_DESIGN.md` §2.3.1、`FREE_MODEL_WORKER_LANE_PLAN.md`、`OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md`、`CURRENT_CAPABILITY_GAP_ASSESSMENT.md`。

## 1. 三层现状

| 层 | 现状 | 机制 | 入口 |
|---|---|---|---|
| 入站：harness 作为 OpenAI-compatible 服务端 | ✅ 已支持 | `ChatFacadeService` 暴露 `POST /v1/chat/completions` + `/v1/responses`，把 chat 请求映射成 session/task | `ChatFacadeService.java:30` `CHAT_COMPLETION_PATH`；`Main.java:314` |
| 出站：LLM upstream（harness 调 OpenAI-compatible 端点做 judgment + 工具规划） | ✅ 已支持 | `OpenAiCompatibleClient`（JDK HttpClient，`chat_completions`/`responses` 双 wire_api），经 `OPENAI_BASE_URL`/`OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_WIRE_API` 配置；被 `PromptBasedJudgmentService`（judgment）与 `ToolAwareWorkerExecutor.planTool`（worker round 的工具决策）复用 | `OpenAiCompatibleClient.java`；`LlmConfig.java`；`ToolAwareWorkerExecutor.java:496 invokeTool` |
| 出站：HTTP 模型作为一等 worker 执行 lane（`provider_http`） | ❌ 未支持 | `ProviderExecutionSupport` 仅有 `provider_native_cli` + `provider_app_server`(codex)；所有 `WorkerExecutor` 用 `ProcessBuilder`；`AgentProviderDescriptor.transport` 默认 `process`（无 `http` 实现） | `ProviderExecutionSupport.java`；`ToolAwareWorkerExecutor`/`ProviderCliWorkerExecutor`/`CodexAppServerWorkerExecutor` |

关键澄清：HTTP `/v1/chat/completions` 并非"完全没接"。`ToolAwareWorkerExecutor` 已经用 `OpenAiCompatibleClient` 跑 in-harness 工具循环（`planTool` -> `invokeTool` -> 多轮，默认 4 轮、目录任务 8-10 轮），CLI 只是 fallback 与非工具轮的执行者。缺的是"把某个 HTTP 模型端点配成一条独立 worker lane（per-lane `base_url`/`model`/`api_key`），让它不依赖 CLI agent 直接承担 worker round"。

## 2. 设计线索（文档已有的意图）

- `AGENT_PROVIDER_TECHNICAL_DESIGN.md` §2.3.1 明确：OpenAI-compatible 本地网关（OmniRoute 等）先归 LLM upstream 层（`OPENAI_BASE_URL`），不直接算 provider lane；在没有专属 executor/protocol/inventory contract 前不写成 `worker_id`。
- `FREE_MODEL_WORKER_LANE_PLAN.md`：免费模型走 CCX 网关 + codex CLI lane（`codex-free`），harness 仍 spawn codex CLI 指向 CCX，不直接 HTTP 执行。
- `AgentProviderDescriptor.transport` 字段（`process`/`pty`）是 `http` 扩展钩子；`ProviderExecutionSupport` 可加 `provider_http` backend。
- `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`：当前战略优先级是证明 strong->small 编排主闭环与长任务收口合同，provider 专项已降级（差异由 CCX 收敛）。

## 3. 决策

**不立即建 `provider_http` worker 执行 backend。** 理由：

1. HTTP LLM 价值已通过 upstream 层获得（judgment + 工具规划），免费模型已通过 CCX+CLI lane 获得。
2. `provider_http` lane = 在 harness 内建完整 agent 循环（native tool-calling / 流式 / 工具分发），与 `ToolAwareWorkerExecutor` + CLI agent 现有职责重叠，是大表面新增。
3. 与当前战略优先级（编排主闭环、长任务收口）不一致；扩 provider 概念被明确降级。

## 4. 中长期方向

### 近期（本阶段，对齐 CURRENT_CAPABILITY_GAP_ASSESSMENT）
- 收硬 strong->small 编排主闭环（P1）与长任务收口合同（`continue`/`done`/`waiting_human` 边界）。
- 免费模型走 CCX+`codex-free` lane（`FREE_MODEL_WORKER_LANE_PLAN`），低风险复用 CLI executor。
- 强化现有 HTTP LLM upstream：可把 `ToolAwareWorkerExecutor` 的工具规划路径做得更一等公民（prompt 收口、可选 native `tools` API），无需新 backend。

### 中期（先设计文档，按需再建）
- `provider_http` backend 的触发条件：要编排"无 CLI agent 包装"的模型（raw OpenAI/Kimi/DeepSeek API、本地 vLLM）为一等 worker lane，或为 eval matrix 广度砍掉 CLI 开销。
- 复用基础：`ToolAwareWorkerExecutor` 已有 in-harness 工具循环 + `ToolRegistry`/`ToolPolicy` 面，`HttpAgentWorkerExecutor` 可复用，只是把 `LlmClient` 配成 per-lane（`harness-config.yml` worker profile 扩 `base_url`/`model`/`api_key`/`wire_api`）并可选 native `tools`。
- 落地前先写 `HTTP_PROVIDER_EXECUTION_DESIGN.md`：`transport=http`、`provider_http` backend、per-lane `LlmClient`、native tool-calling vs 文本协议取舍、failure 分类复用、流式 vs 批量。

### 长期：执行底座抽象
- 可插拔执行 backend（CLI / HTTP / sandbox-microVM）统一在 `transport` 字段后，对齐 `AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md` 北极星（隔离/可快照/可 fork 执行环境）。
- 多 provider fan-out / fork 并行探索。

## 5. 参考入口

- 入站 `/v1/chat/completions`：`src/main/java/com/agentcloud/engine/ChatFacadeService.java:30`
- 出站 LLM upstream：`src/main/java/com/agentcloud/llm/OpenAiCompatibleClient.java`、`src/main/java/com/agentcloud/llm/LlmConfig.java`
- HTTP LLM 驱动的 in-harness 工具循环：`src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java:496`
- backend 矩阵（无 `provider_http`）：`src/main/java/com/agentcloud/worker/ProviderExecutionSupport.java`
- 设计意图：`docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md` §2.3.1、`docs/FREE_MODEL_WORKER_LANE_PLAN.md`、`docs/OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md`
