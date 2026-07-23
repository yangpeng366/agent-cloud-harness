# DevEco 接入与 CodeBuddy 默认参数强化计划

> 文档类型：方案计划（`*_PLAN.md`）。本文档描述“要做什么、为什么、改哪里、怎么验证”，落地后应配套 runbook 与执行记录。

## 1. 背景与目标

当前 harness 已经把一批 provider-native CLI worker 接进 `provider_native_cli` 执行后端，但不同 worker 的“默认启动参数 + 输出解析”并未统一收口。本次任务聚焦三个 provider：

| Provider | 期望命令形态 | 当前状态 |
|----------|--------------|----------|
| `codex` | `codex app-server --listen stdio://`（app-server）/ `codex exec --no-alt-screen --json ...`（exec-json） | **已调整**：`--no-alt-screen` 仅保留在 exec-json；Codex CLI 0.144.4 的 app-server 不接受该参数 |
| `codebuddy` | `codebuddy -y --print --output-format stream-json --permission-mode bypassPermissions --subagent-permission-mode bypassPermissions --tools default <prompt>` | **缺口**：已注册 provider/worker，但无专属 protocol，执行会 fail fast |
| `deveco` | `deveco run --skip-agreement --format json <message>` | **缺口**：完全未接入 |

目标：

1. 让 `codebuddy` 用 Claude Code 同款 stream-json 协议稳定输出，自动抽取 session/model/usage/错误。
2. 把 `deveco` 作为新 provider-native CLI worker 接入，复用 opencode 事件流解析。
3. 固化 `codex` 参数边界：exec-json 保留 `--no-alt-screen`，app-server 不再携带该参数。

非目标：

- 不改 codex app-server 的 JSON-RPC 握手。
- 不引入新执行后端；codebuddy / deveco 都走 `provider_native_cli`。

## 2. 真实 CLI 能力核对（已采样）

> 以下参数与输出格式来自本机真实 `--help` 与最小 prompt 实跑，不是猜测。

### 2.1 codebuddy（v2.107.0，Claude Code 风格 CLI）

顶层即支持 `[prompt]`，关键参数：

| 参数 | 作用 | harness 是否需要 |
|------|------|------------------|
| `-y, --dangerously-skip-permissions` | 跳过全部权限确认 | ✅ 用户指定 |
| `--permission-mode bypassPermissions` | 主会话权限模式 | ✅ 用户指定 |
| `--subagent-permission-mode bypassPermissions` | subagent 权限模式 | ✅ 用户指定 |
| `--tools default` | 使用默认工具集 | ✅ 用户指定 |
| `--print` | 非交互模式，输出后退出 | ✅ **必须**（否则进 TUI 卡死） |
| `--output-format stream-json` | 结构化事件流 | ✅ **必须**（否则拿不到 session/usage） |
| `--max-turns <n>` | 限制 agentic 轮数 | ⚪ 可选，用于防跑飞 |
| `--model <model>` | 指定模型（glm-5.1/glm-5.0/kimi-k2.6/deepseek-v4-pro 等） | ⚪ 有配置则带 |
| `-r, --resume [sessionId]` | 续接会话 | ⚪ 有 session_id 则带 |
| `--add-dir <dirs>` | 额外可访问目录 | ⚪ 暂不 |

实跑 `codebuddy -y --permission-mode bypassPermissions --print --output-format stream-json "reply PONG"` 输出（stream-json，逐行 JSON）：

```json
{"type":"system","subtype":"init","session_id":"...","model":"glm-5.1","permissionMode":"bypassPermissions","tools":[...]}
{"type":"assistant","session_id":"...","message":{"content":[{"type":"text","text":"PONG ..."}],"model":"glm-5.1","usage":{...}}}
{"type":"result","subtype":"success","is_error":false,"result":"PONG ...","duration_ms":6104,"num_turns":2,"usage":{"input_tokens":...,"output_tokens":...}}
```

**关键结论**：codebuddy 的 stream-json 与项目现有 `ClaudeProtocol` 的解析目标 1:1 对齐 —— 同样是 `type:system(subtype:init)` 含 `session_id`/`model`，`type:assistant` 含 `message.content[].text`，`type:result` 含 `result`/`is_error`/`usage`。可直接复用该 parser 逻辑。

