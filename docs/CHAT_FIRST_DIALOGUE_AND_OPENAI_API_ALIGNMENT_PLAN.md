# Chat-First Dialogue 与 OpenAI API 对齐方案

这份文档回答两个问题：

1. `/dialogue/` 应该如何继续收口，变得更简洁、更像真正的对话台。
2. Agent Cloud Harness 应该如何在不破坏现有 control-plane 结构的前提下，补一个更通用的 `/v1/chat/completions` 兼容层。

结论先说：

- UI 层应继续往 **session-first / chat-first** 收，而不是继续把大量 task 控制器长期铺在主视图。
- API 层不应把现有 `/api/v1/sessions`、`/api/v1/tasks`、`/api/v1/live_flow` 直接改造成“类 OpenAI”接口；更稳的路径是 **保留原生 control-plane API，再增一层 `/v1/*` façade**。
- `openclaw` 适合作为交互心智模型参考，但不适合作为底层对象模型一比一照搬，因为本项目的核心仍然是 `task + control_node_graph + packet/checkpoint/judgment`。

---

## 1. 参考结论：`openclaw` 给我们的不是“任务表单”，而是“会话式聊天壳层”

基于 `D:/gitAll/openclaw` 当前文档与代码，最值得参考的是下面几件事：

### 1.1 会话心智模型是第一位的

`openclaw` 的 TUI / WebChat / Control UI 都围绕同一个心智模型：

- 用户先进入一个 `session`
- 在 `session` 中连续发消息
- agent run、tool card、system notice 都是聊天流里的事件
- 任务/控制项是附着在会话上的能力，不是主界面的第一视觉中心

参考：

- [D:/gitAll/openclaw/docs/web/tui.md](</D:/gitAll/openclaw/docs/web/tui.md:60>)
- [D:/gitAll/openclaw/docs/web/webchat.md](</D:/gitAll/openclaw/docs/web/webchat.md:7>)
- [D:/gitAll/openclaw/docs/web/control-ui.md](</D:/gitAll/openclaw/docs/web/control-ui.md:84>)

### 1.2 聊天接口是独立 façade，不暴露底层全部控制面对象

`openclaw` 的聊天 UI 不是直接拼 `/sessions + /tasks + /live_flow`，而是通过 gateway chat façade：

- `chat.history`
- `chat.send`
- `chat.abort`
- `chat.inject`

这层 façade 有几个关键特点：

- 对 UI 而言是“连续聊天”
- 对底层 runtime 而言仍然可以有更复杂的 agent / routing / tool / session 机制
- UI 不需要知道全部内部对象关系，最多感知 session、run、stream event

而且 `openclaw` 不只是“有一个 chat 接口”，它对聊天壳层还做了几件很关键的收口：

- `chat.history` 是有边界的 display surface，不把重 metadata、runtime-only envelope、tool-call XML 原样灌回主聊天流
- `chat.send` 是主发送入口，session 级别的 model / thinking / verbose 等控制放在会话层，而不是长期展开在 composer 里
- `chat.inject` 明确区分“写一条 assistant/system note”与“真的跑一轮 agent”

对我们最重要的启发是：

- **UI 和通用接入层应该尽量像 chat**
- **底层 control-plane 可以继续保留 task / packet / checkpoint / judgment 的复杂结构**
- **主聊天流只保留用户真正需要读的 transcript，不要默认暴露完整 runtime 元数据**

### 1.3 主界面是聊天流，复杂控制面下沉

`openclaw` 的聊天界面主区域始终是：

- header
- transcript
- composer
- 状态条 / pickers / overlays

复杂信息例如：

- sessions picker
- model picker
- logs / config / status
- tool cards / side panels

都不是长期占据主内容宽度的默认结构。

这点和当前 harness 已经在做的方向一致，但还不够彻底。

### 1.4 不该直接照搬的部分

`openclaw` 值得借的是交互壳层，不是底层对象模型。

本项目不适合照搬的地方有：

- `openclaw` 的核心对象更偏 `session + run + stream event`
- `agent-cloud-harness` 的核心对象仍然是 `task + control_node_graph + packet/checkpoint + judgment`
- `openclaw` 的 UI 可以把大量行为抽象成“会话内连续聊天”；而本项目仍然要保留 task continuity、handoff、resume packet、runtime context 这些显式能力

所以正确路径不是“把 harness 改成另一个 openclaw”，而是：

- **外层学它的 chat shell**
- **内层继续保留 task-native continuity substrate**

---

## 2. 当前 Harness 的真实状态

当前 `agent-cloud-harness` 并不是“没有 chat 形态”，而是已经处在中间态。

### 2.1 已经做对的部分

当前 `/dialogue/` 已经具备这些优点：

- 左侧是 session rail，而不是纯 task 列表
- 中间默认先看 `Session Messages`
- composer 已经收成 unified composer
- composer 默认已经是 `自动` 模式，而不是强制先选“聊天 / 新任务 / follow-up”
- 同一个 session 里可继续聊天，也可继续发 follow-up / new task
- `task done/failed` 不会阻止继续聊天；真正阻断条件是 `session=closed`
- `related_messages` 已经进入 `live_flow`
- `/dialogue/` 主发送链已经优先走 `POST /v1/chat/completions`
- diagnostics 仍保留在原生 `/api/v1/tasks/*` 读面

相关实现位置：

- [src/main/resources/web/dialogue/index.html](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/index.html:1>)
- [src/main/resources/web/dialogue/app.js](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/app.js:201>)
- [src/main/java/com/agentcloud/engine/ChatFacadeService.java](</D:/gitAll/agent-cloud-harness/src/main/java/com/agentcloud/engine/ChatFacadeService.java:1>)
- [src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java](</D:/gitAll/agent-cloud-harness/src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java:103>)

