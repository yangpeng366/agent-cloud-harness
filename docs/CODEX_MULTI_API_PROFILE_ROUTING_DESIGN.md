# Codex 多 API / 多 Profile 路由设计

> 文档类型：技术设计（`*_DESIGN.md`）。本文档收口“同一个 `codex` CLI 对接不同 API / 账户通道 / 计费通道”的设计边界。当前已进入第一版落地后的设计复核阶段：既记录稳定边界，也保留后续扩展方向。

## 1. 背景

当前希望把 `codex` 这条执行链拆成 3 条更贴近真实使用习惯的 profile：

1. 最强方案位：用于方案设计、问题排查、业务闭环复核，质量优先，但单次成本更高。
2. 月卡主力位：用于解决问题和落地代码，量更足，适合作为默认 implementation codex lane。
3. 按量备选位：当前两条不可用时再切入，承担 paid fallback。

用户当前给出的目标命令形态是：

```text
codex -c model_provider=OpenAI -c model=gpt-5.4 --no-alt-screen "任务XXX"
codex -c model_provider=xfyun -c model=xopglm51 --no-alt-screen "任务XXX"
codex -c model_provider=deepseek -c model=deepseek-v4-pro --no-alt-screen "任务XXX"
```

这条需求和已有两份文档直接相关：

- `COMPLEX_REQUIREMENT_DESIGN_IMPLEMENT_VERIFY_PLAN.md`
- `FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`

也就是说，这次设计既要回答“同一个 codex 如何接不同 API”，也要回答“它和现有免费优先 / 三段式协作流程如何并存”。

## 2. 当前源码核对结论

以下结论都来自当前仓库代码与本机真实 CLI，不是推测。

### 2.1 当前 codex 已是“单 provider + 多 profile worker lane”

当前实现里：

- `CodexProvider` 已通过扩展后的 `LocalCliProviderConfig(...)` 注入：
  - binary/path
  - model
  - model_provider
  - cli profile
  - config_json -> `configOverrides`
- `WorkerRegistry` 当前同时内置：
  - 兼容 lane：`codex`
  - profile lanes：`codex-openai` / `codex-xfyun` / `codex-deepseek`
- `AgentProviderResolver` 会把任何命中 `codex` 的 worker/type 统一解析成同一个 `providerId=codex`。

因此当前系统已经具备：

- `codex` 多 profile 数据合同
- `model_provider` / `profile` / `config override` 注入入口
- 多个 codex lane 的控制面可见性

### 2.2 当前执行器已消费 profile，本轮 focused regression 又锁住了两个真实回归点

`CodexAppServerWorkerExecutor` 当前的真实状态：

- `buildPlan(...)` / `buildExecJsonPlan(...)` 会先解析 `task > worker > provider default` 三层 profile，再写入 `CodexExecutionPlan`
- `app_server` 与 `exec_json` 两条链都会把 profile 渲染到 CLI，形态为：
  - `-c model_provider=...`
  - `-m ...`
  - `-p ...`
- `thread/start` 当前已透传：
  - `modelProvider`
  - `profile`
  - `config`
- 结果 metadata 当前稳定导出：
  - `selected_provider_profile`
  - `configured_model_provider`
  - `configured_model`
  - `configured_cli_profile`
  - `configured_config_overrides`

本轮针对 provider/profile 的 focused tests 又暴露并促成修复了两个真实回归点：

1. `resolveProfile(...)` 的 merge 顺序曾写反，导致 provider default 可能覆盖 worker/task profile。
2. `execJsonCommand(...)` 曾忽略 `appendProfileArgs(...)` 的返回值，导致 `exec_json` 实际命令丢失 profile 参数。

这说明 codex 多 profile 主链已经接通，但后续仍需要用 focused regression 持续守住执行器双链一致性。

### 2.3 本机 `codex-cli 0.142.0` 已具备所需能力

本机已验证：

- `codex --version` -> `codex-cli 0.142.0`
- `codex --help` 支持：
  - `-c, --config <key=value>`
  - `-m, --model <MODEL>`
  - `-p, --profile <CONFIG_PROFILE_V2>`
  - `--no-alt-screen`
- `codex exec --help` 同样支持 `-c / -m / -p`
- `codex app-server --help` 也支持 `-c / --config`

