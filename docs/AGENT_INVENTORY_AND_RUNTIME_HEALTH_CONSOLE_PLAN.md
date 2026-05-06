# Agent Inventory 与 Runtime Health Console 方案（面向 agent-cloud-harness）

## 1. 文档目标

本文档用于定义 `agent-cloud-harness` 在引入 Agent Provider 能力之后，Console / Web UI 应如何呈现：

- Agent Inventory
- Provider Detail
- Runtime Health
- Agent Run Detail
- 与 Task / Live Flow 的联动关系

目标不是追求复杂 UI，而是让系统在保留当前 task/control-plane 观测能力的同时，新增 **managed agents platform** 视角。

---

## 2. 设计目标

### 2.1 保留现有 Console 主定位
当前 `/console/` 的优势是：
- 看任务
- 看 control flow
- 看 live flow diagnostics
- 看 packet / runtime context / tool trace / experiment

这些都应该保留。

### 2.2 新增 Agent 管理视角
补齐以下“当前还看不见”的问题：
- 本机到底发现了哪些 Agent Provider
- 哪些 provider 已安装
- 哪些 provider 缺登录
- 哪些 provider 可接单
- 某个任务最后到底落到了哪个 provider
- 某次 provider run 发生了什么

### 2.3 观测优先，而不是美术优先
这部分 UI 的关键是：
- 信息结构清晰
- 状态可解释
- 出错可排查
- 任务与 agent 关系可追踪

不是先追求复杂视觉。

---

## 3. 总体信息架构

建议在当前 `/console/` 下扩展出 4 个一级视图：

1. **Tasks**
2. **Live Flow**
3. **Agents**
4. **Runtime**

对应关系：

```text
/console/
  ├── Tasks
  ├── Live Flow
  ├── Agents
  └── Runtime
```

其中：
- `Tasks` 继续是用户主入口
- `Live Flow` 继续是聚合诊断面
- `Agents` 是新增的 provider inventory
- `Runtime` 是新增的 daemon/执行健康视图

---

## 4. 视图一：Agents（Agent Inventory）

## 4.1 目标
让用户快速回答：
- 系统当前发现了哪些 agent
- 每个 agent 现在能不能用
- 哪个 agent 更适合接哪类任务

## 4.2 页面结构

建议布局：

### 顶部统计卡
显示：
- `Providers Total`
- `Ready`
- `Auth Needed`
- `Unavailable`
- `Active Runs`

示意：

```text
[ Providers 4 ] [ Ready 2 ] [ Auth Needed 1 ] [ Unavailable 1 ] [ Active Runs 3 ]
```

### 中部列表表格
每行一个 provider。

建议字段：
- Provider Name
- Type
- Installed
- Auth Status
- Ready
- Capabilities
- Model Tier
- Last Check
- Active Runs
- Last Error

示意字段：

| Provider | Type | Installed | Auth | Ready | Capabilities | Tier | Active Runs | Last Check | Last Error |
|----------|------|-----------|------|-------|--------------|------|-------------|------------|------------|
| Codex | local_cli | yes | ok | yes | code, patch | strong | 2 | 10:01 | - |
| OpenClaw | embedded | yes | ok | yes | chat, tool | orchestrator | 1 | 10:01 | - |
| Claude Code | local_cli | yes | auth_needed | no | code, patch | strong | 0 | 10:01 | auth expired |

### 右侧详情抽屉或详情区
点击 provider 后显示：
- 基本信息
- 版本
- binary 路径
- transport
- tool capabilities
- 最近 runs
- 最近错误
- refresh status 按钮

---

## 4.3 状态颜色建议
### Installed
- yes: 灰/默认
- no: 浅灰

### Auth Status
- `ok`: 绿色
- `auth_needed`: 橙色
- `unknown`: 黄色
- `unsupported`: 灰色

### Ready
- `true`: 绿色
- `false`: 红色/灰色

这样用户一眼就能知道到底是：
- 没装
- 没登录
- 装了但不可用
- 完全可用

---

## 4.4 交互动作建议
每个 provider 支持：
- `Refresh`
- `View Runs`
- `Copy Diagnostics`

后续可扩：
- `Test Probe`
- `Open Setup Guide`

---

## 5. 视图二：Provider Detail

## 5.1 目标
查看单个 provider 的完整可观测信息。

## 5.2 信息分区

### A. Summary
显示：
- provider id
- display name
- provider type
- transport
- version
- installed
- auth status
- ready
- readiness reason

### B. Capabilities
显示：
- supported capabilities
- default role
- model tier
- session support
- tool support

### C. Runtime Diagnostics
显示：
- active runs
- last successful run
- last failed run
- last stderr snippet
- last exit code
- recent health checks

### D. Recent Runs
表格字段：
- run id
- task id
- role
- status
- started at
- duration
- summary

---

## 6. 视图三：Runtime Health

## 6.1 目标
这是整个 managed runtime 的健康面板，用来回答：
- 当前系统有没有在正常管理 agent 执行
- 有没有异常 run
- 哪些 provider 出现了连续失败

## 6.2 页面结构

### 顶部总览卡
建议显示：
- Active Runs
- Failed Runs (24h)
- Crashed Runs
- Unavailable Providers
- Auth Needed Providers

### 中部运行队列表
建议字段：
- Run ID
- Task ID
- Provider
- Worker Role
- Status
- Started At
- Duration
- Exit Code
- Output Preview

### 下部异常面板
建议拆成 3 块：
- Recent Failures
- Recent Auth Problems
- Recent Provider Unavailable Events

---

