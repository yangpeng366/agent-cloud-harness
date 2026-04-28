# Web Console

内置页面现在分成两个入口：

- `GET /dialogue/`：chat-first 的任务对话页
- `GET /console/`：原有的 continuity 诊断台
- 根路径 `/` 会 302 跳转到 `/dialogue/`

## 页面分工

- `Dialogue`
  - 更接近对话应用的布局
  - 左侧保留 session rail
  - 中间是任务对话流与 composer
  - 右侧是轻量 task details，保留 chain、route、judgment、artifact、tool trace
- `Console`
  - 保留原来的 control-plane 观察视角
  - 更适合集中看 `live_flow`、raw JSON 和细节排障

当前稳定能力仍然主要建立在 `task/session/live_flow` API 上。关于下一步的真实消息层方案，见：

- `docs/DIALOGUE_MESSAGE_LAYER_PLAN.md`

## 当前功能

- 创建会话
- 在当前会话下记录 `session_messages`
- 查看会话下任务对话线程
- 查看同一 session 下的消息流，并把消息转成任务草稿
- 发布任务（`POST /api/v1/tasks`）
- 发布任务后自动镜像为一条 `user` message
- 任务创建、状态更新和控制动作后，自动追加 `assistant/system` 回执消息
- 任务自动启动、继续推进、恢复或移交后，若 runtime 已产出可读摘要，还会补 `assistant` 侧的 `task_progress / task_result`
- 发布任务时可选择 `auto_start=true/false`
- 基于当前选中任务生成 follow-up 草稿
- 把同一主题的 follow-up 任务组织成显式迭代链
- 选择任务并查看 `live_flow`
- 触发 `continue / pause / resume / escalate / handoff`
- 查看 route preview、judgment、artifact、tool trace、原始 `live_flow` JSON

## 当前交互形态

- 左侧是 session 轨道
- 中间上半区是 `session message` 流，可记录上下文、补充约束，或把某条消息直接转成新的任务草稿；其中会混合显示 `user / assistant / system` 三种角色，并支持按 `role` 与 `scope(task-only / session-only)` 过滤
- 上半区消息流前面会额外给出 `assistant / system` 分组摘要卡片，快速显示最近回执、top message types 和最新 trigger/completion/action 信号
- 中间是任务对话线程：每个 task 会被拼成一组 `Brief / Harness` 双消息气泡，同一 `parent_task_id` 链会聚合成一条 iteration chain
- message composer 支持把消息附着到当前选中 task；右侧也会展示该 task 的 `Related Messages`
- composer 支持引用当前选中任务，按 `summary / next_step / judgment` 预填 follow-up 任务草稿；发布时会把该任务作为 `parent_task_id` 传给后端
- composer 同时暴露 `auto_start` 开关，适合只创建任务、稍后手动 `continue`
- 任务表单提交成功后，会自动向 `/api/v1/sessions/{id}/messages` 镜像一条 `task_brief` 或 `task_followup` 消息
- 任务创建、`pause / resume / continue / escalate / handoff / state update` 之后，后端也会 best-effort 回写 `task_receipt / task_action / task_state` 消息，方便直接在对话流里回看 harness 回执
- 对于 `auto_start / resume / continue / handoff` 这类会重新进入执行链的动作，后端还会尽量根据 `summary / judgment / artifact / active_context` 生成 `task_progress`；若任务已进入 `done / failed`，则会写成 `task_result`
- `task_progress / task_result` 现在会额外带上 `model_mode / route_source / preferred_worker_hint / learning_hint_applied / tool_chain_* / acceptance_result` 等结构化 metadata，方便在消息层直接看当前 route 与实验上下文
- 任务气泡和链头会直接显示 `auto-start / manual-start` 标记，信号来自任务元数据里的 `start_mode`
- message card 会额外显示 `trigger / completion / action` 信号标签，便于快速判断这条 assistant 回执是由哪次推进动作触发的
- `/dialogue/` 的 message card 现在也会直接把 route / tool chain / mode / learning hint 转成 badge，不必切到右侧 detail 才能看当前执行上下文
- 右侧是诊断面：继续保留 `live_flow`、路由、判断、artifact、tool trace
- `/dialogue/` 右侧现在也会直接显示当前 task 的 experiment 对比卡片，能在默认入口下看到 `strong_only / small_only / orchestrated` 三种 mode 的摘要与同 case 对照
- `/dialogue/` 的 route 卡片也会补 `route_source / preferred_worker_hint / learning_hint_applied / fallback_reason` 等字段，不再只显示一个 selected worker
- inspector 新增“迭代链上下文”卡片，可直接查看当前任务在整条链里的轮次，并跳转上一轮 / 下一轮 / 任意一轮
- inspector 现在也会显示与当前 task 绑定的 `Related Messages`
- 当前选中的 `session/task` 会同步到 URL hash，刷新页面或分享链接时能直接落到同一轮任务
- `/dialogue/` 选中任务后会优先消费 `GET /api/v1/tasks/{id}/live_flow` 里的 `related_messages`，只有旧实例或缺字段时才回退到 `/api/v1/sessions/{id}/messages?task_id=...`
- 如果消息数很多，可以先用上半区过滤器只看 `assistant` 回执，或只看绑定了 `task_id` 的 task 级消息
- 如果消息流很长，优先看摘要卡片，再决定是否切到 `assistant` 或 `system` 过滤器看明细

现在的 `/dialogue/` 不是纯前端重组：

- 任务线程仍然主要来自 `task + live_flow`
- 但 session 级消息流已经接到了真实后端接口：`/api/v1/sessions/{id}/messages`
- task 级 `Related Messages` 也已经开始并入 `live_flow`
- 因此页面里已经同时存在“消息层”和“任务层”，适合做更连续的多轮工作流

## 设计定位

这不是独立前端工程，而是由 `HttpServer` 直接分发的内置控制台，适合：

- 本地调试 harness
- 观察任务多轮推进
- 让“发布任务 + 运行态排障”在同一页面完成

## 相关文件

- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/dialogue/index.html`
- `src/main/resources/web/dialogue/app.css`
- `src/main/resources/web/dialogue/app.js`
- `src/main/resources/web/console/index.html`
- `src/main/resources/web/console/app.css`
- `src/main/resources/web/console/app.js`
