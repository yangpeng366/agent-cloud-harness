# Agent Cloud Harness — 项目评价与下一步方案

> 基于对全部源码（203 主文件、56 测试文件、72 份文档）的系统扫描和实际运行验证。
> 评价日期：2026-06-01

---

## 目录

1. [总体评价](#1-总体评价)
2. [分维度评分](#2-分维度评分)
3. [好的部分](#3-好的部分)
4. [需要改进的部分](#4-需要改进的部分)
5. [当前瓶颈与风险](#5-当前瓶颈与风险)
6. [文档体系问题](#6-文档体系问题)
7. [下一步方案](#7-下一步方案)

---

## 1. 总体评价

**一句话**：`agent-cloud-harness` 是一个架构方向正确、有着清晰 continuity-first 叙事、已具备真实 tool-aware 执行 + provider 接入面的 orchestration 控制面项目，但目前仍处于"能力散、大文件多、闭环弱"的早期阶段——像一个已经搭好骨架但没有收肉的引擎。

**3 words**: Promising, scattered, under-tested.

---

## 2. 分维度评分

| 维度 | 评分 | 说明 |
|------|:---:|------|
| **架构方向** | ⭐⭐⭐⭐ | continuity-first control plane 定位明确，6-node 状态机 + provider 分层 + packet 体系构成的核心路线正确 |
| **可运行性** | ⭐⭐⭐⭐ | 单 JAR 启动、零外部依赖、启动后 HTTP API + Web Console + Dialogue 三位一体即可用 |
| **代码整洁度** | ⭐⭐½ | 4 个文件 > 1000 行，最大 4153 行；provider 协议已抽出 `ProviderProtocol` 主路径，但 executor 内仍保留旧 fallback parser 和大段兼容代码 |
| **测试覆盖** | ⭐⭐½ | 已补 worker protocol / discovery / dynamic worker 注册测试，但 `agent/providers`、`model`、`cli` 仍缺分层单元测试 |
| **文档体系** | ⭐⭐ | 72 份 md 但 30+ 份是 GitHub release 临时文档；核心架构文档、API 契约仍需更新 |
| **Provider 可靠性** | ⭐⭐ | 内置 provider-native allowlist 已覆盖 kimi/trae/codebuddy，并支持 `providers.yaml/json` 动态注册；真实 ready 数仍取决于本机 binary、认证和 dispatch preflight |
| **闭环完成度** | ⭐⭐½ | 有 task→execute→judge→handoff 完整链路，但"强模型调小模型"的核心叙事尚未打通 |

**综合**: 3.0 / 5 — 一个已经完成 Codex partial、Dialogue 呈现和 provider discovery 主链改造，但仍需要长任务稳定性、LLM 激活和代码卫生收口的 control plane 底座。

---

## 3. 好的部分

### 3.1 架构基础扎实

- **ControlNodeGraph**（`engine/ControlNodeGraph.java`）的 6-node 状态机是一个干净的设计——Intake → Scheduler → Continue → Packet / Human Gate / Handoff → End。每个节点语义清晰，状态转移规则可追溯。
- **Provider 分层**：`AgentProvider → Worker → WorkerExecutor` 三层解耦。新 provider 可以通过 `LocalCliAgentProvider` 快速接入而不需要写定制 Java 类。
- **Continuity 体系**：Resume Packet + Handoff Packet + Consolidation 五步 + Learning Memory 形成了一条思路连贯的"任务持续交付"链路。

### 3.2 内部可观测面完整

- `/api/v1/tasks/{id}/live_flow` — 聚合诊断面
- `/api/v1/tasks/{id}/judgment_trace` — 执行/完成判断轨迹
- `/api/v1/tasks/{id}/runtime_context` — 运行时上下文查看
- `/api/v1/tasks/{id}/tool_trace` — 工具调用追溯
- Web Console + Dialogue 前端同时支持观测

### 3.3 构建和部署零摩擦

- `scripts/Build-WithJava21.ps1` + `scripts/Run-HarnessWithJava21.ps1` 一行命令启动
- 虚拟线程执行器 + JDK 自带 HttpServer → 无 Servlet 容器依赖
- 支持 `-AutoStop -Background` 便捷运维

### 3.4 本周进展（Reasonix + Codex/Provider 闭环）

| 改动 | 文件 | 状态 |
|------|------|------|
| Reasonix provider 注册 | `BuiltinAgentProviders.java` | ✅ |
| Reasonix worker 注册 | `WorkerRegistry.java` | ✅ |
| CLI 协议适配 | `ProviderCliWorkerExecutor.java` (+ buildPlan/consume) | ✅ |
| Provider allowlist | `ProviderExecutionSupport.java` | ✅ |
| DispatchProbeArgs | `LocalCliAgentProvider.java` | ✅ |
| Dialogue 空白修复 | `dialogue/app.css` (min-height→height) | ✅ |
| Codex partial timeout | `CodexAppServerWorkerExecutor.java` + `WorkerExecutionResult.java` | ✅ |
| worker_round 主流呈现 | `ControlNodeGraph.java` + `dialogue/app.js` | ✅ |
| ProviderProtocol 主路径 | `ProviderProtocol*.java` + `ProviderCliWorkerExecutor.java` | ✅ |
| providers.yaml/json discovery | `ProviderProtocolDiscovery.java` + `Main.java` | ✅ |

---

## 4. 需要改进的部分

### 4.1 🔴 超大文件（> 1000 行）

| 文件 | 行数 | 问题 |
|------|-----|------|
| `engine/ControlNodeGraph.java` | **4,153** | 状态转移 + worker 执行 + judgment + packet 构建 + 9 个失败模式正则 — 一份文件做了 5 件事 |
| `engine/TaskService.java` | **3,266** | 16+ 构造注入参数，pause/resume/handoff/escalate/recover 全在一个类 |
| `engine/ExperimentRunService.java` | 2,258 | 实验运行全生命周期 |
| `tool/AbstractCommandTool.java` | ~1,500 | 混合了 ProcessBuilder 管理 + 输出解码 + 超时处理 |
| `runtime/RuntimeFactSetAssembler.java` | 1,199 | 运行时事实集组装 |
| `runtime/ContextObjectAdapter.java` | 1,195 | 150+ 类型到 ContextObject 的适配 |

**影响**：新人上手困难，改动一处容易连锁破坏。4 个超过 1000 行的文件承担了项目 30% 的代码量。

### 4.2 🟡 ProviderCliWorkerExecutor 仍需瘦身

**当前状态**（`worker/ProviderCliWorkerExecutor.java`）：
- `ProviderProtocol` / `ProviderProtocolRegistry` 已存在，Claude / Cursor / DeepSeek / Reasonix / Gemini / Kimi / Copilot / OpenCode 已走 protocol build/parse 主路径。
- `providers.yaml` / `providers.json` 可注册 generic native CLI provider，新 provider 不再必须改 Java allowlist。
- `ProviderCliWorkerExecutor` 内仍保留旧 `consumeXxx()`、`expectedOutputMode()`、`expectedParser()` fallback 和反射测试兼容代码，文件仍偏大。

**剩余问题**：协议抽象主链已经落地，但旧 fallback 还没有删除，ProviderCliWorkerExecutor 仍然承担进程执行、输出解析兼容、metadata 组装等多种职责。下一步应先冻结 protocol 测试，再删除旧 parser fallback 或移动到独立兼容类。

### 4.3 🟡 测试覆盖的不对称

| 覆盖等级 | 包 | 当前状态 |
|------|------|-----|
| 好 | `engine/`, `server/`, `worker/` | 已覆盖 orchestration、HTTP contract、worker protocol、Codex partial、dynamic provider discovery |
| 不足 | `runtime/`, `tool/` | 有关键路径测试，但缺 failure/edge case 组合 |
| **零或弱覆盖** | `agent/providers/`, `model/`, `cli`, `runtime/model`, `runtime/policy` | 仍缺分层单元测试 |

**风险点**：
- `agent/providers/`：0 测试 — provider 探测和 CLI 命令构建完全没有单元验证
- `model/`：0 测试 — 57 个 Record 的 JSON 序列化/反序列化没有覆盖
- `cli/Main.java`：0 测试 — 虽然风险低，但启动错误很难被 CI 捕获

### 4.4 🟡 长任务闭环未打通

- 强模型↔小模型分工虽然设计完整，但实际验证仅到单元级闭环
- Experiment Matrix 可以跑矩阵但用的是"冒烟"任务，没有真实业务场景
- Provider 可靠性：内置 worker + 动态 provider discovery 已能路由，但 ready 数和长任务稳定性仍取决于宿主机安装、认证、CLI 输出协议和超时策略

### 4.5 🟡 LLM 配置单点

- 只有一个 `LlmConfig`，一个 `OpenAiCompatibleClient`
- 没有 per-worker LLM 配置（每个 worker 可能挂不同 model/apiKey）
- 没有 provider-specific LLM 配置（cursor 用自己的 LLM，deepseek 有自己的）
- `/api/v1/health` 已投影 `llm.available` / `api_key_configured` / model / wire API，能验证启动进程是否读取到 LLM 配置；真实远端 judgment smoke 仍取决于本机环境变量和兼容 endpoint

### 4.6 🟡 日志系统混乱

- 中英混合：`"Task 进入 waiting"` ↔ `"Worker dispatch preflight warmup ready"`
- 结构不统一：有的用 `{}` 占位符（SLF4J 风格），有的用字符串拼接
- WebConsole/Dialogue 访问无日志

---

## 5. 当前瓶颈与风险

| 风险 | 严重度 | 说明 |
|------|:---:|------|
| **Provider 可用率** | 🟡 | allowlist/suggestOnly 层面已收口，真实 ready 仍是宿主机相关；需要用统一 smoke 记录 binary/auth/preflight 失败原因 |
| **大文件重构阻力** | 🔴 | ControlNodeGraph (4153行) 如果出 bug 很难定位 |
| **LLM 真实连通未验收** | 🟡 | health 已可显示 LLM 配置是否存在，但还缺带真实 compatible endpoint 的 judgment smoke 证据 |
| **测试贫瘠** | 🟡 | worker protocol 测试已补，但 agent/providers/model/cli 仍缺单元安全网 |
| **文档膨胀** | 🟡 | 72 份 md，近半是 GitHub release 临时产物。核心架构文档过时风险高 |

---

## 6. 文档体系问题

### 现状

| 分类 | 数量 | 健康度 |
|------|:---:|:---:|
| 核心架构文档 | 3 | 🟢 Architecture / API Contracts / Provider Design |
| 工程优先级/路线图 | 6 | 🟡 有重叠（CURRENT_CAPABILITY_GAP / NEXT_5_PRIORITIES / PHASE2_ROADMAP / HARDNESS_PHASE1） |
| GitHub release 产物 | **35+** | 🔴 GITHUB_FIRST_RELEASE_* / STAGE_PREVIEW_* / COMMIT_DRY_RUN_* — 都是历史执行记录 |
| 测试/验收文档 | 8 | 🟢 DIALOGUE validation / runbook / test matrix |
| 其他计划文档 | ~15 | 🟡 有不少跨文档覆盖（EVAL_SCENARIOS / GOAL_ORIENTED_EVAL / EXPERIMENT_*） |

**建议**：35+ 份 GitHub release 文档应收进 `docs/archive/release/` 或删除。重复度高的 route-map 文档也应该合并。

---

## 7. 下一步方案

### 7.1 总体原则

围绕 3 条主线推进：

```
主线 A — 硬度的质量闭环（Hardness Contract）
  把 tool/judgment/checkpoint/runtime 收束成可验证的 hardness runtime contract

主线 B — Provider 矩阵稳定性（Provider Matrix）
  让可运行 provider ≥ 8 个，并每个都通过冒烟测试

主线 C — 代码卫生与测试防御（Code Health）
  把 4 个大文件拆小，补齐 0-测试包的单元测试
```

### 7.2 立即执行（本周）

| 优先级 | 任务 | 预计工时 | 验收标准 |
|:---:|---|---|---|
| **P0-1** | LLM 接入最小可用环境变量和 smoke（`OPENAI_API_KEY` / compatible base URL） | 部分完成 | `/api/v1/health` 已投影 `llm.available=true/false` 且不泄露密钥；仍需在真实 configured endpoint 上跑 judgment smoke |
| **P0-2** | 固化 provider discovery smoke 到 release/precheck 文档或脚本 | 已接入，待全量 precheck 回放 | `Run-GitHubFirstReleasePrecheck.ps1` 已新增 provider discovery smoke 步骤；临时 `providers.yaml` 启动后，新 provider 同时出现在 `/agents` 和 `/workers`，readiness 一致 |
| **P0-3** | 补 `agent/providers/` 单元测试：`LocalCliAgentProvider` missing binary / descriptor / dispatch probe args | 已完成 | `LocalCliAgentProviderTest` 覆盖 descriptor metadata、missing binary、dispatch preflight command shape |
| **P0-4** | 跑 Codex partial_timeout 真实回放或最小模拟验收 | 最小 smoke 已接入 precheck | `scripts/Run-CodexPartialTimeoutSmoke.ps1` 覆盖 executor partial output、ControlNodeGraph worker_round + human gate、provider thread continue metadata、Dialogue 继续/移交 action plan；`Run-GitHubFirstReleasePrecheck.ps1` 默认执行该 smoke，可用 `-SkipCodexPartialTimeoutSmoke` 跳过 |

### 7.3 短期（2 周）

| 优先级 | 任务 | 预计工时 |
|:---:|---|---|
| **P1-1** | 清理 `ProviderCliWorkerExecutor` 旧 fallback parser，保留 protocol registry 主路径 | 1-2d |
| **P1-2** | 补 `agent/providers/` 测试：`LocalCliAgentProvider` 的 detect/refresh/preflight | 1d |
| **P1-3** | 补 `model/` 序列化测试：3-5 个关键 Record 的 JSON round-trip | 0.5d |
| **P1-4** | 拆分 `ControlNodeGraph`：分离出 `ControlNodeExecutor` + `FailureClassifier` | 2d |
| **P1-5** | 跑一轮 `baseline_matrix_v1`：reasonix / deepseek / codex 各跑 3 个简单任务 | 1d |
| **P1-6** | 健康检查深改：`/api/v1/health` 返回 provider/worker/db 三级检查 | 1d |

### 7.4 中期（1 月）

| 优先级 | 任务 |
|:---:|---|
| **P2-1** | 固化的 resilience layer：circuit breaker（连续失败 N 次熔断 30s）、超时断开 |
| **P2-2** | Per-worker LLM 配置：每个 worker 可挂独立 model / apiKey / baseUrl |
| **P2-3** | "强模型调小模型" 最小闭环：用 deepseek-v4-flash 调度 reasonix（v4-flash）完成一个 3 步任务 |
| **P2-4** | 文档瘦身：archive 35+ release 文档到 `docs/archive/`，合并 6 份 roadmap |
| **P2-5** | 日志标准化：全部英文 + SLF4J 参数化 + 统一级别规则 |

### 7.5 Phase 优先级总表

```
P0（本周）  LLM 激活 → judgment/executor 可跑
P0          provider discovery smoke 固化 → 新 provider 接入可验证
P0          agent/providers 单测 → provider 探测有安全网

P1（2周）   ProviderCliWorkerExecutor 瘦身 → 旧 fallback 可删除
P1          ControlNodeGraph 拆分 → 单文件 ≤ 800 行
P1          补测试 → agent/providers + model 全覆盖
P1          baseline matrix smoke → 有数据可看

P2（1月）   Circuit breaker → 稳定性
P2          强模型调小模型 → 核心叙事闭环
P2          文档瘦身 → 可维护性
```

---

## 附录 A：文件改动汇总（自 Plan 文档以来）

| 文件 | 改动类型 | 说明 |
|------|------|------|
| `BuiltinAgentProviders.java` | 新增 entry | reasonix provider 注册 |
| `WorkerRegistry.java` | 新增 entry + command shape | reasonix worker 注册 |
| `ProviderExecutionSupport.java` | 新增 entry + dynamic set | reasonix / trae / codebuddy / hermes / pi / kiro 加入 native CLI 支持，动态 discovery provider 可运行时注册 |
| `ProviderCliWorkerExecutor.java` | 协议主路径接入 | 优先走 `ProviderProtocolRegistry`，旧 parser 仅作 fallback/兼容 |
| `ProviderProtocol*.java` | 新增 | Claude/Cursor/DeepSeek/Reasonix/Gemini/Kimi/Copilot/OpenCode/GenericCli 协议类 |
| `ProviderProtocolDiscovery.java` | 新增 | 从 `providers.yaml/yml/json` 发现 generic native CLI provider |
| `Main.java` | 启动装配 | discovery result 注册进 agent provider registry、worker registry 和 execution support |
| `WorkerHandler.java` | readiness 投影 | `/workers` 列表按运行时 readiness 覆盖静态 ready |
| `LocalCliAgentProvider.java` | 新增 case + discovery metadata | reasonix dispatchProbeArgs；dynamic provider 记录 configured_from / provider_discovery |
| `dialogue/app.css` | 修复 | workspace min-height→height，消除空白 |
| `docs/REASONIX_AGENT_SUPPORT_AND_IMPROVEMENT_PLAN.md` | 新增 | 计划文档 + 实施标注 |
| `docs/PROJECT_EVALUATION_AND_NEXT_PLAN.md` | 新增 | 本文档 |
| `scripts/provider-discovery-smoke.js` | 新增 | 启动真实 harness 验证 dynamic provider discovery 和 readiness 一致性 |
| `scripts/Run-CodexPartialTimeoutSmoke.ps1` | 新增 | 聚合 Codex partial timeout 后端回归和 Dialogue worker_round action plan |

## 附录 B：待合并/归档文档清单

以下 35+ 份文档建议移到 `docs/archive/release/`：

```
GITHUB_FIRST_RELEASE_*.md (30 份)
GITHUB_RELEASE_*.md (4 份)
```

以下 6 份路线图/优先级文档建议合并为 1 份：

```
CURRENT_CAPABILITY_GAP_ASSESSMENT.md
NEXT_5_ENGINEERING_PRIORITIES.md
PHASE2_ROADMAP.md
HARDNESS_PHASE1_ALIGNMENT.md
HARDNESS_PHASE1_IMPLEMENTATION_ROADMAP.md
BOUNDED_AUTONOMY_REFACTOR_V1.md
```

## 附录 C：当前 Provider 矩阵解释

Provider ready 不是稳定的仓库属性，而是宿主机属性：同一份代码在不同机器上会因为 binary 是否安装、账号是否登录、CLI 是否支持 headless dispatch、命令探测是否超时而不同。因此本文不再把 `3/14 ready` 写成项目事实。

当前代码层面的事实：

| 类别 | 当前状态 |
|------|------|
| 内置 native CLI support | `cursor/openclaw/claude/gemini/deepseek/kimi/copilot/opencode/reasonix/trae/codebuddy/hermes/pi/kiro` 已进入 `ProviderExecutionSupport` |
| 非 suggestOnly worker | kimi / trae / codebuddy 当前注册为 `suggestOnly=false`，可进入路由候选；是否 ready 由运行时 readiness 决定 |
| Protocol 主路径 | Claude / Cursor / DeepSeek / Reasonix / Gemini / Kimi / Copilot / OpenCode 已注册到 `ProviderProtocolRegistry.defaultRegistry()` |
| 动态 provider | `providers.yaml` / `providers.yml` / `providers.json` 可注册 generic native CLI provider 到 `/api/v1/agents` 和 `/api/v1/workers` |
| readiness 一致性 | `/api/v1/workers` 列表会投影运行时 readiness，避免静态 `ready=true` 和 readiness endpoint 矛盾 |

最近一次真实 smoke 证据：

```powershell
node .\scripts\provider-discovery-smoke.js --port 18432 --report .\.tmp\provider-discovery-smoke\report.json
```

结果：`passed=true`。临时 `providers.yaml` 中的 `smoke_agent` 同时出现在 `/api/v1/agents` 和 `/api/v1/workers`；缺失 binary 时 worker list 与 readiness endpoint 均返回 `ready=false`，reason 为 `binary not found: smoke-agent-missing-binary`。