因此，这次需求不是“上游 CLI 不支持”，而是“harness 还没把这组能力暴露成稳定合同”。

## 3. 核心问题

当前真正要解决的，不是“再注册几个叫 `codex-openai` / `codex-xfyun` 的假 provider”，而是：

1. 同一个 `codex` provider 如何挂多套执行 profile。
2. 这些 profile 如何通过 worker / task metadata 进入路由和执行器。
3. 它如何与现有 `free_first` 路由和平共存，而不是互相覆盖。
4. 失败时如何区分：
   - auth 问题
   - backend 不可用
   - quota/allowance 用尽
   - profile 配错

## 4. 设计目标

第一版设计目标：

1. 保持 `providerId=codex` 不变，只在其内部引入多 profile。
2. 支持至少 3 条命名 profile：
   - `OpenAI / gpt-5.4`
   - `xfyun / xopglm51`
   - `deepseek / deepseek-v4-pro`
3. 允许按任务阶段为 codex 选择不同 profile。
4. 允许 task / worker / provider 三层配置覆盖。
5. 让 `app_server` 与 `exec_json` 两条执行链都能拿到同一套 profile 配置。
6. 把 profile 选择结果暴露到 `/select_worker`、`live_flow`、artifact metadata。

第一版非目标：

1. 不把不同 codex profile 拆成不同 provider 进 inventory。
2. 不做窗口级自动输入。
3. 不要求一开始就能主动读取所有后端剩余额度。
4. 不依赖自然语言猜测强行推断 profile；优先使用显式 metadata 或稳定路由规则。

## 5. 稳定方案：一个 provider，多条 execution profile

### 5.1 为什么不拆成多个 provider

不推荐把它做成：

- `providerId=codex-openai`
- `providerId=codex-xfyun`
- `providerId=codex-deepseek`

原因：

1. 三者本质上仍是同一个 `codex` 二进制和同一套协议。
2. readiness、binary 检测、output parser、app-server/exe-json 执行器都相同。
3. 拆成多个 provider 会让 `/agents`、runtime health、run projection 出现重复 inventory。
4. 真正变化的是“执行 profile”，不是“接入协议类型”。

因此第一版稳定口径应是：

- `provider_id = codex`
- `provider_profile_id = codex_openai_strong | codex_xfyun_execute | codex_deepseek_fallback`
- `worker_id` 可以是面向路由和观测的 profile worker，例如：
  - `codex-openai`
  - `codex-xfyun`
  - `codex-deepseek`

### 5.2 三层身份模型

| 层级 | 作用 | 第一版建议值 |
|---|---|---|
| `provider_id` | 表示接入源与协议实现 | `codex` |
| `provider_profile_id` | 表示同一 provider 下的执行 profile | `codex_openai_strong` / `codex_xfyun_execute` / `codex_deepseek_fallback` |
| `worker_id` | 表示路由候选与控制面观测对象 | `codex-openai` / `codex-xfyun` / `codex-deepseek` |

这允许系统保持：

- provider 只有一个：`codex`
- worker 可以有多个：面向不同用途与计费通道

## 6. 第一版 profile 目录

### 6.1 固定 profile 目录

| `provider_profile_id` | 推荐用途 | 目标配置 | 计费语义 |
|---|---|---|---|
| `codex_openai_strong` | 方案设计、疑难排查、闭环复核 | `model_provider=OpenAI`，`model=gpt-5.4` | 高质量高成本 |
| `codex_xfyun_execute` | 落地代码、实现问题、常规修复 | `model_provider=xfyun`，`model=xopglm51` | 月卡预付主力 |
| `codex_deepseek_fallback` | 前两条不可用时的 paid fallback | `model_provider=deepseek`，`model=deepseek-v4-pro` | 按量备选 |

### 6.2 推荐 CLI 渲染方式

为了和当前 executor 的 `model` 字段兼容，第一版建议把 profile 存成归一化结构，再渲染到 CLI：

```json
{
  "model_provider": "OpenAI",
  "model": "gpt-5.4",
  "cli_profile": null,
  "config_overrides": {}
}
```

渲染到命令行时建议遵循：

- `model_provider` 走 `-c model_provider=...`
- `model` 走 `-m ...` 或兼容写成 `-c model=...`
- `cli_profile` 走 `-p ...`
- 其余 override 走多个 `-c key=value`

