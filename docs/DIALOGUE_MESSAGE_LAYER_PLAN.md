# Dialogue Message Layer Plan

这份文档只回答一件事：`/dialogue/` 下一步怎么从“task 重组出来的对话感”推进到“有真实消息层的任务式对话台”。

## 1. 背景

当前仓库已经有两套内置页面：

- `/dialogue/`
  - chat-first 的任务工作台
  - 左侧 `session`
  - 中间 `task chain`
  - 右侧 `live_flow / judgment / artifact / tool trace`
- `/console/`
  - continuity / control-plane 诊断台

`/dialogue/` 现在已经支持：

- 创建会话
- 发布任务
- follow-up 链接到 `parent_task_id`
- `auto_start / manual-start`
- URL hash 深链
- 任务链上下文与诊断面

但它仍然有一个明显缺口：

- “对话感”主要来自 `task + live_flow` 的 UI 重组
- 用户的连续输入还没有独立消息层
- 任务发布前的讨论、备注、澄清、草稿都无法单独沉淀
- 同一主题下的多轮输入仍然容易退化成一串 task

这也是下一步最值得补的地方。

## 2. 目标

把 `/dialogue/` 从“task-first 的伪对话页”推进到“message-assisted 的任务对话台”，但**不把 control plane 改成普通 chat app**。

一句话目标：

- `message` 负责交互层
- `task` 负责执行层
- 两者通过 `session_id / task_id` 显式关联

## 3. 设计原则

### 3.1 保持 task 为一等公民

本项目的核心仍然是：

- `session`
- `task`
- `control_node_graph`
- `live_flow`
- `packet / checkpoint / judgment / artifact`

消息层不能反过来把任务边缘化。消息只是让用户输入、草稿、备注、跟进语义更自然。

### 3.2 先做持久化消息，不做智能聊天后端

第一阶段不做这些：

- 不接 WebSocket
- 不做 token streaming
- 不把 LLM 直接包装成通用聊天机器人
- 不新开独立前端工程

第一阶段只做：

- 持久化 session message
- 在 `/dialogue/` 上可读、可写、可关联 task
- 能把消息转成 task 草稿

### 3.3 message 与 task 必须可追溯

消息不是匿名文本。

每条消息至少要能回答：

- 属于哪个 `session`
- 是否关联某个 `task`
- 是什么角色发的
- 是普通 note，还是 task brief / follow-up note / system note

否则后面很难做 chain 回放和 continuity 审计。

## 4. 当前工作树状态

当前工作树已经开始补消息层骨架，方向是对的，但还没有完成端到端闭环：

- 已开始的后端骨架：
  - `SessionMessage`
  - `SessionMessageCreateRequest`
  - `session_messages` 表
  - `SessionMessageDao`
  - `SessionService.addMessage(...) / listMessages(...)`
  - `GET/POST /api/v1/sessions/{id}/messages`
- 还没完成的部分：
  - `/dialogue/` 前端还没接到该 API
  - 没有 session message 的 UI
  - “发布 task 后镜像成一条 user message” 还没接完
  - 还没有 smoke 级端到端验证

所以接下来最重要的是把这个半截骨架收口，而不是再扩新概念。

## 5. 最小可用目标（MVP）

MVP 完成后，`/dialogue/` 至少要满足：

1. 用户可以在当前 session 下记录一条普通消息。
2. 普通消息会持久化到 SQLite。
3. 页面中能看到最近消息流。
4. 某条消息可以“用作任务草稿”，自动填入 task composer。
5. 任务创建后会补一条关联 `task_id` 的 user message。
6. 任务详情侧栏能看到与当前 task 关联的消息。

只要这 6 件事成立，这个页面就已经从“任务拼装页”升级成“任务式对话页”了。

## 6. 分阶段落地

## 6.1 Phase A：收口后端消息层

目标：先把 API 和持久化层做成稳定基线。

### A1. 模型与表结构

建议模型：

- `SessionMessage`
  - `id`
  - `sessionId`
  - `taskId`
  - `role`
  - `messageType`
  - `content`
  - `createdAt`
  - `metadata`

- `SessionMessageCreateRequest`
  - `role`
  - `messageType`
  - `content`
  - `taskId`
  - `metadata`

建议表：

- `session_messages`
  - `id`
  - `session_id`
  - `task_id`
  - `role`
  - `message_type`
  - `content`
  - `created_at`
  - `metadata_json`

索引：

- `idx_session_messages_session_created`
- `idx_session_messages_task_created`

### A2. Service 层

`SessionService` 建议稳定提供：

- `addMessage(sessionId, request)`
- `listMessages(sessionId, limit)`
- `listMessages(sessionId, limit, taskId)`

校验规则：

- `session` 必须存在
- `content` 不能为空
- 如果给了 `taskId`，该 task 必须属于同一个 session
- `limit` 做边界限制，例如 `1..100`

### A3. HTTP API

新增：

- `GET /api/v1/sessions/{id}/messages?limit=50`
- `GET /api/v1/sessions/{id}/messages?task_id=...&limit=20`
- `POST /api/v1/sessions/{id}/messages`

第一版请求体：

```json
{
  "role": "user",
  "message_type": "note",
  "content": "先把选题拆成提纲和证据清单。",
  "task_id": "task_xxx",
  "metadata": {
    "source_surface": "web_dialogue"
  }
}
```

### A4. A 阶段验收

- `mvn test` 通过
- 能插入/读取消息
- 非法 `task_id` 跨 session 会被拒绝
- `GET /api/v1/sessions/{id}/messages` 能按时间升序返回最近 N 条

## 6.2 Phase B：接入 `/dialogue/` 页面

目标：让页面真正可见、可写、可关联。

### B1. UI 结构

