# OmniRoute OpenAI-Compatible 本地网关接入计划

> 文档类型：方案计划（`*_PLAN.md`）。本文档聚焦 OmniRoute 在本仓库中的落地方式、自动化启动入口、验活口径与后续执行顺序。它解决的是“让 Harness 通过一个本地 OpenAI-compatible 网关稳定接上免费或低成本上游 provider”，不是把 OmniRoute 当成 `WorkerRegistry` 里的原生 provider lane。
>
> 2026-07-06 当前状态：Phase A 已完成，Phase B-C 待按真实上游配置与 smoke 验收继续收口。

## 1. 背景与目标

当前 provider 主题已经同时存在两类接入路径：

1. provider-native CLI worker，例如 `codex`、`codebuddy`、`deveco`
2. OpenAI-compatible LLM upstream，例如直接配置 `OPENAI_BASE_URL`

OmniRoute 更适合落在第二类。它的价值不是替代 Harness 现有的 worker/provider 路由语义，而是把“开源、可本地托管、可聚合多上游、最好能把免费 OpenAI-compatible provider 收到一个入口里”这件事先收口为一个稳定网关。

本计划的目标有四个：

1. 给本机运维与开发提供一个可重复的 OmniRoute 启动入口，而不是要求每次手动点开 GUI 或手动补环境变量。
2. 让 Harness 启动前就能判断“网关是否真的可用”，避免只看到本地端口起来却没有任何可用模型的假绿状态。
3. 明确 OmniRoute 在本仓库中的定位：它先是一个 OpenAI-compatible LLM 上游，不是原生 `WorkerRegistry` provider。
4. 为后续的上游 provider / combo 配置、smoke 验证、运行手册和 dated execution record 预留统一落点。

## 2. 非目标

本轮不做下面这些事：

1. 不把 OmniRoute 注册成新的 `Worker` 或新的 provider-native CLI lane。
2. 不在 Harness 内部实现 OmniRoute dashboard 配置 API 适配。
3. 不承诺仓库内自动发现并写入所有第三方免费 provider token。
4. 不把 OmniRoute 的上游配方、账号体系或限额策略写死进代码仓库。

## 3. 当前事实基线

### 3.1 已经完成的入口

- 已新增 `scripts/Run-HarnessWithOmniRoute.ps1`。
- 该脚本会优先复用本机 `omniroute` 进程；若 `http://localhost:20128/v1` 未监听，则自动尝试拉起本地 OmniRoute。
- 该脚本会在启动 Harness 前临时设置：
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL`
  - `OPENAI_MODEL`
  - `OPENAI_REVIEW_MODEL`
  - `OPENAI_WIRE_API`
- 当前默认口径：
  - `OPENAI_BASE_URL=http://localhost:20128/v1`
  - `OPENAI_MODEL=auto/coding`
  - `OPENAI_REVIEW_MODEL=auto`
  - `OPENAI_WIRE_API=chat_completions`

### 3.2 已经确认的真实边界

- OmniRoute 本地进程启动成功，不代表真实上游已经可用。
- `GET /v1/models` 为空时，说明“本地网关活着，但 dashboard 里尚未配置上游 provider / combo，或者当前配置没有导出任何可用模型”。
- 因此 `Run-HarnessWithOmniRoute.ps1` 默认会把“模型目录为空”视为阻断条件；只有显式传 `-SkipModelCatalogCheck` 才允许继续启动 Harness。
- Harness 在这种模式下会把自己看作“连到了一个 OpenAI-compatible LLM upstream”；它不会因为 `OPENAI_BASE_URL` 指向 OmniRoute，就自动生成一个新的 worker route 语义。

### 3.3 当前文档与实现落点

- 启动与使用入口：`../STARTUP_GUIDE.md`
- provider 技术定位：`AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- 主题进度入口：`provider/PROGRESS.md`
- 跨主题短摘要：`../STATE.md`

## 4. 方案范围

### 4.1 本轮要收口

1. 本地自动拉起 OmniRoute。
2. Harness 启动前做 `/v1/models` 验活。
3. 把默认 `auto/coding`、`auto` 模型映射写入启动脚本与启动文档。
4. 明确“空模型目录 = 未完成上游配置”的排障口径。

### 4.2 下一轮要收口

1. 形成一套最小可复用的 OmniRoute dashboard 配置基线。
2. 形成 Harness + OmniRoute 的 dated smoke / acceptance 记录。
3. 视真实使用情况决定是否补充专项 runbook。

## 5. 分阶段落地

### Phase A：本地自动化启动入口

#### 目标

让操作者只需要执行一个脚本，就能完成 OmniRoute 启动检查和 Harness 环境注入。

#### 当前状态

已完成。

#### 交付物

- `scripts/Run-HarnessWithOmniRoute.ps1`
- `STARTUP_GUIDE.md` 里的 OmniRoute 启动说明
- `AGENT_PROVIDER_TECHNICAL_DESIGN.md` 里的定位说明

#### 关键约束

- 入口脚本不能把真实第三方 token 固化进仓库。
- 入口脚本必须允许覆盖 `-ApiKey`、`-BaseUrl`、`-Model`、`-ReviewModel`、`-WireApi`。
- 入口脚本必须在退出时恢复调用前的 OpenAI 相关环境变量，避免污染当前终端。

### Phase B：OmniRoute 上游配置基线

#### 目标

把“如何在 OmniRoute 里接上一个可用的免费或低成本 OpenAI-compatible 上游”从口头步骤收口为可复用 checklist。

#### 当前状态

未完成。

#### 建议动作

1. 在 OmniRoute dashboard 中至少配置一个真实可用的 provider 或 combo。
2. 确保它能导出 `auto/coding`，或者把脚本参数改成 dashboard 中真实存在的模型别名。
3. 验证 `GET http://localhost:20128/v1/models` 返回非空 `data[]`。
4. 若后续形成稳定做法，再补一份 runbook 或 dated execution record。

