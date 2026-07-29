# Provider / Worker / Recovery

本专题覆盖 Provider 接入、Worker 注册与路由、恢复策略、handoff、工具层，以及本地 CLI 协议集成。

> 2026-07-21 方向调整：Codex 多 provider 的协议差异已由本机 CCX 网关统一收敛，provider 专项工作从主线优先级降级，只在接入新协议或读面诊断时推进。下一阶段方向主入口为 `../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md`。

当前 `provider/` 已升级到轻量工作区：除 `README.md` 外，已启用 `PROGRESS.md`，用来承接 provider 路由、codex profile、CLI protocol 接入、manual window 与恢复策略的持续推进。当前默认阅读顺序是 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md`；`tasks/`、`archive/` 仍未启用，`runs/README.md` 负责 provider 主题的 dated evidence 聚合入口。

当前 provider 主题内部已经不止一条线，不要把下面所有文档都当成并列主线。先判断当前任务属于哪一类，再进入对应子主题：

- provider 总体接入边界 / API contract
- 全局路由与恢复
- codex family 多 profile lane
- 具体 CLI protocol / 参数 / parser 接入
- 工具试点、console 读面或专项调研

## 命中信号

- 任务提到 provider、worker、route、handoff、recovery、manual window
- 任务提到 codex/codebuddy/deveco/trae/reasonix 等 provider lane
- 任务提到 tool layer、本地 CLI protocol、dispatch readiness、profile routing

## 先做子主题判断

| 当前问题 | 先看哪里 | 再下钻 |
|------|------|------|
| 新 provider 如何接进系统、provider/agent run/API 合同是什么 | `../AGENT_PROVIDER_TECHNICAL_DESIGN.md` | `../AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`、`../API_CONTRACTS.md` |
| 免费优先、paid fallback、manual window、自动恢复怎么收口 | `../FREE_FIRST_PROVIDER_ROUTING_DESIGN.md` | `../WORKER_FAILURE_RECOVERY_POLICY.md`、`../FREE_FIRST_PROVIDER_ROUTING_PLAN.md` |
| codex 的 OpenAI / xfyun / deepseek profile 怎么路由和透传 | `../CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md` | `../CODEX_MULTI_API_PROFILE_ROUTING_PLAN.md`、`../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md` |
| codebuddy / deveco 的命令参数、parser、resume 怎么接 | `../DEVECO_AND_CODEBUDDY_PROVIDER_PARAMS_PLAN.md` | `../AGENT_PROVIDER_TECHNICAL_DESIGN.md` |
| OmniRoute 这种本地 OpenAI-compatible 网关怎么自动拉起、验活并接到 Harness | `../OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md` | `../AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`../STARTUP_GUIDE.md` |
| `/v1/chat/completions` 这类 HTTP 调用 agent provider 支不支持、要不要建 provider_http lane | `../HTTP_PROVIDER_EXECUTION_DIRECTION.md` | `../AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`../OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md` |
| tool-aware worker、本地文档试点、provider run/console 读面 | `../TOOL_LAYER_IMPLEMENTATION_PLAN.md` | `../LOCAL_DOC_WORKER_PILOT.md`、`../AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md` |
| 专项接入评估或单 provider 深挖 | 对应专项文档 | 例如 `../REASONIX_AGENT_SUPPORT_AND_IMPROVEMENT_PLAN.md` |
| task_type / workspace 推断改用 LLM 替代硬编码关键词、codex 跑错仓库 | `../LLM_TASK_UNDERSTANDING_PLAN.md` | `../WORKER_FAILURE_RECOVERY_POLICY.md`、`../AGENT_PROVIDER_TECHNICAL_DESIGN.md` |

## 最小阅读顺序

1. `PROGRESS.md`
2. `../AGENT_PROVIDER_TECHNICAL_DESIGN.md`
3. `../API_CONTRACTS.md`
4. `../WORKER_FAILURE_RECOVERY_POLICY.md`
5. `../TROUBLESHOOT.md`
6. 如果任务已经明确落在某条子线，再按上面的子主题判断进入对应文档，不需要把本专题所有计划全文通读一遍。

## 稳定基线

- `../AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- `../AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- `../API_CONTRACTS.md`
- `../WORKER_FAILURE_RECOVERY_POLICY.md`
- `../CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md`

这些文档更接近“今天仍然为真”的 provider 边界、协议字段、恢复口径。若本轮修改改变了稳定行为，优先回写这里。

## 当前主线文档

### 主题进度

- `PROGRESS.md`

### Dated Evidence 聚合入口

- `runs/README.md`

### 免费模型 Worker Lane

- ../FREE_MODEL_WORKER_LANE_PLAN.md
- `../E2_CODEX_FREE_E2E_SMOKE_EXECUTION_RECORD_2026-07-29.md`：codex-free lane（经本地 CCX + codex app-server）真机 e2e 冒烟 + 长任务收口合同字段验证。

### 全局路由与恢复

- `../FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`
- `../FREE_FIRST_PROVIDER_ROUTING_PLAN.md`
- `../WORKER_FAILURE_RECOVERY_POLICY.md`
- `../CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md`

### Codex family / profile lane

- `../CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md`
- `../CODEX_MULTI_API_PROFILE_ROUTING_PLAN.md`
- `../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md`

### Provider / CLI 接入

- `../DEVECO_AND_CODEBUDDY_PROVIDER_PARAMS_PLAN.md`

### OpenAI-compatible 本地网关

- `../OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md`

### HTTP Provider 执行方向

- `../HTTP_PROVIDER_EXECUTION_DIRECTION.md`：`/v1/chat/completions` 三层现状（入站 facade / 出站 LLM upstream / provider_http lane）与“暂不建 provider_http、中期按需补设计”的决策。

### 兼容性与宿主机边界

- `../TEXT_ENCODING_COMPATIBILITY_PLAN.md`

### 工具层与观测面

- `../TOOL_LAYER_IMPLEMENTATION_PLAN.md`
- `../AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`
- `../LIVE_FLOW_RUNBOOK.md`

## 专项与试点材料

- `../COMPLEX_REQUIREMENT_DESIGN_IMPLEMENT_VERIFY_PLAN.md`
- `../LOCAL_DOC_WORKER_PILOT.md`
- `../REASONIX_AGENT_SUPPORT_AND_IMPROVEMENT_PLAN.md`
- `../WORKER_PROMPT_HEADER_DEDUP_PLAN.md`

## 外部项目调研

- `../AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md`：kimi3 开源 AgentENV（Firecracker microVM 沙箱执行底座，驱动 Kimi K3 agentic RL）调研；harness 缺的"隔离/可快照/可 fork 执行环境"北极星参照，含 instance identity 事件乱序保护、三层持久化纪律、fork 并行探索等启发点。

## 写回顺序

- 主题级短进展、当前焦点、未完成/下一步/风险：
  - 优先写 `PROGRESS.md`
- focused execution/precheck/dated 证据聚合入口：
  - 优先更新 `runs/README.md`
  - 再决定是否补新的 `*_EXECUTION_RECORD_YYYY-MM-DD.md`

- Provider 身份、字段合同、protocol 字段、agent run 读面变化：
  - 优先写 `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
  - 契约变化同步 `AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`、`API_CONTRACTS.md`
