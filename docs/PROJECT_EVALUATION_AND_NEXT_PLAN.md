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

## 附录 D：2026-06-17 产品 / 用户视角浏览器复核

### 复核方式

- fresh 隔离实例：`http://localhost:18480`
- 隔离 DB：`.tmp/product-eval-18480.db`
- 启动方式：`Run-HarnessWithJava21.ps1 -Background -DisableDispatchPreflightWarmup`
- 浏览器验证链：
  - `node .\scripts\screenshot.js --base-url http://localhost:18480 --out-dir .tmp\product-eval-18480\shell --report .tmp\product-eval-18480\shell-report.json`
  - `node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18480 --report .tmp\product-eval-18480\dialogue-business-smoke-report.json`
  - `node .\scripts\console-provider-window-probe.js --base-url http://localhost:18480 --report .tmp\product-eval-18480\console-provider-window-probe.json --screenshot .tmp\product-eval-18480\console-provider-window-probe.png`
  - `node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:18480 --report .tmp\product-eval-18480\recovery-job-ui-probe.json --screenshot .tmp\product-eval-18480\recovery-job-ui-probe.png`
- 额外对照：
  - `GET /api/v1/health` 返回 `llm.available=false`
  - `GET /api/v1/tasks/task_e4441ab0b6ff4aa7` / `live_flow` / `messages` 用于核对页面回执和真实运行状态

### 一句话结论

当前项目已经能跑通基本会话/任务交互，也有很强的 operator 诊断面；但从第一次上手的普通用户视角看，它更像“控制平面工作台”而不是“可直接信任的任务产品”。最大问题不是功能缺失，而是默认信息层次、环境就绪提示、失败恢复反馈三件事还没有产品化。

### 已验证的正向能力

- `dialogue-business-smoke` 通过，说明创建 session、提交默认 `task_auto`、手动创建任务、追加 `continue-current note` 这些主链路没有坏。
- `console-provider-window-probe` 通过，说明 `/console/` 已经能把 provider recovery window、preflight、startup probe、dispatch probe 这些 operator 诊断面真正渲染出来。
- 因此当前项目不是“页面空壳”或“完全不可用”，而是“有控制面能力，但默认体验还不适合非操作员”。

### 我建议立即做的减法

#### 1. 先把 `/dialogue/` 默认空态收轻，不要把 operator 面板长期摊开

证据：

- `shell-report.json` 三个 profile 全部失败，失败点集中在两件事：
  - `default shell keeps details folded or lightweight` 失败
  - `session-scoped shell keeps composer context hidden` 失败
- 对应截图 `dialogue-shell-desktop.png`、`dialogue-shell-narrow.png` 可以看到：
  - 没有选中 task 时，右侧 details 仍然常驻且内容很重
  - 空白 transcript + 大 details + 大 composer 同时出现
  - 窄屏下 details 直接堆到 composer 下方，第一屏密度过高

决策：

- 没有选中 task 时，details 默认彻底收起，只保留一个轻量入口。
- 没有 task 上下文时，composer 只保留输入框、发送按钮、最少模式切换；task-only context、follow-up、route/judgment 入口继续下沉。
- 窄屏下不要把完整 details 当正文第二屏默认展开；应改成抽屉或单独入口。

#### 2. 从主对话面移除一批 operator 术语，避免把用户首屏做成诊断台

证据：

- `/dialogue/` 默认空态和任务态仍直接露出 `Mounted Context`、`Agent Actions`、`worker proposed / accepted / rejected`、`event / tool / artifact` 这类 operator 术语。
- 顶部也仍有 `idle / focus / threads / tasks / chains` 这类控制平面标签。

决策：

- `/dialogue/` 首屏只保留用户关心的四类信息：当前状态、最近输出、下一步、可执行动作。
- route / judgment / tool trace / mounted context / provider preflight 一律视作 advanced/operator 信息，下沉到折叠层。
- `/console/` 保留强诊断定位，但 `/dialogue/` 不再承担“培训用户理解内部控制平面”的责任。

### 我建议立即做的加法

#### 3. 加一个真正的环境 readiness gate，不要让用户在未就绪环境里“看起来已经开始”

证据：

- `GET /api/v1/health` 明确返回 `llm.available=false`。
- 但默认 `task_auto` 仍能在 UI 上得到“已提交任务，正在推进”的回执。
- 真实任务 `task_e4441ab0b6ff4aa7` 最终状态却是：
  - `status=waiting_human`
  - `control_node=human_gate`
  - `failure_summary_readable=empty planning response`
  - `next_step=Inspect failure trace and decide whether to retry or handoff manually.`