### 2.2 仍然不够简洁的地方

虽然 UI 已经比早期版本收了很多，但它仍然偏“control-plane 工作台”：

- 首页信息密度依然偏高
- `任务链` 虽然已经下沉到 transcript 下方的折叠区，但默认聊天流里仍然保留较多 control-plane 信号
- 右侧 detail drawer 已经开始做二级折叠，但整体仍然很像 control-plane inspector
- composer 虽然默认是 `自动`，而且发送模式已下沉到“更多发送方式”折叠区，但 task-only 控制仍然比较近
- 高级参数仍然离主发送路径太近，用户很容易被提醒“底层是 task system”
- message card 默认仍然暴露较多 route / tool / lifecycle chips，日常使用时噪音偏大

这对调试很好，但对日常交互不够自然。

### 2.3 API 仍然是原生 control-plane 资源面

当前服务入口明确是资源型 API：

- `/api/v1/sessions`
- `/api/v1/sessions/{id}/messages`
- `/api/v1/tasks`
- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/runtime_context`
- `/api/v1/tasks/{id}/judgment_trace`

注册位置见：

- [src/main/java/com/agentcloud/server/NioHttpServer.java](</D:/gitAll/agent-cloud-harness/src/main/java/com/agentcloud/server/NioHttpServer.java:78>)

这对 control-plane 和观测面是合理的，但对通用接入不够友好：

- 外部 chat client 很难直接对接
- `/dialogue/` 仍需自己拼多种资源
- 无法直接兼容 OpenAI SDK / chat-style tooling

---

## 3. 目标定位

目标不是把项目改成普通聊天机器人服务，而是把它做成：

**chat-first 交互壳层 + task-native continuity control-plane**

可以拆成两层：

### 3.1 外层：通用聊天接入层

面向：

- 简洁 UI
- 通用 client
- OpenAI SDK 兼容调用
- 未来的轻量 mobile / desktop chat shell

推荐暴露：

- `POST /v1/chat/completions`
- `GET /v1/models`

后续可选：

- `POST /v1/responses`
- `POST /v1/chat/completions` 的 stream 模式

### 3.2 内层：保持现有 control-plane 原生面

继续保留：

- `/api/v1/sessions`
- `/api/v1/tasks`
- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/runtime_context`
- `/api/v1/tasks/{id}/packet`

这层继续服务于：

- 调试
- 回放
- continuity 审计
- 路由/Judgment/packet/checkpoint 诊断

一句话：

- `/v1/*` 是 **chat façade**
- `/api/v1/*` 是 **native control-plane**

---

## 4. 设计原则

### 4.1 不把 control-plane API 直接“改名伪装成 OpenAI”

不建议把现在的 `/api/v1/tasks` 直接硬改成 `/v1/chat/completions` 语义，因为两者抽象层级不同：

- `/api/v1/tasks` 是显式任务对象
- `/v1/chat/completions` 是一轮对话推理请求

正确做法是：**在 task/session 之上加 façade service**。

### 4.2 session 继续是一等连续性边界

即使引入 `/v1/chat/completions`，也不应该退化成“完全无状态单轮聊天”。

建议维持：

- session 是 continuity 主边界
- task 是 execution 主边界
- chat façade 只是把两者包起来

### 4.3 `/dialogue/` 默认只暴露聊天必要面

推荐新的默认视觉层级：

1. 左侧：thread rail
2. 中间：transcript + composer
3. 右侧：默认收起，仅在需要时打开 task details drawer

当前实现已经往这条线推进了一步：detail drawer 内部也做了二级 progressive disclosure，保留 `迭代链 / Related Messages / 连续性摘要` 常驻，把 `Mounted Context / 路由与判断 / 实验对比 / 最近产物 / 工具轨迹` 下沉为按需展开。

也就是说：

- 诊断能力不是删除
- 只是从默认主视图下沉到按需展开

### 4.4 assistant 输出分两层

chat façade 返回给普通客户端的内容应尽量简洁：

- 用户可读摘要
- 下一步建议
- 必要的简短状态

更复杂的信号继续留在：

- `live_flow`
- `judgment_trace`
- `runtime_context`
- `packet`

---

## 5. 目标 UI：把 `/dialogue/` 收成真正的 chat workspace

## 5.1 目标主布局

推荐布局：

```text
+----------------+--------------------------------------+----------------------+
| thread rail    | transcript                           | details drawer       |
|                |                                      | (default collapsed)  |
| - session A    | user                                 |                      |
| - session B    | assistant                            | route/judgment       |
| - session C    | user                                 | tool trace           |
|                | assistant                            | packet/runtime       |
|                |                                      |                      |
|                | composer                             |                      |
+----------------+--------------------------------------+----------------------+
```

默认行为：

- 打开 `/dialogue/` 后，用户第一眼看到的是聊天流，不是 task metrics
- 只有在点开某条任务回执或显式切换时，才展开 details drawer

### 5.2 首页只保留最少状态

建议首页只保留：

- 当前 session 标题
- 当前 active task 简短状态
- 当前 worker / mode 的小 badge

不建议默认展示：

- 大块 metrics 卡
- 长串 experiment / mounted context / continuity chips
- 大量控制按钮

这些内容可下沉进：

- details drawer
- task detail modal
- `/console/`

### 5.3 composer 收口建议

当前 composer 已经比过去好很多，但还可以再收：

- 默认只显示一个输入框和一个主按钮
- “聊天 / 新任务 / follow-up” 不做长期显式三态
- 系统根据当前上下文自动推断“这是普通消息还是 follow-up”

推荐交互：

