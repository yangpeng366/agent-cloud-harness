# 免费模型 Worker Lane 落地计划

> 本文档吸收 Obsidian `harness加免费模型编排方案.md` 的构想，收成可执行的产品决策与工程计划。

## 1. 产品决策

### 决策 1：配置驱动，不硬编码

operator 通过 `harness-config.yml` 声明 worker lane，无需改 Java 代码即可接入 CCX 新渠道。参考 CCX 的 `config.json` 模式——CCX 渠道变更只需改 JSON + 热重载，不需要重新编译。

### 决策 2：Sublime 式默认 + 用户覆盖

harness 维护一个本机状态 JSON（`harness-state.json`），记录自动发现的 provider / worker 状态。用户通过 `harness-config.yml` 覆盖：启用/禁用 provider、调整优先级、指定模型。

- `harness-state.json`：harness 自动维护，记录本机实际可用的 provider / worker / 模型
- `harness-config.yml`：用户手动编辑，声明想要的 provider 配置和优先级
- 合并规则：用户配置覆盖自动发现，未声明的用自动发现结果

### 决策 3：两个 codex%20CLI lane，不更多

harness 只用两个 codex CLI lane：

| Lane | CCX 模型 | model_tier | cost_class | 角色 |
|------|----------|-----------|-----------|------|
| codex-main | `codex` | strong | paid_auto | 主力 coding，CCX 路由到付费渠道 |
| codex-free | `codex-free` | small | free_auto | 免费炮灰，CCX 路由到免费渠道 |

CCX 负责模型路由：`codex-free` 通过各渠道的 `modelMapping` 映射到实际免费模型（硅基 Qwen2.5-7B、智谱 glm-4-flash、OpenRouter Nemotron、GitHub GPT-4o-mini）。harness 不关心 CCX 背后是哪个渠道。

+-----------+          +--------+          +------------------+
| harness   |  codex   |  CCX   | codex    | 付费渠道         |
| codex-main|--------->| model= |--------->| 讯飞/商汤/...    |
|           |  codex   | codex  |          +------------------+
| codex-free|--------->| model= |  codex   +------------------+
|           |          | codex  |--------->| 免费渠道         |
|           |          | -free  |          | 硅基/智谱/...    |
+-----------+          +--------+          +------------------+

### 决策 4：CCX 渠道健康检查集成

第一版做成启动时 precheck + 手动 refresh，不做自动 re-ready。

### 决策 5：cost_class 路由增强

新增的免费 worker lane 标记 `cost_class: free_auto`，直接复用现有 `free_first` 路由逻辑。

## 2. harness-config.yml 设计

### 2.1 文件搜索路径

```
1. ./harness-config.yml          （当前工作目录）
2. ./config/harness-config.yml   （config 子目录）
3. ~/.agentcloud/harness-config.yml  （用户主目录）
4. -Dagentcloud.config.path=...     （显式指定）
```

### 2.2 Schema 设计

```yaml
# harness-config.yml — 用户配置（覆盖自动发现结果）

harness:
  defaults:
    provider_model_provider: ccx
    provider_base_url: http://127.0.0.1:3688/v1
    provider_wire_api: chat_completions
    provider_bearer_token: ccx-YOUR_BEARER_TOKEN_HERE

  ccx:
    base_url: http://127.0.0.1:3688
    admin_key: ccx-YOUR_ADMIN_KEY_HERE
    health_check_on_startup: true
    channel_sync_on_startup: false

  workers:
    - id: codex-main
      provider: codex
      model_tier: strong
      cost_class: paid_auto
      selection_priority: 100
      capabilities: [chat, code, patch, session]
      profile:
        model: codex
        model_provider: ccx
      metadata:
        primary_role: planner_executor
        execution_backend: provider_app_server
        local_workspace_access: true

    - id: codex-free
      provider: codex
      model_tier: small
      cost_class: free_auto
      selection_priority: 70
      capabilities: [chat, code, session]
      profile:
        model: codex-free
        model_provider: ccx-free
      metadata:
        primary_role: cannon_fodder
        execution_backend: provider_app_server
        local_workspace_access: true
```

###  2.3 harness-state.json（自动维护）

harness 启动时自动探测本机环境，生成 `harness-state.json`：

```json
{
  "lastUpdated": "2026-07-23T12:00:00",
  "ccx": {
    "reachable": true,
    "models": ["codex", "codex-free", "glm-5.2", ...],
    "channels": {
      "desktop-xfyun-responses": { "status": "active", "priority": 1 },
      "siliconflow-free-9b": { "status": "active", "priority": 50 },
      "zhipu-flash-free": { "status": "active", "priority": 60 }
    }
  },
  "workers": {
    "codex": { "cliAvailable": true, "lastCheck": "..." },
    "codex-free": { "cliAvailable": true, "lastCheck": "..." }
  },
  "providers": {
    "deepseek": { "available": true, "userEnabled": false },
    "trae": { "available": true, "userEnabled": false },
    "codex": { "available": true, "userEnabled": true },
    "codex-free": { "available": true, "userEnabled": true }
  }
}
```

