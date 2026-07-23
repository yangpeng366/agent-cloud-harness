# Codex 多 API / 多 Profile 落地计划

> 文档类型：方案计划（`*_PLAN.md`）。本文档承接 `CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md`，把实现顺序、验收面和后续验证入口固定下来。当前已进入第一版落地后的回归与验证阶段。

## 1. 目标

把同一个 `codex` provider 扩成多 profile 执行链，至少覆盖：

- `codex_openai_strong`
- `codex_xfyun_execute`
- `codex_deepseek_fallback`

并让这组 profile 能稳定服务：

- 复杂需求的 `design / implement / verify`
- `free_first` 全局 paid fallback
- `app_server` 与 `exec_json` 双执行链

## 2. 初始缺口与当前状态

本计划最初锁定了 4 个关键缺口；截至 2026-06-30，主链都已接通，本轮 focused regression 主要是在现有实现上补最小回归保护。

1. `LocalCliProviderConfig` 的 profile 数据合同缺口，现已由 `ProviderProfileConfig` / `ProviderDefaultProfile` + `resolveDefaultProfile()` 收口。
2. `WorkerRegistry` 的 codex profile lane 缺口，现已由 `codex-openai / codex-xfyun / codex-deepseek` 收口。
3. `CodexAppServerWorkerExecutor` 的双链 profile 透传缺口，现已由 `buildPlan / buildExecJsonPlan / execJsonCommand / thread/start / metadata` 收口。
4. route trace、artifact metadata、task 读面的 codex profile 选择结果，当前已进入既有 broader suite 与读面逻辑的覆盖面；后续若继续补更细解释字段，再增量扩展。

## 3. 推荐实施顺序

### Step 1: 补 codex profile 数据合同 ✅ 已完成

目标：

- 先把 profile 字段收成稳定结构，不急着改路由。

建议改动：

- 为 codex 执行链引入内部 profile 结构，例如：
  - `providerProfileId`
  - `modelProvider`
  - `model`
  - `cliProfile`
  - `configOverrides`
- 扩展 `LocalCliProviderConfig`，允许 provider 级默认值读取：
  - `agentcloud.providers.codex.model_provider`
  - `agentcloud.providers.codex.profile`
  - `agentcloud.providers.codex.config_json`
  - 对应环境变量 `MULTICA_CODEX_MODEL_PROVIDER`、`MULTICA_CODEX_PROFILE`、`MULTICA_CODEX_CONFIG_JSON`

验收标准：

- 不带任何新 metadata 时，旧 `codex` 行为不变。
- 新字段能被解析成稳定的 resolved config 对象。

### Step 2: 给 `WorkerRegistry` 增加 codex profile lane ✅ 已完成

目标：

- 让控制面能显式看到多个 codex worker。

建议新增 worker：

- `codex-openai`
- `codex-xfyun`
- `codex-deepseek`

建议 metadata：

- `provider_profile_id`
- `provider_profile_role`
- `provider_model_provider`
- `provider_model`
- `provider_cli_profile`
- `provider_config_overrides`
- `provider_billing_class`

兼容要求：

- 旧 `codex` worker 暂时保留，作为兼容 lane。
- 若后续 route 已完全转向 profile worker，再评估是否降级旧 `codex` lane。

验收标准：

- `/api/v1/workers` 可看到 3 个 codex profile worker。
- `AgentProviderResolver` 仍统一把它们解析到 `providerId=codex`。

### Step 3: 让执行器双链消费 profile ✅ 已完成

目标：

- `app_server` 与 `exec_json` 都能真实把 profile 带到 CLI。

建议改动点：

- `CodexAppServerWorkerExecutor`
  - `buildPlan(...)`
  - `buildExecJsonPlan(...)`
  - `execJsonCommand(...)`
  - `thread/start` params
  - metadata 导出

具体要求：

- CLI 启动参数支持：
  - `-c model_provider=...`
  - `-m ...` 或 `-c model=...`
  - `-p ...`
  - 其他 `config_overrides` -> 多个 `-c key=value`
- `thread/start` 不再把：
  - `modelProvider`
  - `profile`
  - `config`
  写成 `null`

验收标准：

- `app_server` 链执行时，启动命令 preview 与 `thread/start` 参数都带上 profile 解析结果。
- `exec_json` 链执行时，命令 preview 也带上同一组 profile 字段。
- provider run metadata 能稳定导出：
  - `selected_provider_profile`
  - `configured_model_provider`
  - `configured_model`
  - `configured_cli_profile`