这样可以同时服务：

- `codex app-server`
- `codex exec --json`
- 未来可能补进的其他 `codex` 子命令

补充约束：

- `model_provider` 作为 passthrough 字段，不应在 harness 侧擅自大小写归一化。
- 用户显式写 `OpenAI`，系统就应保留 `OpenAI`。

## 7. 与现有全局路由的关系

这是本设计最重要的边界。

### 7.1 先选 provider family，再选 codex profile

当前已存在的全局策略是：

- `FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`

它已经定义了：

- `deveco` / `codebuddy` 免费优先
- `codex` / `reasonix` 付费 fallback
- `trae` / `zcode` 手动窗口

因此 codex 多 profile 不是新的顶层 provider 路由，而是：

```text
全局 provider route
  ->
如果选中 codex family
  ->
codex profile route
```

换句话说：

1. 若全局已选 `deveco` 或 `codebuddy`，就不进入 codex profile 选择。
2. 若任务显式 pin 到 codex，或全局 route 已落到 codex，才在 codex 家族内部继续选 `openai / xfyun / deepseek`。
3. codex profile 选择不能绕过 `free_first` 的全局边界。

### 7.2 与三段式任务链的关系

与 `COMPLEX_REQUIREMENT_DESIGN_IMPLEMENT_VERIFY_PLAN.md` 对齐后，推荐链路是：

| 任务阶段 | 首选 codex profile | 候选回退 |
|---|---|---|
| `design` | `codex_openai_strong` | `codex_xfyun_execute` -> `codex_deepseek_fallback` |
| `implement` | `codex_xfyun_execute` | `codex_openai_strong` -> `codex_deepseek_fallback` |
| `verify` | `codex_openai_strong` | `codex_xfyun_execute` -> `codex_deepseek_fallback` |

这条链只在“本轮已经决定由 codex 家族执行”时生效。

## 8. 配置合同

第一版建议采用“三层覆盖”。

### 8.1 覆盖优先级

稳定优先级建议固定为：

```text
task metadata override
  >
worker-bound provider profile
  >
provider default config
  >
CLI binary default
```

### 8.2 Task metadata

建议新增：

- `provider_routing_policy=codex_profile_chain`
- `workflow_stage=design|implement|verify`
- `preferred_provider_profile`
- `provider_profile_candidates`
- `provider_model_provider`
- `provider_model`
- `provider_cli_profile`
- `provider_config_overrides`

说明：

- `provider_model` 复用当前已有字段，避免打破 `CodexAppServerWorkerExecutor` 现有逻辑。
- 新增的 `provider_model_provider`、`provider_cli_profile`、`provider_config_overrides` 用于补齐 profile 维度。

### 8.3 Worker metadata

建议每个 codex profile worker 固定写入：

- `provider_profile_id`
- `provider_profile_role`
  - `design_verify`
  - `implement`
  - `fallback`
- `provider_model_provider`
- `provider_model`
- `provider_cli_profile`
- `provider_config_overrides`
- `provider_cost_class=paid_auto`
- `provider_billing_class`
  - `high_cost_strong`
  - `monthly_prepaid`
  - `usage_metered`

### 8.4 Provider 级默认配置

为了回答“或者配置支持”这个诉求，第一版建议在 `LocalCliProviderConfig` 的现有模式上补 provider 级默认项：

- `agentcloud.providers.codex.model_provider`
- `agentcloud.providers.codex.profile`
- `agentcloud.providers.codex.config_json`

对应环境变量可保守扩成：

- `MULTICA_CODEX_MODEL_PROVIDER`
- `MULTICA_CODEX_PROFILE`
- `MULTICA_CODEX_CONFIG_JSON`

这层的目标不是承载全部多 profile 目录，而是承载：

- 全局默认 model provider
- 全局默认 codex profile
- 全局默认 config override

命名 profile 本身仍建议挂在 worker metadata 或 `workers.yml` 覆盖中。

## 9. 路由规则

### 9.1 显式优先

若任务显式给出：

- `assigned_worker=codex-openai`
- 或 `preferred_provider_profile=codex_openai_strong`

则优先按显式配置执行，不再做阶段推断。

### 9.2 阶段驱动

若任务未显式 pin 某个 codex profile，但 metadata 已带：