#### 验收口径

- `/v1/models` 不为空。
- 返回的模型名与脚本默认值一致，或有明确覆写参数。
- operator 不需要进入 Harness 后才发现上游完全不可用。

### Phase C：Harness 端 smoke 与观测面确认

#### 目标

确认 Harness 在接到 OmniRoute 后，健康面、运行面和最小任务链路都与预期一致。

#### 当前状态

部分已验证，仍待“带真实上游模型”的完整 smoke。

#### 建议动作

1. 用 `Run-HarnessWithOmniRoute.ps1` 拉起隔离实例。
2. 检查 `/api/v1/health` 中的：
   - `llm.available=true`
   - `base_url=http://localhost:20128/v1`
   - `model=auto/coding`
   - `review_model=auto`
   - `wire_api=chat_completions`
3. 派发最小 coding / review 任务，确认不会在首次调用时卡死在空上游。
4. 若真实 smoke 完成，补 dated execution record。

#### 验收口径

- Harness 健康面读到的 LLM 配置与启动脚本一致。
- 最小任务在 OmniRoute 已配置上游时可正常完成。
- 若 OmniRoute 未配置上游，启动阶段就能失败并给出可读提示。

## 6. 关键风险

### 6.1 网关活着但模型目录为空

这是当前最真实的风险。端口监听成功只说明本地进程已起，不说明实际可派发。

当前应对：

- 启动脚本默认阻断空 `/v1/models`
- 文档明确把它归类为“上游未配置”而不是 Harness 故障

### 6.2 默认模型别名与 dashboard 配置不一致

脚本默认用 `auto/coding` 与 `auto`，但真实 dashboard 里可能只有别的别名。

当前应对：

- 允许通过 `-Model`、`-ReviewModel` 覆写
- 后续把真实可复用别名沉淀进 runbook 或 execution record

### 6.3 把 OmniRoute 误解成原生 provider lane

这会导致后续在 `WorkerRegistry`、route trace、manual window 等语义上走偏。

当前应对：

- 本文档与 `AGENT_PROVIDER_TECHNICAL_DESIGN.md` 都明确它当前只是一层 OpenAI-compatible upstream gateway

### 6.4 本地启动耗时与日志位置不透明

OmniRoute 与 Harness 串联启动通常比直连云端慢；若日志位置不清晰，操作者容易误判为卡死。

当前应对：

- 继续沿用脚本 stdout/stderr 重定向与 `.tmp/` 证据目录
- 后续若 runbook 成型，再固化日志检查顺序

## 7. 操作与验证入口

### 7.1 启动入口

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1
```

### 7.2 常见覆写

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1 `
  -ApiKey "<your-key>" `
  -Model "auto/coding" `
  -ReviewModel "auto" `
  -Port 18411
```

### 7.3 预检查

```powershell
Invoke-RestMethod -Headers @{ Authorization = 'Bearer sk-omniroute' } `
  -Uri 'http://localhost:20128/v1/models'
```

### 7.4 Harness 健康检查

```powershell
Invoke-RestMethod -Uri 'http://localhost:18411/api/v1/health'
```

## 8. 后续文档落点

如果继续推进 OmniRoute 这条线，建议按下面顺序写回：

1. 真实上游配置与实际可用模型别名：
   - 优先补本文档，或新增 dated execution record
2. 启动步骤、常见报错、日志定位：
   - 优先补 `STARTUP_GUIDE.md`
3. provider 定位或路由语义变化：
   - 优先补 `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
4. 跨主题短摘要：
   - 写 `../STATE.md`

## 9. 完成定义

这条子线达到“第一版可落地”至少需要同时满足：

1. `Run-HarnessWithOmniRoute.ps1` 可自动拉起 OmniRoute 或复用已启动实例。
2. 未配置上游时，脚本会因空 `/v1/models` 明确失败，而不是放出假绿 Harness。
3. 已配置上游时，Harness `/api/v1/health` 能稳定读到 OmniRoute 的 OpenAI-compatible 配置。
4. 至少有一份 dated smoke / execution record 证明“带真实上游模型”的最小任务链路可用。