这说明当前产品层把“请求已被接收”和“系统已具备执行条件”混在了一起。

决策：

- 如果 `llm.available=false`，`/dialogue/` 首屏要有明确 banner：模型未配置，自动推进不可用。
- 在未就绪状态下，默认发送不应继续伪装成“正在推进”；更合理的是：
  - 降级成保存任务草稿 / manual-start
  - 或直接阻止 `task_auto`，并告诉用户缺少什么配置
- readiness gate 必须给出精确缺项：模型、认证、可执行 worker，而不是只给一个 generic failed。

#### 4. 给所有异步控制动作补可见反馈，尤其是恢复任务

证据：

- `recovery-job-ui-probe` 已确认页面会发出 `POST /api/v1/tasks/{id}/recover?async=true`。
- 但 30 秒内没有看到 recovery job、request id、运行状态：
  - `recovery_job_visible=false`
  - `request_id_visible=false`
  - `running_status_visible=false`
- 这意味着用户点击“自动恢复”后，不知道请求是否真的被系统接受、是否排队、是否还在跑。

决策：

- 所有 async task action 执行后，都要立即在 task header 或 transcript 中生成一条可见 job receipt。
- receipt 至少包含：`request_id`、当前状态、触发时间、最近更新时间、下一步入口。
- 这条反馈要先 optimistic 渲染，再由轮询或 SSE 更新，而不是等后端长链条全部结束后才回写。

#### 5. 把“最近输出”改成真正的人话结果卡，而不是状态广播

证据：

- `dialogue-business-smoke` 虽然通过了 pinned surface 检查，但 pinned 内容在默认任务上主要还是 `active / scheduler`、`最近输出`、`待继续` 这类状态性文字。
- 同一任务的真实 message / live_flow 已经给出了更关键的信息：`waiting_human`、`empty planning response`、`Inspect failure trace and decide whether to retry or handoff manually.`

决策：

- pinned `最近输出` 第一优先级应展示：
  - 这轮到底成功了、失败了、还是卡在等待人工
  - 原因是什么
  - 用户接下来应该点什么
- “已完成一轮推进”这类模板句，在没有实质输出时不应占据首屏主叙述。

### 我建议保留但重新分层的能力

#### 6. `/console/` 的 operator 价值是真实存在的，但需要更强分层

证据：

- `console-provider-window-probe` 全绿，说明 provider recovery window、preflight、protocol probe、dispatch readiness 这条 operator 诊断链已经有实际价值。
- 但截图 `console-provider-window-probe.png` 也说明：右侧诊断列极长，中间主画布较空，信息组织更像“原始排障台”而不是“先摘要、再深钻”的控制台。

决策：

- `/console/` 继续保留 operator 视角，不需要像 `/dialogue/` 那样做成聊天产品。
- 但布局上建议分成 `Summary / Diagnostics / Raw` 三层，而不是把 probe、provider detail、runtime health、run search 全堆一列。
- 对 operator 最重要的“当前阻塞点 / 当前恢复窗口 / 当前建议动作”应固定在首屏摘要，不要埋在长列表中。

### 现阶段我明确不建议再加的东西

- 不要再往 `/dialogue/` 首屏继续加 worker、experiment、route、context 相关控件。
- 不要在 readiness / 恢复反馈 / 空态减法没收口前，继续扩更多 task action。
- 不要把“控制面信息更多”误当成“产品更强”；当前真正缺的是可理解、可确认、可恢复。

### 我认为最值得立刻排进本周优先级的 5 件事

1. `/dialogue/` 空态默认收起 details，并进一步压薄 composer。
2. 在 `/dialogue/` 增加 readiness banner，并在 `llm.available=false` 时阻止或降级 `task_auto`。
3. 为 `recover / resume / continue` 补统一 async receipt：`request_id + status + next step`。
4. 把 pinned `最近输出` 改成短结果卡，优先展示失败原因和下一步。
5. 把 `/dialogue/` 与 `/console/` 的信息边界彻底拉开：前者偏用户，后者偏 operator。

### 2026-06-17 落地回写：Dialogue 首批产品减法 / 加法已收口

本轮先落地了上面优先级中的前三项核心交互，范围集中在 `/dialogue/` 和浏览器探针：

