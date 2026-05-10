# Web Console

内置页面现在分成两个入口：

- `GET /dialogue/`：chat-first 的任务对话页
- `GET /console/`：原有的 continuity 诊断台
- 根路径 `/` 会 302 跳转到 `/dialogue/`

## 页面分工

- `Dialogue`
  - 更接近对话应用的布局
  - 左侧保留 session rail
  - 中间默认是 transcript-first 的聊天流与 composer
  - task details 默认收起，按需展开，保留 chain、route、judgment、artifact、tool trace
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
- 左侧 session rail 现在支持收起；窄屏下会退化成抽屉式 thread picker，更接近 chat app
- 中间主视图现在默认就是单一 transcript surface：先看 `session message` 流；`任务链` 被下沉到 transcript 下方的折叠区，需要时再展开
- 顶部状态区已经从大块 metrics 卡收成轻量 status pills，只保留会话数、任务数、链路数和当前焦点
- task details 头部现在也只把 `状态 / 控制节点` 保留在焦点线，overview 卡缩成 `任务 ID / Worker / 实验模式 / Tool chain` 四项，避免和顶部焦点重复
- 在 `聊天流` 视图里，会混合显示 `user / assistant / system` 三种角色，并支持按 `role` 与 `scope(task-only / session-only)` 过滤
- `聊天流` 前面会额外给出 `assistant / system` 分组摘要卡片，快速显示最近回执、top message types 和最新 trigger/completion/action 信号
- 这组 `assistant / system` 摘要卡片现在默认只保留一条最强的生命周期信号和更短的最新摘要，避免它本身又变成一块新的诊断面板
- 现在顶部 summary 会进一步只保留“最新的一张主卡”；另一角色若存在，则下沉成更短的 brief，减少 transcript 第一屏被两张摘要卡同时占掉
- 展开 `任务链` 折叠区后，每个 task 会被拼成一组 `Brief / Harness` 双消息气泡，同一 `parent_task_id` 链会聚合成一条 iteration chain
- 页面底部现在是一个 unified composer：默认是 `自动` 模式，必要时再显式切到 `聊天 / 新任务`
- composer 现在默认只暴露主输入框和 `发送`；发送模式与“附着到当前任务”被下沉到 `更多发送方式` 折叠区，而不是长期常驻在主路径上
- composer 现在支持 `Ctrl+Enter / Cmd+Enter` 快捷发送，更接近常见 chat composer 手势
- `自动` 模式的升格规则已经抽成独立 helper，并有 Node 级 smoke 覆盖：`src/test/js/dialogue-composer-plan.test.mjs`
- transcript 里的 message card 现在默认优先显示 lifecycle signal，例如 `trigger / event / completion / action`；`route / tools / mode / learning hint` 这类 control-plane signal 会在 compact/related message 或右侧 inspector 里更完整地出现，避免主聊天流过吵
- 如果当前 `session=closed`，composer 会显式禁用发送，并给出“新建会话并继续”的恢复入口
- `/dialogue/` 主发送链现在已经优先走 `POST /v1/chat/completions`，并会优先尝试最小 `stream=true` SSE；若响应不是 SSE，或当前返回体本身更接近普通 JSON completion，会在同一次响应里直接降级解析，而不是再次重发同一条 user turn。右侧任务诊断仍继续消费原生 `/api/v1/tasks/*`
- 默认 UI 仍然走 `chat completions`；如果要验证最小 `/v1/responses` 发送面，可用 `#facade=responses` 切到可选 façade surface，而不影响默认 chat-first 路径；这个 surface 也会跟随当前 session/task 一起写回 URL hash
- 这条响应分流现在也有独立 contract helper 和 smoke：前端会按 `Content-Type` 先判定 `event-stream / json`，`event-stream` 下若 body 实际更像普通 completion JSON，也会在同一次响应内直接回退到 JSON 解析，而不是重新提交请求
- `/dialogue/` 现在也会消费 façade 返回的 `agentcloud.reply_type / reply_source`：至少会区分“已记录 / 已推进 / 已完成”三类发送回执，不再把所有 task reply 都提示成同一种“任务已发布”
- 对 task receipt/progress/result 这类 façade 回执，transcript 里当前作用域下最新一条 assistant/system 回执卡也会带 `latest receipt / latest progress / latest result` 标记，不必只看 toast 或 composer inline state
- 这三处 façade reply 消费（toast、composer inline、transcript latest badge）现在已经共用同一套 reply-kind helper，减少 `reply_type / reply_source` 文案和 tone 的漂移风险
- composer 的内联上下文现在也会短暂保留这条最近 façade 回执，形成稳定的“上一轮系统反馈”，不再只靠瞬时 toast 传达任务已记录、已推进或已完成
- 这条内联 façade 回执现在也会按当前 `session/task` 作用域约束，切到别的会话或别的任务时不会继续显示上一轮不相关的反馈
- 在 `自动` 模式下，composer 默认先走 façade 的 `task_mode=message_only`；只有当用户绑定了 follow-up、展开高级参数、修改 task-only 控制项，才会自动升格成 task publish
- 在 `聊天` 模式下，composer 会强制走 façade 的 `task_mode=message_only`，并可选择是否把这条消息附着到当前选中 task
- 在 `新任务` 模式下，composer 会走 façade 的 `task_mode=task_required`，由 façade 复用原生 `TaskService` 创建并启动任务
- `follow-up` 不再长期占一个显式模式位；现在更偏向通过“生成 follow-up”按钮或已有 parent 绑定自动触发，并仍然透传 `parent_task_id` 来生成新的 follow-up task，而不是继续父任务本身
- 这组发送请求体现在也已经抽成独立 contract helper，并有前端 smoke 覆盖 `message_only / task_required / follow-up parent / task note attach / manual-start` 五类主路径，避免 composer request 语义和 façade continuity contract 漂移
- 如果要做一轮完整的 `/dialogue/` + façade 人工验收，直接参考 [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)
- unified composer 同时暴露 `auto_start` 开关，适合只创建任务、稍后手动 `continue`
- 下一步的主要方向不是再加一层模式，而是继续减默认信息密度：把更多 task-only 控制继续下沉，让 `/dialogue/` 更接近单聊天壳层
- façade 在创建任务前会先写入 `task_brief / task_followup / task_note / user_note` 等 user turn，所以前端不再额外手工 mirror 一次消息
- 任务创建、`pause / resume / continue / escalate / handoff / state update` 之后，后端也会 best-effort 回写 `task_receipt / task_action / task_state` 消息，方便直接在对话流里回看 harness 回执
- 对于 `auto_start / resume / continue / handoff` 这类会重新进入执行链的动作，后端还会尽量根据 `summary / judgment / artifact / active_context` 生成 `task_progress`；若任务已进入 `done / failed`，则会写成 `task_result`
- `task_progress / task_result` 现在会额外带上 `model_mode / route_source / preferred_worker_hint / learning_hint_applied / tool_chain_* / acceptance_result` 等结构化 metadata，方便在消息层直接看当前 route 与实验上下文
- `task_progress / task_result` 的 message body 现在也会优先收成 `summary_preview`，并把 `next_step` 作为第二行提示；`task_action / task_state / task_receipt` 则会优先消费 `action_label` 与当前 `task_status / control_node`，更像 thread 回执，而不是原始长文本广播
- 任务气泡和链头会直接显示 `auto-start / manual-start` 标记，信号来自任务元数据里的 `start_mode`
- message card 会额外显示 `trigger / completion / action` 信号标签，便于快速判断这条 assistant 回执是由哪次推进动作触发的
- `/dialogue/` 的 message card 现在也会直接把 route / tool chain / mode / learning hint 转成 badge，不必切到右侧 detail 才能看当前执行上下文
- `GET /api/v1/sessions/{id}/messages` 现在已经能直接返回上述结构化 message metadata，前端不需要再从 `live_flow` 或右侧 inspector 反推 route / next-step / control action
- 右侧任务面板现在默认收起，通过 header 上的 `查看任务面板` 再展开；窄屏下继续以单列方式展示
- 展开的任务面板内部也进一步做成 progressive disclosure：`迭代链 / Related Messages / 连续性摘要` 常驻，`Mounted Context / 路由与判断 / 实验对比 / 最近产物 / 工具轨迹` 默认折叠，避免 inspector 一打开就把整页诊断信息全部铺开
- `路由与判断` 里的 judgment 卡片现在默认也只保留 summary、execution boundary 和少量关键 diagnostics，不再把 alignment/candidate/evidence/unfinished 等所有细项一次性拼成大段文本
- `/dialogue/` 右侧现在也会直接显示当前 task 的 experiment 对比卡片，但默认只常驻当前 mode headline；其余 mode 对比、prompt rollout 和 case 对照已下沉到折叠区
- `/dialogue/` 的 route 卡片也会补 `route_source / preferred_worker_hint / learning_hint_applied / fallback_reason` 等字段，不再只显示一个 selected worker
- route 卡片当前默认常驻只保留 `selected worker / route source / route reason`；candidate workers、route chips 和 cognition timeline 已下沉到折叠区，避免 inspector 默认展开时信息过满
- `Related Messages` 和 `连续性摘要` 仍然常驻，但默认只显示小预览；额外消息和 overflow continuity chips 会进内嵌折叠区，避免 details drawer 中段高度过高
- inspector 的“迭代链上下文”卡片现在默认只常驻当前轮和上一轮/下一轮导航；完整链表会下沉到折叠区，避免右侧默认出现整条历史列表
- task details 的控制动作现在也按 chat-first 方向收口：默认常驻只保留一个状态感知的主动作（例如 `继续推进` 或 `恢复`），`暂停 / 升级 / Worker 移交` 等次级控制下沉到 `更多操作`
- inspector 现在也会显示当前 task 的 `Related Messages`
- `Related Messages` 不再只看 `task_id=当前任务` 的回执，也会有限度并入同 session 的普通连续聊天消息；这些消息会带 `session continuity` badge，而 task 自己的回执/brief 会带 `task-bound` badge
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