## 6.3 Runtime Health 核心指标
建议最小暴露这些指标：
- `active_run_count`
- `failed_run_count_24h`
- `cancelled_run_count_24h`
- `auth_needed_provider_count`
- `unavailable_provider_count`
- `average_run_duration`
- `provider_failure_rate`

如果一开始没有完整时间窗口统计，也可以先只做最近 N 条近似统计。

---

## 7. 视图四：Agent Run Detail

## 7.1 目标
查看一次 provider run 的完整过程。

## 7.2 页面分区

### A. Run Summary
显示：
- run id
- task id
- session id
- provider
- worker role
- selected worker
- status
- started at / ended at
- duration
- exit code

### B. Timeline
显示事件流：
- run.started
- stdout/stderr
- artifact.created
- run.completed / run.failed

### C. Artifacts
显示：
- diff
- file
- log
- text summary

### D. Diagnostics
显示：
- provider selection reason
- fallback reason
- working directory
- command transport
- model tier
- retry count

---

## 7.3 与现有 Live Flow 的关系
建议：
- `Live Flow` 继续作为 task 聚合诊断
- `Agent Run Detail` 专门看 provider run

关系是：
- Live Flow 看“任务整体推进”
- Run Detail 看“某个 provider 的单次执行”

这两个视图不重复，而是分工。

---

## 8. 与现有 Task Detail 的联动

建议在任务详情页中新增一个 `Agent Execution` 区块。

显示：
- selected provider
- provider auth status
- provider ready
- current/latest run id
- current/latest run status
- selected worker role
- model tier
- provider selection reason
- fallback reason

并提供链接：
- `View Provider Detail`
- `View Run Detail`

这样任务页就能把 control plane 视角与 provider 视角连起来。

---

## 9. 与 Live Flow 聚合接口的联动建议

建议在 `GET /api/v1/tasks/{id}/live_flow` 聚合结构中新增：
- `provider_selection`
- `agent_run`
- `agent_run_events`
- `agent_artifacts`

这样前端在 task 维度下仍然可以一站式加载。

但列表页和 provider detail 仍应使用专门的：
- `/api/v1/agents`
- `/api/v1/agents/{id}`
- `/api/v1/agent_runs/{runId}`

避免 live flow 承担所有读路径。

---

## 10. 前端组件建议

## 10.1 可复用组件
建议抽出：
- `StatusBadge`
- `ProviderCapabilityList`
- `RunStatusBadge`
- `TimelineList`
- `MetricCard`
- `DiagnosticsPanel`

### StatusBadge 建议支持
- ready / unavailable
- auth ok / auth needed
- run running / failed / completed

---

## 10.2 页面组件拆分建议

```text
web/console/
  agents/
    agent-list.js
    agent-detail.js
    provider-status-badge.js
  runtime/
    runtime-health.js
    run-list.js
    run-detail.js
  shared/
    metric-card.js
    timeline.js
    diagnostics-panel.js
```

如果当前 console 还是单文件脚本，也可以先逻辑分段，不急着重构成很多文件。

---

## 11. 第一阶段最小 UI 范围

如果控制投入，建议第一阶段只做这 3 个能力：

### MVP-1 Agent Inventory 列表
必须有：
- provider name
- installed
- auth status
- ready
- capabilities
- active runs

### MVP-2 Task Detail 中的 Agent Execution 区块
必须有：
- selected provider
- current/latest run status
- selection reason
- fallback reason

### MVP-3 Runtime Health 简版面板
必须有：
- active runs
- failed runs
- unavailable providers
- recent failures

这样已经足够把“managed agents platform”的壳立起来。

---

## 12. 第二阶段可扩展项

### A. Provider Setup Guide
对于 `auth_needed` 或 `not_installed` 的 provider，提供 setup hint。

### B. Run Search / Filter
支持按：
- provider
- status
- role
- task id

过滤。

### C. Provider Failure Analytics
显示：
- 最近 24h 连续失败次数
- 常见失败原因聚类

### D. Provider Comparison View
按 provider 比较：
- 平均完成率
- 平均耗时
- 失败率
- 常见任务类型

这块后续会和 experiment matrix 联动得很好。

---

## 13. 设计取舍建议

### 不建议现在就做复杂会话聊天 UI
因为当前项目的差异化不是聊天，而是：
- continuity
- control graph
- runtime diagnostics
- orchestration trace

### 建议优先做表格 + 详情 + 时间线
原因：
- 最容易落地
- 最适合排障
- 与现有 console 风格一致

### 不建议把 Agent 页面做成独立产品壳
建议先作为 `/console/` 下的一个新视图，保持一体化。

---

## 14. 与 Multica 借鉴关系总结

Multica 给这块最重要的启发不是“界面长什么样”，而是：
- agent inventory 应该可见
- provider 状态应该可见
- run 执行过程应该可见
- 多 agent 不只是能切换，而是能被管理

对于 `agent-cloud-harness` 来说，借鉴方式应是：
- 保留原有 task/control-plane 核心
- 补上 managed agents 的 inventory/runtime/run UI 层

这样它不会变成一个普通多模型面板，而是继续保持 orchestration 产品特征。

---

## 15. 结论

这份 Console 方案的核心目标是：

**让 `agent-cloud-harness` 从“任务与控制图可见”，升级到“任务、控制图、Agent Provider、Runtime Health 全部可见”。**

建议第一阶段优先补：
1. Agents 视图
2. Task Detail 的 Agent Execution 区块
3. Runtime Health 简版面板

这样产品形态就会非常接近 Multica 的 managed agents 思路，同时仍保留当前项目自己的 continuity-first 优势。