### 2.2 deveco（v0.1.0，opencode 的壳）

帮助明示 `run opencode with a message`，用 yargs 风格。`run [message..]` 子命令关键参数：

| 参数 | 作用 | harness 是否需要 |
|------|------|------------------|
| `--skip-agreement` | 跳过协议确认（仍需登录） | ✅ 用户指定 |
| `--format json` | 原始 JSON 事件流 | ✅ **必须**（否则是格式化人类文本） |
| `--dir <path>` | 工作目录 | ⚪ 用 cwd 传递 |
| `-m, --model provider/model` | 指定模型 | ⚪ 有配置则带 |
| `-s, --session <id>` | 续接会话 | ⚪ 有则带 |
| `--dangerously-skip-permissions` | 自动批准未显式拒绝的权限 | ⚪ 可选 |
| `--pure` | 不加载外部插件 | ⚪ 暂不 |

实跑 `deveco run --skip-agreement --format json "reply PONG"` 输出（逐行 JSON，opencode 事件流）：

```json
{"type":"step_start","sessionID":"ses_...","part":{"messageID":"msg_...","type":"step-start"}}
{"type":"text","sessionID":"ses_...","part":{"type":"text","text":"PONG","time":{...}}}
{"type":"step_finish","sessionID":"ses_...","part":{"reason":"stop","type":"step-finish","tokens":{"total":18735,"input":18732,"output":3},"cost":0}}
```

**关键结论**：deveco 输出是 opencode 事件流（`type:text/step_start/step_finish`，文本在 `part.text`，session 在 `sessionID`/`part.sessionID`）。与项目现有 `OpenCodeProtocol`（`consumeOpenCode`）解析目标同源。

### 2.3 codex：app-server 不再携带 `--no-alt-screen`

`CodexAppServerWorkerExecutor`：`CODEX_EXEC_NO_ALT_SCREEN_FLAG = "--no-alt-screen"` 仅用于 exec-json。2026-07-21 real worker smoke 已确认 `codex app-server --no-alt-screen --listen stdio://` 会返回 `unexpected argument '--no-alt-screen' found`，因此 app-server plan 固定为 `codex app-server --listen stdio://`。

## 3. 设计决策：参照 codex 风格走专属 Protocol

### 3.1 为什么用专属 Protocol 而非 GenericCliProtocol 模板

| 维度 | GenericCliProtocol 模板 | 专属 Protocol（采用） |
|------|------------------------|----------------------|
| 命令参数 | ✅ 能表达固定参数序列 | ✅ 能表达，且支持 profile 裁剪 |
| 输出解析 | ❌ 只有 TEXT/JSON/LINES/STREAM_JSON 四种通用 parser，抽不出 session_id/model/usage | ✅ 可按真实事件流抽取结构化字段 |
| resume 支持 | ❌ 模板无 resume 逻辑 | ✅ 可按 task metadata 注入 `--resume`/`--session` |
| 与既有代码一致性 | 与 copilot/gemini/kimi/claude 风格不一致 | 与项目已成熟的专属 protocol 范式一致 |

codebuddy 的 stream-json 能稳定抽出 `session_id`/`model`/`usage`/`is_error`，deveco 能抽出 `sessionID`/`tokens`/`cost` —— 这些 metadata 对 route trace、live_flow、provider failure 分类都有价值，generic TEXT parser 会全部丢失。因此采用**专属 Protocol**，与 codex/claude/copilot 等保持同一范式。

### 3.2 codebuddy：新建 `CodeBuddyProtocol`

命令（对齐用户指定 + 补齐必需项）：

```text
codebuddy -y --print --output-format stream-json --permission-mode bypassPermissions \
  --subagent-permission-mode bypassPermissions --tools default [--model <m>] [--resume <id>] <prompt>
```

实现要点（结构参照 `ClaudeProtocol`）：

- `providerId()` = `"codebuddy"`。
- `detect(config)`：binary 非空即 ready。
- `buildPlan`：
  - args 固定序列：`-y`、`--print`、`--output-format stream-json`、`--permission-mode bypassPermissions`、`--subagent-permission-mode bypassPermissions`、`--tools default`。
  - model：task metadata `provider_model` 或 provider config 有值且 profile 不拒绝 → `--model <m>`；否则记 `profile_adjustments`。
  - resume：task 有 `provider_session_id`/`resume_provider_session_id` 且非 recovery 阶段 → `-r <id>`（codebuddy 用 `-r, --resume [sessionId]`）。
  - prompt 作为最后位置参数（argv 交付）。
