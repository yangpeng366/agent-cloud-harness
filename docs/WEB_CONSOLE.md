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
- 查看会话下任务对话线程
- 发布任务（`POST /api/v1/tasks`）
- 发布任务时可选择 `auto_start=true/false`
- 基于当前选中任务生成 follow-up 草稿
- 把同一主题的 follow-up 任务组织成显式迭代链
- 选择任务并查看 `live_flow`
- 触发 `continue / pause / resume / escalate / handoff`
- 查看 route preview、judgment、artifact、tool trace、原始 `live_flow` JSON

## 当前交互形态

- 左侧是 session 轨道
- 中间是任务对话线程：每个 task 会被拼成一组 `Brief / Harness` 双消息气泡，同一 `parent_task_id` 链会聚合成一条 iteration chain
- composer 支持引用当前选中任务，按 `summary / next_step / judgment` 预填 follow-up 任务草稿；发布时会把该任务作为 `parent_task_id` 传给后端
- composer 同时暴露 `auto_start` 开关，适合只创建任务、稍后手动 `continue`
- 任务气泡和链头会直接显示 `auto-start / manual-start` 标记，信号来自任务元数据里的 `start_mode`
- 右侧是诊断面：继续保留 `live_flow`、路由、判断、artifact、tool trace
- inspector 新增“迭代链上下文”卡片，可直接查看当前任务在整条链里的轮次，并跳转上一轮 / 下一轮 / 任意一轮
- 当前选中的 `session/task` 会同步到 URL hash，刷新页面或分享链接时能直接落到同一轮任务

这里没有引入独立的 message API。页面里的“对话感”来自现有 `task + live_flow` 数据重组，而不是伪造一层新的 chat 后端。

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