### Step 4: 增加 codex profile route ✅ 已完成

目标：

- 让 codex lane 可以按阶段或显式 pin 选 profile。

建议第一版规则：

1. 显式 `assigned_worker` / `preferred_provider_profile` 优先。
2. 否则按 `workflow_stage`：
   - `design` -> `codex_openai_strong`
   - `implement` -> `codex_xfyun_execute`
   - `verify` -> `codex_openai_strong`
3. 当前 profile 不可用时，按候选链回退到下一条 codex profile。
4. 全部 codex profile 不可用时，再回到更外层 route / human gate。

验收标准：

- `/select_worker` 能解释选中了哪个 codex profile。
- `live_flow` 能解释为什么从 `openai` 切到 `xfyun` 或 `deepseek`。

### Step 5: 补 profile 失败分类与回退语义 ✅ 已完成

目标：

- 让 codex family 内部 fallback 有结构化原因。

建议最小分类：

- `quota_exhausted`
- `auth_blocked`
- `backend_unavailable`
- `profile_misconfigured`
- `dispatch_preflight_failed`

验收标准：

- 同一任务的 route trace 能明确显示“profile fallback 原因”。
- 因 profile 配错或 auth 失败时，不会无限在同一 profile 重试。

## 4. 推荐验证入口

### 4.1 Focused unit / integration tests

建议新增或扩展：

- `CodexAppServerWorkerExecutorTest`
- `AgentProviderSupportTest`
- `WorkerRouterRouteTraceTest`
- `TaskHandlerProviderSelectionHttpTest`
- `TaskHandlerLiveFlowHttpTest`

建议覆盖点：

- codex profile metadata 解析
- `app_server` 命令 preview 是否含 `-c/-m/-p`
- `exec_json` 命令 preview 是否含 `-c/-m/-p`
- `thread/start` 参数是否导出 `modelProvider/profile/config`
- `workflow_stage=design|implement|verify` 的 codex profile 选择
- profile fallback trace

### 4.2 本机 smoke

建议至少做一次本机只读级 smoke：

```text
codex --version
codex --help
codex exec --help
codex app-server --help
```

目的：

- 确认当前安装版本仍支持 `-c / -m / -p`
- 避免代码实现后依赖了本机并不存在的参数

### 4.3 2026-06-30 provider/profile focused regression

本轮实际执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=LocalCliAgentProviderTest,CodexAppServerWorkerExecutorTest,ProviderDefaultProfileTest,ProviderProfileConfigTest,CodexProfileWorkerRegistryTest"
```

结果：

- 退出码 `0`
- `LocalCliAgentProviderTest`：5/5
- `ProviderDefaultProfileTest`：4/4
- `ProviderProfileConfigTest`：13/13
- `CodexProfileWorkerRegistryTest`：10/10
- `CodexAppServerWorkerExecutorTest`：20/20

这轮 focused regression 还直接暴露并促成修复了两处真实实现回归：

- `resolveProfile(...)` 的 merge 顺序错误
- `execJsonCommand(...)` 丢失 `appendProfileArgs(...)` 返回值

## 5. 与现有文档的关系

这条计划与下列文档直接联动：

- `FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`
  - 负责“是否进入 codex family”
- `COMPLEX_REQUIREMENT_DESIGN_IMPLEMENT_VERIFY_PLAN.md`
  - 负责“复杂需求各阶段默认偏向哪个 codex profile”
- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
  - 需要在实现后补入 codex profile 参数合同和探针说明

## 6. 当前风险

| 风险 | 说明 | 收口方式 |
|---|---|---|
| 只补 worker 不补执行器 | 看起来可选，实际不生效 | Step 2 后必须立即接 Step 3 |
| 只补 `exec_json` | `app_server` 仍是默认链 | 双链同时验收 |
| route 先于执行器上线 | UI 会说选了 profile，但真实命令没带上 | 先数据合同，再执行器，再 route |
| profile 默认值污染旧任务 | 旧 `codex` 任务可能被隐式改道 | 保留旧 `codex` lane，默认兼容 |

## 7. 结论

当前第一版已经按上述顺序完成主链落地。后续重点不再是“是否要做 codex 多 profile”，而是：

1. 持续补 focused regression，防止执行器双链再次分叉。
2. 在 provider / route / live-flow 读面上继续增强更细粒度的解释字段。
3. 只在确有新需求时，再扩展 profile fallback trace 和 broader integration coverage。