- 默认主按钮：`发送`
- 默认先按 session message 处理
- 当输入绑定了 follow-up、展开高级参数、或显式点“作为任务推进”时，才 materialize 成 task/follow-up task

当前代码其实已经走在这条路上：

- composer 默认模式已是 `自动`
- `composer-plan.js` 已把“何时升格成 task publish”抽成独立规则
- `/dialogue/` 主发送链也已经改走 `/v1/chat/completions`

因此下一步不该再加模式，而是继续减模式：

- 当前已经把 quick bar 继续弱化成了“更多发送方式”折叠区
- `follow-up` 更适合从当前 task 的动作按钮触发，而不是长期占一个主模式位
- `assigned_worker / model_mode / auto_start / priority` 这类控制更适合下沉到 advanced sheet 或 task drawer

这一步当前也已经落地到 UI：

- composer 的显式 mode switch 现在只保留 `自动 / 聊天 / 新任务`
- `follow-up` 不再长期常驻为第四个显式模式位
- 生成 follow-up 主要通过当前 task 的 `生成 follow-up` 动作或已有 parent 绑定自动触发

这样更像 chat，而不是表单驱动工作流。

### 5.4 任务控制动作下沉

当前的：

- continue
- pause
- resume
- escalate
- handoff

不适合在默认主界面长期高频展示。

建议：

- 默认只保留 `继续` 或 `重试/继续推进` 一个主动作
- 其他动作放进 `更多操作`

---

## 6. 目标 API：新增 `/v1/chat/completions` façade

## 6.1 为什么要单独做 façade

因为外部世界要的是：

- OpenAI SDK 可直接调用
- 更简单的 chat-style contract
- 不必先理解 session/task/live_flow 的内部模型

而我们内部真正有价值的东西是：

- control node graph
- runtime context
- packet/checkpoint
- judgment
- tool trace

所以最稳的方案是：

- **不重写内部模型**
- **把内部模型包装成 OpenAI 风格入口**

## 6.2 最小支持范围

第一阶段建议只支持：

- `POST /v1/chat/completions`
- `GET /v1/models`

其中 `POST /v1/chat/completions` 先只支持：

- 非 streaming JSON completion
- 最小 `stream=true` SSE completion
- 文本输入
- 单个 assistant 最终输出

先不抢跑：

- function/tool call streaming 兼容
- audio / multimodal
- 完整 Responses API

## 6.3 façade 语义映射

建议映射关系如下：

| chat façade 概念 | harness 内部对象 |
|------|------|
| `conversation` / `thread` | `session` |
| 一次 `chat.completion` 请求 | 一次 session message + 可选 task materialization |
| assistant reply | `task_progress` / `task_result` / latest summary 的 façade 输出 |
| run metadata | `task_id` + `session_id` + `live_flow` refs |
| tool diagnostics | 留在 `/api/v1/tasks/{id}/tool_trace` |

关键点：

- façade 层不隐藏 `task_id`
- 但不要求 chat client 先理解 task

### 6.4 推荐请求契约

建议兼容的最小请求体：

```json
{
  "model": "agentcloud-default",
  "messages": [
    {"role": "system", "content": "你是一个任务式 assistant。"},
    {"role": "user", "content": "继续整理这份方案，先给我一个三段式摘要。"}
  ],
  "stream": false,
  "metadata": {
    "session_id": "session_xxx",
    "task_id": "task_xxx",
    "task_mode": "auto",
    "assigned_worker": "codex",
    "model_mode": "orchestrated"
  }
}
```

约定建议：

- `metadata.session_id`
  - 有则附着到现有 session
  - 无则自动创建 session
- `metadata.task_id`
  - 有则把本轮输入视为对既有 task 的 follow-up continuity
  - 无则由 façade 决定是普通消息还是 materialize 成新 task
- `metadata.task_mode`
  - `message_only`
  - `task_auto`
  - `task_required`

### 6.5 推荐响应契约

保持 OpenAI 兼容主干，同时加一个 `agentcloud` 扩展块：

```json
{
  "id": "chatcmpl_xxx",
  "object": "chat.completion",
  "created": 1770000000,
  "model": "agentcloud-default",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "这是当前三段式摘要……"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  },
  "agentcloud": {
    "session_id": "session_xxx",
    "task_id": "task_xxx",
    "task_status": "active",
    "control_node": "packet",
    "reply_type": "task_progress",
    "reply_source": "task_progress",
    "live_flow_path": "/api/v1/tasks/task_xxx/live_flow",
    "packet_path": "/api/v1/tasks/task_xxx/packet"
  }
}
```

说明：

- 对普通 OpenAI client，核心字段是兼容的
- 对 harness-aware client，`agentcloud` 扩展块提供 continuity 钩子；其中 `reply_type / reply_source` 可直接区分当前 reply 来自 `chat_reply`、`task_receipt`、`task_progress`、`task_result` 等哪一层回执

### 6.6 `GET /v1/models`

建议返回 façade 级模型，而不是把内部 worker/provider 全量暴露出去。

例如：

- `agentcloud-default`
- `agentcloud-strong`
- `agentcloud-fast`

再由服务内部映射到：

- `model_mode`
- `selected_model_tier`
- `worker routing preference`

这样可以避免把内部路由/worker 结构直接外泄到通用接口。

---

## 7. façade 背后的执行策略

## 7.1 推荐做成 `ChatFacadeService`

不要把 façade 逻辑硬塞进 `TaskHandler` 或 `SessionHandler`。

建议新增一层：

- `ChatFacadeHandler`
- `ChatFacadeService`

职责：

1. 解析 OpenAI 风格请求
2. 解析或创建 `session`
3. 把最后一条 user turn 写入 `session_messages`
4. 根据 `task_mode` 决定：
   - 只记消息
   - 复用当前 active task
   - 新建 task / follow-up task