- 空态减法：`/dialogue/` 默认不再打开 details rail；没有 task hash 时首屏保持 session transcript + composer，task-only composer context / follow-up / attach 入口继续隐藏；显式点击任务或带 `#task=` 深链时再自动打开 details。
- Readiness gate：`GET /api/v1/health` 返回 `llm.available=false` 时，Dialogue 头部显示模型未就绪提示；提交链路把原本的 `task_auto` 降级为 `task_required + auto_start=false`，避免继续出现“看似正在自动推进、实际停在 human_gate”的误导。
- 异步恢复反馈：`POST /recover?async=true` 成功返回后，前端立即生成本地 optimistic recovery receipt，并复用 recovery job panel 展示 `request_id`、`运行中`、动作和失败类型；后端真实 `/recovery_jobs` 结果到达后再自然覆盖/合并。
- 验收规则同步：`scripts/screenshot.js` 的 shell 验收现在接受“桌面 details 完全折叠”作为合格状态；此前脚本仍隐含要求桌面显示轻量 details rail，和本轮产品决策不一致。

验证证据：

- `node --test src/test/js/dialogue-product-readiness-plan.test.mjs src/test/js/dialogue-shell-markup-plan.test.mjs src/test/js/dialogue-recovery-job-plan.test.mjs src/test/js/dialogue-recovery-label-render.test.mjs src/test/js/dialogue-composer-submit-context-plan.test.mjs src/test/js/dialogue-transcript-layout-plan.test.mjs`：27/27 通过。
- `powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven`：构建通过。
- fresh 实例 `http://localhost:18483`，隔离库 `.tmp/product-eval-18483.db`。
- `node .\scripts\screenshot.js --base-url http://localhost:18483 --out-dir .tmp\product-eval-18483\shell --report .tmp\product-eval-18483\shell-report.json`：desktop / narrow / responses 全部通过。
- `node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18483 --report .tmp\product-eval-18483\dialogue-business-smoke-report.json`：业务 smoke 通过，且报告确认默认提交在 LLM 未就绪时降级为 manual-start receipt。
- `node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:18483 --report .tmp\product-eval-18483\recovery-job-ui-probe.json --screenshot .tmp\product-eval-18483\recovery-job-ui-probe.png`：`async_recover_request`、`request_id_visible`、`running_status_visible` 全部为 true。

仍未落地：

- pinned `最近输出` 已从状态/结果混合条改成更明确的“当前结果 / 原因 / 下一步”人话卡；剩余问题是 `/console/` 的 `Summary / Diagnostics / Raw` 分层尚未处理。

### 2026-06-17 落地回写：pinned `最近输出` 已收口为短结果卡

本轮继续沿着上面的第 4 项优先级，把 `/dialogue/` 顶部 pinned `最近输出` 从“状态条 + 预览”改成更明确的结果卡，目标是让用户在不打开 details 的情况下，也能先看到本轮到底发生了什么、为什么卡住、下一步该做什么。

本轮改动：

- 顶部 pinned 卡保留原有执行信号（`执行中 / 最近执行 / 待继续`）作为第一层状态面，避免丢失当前 round/worker 的运行语义。
- 原来的 `最近输出` outcome strip 改成新的 `当前结果` 卡，固定按 `当前结果 / 原因 / 下一步 / 补充` 的顺序组织内容：
  - `当前结果`：把 `waiting_human / human_gate / done / failed / active` 等内部状态翻成人话，如 `等待人工确认`、`已完成`、`执行失败`、`执行中`、`待继续`。
  - `原因`：优先使用脱噪后的 `failure_summary_readable`；没有失败摘要时再回退到最新 task outcome narrative / assistant output preview，避免继续把 `failed`、`done` 这类 terse token 直接展示给用户。
  - `下一步`：优先显示 `task_progress` / `task_result` 里的 `next_step`，没有时再回退到 task 自身的 `next_step`。
  - `补充`：仅在 preview 和原因不重复时显示，避免重复堆字。
- 恢复/失败细节（失败类型、恢复阶段、重试/移交次数）保留在结果卡底部 foot，而不是和主结果混在同一行里。
- CSS 为 pinned 结果卡新增单独的 row-based 视觉层，并按 `active / paused / failed / done` 做轻量 tone 区分；不改动 message history 内普通 transcript card。

验证证据：

- focused 契约测试：
  - `node --test src/test/js/dialogue-transcript-layout-plan.test.mjs src/test/js/dialogue-task-thread-preview-regression.test.mjs src/test/js/dialogue-product-readiness-plan.test.mjs`
  - 26/26 通过。
- 构建：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven`
  - 通过。
- fresh 实例：
  - `http://localhost:18484`
  - 隔离库 `.tmp/product-eval-18484.db`
  - 启动方式按 `STARTUP_GUIDE.md` 推荐链路：`Use-Java21.ps1` + `Run-HarnessWithJava21.ps1 -Background -Port 18484 -DisableDispatchPreflightWarmup -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\product-eval-18484.db")`