- `workflow_stage=design`
- `workflow_stage=implement`
- `workflow_stage=verify`

则按上一节的阶段默认链选择候选顺序。

### 9.3 无阶段时保持保守

若任务只是“走 codex”，但没有：

- `assigned_worker`
- `preferred_provider_profile`
- `workflow_stage`

第一版建议保持保守：

- 不从标题自然语言硬猜
- 要么继续沿用旧单 worker `codex`
- 要么在 route trace 里明确提示“缺少 codex profile 选择信号”

这样可以避免实现上线后，普通 `codex` 任务被意外切到高成本 profile。

## 10. 执行器改造边界

### 10.1 `CodexExecutionPlan` 已新增的核心字段

当前实现里，`CodexExecutionPlan` 已稳定携带：

- `providerProfileId`
- `modelProvider`
- `model`
- `cliProfile`
- `configOverrides`

### 10.2 `app_server` 与 `exec_json` 已双链一致，但必须继续保持一致

当前仓库既支持：

- `app_server`
- `exec_json`

当前两条链都已经拿到同一套 profile 配置：

- `app_server` 启动命令加 profile override
- `exec_json` 启动命令也加 profile override

本轮 focused regression 进一步锁住了“不能只改一条链，否则 route trace 和真实运行会分叉”这一点。

### 10.3 `thread/start` 已不再把 profile 相关字段写成 `null`

当前 `thread/start` 已改成透传 resolved profile：

- `modelProvider = resolved model provider`
- `profile = resolved cli profile`
- `config = resolved config overrides`

这样 provider run 的结构化 trace 能和最终 CLI 行为对齐。

## 11. 观测与回放字段

第一版建议至少新增以下导出字段：

- `selected_provider_profile`
- `configured_model_provider`
- `configured_model`
- `configured_cli_profile`
- `configured_config_overrides`
- `provider_profile_candidates_considered`
- `provider_profile_fallback_reason`
- `provider_billing_class`

这些字段应进入：

- `WorkerExecutionResult.metadata`
- `artifact.metadata`
- `/api/v1/tasks/{id}/select_worker`
- `/api/v1/tasks/{id}/live_flow`

## 12. 失败分类与回退

第一版不要求主动查询所有 API 余额，但需要把失败分类补硬。

建议 codex profile fallback 至少识别：

- `quota_exhausted`
- `auth_blocked`
- `backend_unavailable`
- `profile_misconfigured`
- `dispatch_preflight_failed`

回退语义建议：

1. 当前首选 profile 因 `quota_exhausted` 失败 -> 进入候选链下一个 profile。
2. 因 `auth_blocked` 或 `profile_misconfigured` 失败 -> 标记该 profile 不可用，并给出明确 trace。
3. 因 `backend_unavailable` 失败 -> 可短期重试或切下一候选。
4. 全部 codex profile 都不可用 -> 回到全局 route / human gate 决策。

## 13. 当前风险

| 风险 | 当前状态 | 设计口径 |
|---|---|---|
| 把 codex profile 误做成多个 provider | 当前 provider 骨架只有一个 codex | 第一版固定为“一个 provider，多条 profile” |
| 只改 `exec_json` 不改 `app_server` | 当前两条链并存 | 必须双链一致 |
| 和 `free_first` 冲突 | 当前 `codex` 仍是 paid fallback | 先全局 route，再进入 codex profile route |
| `model_provider` 大小写被静默改写 | 用户显式给出 `OpenAI` | 作为 passthrough 保留原值 |
| 普通 codex 任务被默认切到贵 profile | 当前无 profile 语义 | 无阶段 / 无显式 pin 时保持保守 |

## 14. 结论

按当前源码现实，最稳的方案不是“新增多个 codex provider”，而是：

1. 保持 `providerId=codex` 不变。
2. 引入命名 `provider_profile_id`。
3. 允许 `codex-openai / codex-xfyun / codex-deepseek` 作为多个 worker lane 出现在控制面。
4. 通过 `task > worker > provider default` 的优先级解析 profile。
5. 让 profile 选择成为“codex 家族内部的第二层路由”，不覆盖现有 `free_first` 顶层策略。

当前更合适的后续文档是 execution record、provider contract 补充和更宽范围的 route/live-flow 回归，而不是重新讨论是否要把 codex 拆成多个 provider。