用户配置中 `userEnabled: false` 的 provider 不进入路由候选。

### 2.4 与 BuiltinAgentProviders 的关系

`BuiltinAgentProviders.defaults()` 继续提供内置 worker 注册。`harness-config.yml` 中声明的 worker lane 是增量注册。配置不存在时回退到内置默认值。

## 3. CCX 配置要点

### 3.1 不加新渠道，只加 modelMapping

CCX 的设计是输入模型名（如 `codex-free`），各渠道通过 `modelMapping` 映射到自己的上游模型。不需要为每个免费模型加新渠道。

当前 CCX Desktop 配置中，以下渠道已添加 `codex-free` 映射：

| 渠道 | 类型 | codex-free 映射到 | 状态 |
|------|------|-------------------|------|
| siliconflow-free-9b | responsesUpstream | Qwen/Qwen2.5-7B-Instruct | active |
| zhipu-flash-free | responsesUpstream | glm-4-flash | active |
| openrouter-free-coding | responsesUpstream | nvidia/nemotron-3-super-120b-a12b:free | active |
| github-models-free | responsesUpstream | gpt-4o-mini | active |
| desktop-siliconflow-free-chat | chatUpstream | Qwen/Qwen2.5-7B-Instruct | active |

### 3.2 chatUpstream 和 responsesUpstream 都要配

- `responsesUpstream`：codex CLI 用的（Responses API）
- `chatUpstream`：harness 直接 LLM 调用用的（Chat Completions API）

两个都要加 `codex-free` 的 `supportedModels` 和 `modelMapping`。

### 3.3 codex config.toml

```toml
[model_providers.ccx]
name = "CCX Proxy"
base_url = "http://127.0.0.1:3688/v1"
wire_api = "responses"
requires_openai_auth = true
experimental_bearer_token = "ccx-YOUR_BEARER_TOKEN_HERE"

[model_providers.ccx-free]
name = "CCX Free Proxy"
base_url = "http://127.0.0.1:3688/v1"
wire_api = "responses"
requires_openai_auth = true
experimental_bearer_token = "ccx-YOUR_BEARER_TOKEN_HERE"
```

codex CLI 使用：
- 主力：`codex -c model_provider=ccx -c model=codex`
- 免费：`codex -c model_provider=ccx-free -c model=codex-free`

## 4. 路由场景

### 场景 1：日常 coding（默认走主力）

```
用户: "改一下 articleeditor 的登录页"
harness: route -> codex-main (strong tier, paid_auto)
CCX: 讯飞/商汤 -> glm-5.2
```

### 场景 2：免费炮灰 subagent

```
用户: "用 N 路评测跑10个方案的lint"
harness: model_mode=small_only + free_first -> codex-free
CCX: 硅基 -> Qwen2.5-7B-Instruct
```

### 场景 3：advisory handoff 升级

```
codex-free 执行中遇到 ESCALATE
harness: advisory handoff -> codex-main (strong tier)
codex-main 给出 advisory 判断
harness: auto-resume 原任务给 codex-free 继续
```

## 5. 落地优先级

| 优先级 | 切片 | 状态 | 验证入口 |
|--------|------|------|----------|
| P1 | `harness-config.yml` 加载 + worker 注册 | done | HarnessConfigLoaderTest 7 + WorkerRegistryConfigRegistrationTest 6 |
| P2 | CCX 渠道 codex-free 映射 + codex config.toml | done | CCX chat completions + responses API 实测通过 |
| P3 | harness-state.json 自动发现 | done | HarnessStateWriterTest 6 + wired into Main.java startup |

## 6. 代码改动面

### P1: HarnessConfig 加载（已完成）

新增类：HarnessConfig / HarnessConfigLoader / WorkerLaneConfig / WorkerLaneProfileConfig
修改类：WorkerRegistry（新增 registerFromConfig）
新增依赖：jackson-dataformat-yaml

### P2: CCX + codex 配置（已完成）

修改文件：
- CCX Desktop config.json：chatUpstream + responsesUpstream 加 codex-free 映射
- codex config.toml：加 [model_providers.ccx-free]

### P3: harness-state.json 自动发现（待开发）

新增类：
- HarnessState（record）
- HarnessStateWriter（启动时探测 + 写入）

## 7. 与现有文档的关系

- 取代 Obsidian `harness加免费模型编排方案.md` 中的"代码层集成"部分
- 与 `FREE_FIRST_PROVIDER_ROUTING_DESIGN.md` 对齐：免费 lane 复用 free_first 路由
- 与 `CODEXD_MULTI_API_PROFILE_ROUTING_DESIGN.md` 对齐：codex-free 是 codex provider 的 profile lane
- 与 `CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md` 对齐：codex-free 是 small-tier worker