- 浏览器验证：
  - `node .\scripts\screenshot.js --base-url http://localhost:18484 --out-dir .tmp\product-eval-18484\shell --report .tmp\product-eval-18484\shell-report.json`
    - `desktop / narrow / responses` 全部通过。
  - `node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18484 --report .tmp\product-eval-18484\dialogue-business-smoke-report.json`
    - 通过；报告中的 pinned 输出已变成 `当前结果` 卡，并保留上层 `执行中` 状态面。
  - `node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:18484 --report .tmp\product-eval-18484\recovery-job-ui-probe.json --screenshot .tmp\product-eval-18484\recovery-job-ui-probe.png`
    - 通过；说明这轮结果卡改造没有打坏 recovery job 可见性。

剩余未落地：

- `/console/` 仍需要按 `Summary / Diagnostics / Raw` 做更彻底的分层；这是当前产品优先级中的最后一项未收口内容。

### 2026-06-17 落地回写：`/console/` 已拆成 `Summary / Diagnostics / Raw`

本轮继续收口上面最后一项未落地内容，把 `/console/` 右侧 inspector 从“长诊断列”改成三层 surface，目标不是削弱 operator 能力，而是把最关键的恢复窗口、当前任务和路由判断固定留在首屏，把深诊断和原始 JSON 往后收。

本轮改动：

- inspector header 下新增 `Summary / Diagnostics / Raw` surface switch，并把当前 surface 同步进 hash：
  - 默认 `Summary` 不写 hash 参数，保持 `/console/#session=...&task=...` 的短链接形态。
  - 切到 `Diagnostics / Raw` 时，hash 会附带 `surface=diagnostics|raw`，便于深链回到具体诊断层。
- `Summary` 默认只保留 operator 首屏最需要的块：
  - `Agent Inventory`
  - `Provider Detail`
  - `Runtime Health`
  - `Agent Execution`
  - `路由与判断`
- `Diagnostics` 展开长链路诊断：
  - `Run Search`
  - `Agent Run Detail`
  - `迭代链上下文`
  - `连续性摘要`
  - `Mounted Context`
  - `实验对比`
  - `最近产物`
  - `Agent Actions`
  - `工具轨迹`
- `Raw` 只保留 `live_flow` 原始 JSON，并自动展开 `details`，避免操作员还要再点一次才能看到 payload。
- 兼容性上没有移动现有 operator probe 依赖的 DOM id：
  - `#runtimeHealth`
  - `#routeBox`
  - `#agentDetail`
  这些节点继续保留在 `Summary`，因此 provider recovery window / preflight / startup protocol probe / dispatch readiness 现有链路无需改脚本就能继续跑通。

验证证据：

- focused JS 契约测试：
  - `node --test src/test/js/console-provider-window-plan.test.mjs src/test/js/console-time-normalization.test.mjs src/test/js/console-surface-layering-plan.test.mjs`
  - 12/12 通过。
- 构建：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven`
  - 通过。
- fresh 实例：
  - `http://localhost:18486`
  - 隔离库 `.tmp/console-surface-18486.db`
  - 启动方式按 `STARTUP_GUIDE.md` 推荐链路：`Use-Java21.ps1` + `Run-HarnessWithJava21.ps1 -Background -Port 18486 -DisableDispatchPreflightWarmup -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\console-surface-18486.db")`
- 浏览器验收：
  - `node .\scripts\console-provider-window-probe.js --base-url http://127.0.0.1:18486 --report .tmp\console-surface-probe-18486.json --screenshot .tmp\console-surface-probe-18486.png`
    - 继续全绿：`runtime_health_window`、`route_box_hint`、`provider_preflight_post`、`provider_preflight_rendered`、`startup_protocol_probe_rendered`、`worker_dispatch_probe_rendered` 全为 true。
  - 额外 DOM / surface 验收：
    - `.tmp/console-surface-dom-18486.json`
    - `.tmp/console-surface-summary-18486.png`
    - `.tmp/console-surface-diagnostics-18486.png`
    - `.tmp/console-surface-raw-18486.png`
    - 结果确认：
      - `Summary` 默认隐藏 `Run Search` 与 `Raw`，保留 `Runtime Health`
      - `Diagnostics` 会把 `Run Search` 与 `Mounted Context` 展开，并把 hash 改成 `&surface=diagnostics`
      - `Raw` 会显示 `live_flow` JSON、自动展开 `details`，同时隐藏 `Runtime Health`，并把 hash 改成 `&surface=raw`

当前产品评估计划中的 5 个优先级项已全部落地；接下来更适合转向新的用户任务或下一批 operator 收口，而不是继续往 `/dialogue/` 和 `/console/` 首屏加信息。