- `parseOutput`：复用 Claude stream-json 解析逻辑（`type:system(init)`→session/model，`type:assistant`→message.content[].text，`type:result`→result/is_error/usage）。parser 名标 `codebuddy_stream_json`。可抽取 `provider_active_model`、`provider_session_id`。

### 3.3 deveco：新建 `DevecoProtocol` 并全链路注册

命令：

```text
deveco run --skip-agreement --format json [--dir <cwd>] [-m <model>] [-s <session>] <message>
```

实现要点（结构参照 `OpenCodeProtocol`）：

- `providerId()` = `"deveco"`。
- `buildPlan`：args 固定 `run`、`--skip-agreement`、`--format json`；cwd 非空 → `--dir <cwd>`；model 有值 → `-m <model>`；resume → `-s <session>`；message 最后。
- `parseOutput`：opencode 事件流解析（`type:text`→`part.text`，`type:step_finish`→`part.reason`/`part.tokens`/`part.cost`，session 从 `sessionID` 或 `part.sessionID` 抽取）。parser 名标 `deveco_opencode_json`。

接入清单（按依赖顺序）：

1. **支持矩阵**：`ProviderExecutionSupport.PROVIDER_NATIVE_CLI` 追加 `"deveco"`。
2. **Provider 注册**：`BuiltinAgentProviders.defaults()` 追加 deveco provider（binary `deveco`，env `MULTICA_DEVECO_PATH`/`MULTICA_DEVECO_MODEL`，capabilities `coding/reading/session`）。
3. **Worker 注册**：`WorkerRegistry` 追加 deveco worker（`execution_backend=provider_native_cli`，`selection_priority=84` 低于已验证 worker，`workspace_access_mode=native_cli_cwd`，`auto_route_task_types=[coding,reading,session]`）。
4. **Protocol**：新建 `DevecoProtocol`。
5. **Protocol 注册**：`ProviderProtocolRegistry.defaultRegistry()` 追加 `.register(new CodeBuddyProtocol())` 与 `.register(new DevecoProtocol())`。
6. **Dispatch probe args**：`LocalCliAgentProvider` 第 257 行 switch 补 `case "deveco" -> List.of("--help");`（codebuddy 已有）。
7. **Command shape 文案**：`WorkerRegistry.providerNativeCommandShape()`：
   - codebuddy → `"codebuddy -y --print --output-format stream-json --permission-mode bypassPermissions --subagent-permission-mode bypassPermissions --tools default <prompt>"`
   - deveco → `"deveco run --skip-agreement --format json <message>"`
8. **Recovery 候选**：初版**暂不**把 deveco/codebuddy 加入 `ControlNodeGraph` 自动 recovery 候选序列，待真实 smoke 稳定后再评估。

### 3.4 codex：现状固化，不改代码

把“`--no-alt-screen` 只属于 codex exec-json，不属于 codex app-server”写入 `AGENT_PROVIDER_TECHNICAL_DESIGN.md` provider 参数表，避免后续重构误加回 app-server。

## 4. 实现步骤

### Step 1：codebuddy 参数 + parser 收口

- [ ] 新建 `CodeBuddyProtocol`（第 3.2 节）
- [ ] `ProviderProtocolRegistry.defaultRegistry()` 注册
- [ ] `WorkerRegistry.providerNativeCommandShape()` 更新 codebuddy 文案
- [ ] codebuddy worker metadata：`output_mode=stream_json`、`recovery_resume_policy=resume_if_session_id`、`supports_resume=true`（`WorkerRegistry` 相关 switch 已能覆盖，确认即可）

验证：focused test `CodeBuddyProtocolTest` 断言命令含全部参数 + parser 能解析第 2.1 节真实样本。

### Step 2：deveco 全链路接入

- [ ] 第 3.3 节接入清单 1-7 全部完成
- [ ] 新建 `DevecoProtocol`
- [ ] `BuiltinAgentProviders` / `WorkerRegistry` / `ProviderExecutionSupport` / `LocalCliAgentProvider` 同步补 deveco