## 8. 不做的事

- 不做 `harness-config.yml` 热重载（第一版重启生效）
- 不做 CCX 渠道状态自动同步到 worker lane（第一版只做启动时 precheck）
- 不引入新 provider 或新 IPC 协议
- 不在 harness 内复制 CCX 的路由逻辑
- 不为每个免费模型加独立 CCX 渠道（用 modelMapping 即可）

## 9. 成本收益

| 场景 | 优化前 | 优化后 |
|------|--------|--------|
| subagent 评测 | GLM-5.2 ~1.2元/天 | Qwen2.5-7B 0元 |
| 长文件 coding | GLM-5.2 ~0.5元/天 | Nemotron 0元 |
| 中文补全 | GLM-5.2 ~0.5元/天 | glm-4-flash 0元 |

预估月节省 ~66 元。

## 10. 写回顺序

- 本计划为主入口
- 配置 schema 变化写 `API_CONTRACTS.md`
- 路由行为变化写 `FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`
- 稳定取舍写 `DECISIONS.md`
- 跨主题摘要写 `STATE.md`

## 11. CCX 启动服务集成

### 11.1 启动时 CCX 可达性检测

harness 启动时，如果 harness-config.yml 中 ccx.health_check_on_startup 为 true，会执行以下 precheck：

1. 向 ccx.base_url + /models 发送 GET 请求（Bearer token 取 defaults.provider_bearer_token）
2. 如果返回 200，标记 ccxReachable=true，解析模型列表写入 harness-state.json
3. 如果连接失败或超时（5s），标记 ccxReachable=false，输出日志提示用户启动 CCX

当前 CCX Desktop 需要用户手动启动。harness 不负责自动拉起 CCX 进程。

### 11.2 CCX 渠道状态同步

harness-state.json 的 ccxChannels 字段记录 CCX 渠道状态。当前版本只在启动时探测一次，不做运行时自动同步。

用户可通过以下方式手动刷新：
1. 重启 harness
2. 调用 CCX Dashboard API（http://127.0.0.1:3688/api/messages/channels/dashboard）查看渠道状态

### 11.3 CCX 启动方式

CCX Desktop 由用户手动启动（系统托盘应用）。启动后默认监听 127.0.0.1:3688。

harness 的 harness-config.yml 配置：

    harness:
      ccx:
        base_url: http://127.0.0.1:3688
        health_check_on_startup: true

如果 CCX 未启动，harness 仍可运行，但所有依赖 CCX 的 worker lane（codex-main、codex-free）将不可用。

## 12. 配置覆盖完整流程

### 12.1 两层配置模型

| 层 | 文件 | 维护者 | 用途 |
|----|------|--------|------|
| 自动发现 | harness-state.json | harness 自动维护 | 记录本机实际可用的 provider / worker / 模型 |
| 用户配置 | harness-config.yml | 用户手动编辑 | 覆盖自动发现结果，声明想要的 provider 配置和优先级 |

### 12.2 合并规则

1. harness-config.yml 中声明的 worker lane 增量注册到 WorkerRegistry，覆盖同名内置 worker
2. harness-config.yml 中未声明的 provider 使用 harness-state.json 的自动发现结果
3. harness-state.json 中 providers.userEnabled=false 的 provider 不进入路由候选
4. 配置不存在时回退到内置默认值（BuiltinAgentProviders.defaults()）

### 12.3 用户配置示例

启用 codex + codex-free，禁用 deepseek 和 trae：

    harness:
      defaults:
        provider_model_provider: ccx
        provider_base_url: http://127.0.0.1:3688/v1
        provider_bearer_token: ccx-YOUR_BEARER_TOKEN_HERE

      workers:
        - id: codex-main
          provider: codex
          model_tier: strong
          cost_class: paid_auto
          selection_priority: 100
          profile:
            model: codex
            model_provider: ccx

        - id: codex-free
          provider: codex
          model_tier: small
          cost_class: free_auto
          selection_priority: 70
          profile:
            model: codex-free
            model_provider: ccx-free

harness-state.json 中 deepseek.userEnabled=false 和 trae.userEnabled=false 确保这两个 provider 不进入路由。

### 12.4 配置生效方式

当前版本：修改 harness-config.yml 后需重启 harness 生效。不支持热重载。

### 12.5 配置验证

harness 启动时会输出加载日志：

    Harness config loaded from: ./harness-config.yml (2 worker lanes)
    Harness state written to: ./.tmp/harness-state.json

如果配置文件有语法错误，会输出警告并回退到内置默认值：

    Harness config ignored. path=./harness-config.yml reason=...
    No harness-config.yml found; using builtin defaults