5. 调用 `TaskService` 推进
6. 从 `task_progress / task_result / latest summary` 组装 chat completion 响应

## 7.2 不建议 façade 直接旁路 `TaskService`

不应让 `/v1/chat/completions` 自己重新实现一套执行链。

应该继续复用：

- `SessionService`
- `TaskService`
- `ControlNodeGraph`
- `TaskRuntimeContextBuilder`

否则会形成两套语义：

- 一套是 `/api/v1/tasks`
- 一套是 `/v1/chat/completions`

这会很快失控。

## 7.3 assistant 响应来源建议

第一阶段不要幻想拿到“完美聊天回复”，建议按下面优先级组装：

1. 最新 `task_result` 的 `summary_preview`
2. 最新 `task_progress` 的 `summary_preview`
3. 最新 artifact / decision / task summary 的压缩摘要
4. fallback：控制动作状态文本

这能保证 façade 输出先稳定，再逐步提升质量。

---

## 8. `/dialogue/` 与 façade 的关系

## 8.1 当前已经是“发送走 façade，诊断走 native API”的混合形态

当前真实状态已经是：

- `/dialogue/` 主 composer 已优先走 `POST /v1/chat/completions`
- 右侧 continuity / live-flow / runtime_context / packet 仍继续走原生 `/api/v1/tasks/*`
- `/v1/models` 已存在，但只暴露 façade 级模型，而不是把内部 worker/provider 全量透出

这其实就是当前最合理的中间态：

- **发送面** 尽量像 chat
- **诊断面** 继续保留 control-plane 透明度
- **continuity substrate** 仍由 task/session/packet/checkpoint 提供

### 8.2 中期可以让 `/dialogue/` 主发送动作改走 façade

这一步已经完成。当前剩下的不是“要不要切 façade”，而是：

- 要不要继续收窄默认 UI，只保留更纯的 transcript + composer
- 要不要把更多 task-only 控制移出主 composer
- 要不要继续扩 façade，例如 `stream` 或更接近 OpenAI 的 read/write contract

---

## 9. 分阶段改造建议

## 9.1 Phase 1：文档与 façade 设计收口

目标：

- 明确 UI 和 API 双层目标
- 不动已有 control-plane 语义

产物：

- 本文档

当前状态：

- **已完成**

## 9.2 Phase 2：继续收 UI

目标：

- `/dialogue/` 默认只保留 transcript + composer
- task details 改成 drawer / modal
- 隐藏非必要 metrics 和控制按钮

优先改动文件：

- [src/main/resources/web/dialogue/index.html](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/index.html:1>)
- [src/main/resources/web/dialogue/app.css](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/app.css:1>)
- [src/main/resources/web/dialogue/app.js](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/app.js:1>)

当前状态：

- **已进一步推进**
- 已有 transcript-first workspace
- task details 默认收起
- `任务链` 已从主切面下沉到 transcript 下方的折叠区
- composer 常驻 quick bar 已弱化成“更多发送方式”折叠区
- 但 detail drawer 信息密度、message card signal 密度仍可继续收

## 9.3 Phase 3：新增 `/v1/chat/completions`

目标：

- 增加 façade 接入层
- 不改变 `/api/v1/*` contract

建议新增：