验证：`/api/v1/workers` 见 deveco；focused test `DevecoProtocolTest` 断言命令 + parser 解析第 2.2 节真实样本。

### Step 3：codex 现状固化（文档）

- [ ] `AGENT_PROVIDER_TECHNICAL_DESIGN.md` provider 参数表补 codex app-server / exec-json 参数边界说明
- [ ] 把 codebuddy 从该文档第 1059 行“已注册但未完全接通 worker”表格移除

## 5. 验证入口

### 5.1 focused test

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=CodeBuddyProtocolTest,DevecoProtocolTest,AgentProviderSupportTest"
```

- `CodeBuddyProtocolTest`：buildPlan 命令含 `-y/--print/--output-format stream-json/--permission-mode bypassPermissions/--subagent-permission-mode bypassPermissions/--tools default` + prompt；parseOutput 用第 2.1 节真实样本断言 `status=completed`、`session_id` 非空、`outputText` 含 PONG。
- `DevecoProtocolTest`：buildPlan 命令含 `run/--skip-agreement/--format json` + message；parseOutput 用第 2.2 节真实样本断言 `outputText` 含 PONG、`session_id` 非空。
- `AgentProviderSupportTest`：deveco provider 已注册、binary/env var 正确。

### 5.2 真实 CLI smoke（本机已装 v2.107.0 / v0.1.0）

隔离库 + 隔离端口起实例，分别派发最小任务：

```bash
curl -X POST http://localhost:<port>/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"codebuddy smoke","task_type":"coding","source":"user","priority":"high","intent":"reply PONG","assigned_worker":"codebuddy"}'

curl -X POST http://localhost:<port>/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"deveco smoke","task_type":"coding","source":"user","priority":"high","intent":"reply PONG","assigned_worker":"deveco"}'
```

观测：

- `/api/v1/tasks/{id}/live_flow` 的 `cli_command_preview` 符合期望命令。
- `.tmp/provider-runs/<provider>/<task_id>/<execution_id>/metadata.json` 的 `provider_session_id`、`provider_active_model`（codebuddy）、`provider_output_parser` 字段。
- 输出 `last_message.md` 含 PONG。

### 5.3 回归保护

- codebuddy 当前 fail fast（switch 无 case），本计划转为可执行；确认无测试**依赖**旧 fail fast 行为。
- 真实样本已存 `.tmp/codebuddy-sample.jsonl`、`.tmp/deveco-sample.json`，可固化为 test fixture。

## 6. 风险与边界

| 风险 | 说明 | 缓解 |
|------|------|------|
| 交互式 TUI 卡死 | codebuddy 不加 `--print` 会进交互模式 | 强制 `--print`，无 `--print` 分支 |
| 输出格式漂移 | 后续版本 stream-json 字段可能变 | 用 test fixture 锁定第 2.1/2.2 节真实样本 |
| 自动路由误选 | deveco priority 过高 | priority=84，低于 codex/claude/cursor/copilot/opencode；readiness 把关 |
| bypass 安全 | `bypassPermissions` 跳过权限，仅本地受控环境 | 与现有 claude 一致，`side_effect_risk=high`，遵循 AGENTS.md S01/S04 |
| 登录态 | deveco `--skip-agreement` 仍需登录 | readiness/dispatch preflight 在登录态缺失时 not ready，不假装可执行 |

## 7. 后续动作

1. codebuddy/deveco 真实 `--help` 已采到，可补 `CliCapabilityProfile` probe fixture 让 dispatch preflight 精确判断参数支持。
2. 若需限制 agentic 轮数，codebuddy 加 `--max-turns`，deveco 无直接对应项（opencode 侧控制）。
3. 验证完成后补带日期执行记录 `*_EXECUTION_RECORD_YYYY-MM-DD.md`，并在 `STATE.md` 留写回痕迹。

## 8. 关联文档

- `docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md`（第 7.3 节 worker 优化、第 1059 行未接通表格）
- `docs/AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- `docs/WORKER_FAILURE_RECOVERY_POLICY.md`
- `docs/TROUBLESHOOT.md`（provider 优先级口径）
- 证据样本：`.tmp/codebuddy-sample.jsonl`、`.tmp/deveco-sample.json`