建议在中间区保留“任务链”为主，但新增一块轻量消息流：

- `Session Messages`
  - 展示当前 session 最近消息
  - 风格接近 chat bubble，但比 task bubble 轻
- `Task Dialogue`
  - 继续保留现有 task chain

换句话说，不是把 task thread 删除，而是让 message 成为 task 之上的补充交互层。

### B2. 前端状态

`src/main/resources/web/dialogue/app.js` 建议新增：

- `state.messages`
- `loadMessages()`
- `renderMessages()`
- `onCreateMessage()`
- `onMessageActionClick()`

建议保留现有：

- `state.tasks`
- `state.liveFlow`
- `buildTaskChains()`

不要把 task 和 message 直接揉成一个数据结构，第一版分开渲染更稳。

### B3. 页面交互

建议新增一个轻量 message composer：

- 输入框：记录备注/澄清/草稿
- 复选框：是否关联当前选中 task
- 提交按钮：写入 `session_messages`

消息列表上每条消息至少提供两个动作：

- `用作任务草稿`
- `查看关联任务`（如果存在 `task_id`）

### B4. 发布任务时镜像 user message

这个点非常重要。

在 `/dialogue/` 页面里，用户点击“发布任务”后，除了 `POST /api/v1/tasks`，还建议做一次 best-effort 的消息镜像：

- `role = user`
- `message_type = task_brief` 或 `task_followup`
- `task_id = 新建任务 id`
- `content = 本次 intent`

这样页面上会同时保留：

- 原始输入消息
- 结构化任务对象

这才叫“真正的任务式对话”。

### B5. B 阶段验收

- 能在 `/dialogue/` 里直接发一条消息
- 能从消息一键填充 task composer
- 发布 task 后，消息流里能看到与 `task_id` 关联的 brief
- 选中 task 后，页面能跳到对应链，且能查看关联消息

## 6.3 Phase C：任务详情与 observability

目标：让消息层和 continuity 观测面接起来。

建议在右侧详情面新增：

- `Related Messages`
  - 显示与当前 `task_id` 关联的消息
  - 先只显示最近 10 条

这样当前 task 的上下文会同时包含：

- `chain context`
- `continuity summary`
- `route / judgment`
- `artifact / tool trace`
- `related messages`

这一步的收益很直接：当一条任务为什么会这么写、为什么会 follow-up 到这个方向时，能直接回看对应 user input。

## 6.4 Phase D：后续增强，不要抢跑

这几个点有价值，但不要放进第一批：

- 自动把 judgment / artifact 反写成 assistant/system message
- 在消息流里显示 task state changes
- 消息过滤器：只看 note / 只看 task_brief / 只看关联当前 task
- 链级摘要消息
- SSE / streaming
- 多用户协作与认证

这些都应建立在前面 A/B/C 稳定之后。

## 7. 文件级改造清单

## 7.1 后端

- `src/main/resources/schema.sql`
  - 增加 `session_messages` 表和索引
- `src/main/java/com/agentcloud/model/SessionMessage.java`
- `src/main/java/com/agentcloud/model/SessionMessageCreateRequest.java`
- `src/main/java/com/agentcloud/store/SessionMessageDao.java`
- `src/main/java/com/agentcloud/store/Mappers.java`
- `src/main/java/com/agentcloud/store/DatabaseManager.java`
- `src/main/java/com/agentcloud/store/SessionDao.java`
  - 建议增加 `touch(...)`
- `src/main/java/com/agentcloud/engine/SessionService.java`
- `src/main/java/com/agentcloud/server/SessionHandler.java`
- `src/main/java/com/agentcloud/cli/Main.java`

## 7.2 前端

- `src/main/resources/web/dialogue/index.html`
  - 增加 message stream / composer 容器
- `src/main/resources/web/dialogue/app.css`
  - 新增 message bubble / message panel 样式
- `src/main/resources/web/dialogue/app.js`
  - 新增 message state、API 调用、渲染与动作

## 7.3 文档

- `docs/WEB_CONSOLE.md`
  - 同步 `/dialogue/` 的 message layer 形态
- `docs/API_CONTRACTS.md`
  - 新增 session message API
- `docs/TROUBLESHOOT.md`
  - 补一节“message 已写入但页面不显示”的排查路径

## 8. 推荐提交切分

建议按下面的顺序切 commit：

1. `add-session-message-storage-and-api`
2. `add-session-message-service-tests`
3. `wire-dialogue-page-to-session-messages`
4. `mirror-task-brief-into-session-messages`
5. `show-related-messages-in-task-details`
6. `document-dialogue-message-layer`

这样每一步都能独立回归，问题也容易定位。

## 9. 验收脚本建议

建议准备一条 smoke 路径：

1. 新建 session
2. 发送一条 `note` 消息
3. 在页面中把这条消息转成 task 草稿
4. 发布 task，`auto_start=false`
5. 确认：
   - `/api/v1/sessions/{id}/messages` 有 note
   - 同时多出一条 `task_brief`
   - `task_brief.task_id = 新任务 id`
   - `/api/v1/sessions/{id}/tasks` 能看到该任务
   - `/dialogue/` 中消息流和任务链都能看到对应内容

如果这条 smoke 走通，说明消息层已经真正落地。

## 10. 当前最该做什么

当前最推荐的下一步，不是继续做样式，而是按这个顺序推进：

1. 先把后端消息骨架编译、测试、接口验证收稳。
2. 再把 `/dialogue/` 前端接到 `/sessions/{id}/messages`。
3. 然后补“发布任务后镜像成一条 user message”。
4. 最后才做 task details 里的 `Related Messages`。

顺序不要反。

如果先做 UI 样式，不先收口 API，页面很快就会变成“看起来像 chat，实际上没有消息语义”的半成品。