- `src/main/java/com/agentcloud/server/ChatFacadeHandler.java`
- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/model/openai/*`

并在：

- [src/main/java/com/agentcloud/server/NioHttpServer.java](</D:/gitAll/agent-cloud-harness/src/main/java/com/agentcloud/server/NioHttpServer.java:78>)

注册：

- `/v1/chat/completions`
- `/v1/models`

当前状态更新：

- 已新增 `ChatFacadeService`
- 已新增 `ChatFacadeHandler`
- 已新增 `model/openai/*` 最小 records
- 已注册 `/v1/chat/completions` 与 `/v1/models`
- 已进一步注册最小 `/v1/responses`
- 当前已支持非 streaming 文本 completion，以及最小 `stream=true` SSE 包装
- 当前也已支持最小 Responses JSON 与最小 Responses SSE event 流
- 当前 SSE 不是 token 级增量流，而是“最终完整文本 chunk + stop chunk + [DONE]”
- `/dialogue/` 主发送链已切到 façade
- `task_id` / active-task continuity 现在也遵守 `auto_start`：`false` 时只记录 `task_note` user turn，不自动继续执行链

当前状态：

- **已完成**

## 9.4 Phase 4：让 `/dialogue/` 主发送动作试点走 façade

目标：

- 保持诊断仍走 native API
- 发送动作改走 chat façade

这一步可以验证：

- façade 对 UI 是否足够好用
- `task_id / session_id` continuity 是否还清晰

当前状态：

- **已完成**
- `/dialogue/` 发送已优先走 `/v1/chat/completions`
- 仍保留 `/api/v1/tasks/*` 诊断面，说明 mixed architecture 可行

## 9.5 Phase 5：继续把 `/dialogue/` 收成单聊天壳层

目标：

- 默认只保留一条 transcript surface，而不是显式 `聊天流 / 任务链` 双切面
- 弱化模式入口，让“发送”成为唯一默认主动作
- 把更多 task-only 控制下沉到 detail drawer / advanced sheet / task action menu

优先改动文件：

- [src/main/resources/web/dialogue/index.html](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/index.html:1>)
- [src/main/resources/web/dialogue/app.css](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/app.css:1>)
- [src/main/resources/web/dialogue/app.js](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/app.js:1>)
- [src/main/resources/web/dialogue/composer-plan.js](</D:/gitAll/agent-cloud-harness/src/main/resources/web/dialogue/composer-plan.js:1>)

当前状态：

- **已进一步推进，且主收口项基本落地**
- 默认主区已经是单一 transcript surface；`任务链` 已下沉到 transcript 下方折叠区
- composer 默认主路径已经收成单输入框 + `发送`，`更多发送方式` 和 `高级参数` 继续保留为按需展开
- composer 的显式 mode switch 现在也已经收成三态：`自动 / 聊天 / 新任务`
- task details 默认收起；drawer 内部也做了二级 progressive disclosure
- task details 顶部已收成 `状态 / 控制节点` 焦点线 + 精简 overview 卡
- task details 控制动作已改成“一个状态感知主动作 + 更多操作折叠区”，`暂停 / 升级 / Worker 移交` 已不再长期常驻
- transcript / summary / route / experiment / related messages / continuity summary / chain context 都已补过一轮默认密度收口与对应前端 smoke

剩余更适合放在这一阶段尾声继续做的，不是再加模式或再抬高控制面，而是：

- 少量组合级 render smoke，继续稳住已经收下来的 UI contract
- 必要时再做一轮真实浏览器手工验收，而不是继续拆更多局部 helper
- 手工验收路径与自动化证据清单，见 [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)

## 9.6 Phase 6：稳住 façade contract，再决定是否扩 OpenAI 面

建议顺序：

1. 先把当前 `/v1/chat/completions` 的 session/task continuity contract 稳住
2. 再把最小 `stream=true` 接到真实 chat-first UI 发送链
3. 最后再评估是否值得加 `/v1/responses`

当前状态：

- **continuity contract 已明显收紧，并已扩出最小 streaming 接口面**
- `/v1/chat/completions` 已覆盖 `message_only / task_auto / task_required` 三条主路径
- `/v1/responses` 已补到最小同构 façade：复用同一套 session/task continuity contract，而不是新开第二套执行语义
- `/v1/responses` 现在也已有最小前端 parser contract，并补到了可选 UI 发送面；默认 `/dialogue/` 仍不切换，只有显式 `#facade=responses` 才走这条 surface
- `task_id / active-task continuity` 已遵守 `auto_start`；`auto_start=false` 不再偷偷继续执行链
- `reply_type / reply_source` 已稳定进 `agentcloud` 扩展块，并已有 HTTP 回归锁住多条路径
- `/dialogue/` 对 façade reply 的三处主要 UI 消费现在都已有显式 contract：
  - toast / composer inline 文案
  - composer inline state 的真实 HTML 组合
  - transcript latest receipt/progress/result badge 的真实 HTML 片段
- `/v1/chat/completions?stream=true` 已有最小 SSE 支持，并已有 HTTP 回归
- 当前 stream 仍不是 token 级增量流；它只是把既有最终 reply 包装成一段 assistant content chunk、一个 stop chunk 和 `[DONE]`
- `/v1/responses?stream=true` 当前同样只是最小 event façade：`response.created -> response.output_text.delta -> response.completed -> [DONE]`
- `/dialogue/` 主发送链现在也已优先尝试这条最小 SSE façade，并在 stream 不可用时自动退回普通 JSON completion

因此这一阶段剩余真正未做的，不是“再补一层 reply 文案 helper”，而是：

- 如果要继续扩 façade，就进入“真正的增量 `stream=true` / tool-call delta / `/v1/responses`”的实质设计与实现
- 现在 `/v1/responses` 的“接口存在且 continuity 不分叉”这一步已经完成；后续如果继续扩，焦点应转到更完整的 item surface / tool-call delta / token-level stream
- 如果先不扩接口，就应把重点转回 completion audit / 文档收口 / 轻量验收，而不是继续在既有 reply surface 上做无穷细分

当前更像“验收脚手架”的证据矩阵已经基本齐了，至少可以围绕下面五条真实用户路径做 completion audit：

1. `message_only`
   - 后端 HTTP：`ChatFacadeHandlerHttpTest.postChatCompletionMessageOnlyCreatesSessionMessagesWithoutTask()`
   - 后端 HTTP（task continuity attach）：`postChatCompletionMessageOnlyWithTaskIdWritesTaskNoteWithoutContinuation()`
   - 前端 request contract：`dialogue-composer-request-plan.test.mjs`
   - 前端 reply/UI contract：`dialogue-facade-reply-kind.test.mjs`、`dialogue-facade-reply-ui-consistency.test.mjs`
2. `task_required`
   - 后端 HTTP：`postChatCompletionTaskRequiredCreatesAndRunsTask()`、`postChatCompletionTaskRequiredCanReturnTerminalTaskResultReply()`
   - 前端 request contract：`dialogue-composer-request-plan.test.mjs`
   - 前端 reply/UI contract：`dialogue-facade-reply-kind.test.mjs`、`dialogue-facade-reply-highlight-plan.test.mjs`
3. `follow-up`
   - 后端 HTTP：`postChatCompletionTaskRequiredCanCreateManualStartFollowupTask()`
   - 前端 request contract：`dialogue-composer-request-plan.test.mjs`
   - 前端 composer intent inference：`dialogue-composer-plan.test.mjs`
4. `manual-start continuity`
   - 后端 HTTP：`postChatCompletionTaskIdWithAutoStartFalseOnlyRecordsTaskNote()`、
     `postChatCompletionTaskRequiredWithTaskIdAndAutoStartFalseOnlyRecordsTaskNote()`、
     `postChatCompletionTaskAutoWithActiveTaskAndAutoStartFalseOnlyRecordsTaskNote()`
   - 前端 request contract：`dialogue-composer-request-plan.test.mjs`
5. `stream fallback`
   - 后端 HTTP：`postChatCompletionSupportsMinimalSseStream()`
   - 前端 request contract：`dialogue-composer-request-plan.test.mjs`
   - 前端 response contract：`dialogue-facade-stream-plan.test.mjs`、`dialogue-facade-response-plan.test.mjs`
   - 用户路径矩阵：`dialogue-phase6-path-matrix.test.mjs`

另外，Phase 6 现在还多了一条更接近真实用户路径的 composite HTTP 证据：

- `ChatFacadeHandlerHttpTest.chatFacadeAcceptanceFlowCoversMessageTaskNoteAndManualFollowupInOneSession()`
  - 在同一个 session 内串起：
    `message_only -> task_required manual-start -> message_only + task_id -> follow-up manual-start`
  - 同时锁住一个之前只隐含在实现里的 continuity contract：
    façade 在 task materialization 成功之后，会把 staging 的 `task_brief / task_followup`
    回填成真正的 task-bound message，而不是永久保留为 task-free session note

也就是说，Phase 6 现在缺的已经不是更多 façade helper，而是把这些证据真正收成一次面向用户路径的完成度审计。

补充说明：

- 在把这组路径矩阵补成 smoke 的过程中，还顺手暴露并修正了一处真实 UI contract 漏洞：
  `buildFacadeReplyFeedback(...)` 之前只保留 toast/inline 文案，没有把 `resolvedMode / replyType / replySource` 一起保留下来。
- 这会让 `/dialogue/` 的 transcript latest reply badge 在真实路径里丢失 provenance，表现成 toast 和 composer inline 正常，但 `latest progress / latest receipt / latest result` 可能打不出来。
- 当前这个 provenance 缺口已经补齐，并由 `dialogue-facade-reply-plan.test.mjs` + `dialogue-phase6-path-matrix.test.mjs` 覆盖。

不建议现在立刻做的事：

- 追完整 OpenAI streaming/tool-calls surface
- 为了兼容 SDK 而把内部 task/control-node 抽象抹平
- 在 façade 层重新实现第二套执行链

---

## 10. 兼容性与风险

## 10.1 最大风险：把 façade 做成第二套执行系统

必须避免：

- façade 自己决定 worker / route / runtime / judgment
- 不复用现有 `TaskService`

否则会产生语义分叉。

## 10.2 第二个风险：为了“像聊天”而丢失 continuity 可观测性

不能为了 UI 简洁，就让以下能力消失：

- `task_id`
- `session_id`
- `live_flow`
- `packet`
- `runtime_context`

它们不一定要占主视图，但必须保留入口。

## 10.3 第三个风险：过度追求 OpenAI 完全兼容

第一阶段不应该追求：

- 完整 tool call streaming
- 完整 Responses API
- 完整 multimodal

先做到“可接 SDK、可返回稳定 assistant reply、可拿到 task/session continuity”就够了。

---

## 10.4 当前 completion audit 摘要

基于当前源码、前端 smoke 和 `ChatFacadeHandlerHttpTest`，Phase 5/6 已有的强证据可以概括为：

- Phase 5 chat-first UI
  - 主 composer 已收成 `自动 / 聊天 / 新任务` 三态；`follow-up` 不再长期占一个显式模式位
  - transcript-first、task details progressive disclosure、主动作/更多操作下沉都已落地
  - 对应前端 contract 已覆盖 `dialogue-composer-markup-plan.test.mjs`、`dialogue-composer-plan.test.mjs` 以及现有一组 render/helper smoke
- Phase 6 façade continuity
  - `/v1/chat/completions` 已覆盖 `message_only / task_auto / task_required`
  - `message_only + task_id`、`task_id + auto_start=false`、`task_required manual-start`、`minimal stream=true SSE` 都已有独立 HTTP 回归
  - 新增一条 composite HTTP acceptance flow，覆盖单 session 内的 note -> manual-start task -> task note attach -> manual-start follow-up
  - façade 创建 task / child task 后，staging 的 `task_brief / task_followup` 现在会回填 `task_id`，因此 task continuity 不再依赖前端自己镜像或猜测归属
- `/dialogue/` 对 façade request / response / reply affordance 也已有独立 helper 和 smoke 覆盖
- 还新增了一支本地 acceptance probe：`scripts/Run-ChatFacadeAcceptanceProbe.ps1`，可直接对已启动的本地 harness 分别跑 `chat_completions` 和 `responses` 两条最小 façade 路径
- 同时补了一支更接近真实环境的本地 acceptance runner：`scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`，会自动启动 harness 并串起两条 façade probe
- 现在又补了一支 deterministic live path matrix probe：`scripts/Run-ChatFacadePathMatrixProbe.ps1`
  - 可直接对已启动 harness 分别跑 `chat_completions` 和 `responses`
  - 覆盖 `message_only -> manual-start task -> task note attach -> manual-start continuity -> manual-start follow-up`
  - 验证真实 session/task/message contract 和 `parent_task_id / control_node=intake` 等关键 continuity 语义
- 还新增了一支更接近真实 UI 的浏览器级 probe：`scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
  - 使用本机 Edge headless + CDP 驱动真实 `/dialogue/` 页面
  - 目前已跑通 `chat_completions` surface 下的 `message_only`、`manual-start task`、`task_note_attach`、`manual-start continuity`、`manual-start follow-up`
  - `responses` surface 下也已具备同级真实页面证据：`message_only`、`manual-start task`、`task_note_attach`、`manual-start continuity`、`manual-start follow-up`
  - 现在还补上了两条 surface 下的 scripted browser `stream fallback` 证据：
    - 页面内只发一次 façade POST
    - 响应头保留 `text/event-stream`
    - 但 body 可直接返回普通 completion JSON / response JSON
    - `/dialogue/` 会在同一次响应里完成 fallback 解析，不再补发第二次请求
  - 为了让真实 auto-start path 不必等待长请求完整返回，`/dialogue/` 现在新增了一个 pending auto-task seam：
    - 当新建 `task_required + auto_start=true` 且当前还拿不到最终 `task_id/reply_type` 时
    - 前端会短暂跟踪当前 session 的 task 列表
    - 一旦新 task 出现，就提前把它选中并把 inline state 收敛到“已提交任务，正在推进”
    - 这条 seam 已在 `responses` surface 的 richer scripted browser path 上得到 fresh harness 证据
  - 为避免把正常的异步页面收敛误判成 UI 缺陷，当前 probe 已改成等待
    `task=` hash、active task card、composer inline receipt、detail title 同时到位
  - `/dialogue/` 前端 task 选择也新增了 sticky selection，避免 façade 回包后的中间刷新把新 task 选中态冲掉
  - 在 `18160` / `18162` 的 fresh harness 上重跑后，`responses` surface 最小路径和 richer path 当前都有稳定浏览器级证据
  - 后续 completion audit 还把一条真实运行风险收成了显式运维边界：
    - richer browser probe 曾把默认 local harness 推到 JVM native OOM
    - 这类失败表现为 `ERR_INSUFFICIENT_RESOURCES -> ERR_CONNECTION_REFUSED -> hs_err_pid*.log`
    - 现已通过 acceptance harness 默认 JVM 参数收口：
      - `Start-DialogueChatFacadeManualAcceptance.ps1`
      - `Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
      均默认使用 `-Xms128m -Xmx512m`
    - 在 `18180` fresh harness 上复验后，默认 `chat_completions` richer browser path 当前也已重新稳定通过
  - 为了把 `manual-start continuity` 从“只有 HTTP/path-matrix 语义”推进成真实页面路径，`/dialogue/` 还新增了一个 advanced-only seam：
    `继续当前任务`
    它会在任务模式下把当前输入发送成 `task_required + task_id + auto_start=false`
    但不会把这条 continuity 操作抬成新的顶层 composer mode
  - 同时通过这条 probe 发现并收口了一个真实 provenance 缺口：
    `/v1/responses` 虽然复用同一套 continuity contract，但旧实现写回 session/task message metadata 的 `request_path` 仍误标成 `/v1/chat/completions`
    现已在 `ChatFacadeService` 修正，并由 `ChatFacadeHandlerHttpTest.postResponsesCreatesTaskRequiredResponseEnvelope()` 锁住
  - 这条 probe 还顺手暴露了 `/dialogue/` 静态资源服务只放行 `index.html/app.css/app.js` 的真实生产缺口；该缺口现已在 `WebConsoleHandler` 收口，并由 `WebConsoleHandlerHttpTest` 新增模块资源回归保护
- `/dialogue/` 壳层本身也不再只靠前端 markup smoke；`WebConsoleHandlerHttpTest` 已补上真实 HTTP 级验证，锁住 transcript-first shell 与 `/dialogue/app.js` 的资源路由
- 现在还补了一支真实 `/dialogue/` shell probe：`scripts/Run-DialogueShellAcceptanceProbe.ps1`，并已接入 local harness runner
  - 当前真实运行结果：`Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port 18127` 已同时返回 `dialogue_shell_probe + chat_probe + responses_probe`
- acceptance runner 的默认清理行为也已经过真实复验：不传 `-KeepServerLogs` 时，新运行不会继续留下日志文件；`.tmp` 中残留的固定名日志属于旧版本遗留，不再是当前 runner 的已知缺陷
- 手工验收准备链也进一步收口了：
  - `Start-DialogueChatFacadeManualAcceptance.ps1` 的 `manual_acceptance` 现在不只返回 `recommended_screenshot_dir / candidate_pngs / command_examples / record_seed`
  - 还会显式返回 `record_seed_output_path`
  - starter 现在也会自动把完整返回 JSON 落到 `.tmp\dialogue-manual-<port>.json`
  - 并尝试自动生成一份未勾选的 A-H markdown 骨架到 `.tmp\dialogue-record-seed-<port>.md`
    - 结果通过 `record_seed_generated / record_seed_error` 显式返回
    - 若骨架生成成功，starter 现在还会直接内嵌一份 `record_seed_probe`
      - 用于证明骨架文件确实存在，且首段内容正确
  - 其中：
    - `command_examples.render_record_seed` 可把 starter JSON 渲染成可复制的 A-H markdown 骨架
    - `command_examples.render_record_seed_to_file` 可把骨架半自动落到 `.tmp\dialogue-record-seed-<port>.md`
  - 对应 helper 已落地：
    - `scripts/Render-DialogueAcceptanceRecordSeed.ps1`
    - `scripts/Run-DialogueRecordSeedProbe.ps1`
  - 当前真实验证结果：
    - `18230` starter JSON 已返回 `render_record_seed`
    - `18232` starter JSON 已返回 `render_record_seed_to_file`
    - `18234` starter JSON 已返回 `record_seed_output_path`
    - `18240` starter 已自动落出 `.tmp\dialogue-manual-18240.json`
    - `18242` starter 已自动同时落出：
      - `.tmp\dialogue-manual-18242.json`
      - `.tmp\dialogue-record-seed-18242.md`
      - 且 `record_seed_generated = true`
    - `18244` starter 已直接内嵌：
      - `record_seed_probe.output_path`
      - `record_seed_probe.bytes`
      - `record_seed_probe.preview`
    - `18246` starter 已进一步确认：
      - `record_seed_probe.has_run_metadata = true`
      - `record_seed_probe.has_useful_commands = true`
      - `record_seed_probe.has_base_url = true`
      - `record_seed_probe.has_result_json = true`
      - `record_seed_probe.has_completion_gate = true`
    - `Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18234.json` 已真实确认：
      - `.tmp\dialogue-record-seed-18234.md` 会被生成
      - `## Run Metadata / ## Useful Commands / Base URL / Result JSON / Completion Gate` 都存在
      - A/H 节和 Entry URL 都存在
      - probe 输出本身还会带回首段 `preview`
  - 这层能力的定位仍然只是“人工验收准备”和“半自动记录骨架”，不等于 runbook 第 3 节 A-H 已完成真实手工点验

当前仍然更像“尾声收口项”而不是“主能力缺口”的，主要还剩两类：

- 少量组合级 render smoke 或真实浏览器手工验收，继续验证已收下来的 UI contract 没有被真实 DOM 拼装打坏
- 如果要继续扩 façade，就不该再细分既有 helper，而应直接进入真正的增量 `stream=true` / tool-call delta / `/v1/responses`

## 10.5 当前 completion audit checklist

把这条 chat-first / façade 子线按“交付物 -> 证据 -> 未覆盖 gate”拆开，当前状态是：

1. `/dialogue/` transcript-first shell
   - 证据：
     - `WebConsoleHandlerHttpTest.dialogueRouteServesTranscriptFirstShell()`
     - `Run-DialogueShellAcceptanceProbe.ps1`
   - 未覆盖 gate：
     - 还缺真实人工手点，不应只靠 shell probe 视为完成

2. `/v1/chat/completions` continuity contract
   - 证据：
     - `ChatFacadeHandlerHttpTest` 系列
     - `Run-ChatFacadePathMatrixProbe.ps1`
   - 未覆盖 gate：
     - 当前仍主要是 HTTP / deterministic path matrix 证据，不能替代真实页面手工验收

3. `/v1/responses` 最小 surface
   - 证据：
     - `postResponsesCreatesTaskRequiredResponseEnvelope()`
     - `postResponsesSupportsMinimalSseStream()`
     - `dialogue-responses-path-matrix.test.mjs`
   - 未覆盖 gate：
     - 仍不是完整 Responses API 验收

4. 浏览器级 scripted evidence
   - 证据：
     - `Run-DialogueBrowserAcceptanceProbe.ps1`
     - `chat` / `responses` richer browser paths
     - scripted browser `stream fallback`
   - 未覆盖 gate：
     - 这些 PNG/JSON 仍只是辅助证据，不等于 A-H 真实人工手点已完成

5. 人工验收准备链
   - 证据：
     - `Start-DialogueChatFacadeManualAcceptance.ps1`
     - `Render-DialogueAcceptanceRecordSeed.ps1`
     - `Run-DialogueRecordSeedProbe.ps1`
     - `record_seed_output_path`
     - `record_seed_probe`
   - 当前最新实证：
     - `18246` starter JSON 里已内嵌：
       - `has_run_metadata = true`
       - `has_useful_commands = true`
       - `has_base_url = true`
       - `has_result_json = true`
       - `has_completion_gate = true`
     - 重新渲染后的 `dialogue-record-seed-18246.md` 已把这些 probe 字段写回 markdown scratch pad
   - 未覆盖 gate：
     - 它只证明“可做人工验收”和“可半自动生成记录骨架”，不证明人工验收已完成

6. 验收宿主环境稳定性
   - 证据：
     - runbook 已补充 `CLR failed / 0x800705AF / paging file too small` 的环境级排障说明
   - 当前判断：
     - 这类失败应优先归类为**本机验收环境问题**
     - 不能直接当作 `/dialogue/`、`/v1/chat/completions` 或 `/v1/responses` 的产品回归
   - 未覆盖 gate：
     - 若宿主机反复出现该问题，真实人工验收仍可能被阻塞；这属于环境可用性缺口，不属于当前 façade contract 证据链本身

结论：

- 这条子线当前最强的自动化、HTTP、scripted browser、record-seed 证据都已经到位
- 但最终 gate 仍然只有一个：runbook 第 3 节 A-H 八条真实 `/dialogue/` 人工手点
- 在这些条目逐条回填前，不应把这条子线标记为完成

## 11. 推荐的下一步

基于当前代码状态，最合理的顺序是：

1. 继续收 `/dialogue/` 的默认主视图，把它压成更纯的 transcript-first workspace。
2. 先不要急着扩 façade 范围，而是稳住当前 `/v1/chat/completions` 的 continuity contract。
3. 等 UI 更像单聊天壳层后，再评估 `stream=true` 或 `/v1/responses`。

不建议的顺序是：

1. 先大改底层 task/session API
2. 试图直接把 control-plane 资源改成 OpenAI 语义
3. 过早把 façade 做成第二套 runtime

这条路改动大，而且很容易把现在已经稳定的 continuity / live_flow / packet 诊断面破坏掉。

---

## 12. 一句话架构结论

推荐目标形态：

```text
+----------------------------+        +----------------------------------+
| /v1/chat/completions       | -----> | ChatFacadeService                |
| /v1/models                 |        | - resolve/create session         |
+----------------------------+        | - write user message             |
                                      | - materialize/reuse task         |
                                      | - call TaskService               |
                                      | - assemble assistant reply       |
                                      +----------------+-----------------+
                                                       |
                                                       v
                                      +----------------------------------+
                                      | Native Control Plane             |
                                      | /api/v1/sessions                 |
                                      | /api/v1/tasks                    |
                                      | /api/v1/tasks/*/live_flow        |
                                      | /api/v1/tasks/*/runtime_context  |
                                      | /api/v1/tasks/*/packet           |
                                      +----------------------------------+
```

一句话总结：

- **外面像 chat**
- **里面仍然是 task-native continuity harness**