- 自动路由、handoff、manual window、quota/fallback 变化：
  - 优先写 `WORKER_FAILURE_RECOVERY_POLICY.md`
  - 如果是设计收口，再同步对应 `FREE_FIRST_PROVIDER_ROUTING_*`
- codex profile lane、`app_server / exec_json` 双链透传、profile 读面变化：
  - 优先写 `CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md` / `CODEX_MULTI_API_PROFILE_ROUTING_PLAN.md`
  - 有真实验证时再补 `*_EXECUTION_RECORD_YYYY-MM-DD.md`
- 具体 CLI 参数、parser、resume、宿主机兼容性变化：
  - 优先写对应专项 plan，例如 `DEVECO_AND_CODEBUDDY_PROVIDER_PARAMS_PLAN.md`
  - 若是跨 provider 的稳定现象，再回收进基线文档
- 跨主题短摘要：
  - 写 `../STATE.md`
  - 稳定规则写 `../DECISIONS.md`

## 历史材料使用规则

- `../AGENT_PROVIDER_CODE_SKELETON_PLAN.md` 属于 provider phase-1 骨架清单，今天更适合作为历史 scaffold 对照，不应再作为新任务第一入口。
- dated execution record 只用来证明某轮真实收口，不应用来代替长期设计或契约入口。
- `../LOCAL_DOC_WORKER_PILOT.md`、`../REASONIX_AGENT_SUPPORT_AND_IMPROVEMENT_PLAN.md` 都是专项/试点材料，只有任务明确命中它们时才优先进入。
- 如果旧记录里的参数、恢复口径或 API 合同今天仍然有效，应提炼回设计文档、契约文档或故障口径。

## 当前入口建议

- 要先看最近活跃焦点和风险：`PROGRESS.md`
- 要按 provider 子线回看 dated execution evidence：`runs/README.md`
- 要看稳定 provider 边界：`../AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- 要看 provider API / run / inventory 契约：`../AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- 要看当前路由方案：`../FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`
- 要看 codex 多 profile lane：`../CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md`
- 要看本轮 codex focused 收口证据：`../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md`
- 要看 codebuddy / deveco 真机参数：`../DEVECO_AND_CODEBUDDY_PROVIDER_PARAMS_PLAN.md`
- 要看 OmniRoute 本地网关自动化接入与验活口径：`../OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md`
- 要看工具试点或 provider 观测面：`../TOOL_LAYER_IMPLEMENTATION_PLAN.md`、`../AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`
- 不要直接从 `../AGENT_PROVIDER_CODE_SKELETON_PLAN.md` 开工，除非你是在核对早期骨架与当前实现差异。
