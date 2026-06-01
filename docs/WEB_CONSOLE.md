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
- 当前 `runtime health` 与 `route box` 也会直接显示 provider 恢复降级窗口，不必只靠 raw JSON 判断“恢复时系统会避开谁”
- Provider Detail 现在会额外拉取对应 worker 的 `readiness?mode=dispatch`，直接显示 dispatch preflight 是否 ready、结果是否来自缓存、是否为 active probe，以及本次探测的 CLI 参数/命令形态；排查“provider 看似 ready 但 worker 命令参数不兼容”时不必只看 raw JSON。

当前稳定能力仍然主要建立在 `task/session/live_flow` API 上。关于下一步的真实消息层方案，见：

- `docs/DIALOGUE_MESSAGE_LAYER_PLAN.md`
- 如果要继续把 `/dialogue/` 壳层往更像 `codex` 的 transcript-first shell 收，另见：
  - `docs/DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
- 如果当前问题已经切到“task 失败后是否自动切换 worker / 何时进入人工确认”，另见：
  - `docs/WORKER_FAILURE_RECOVERY_POLICY.md`

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
- 查看 provider detail 中的 worker dispatch probe，包括 `dispatch_preflight_metadata.dispatch_preflight_probe_args` 与 `dispatch_preflight_command_shape`

## 当前交互形态

- 左侧是 session 轨道
- 左侧 session rail 现在支持收起；窄屏下会退化成抽屉式 thread picker，更接近 chat app
- 中间主视图现在默认就是单一 transcript surface：先看 `session message` 流；`任务链` 被下沉到 transcript 下方的折叠区，需要时再展开
- 顶部状态区已经从大块 metrics 卡收成轻量 status pills，只保留会话数、任务数、链路数和当前焦点
- task details 头部现在也只把 `状态 / 控制节点` 保留在焦点线，overview 卡缩成 `任务 ID / 执行方 / 实验模式 / 工具链` 四项；无 experiment metadata 时实验模式显示 `临时任务`，不要露出 `ad hoc`
- 在 `聊天流` 视图里，会混合显示 `user / assistant / system` 三种角色，并支持按 `role` 与 `scope(task-only / session-only)` 过滤
- `聊天流` 前面会额外给出 `assistant / system` 分组摘要卡片，快速显示最近回执、top message types 和最新 trigger/completion/action 信号
- 这组 `assistant / system` 摘要卡片现在默认只保留一条最强的生命周期信号和更短的最新摘要，避免它本身又变成一块新的诊断面板
- 现在顶部 summary 会进一步只保留“最新的一张主卡”；另一角色若存在，则下沉成更短的 brief，减少 transcript 第一屏被两张摘要卡同时占掉
- 展开 `任务链` 折叠区后，每个 task 会被拼成一组 `Brief / Harness` 双消息气泡，同一 `parent_task_id` 链会聚合成一条 iteration chain
- 对多轮任务，`任务链` 第一屏现在应该优先暴露两类信息：`正在执行/最近执行的 worker`，以及 `最近一轮 worker 输出预览`；这两项不应只埋在 details drawer 里
- 对当前选中的 active task，`Harness` 主气泡正文也必须优先显示最近一轮 `task_progress / task_result.content` 的完整叙述；`continuity_summary=failed/done` 这类 terse state 只能退到次级摘要或 output preview，不应覆盖主正文
- 对当前选中的 active task，task thread 里的 `round output` 也应支持显式展开；如果 `full_content / failure_summary_readable` 已存在，用户不应只能切到 related messages 或 details 才能看到完整结果
- 这块 `round output` 的展开态也不应只复用“message card 的展开状态”；如果它本质上绑定的是当前 `task id`，轮询刷新后就不能再被“只保留可见 message id”的清理逻辑误删，否则会出现“下半区点开完整结果，过几秒自己收回”的假回归
- 当前实现已经按这条规则拆成独立展开键：主聊天流继续按 `message id` 维持，task thread 的 `round output` 则单独按 `task id` 维持
- 上半区 transcript 主卡的展开态也不应继续盲信历史 `full_content`。如果当前 `full_content` 只是旧的 `Worker Output / Artifact Content` 空壳，而同一条 message metadata 已经有更可读的 `failure_summary_readable`，展开后应优先显示 `失败摘要 (+ 下一步)`，而不是把空壳正文重新暴露出来
- 对当前选中的失败态 task，如果历史 `task_progress.full_content` 还是旧空壳，而 `live_flow.task.metadata.failure_summary_readable` 已经更完整，thread output 也应优先回退到这条更新后的失败摘要，而不是继续只显示 `failed / Worker Output / Artifact Content`
- 后端新写入的 `task_progress / task_result.full_content` 也应直接使用 `失败摘要 / worker 输出 / 产物内容 / 恢复模式 / 执行轨迹` 中文分段；`Worker Output / Artifact Content / Failure Summary` 只作为兼容旧数据的空壳识别口径，不应继续作为新内容呈现给 operator
- 如果历史 `failure_summary_readable` 本身还是长噪声，主 thread output 也不应整段照抄；第一页应先压成短可读失败摘要，把原始 trace / listing / prompt echo 继续留在 details 与 live flow
- 已知 provider/runtime 失败摘要应在 Dialogue 第一屏人话化：`thread not found` 显示为 `线程未找到 (...)`，`timeout / timed out` 显示为 `执行超时`，避免用户只能看到 `worker failed: timeout` 这类内部英文诊断
- 顶部状态 focus line 里的新增恢复状态也应人话化：partial timeout 显示为 `部分结果待确认`，human gate 显示为 `等待人工确认`，auto handoff pending 显示为 `移交已排队`
- 对当前选中的 active task，`Harness` 结果气泡上沿现在应优先形成更明显的运行态条带：直接露出 `执行中/最近执行 worker` 与当前 `status / control node`，更接近 `codex/openclaw` 的 first-screen execution strip
- 这条运行态条带里的 worker 标签也应人话化为 `执行方`，不要在首屏继续显示 `worker <id>` 或 `worker · <id>` 这类 raw 标签
- 这条 execution strip 不应只剩一层普通摘要文本；更合理的第一页形态是两层结构：`执行中/最近执行 worker + status/control node`，再加一层独立的 `最近输出 + short failure/result`
- 这两层不应只是“两个色块里塞长句”；更接近执行面的做法是优先突出 `label / worker / short result`，把长叙述继续留在正文或展开态
- 这条规则不只适用于下方 task thread；上半区 transcript 里的 `task_progress / task_result` 消息卡也应在默认折叠态就带出 `worker + 短结果预览`，避免用户先看到一条 `failed` 却不知道是哪轮 worker 的结果
- 对当前选中的 task，如果历史 `session message.metadata` 还是旧壳，而 `live_flow.task.metadata.failure_summary_readable` 已经更完整，transcript 主卡也应允许借用这份 task metadata 来补齐默认预览和展开正文
- 这条 transcript 主卡纠偏不应严格绑死在 `selectedTaskId` 上；如果页面当前已经聚焦到同一条 `live_flow.task`，主卡也应允许直接借用这条 focused task 的最新 outcome projection，把 `failed` 收成短可读失败摘要
- transcript 主卡收成可读失败摘要后，还应继续控制默认密度：折叠态正文与 outcome strip 优先保留 `worker + short failure/result`，恢复状态与下一步继续留在 hint / expanded body，而不是重新把第一屏拉成长状态行
- richer acceptance / 真实页复看时，浏览器 console 也应尽量干净；`/dialogue/` 与 `/console/` 现在都声明空 favicon，服务端也对 `/favicon.ico` 返回 `204`，避免稳定可复现的 favicon `404` 污染页面验收噪声
- 当前验证入口：`WebConsoleHandlerHttpTest.consoleRouteDeclaresEmptyFavicon()` 与 `WebConsoleHandlerHttpTest.rootFaviconReturnsNoContentForAcceptanceNoise()`
- 对带 `task=` hash 的真实页，首屏也不应先闪旧态再收敛；更接近 `codex/openclaw` 的体验是：一打开就尽量直接落在当前 selected task 的 worker/status/outcome 上，而不是先渲染一版 `idle / failed` 再等下一轮刷新纠正
- 如果要把 `/dialogue/` 收得更像 `codex/openclaw`，优先增强第一页的 `worker / status / short output` 执行条带，而不是继续往 details 里堆信息；执行中的 worker 必须是第一眼就能扫到的主信息
- 对已选中的 active task，第一页 transcript 顶部最好再钉一块 `最近输出` 摘要，把最近一轮 worker 结果直接放到主视线里，而不是只靠 message list 里的历史顺序自然出现；`pinned-latest-round-output` 只作为稳定 selector 名称，不应作为用户可见标题
- 这块 pinned output 的数据源也不应被 `live_flow.task` 单点卡死；只要当前 selected task 和对应 outcome message 已经存在，顶部摘要就应先显示
- pinned output 也要保持密度克制：正文只露出短结果，恢复状态和下一步继续下沉，不要把顶部摘要自己做成第二块长诊断面板
- 如果 pinned output 已经有独立的 `最近输出` 条带，正文更适合作为 fallback 或补充上下文，不应再和 output strip 重复同一句短失败摘要
- 下方 active task thread 里的 `round output` 也应带显式标题，例如 `最近输出`；如果只有一段绿色正文但没有 output label，用户仍然不容易第一眼分辨“这是本轮 worker 输出，不是普通解释文案”
- 当 transcript 消息较少时，`message summary + message list + collapsed thread drawer` 现在也应作为同一组底部栈被收紧；更准确的运行时 contract 已经落成 browser-probe 布局指标：
  - `task_note_attach.layout_metrics.gapBetweenDrawerAndComposer <= 28`
  - `task_note_attach.layout_metrics.drawerSummaryHeight <= 28`
  若真实页再次出现“消息悬在上半区、composer 上方断一截”，优先先看这组 probe 指标，而不是先怀疑后端没回消息
- 当失败恢复链已经细分到不同 failure class 时，第一页也应直接露出 `failure_class`，否则用户看得到 `auto handoff / human_gate`，但看不出为什么系统会这么处理
- 但这类恢复信号在第一页应优先显示为短的人话标签，而不是原始枚举串；`worker_runtime_transient / human_gate_required` 这类 token 更适合继续留在 API、details 或 live flow 诊断里
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
- 对 task receipt/progress/result 这类 façade 回执，transcript 里当前作用域下最新一条 assistant/system 回执卡也会带 `最新回执 / 最新进展 / 最新结果` 标记，不必只看 toast 或 composer inline state
- 这三处 façade reply 消费（toast、composer inline、transcript latest badge）现在已经共用同一套 reply-kind helper，减少 `reply_type / reply_source` 文案和 tone 的漂移风险
- composer 的内联上下文现在也会短暂保留这条最近 façade 回执，形成稳定的“上一轮系统反馈”，不再只靠瞬时 toast 传达任务已记录、已推进或已完成
- 这条内联 façade 回执现在也会按当前 `session/task` 作用域约束，切到别的会话或别的任务时不会继续显示上一轮不相关的反馈
- 在 `自动` 模式下，composer 现在默认先走 façade 的 `task_mode=task_auto`；如果当前上下文已经选中 task，这一轮就按 continuity 继续该 task；如果当前只有 session，则会 materialize 成新 task，而不是只写 session note
- `task_auto` materialize 新 task 后的前端选择同步已经收口：页面会先显示 `task_pending`，再用 pending auto-task tracker 主动 catch up 同 session 下的新 task，并切到该 task
- 当前验证入口：`node --test src/test/js/dialogue-facade-pending-plan.test.mjs src/test/js/dialogue-pending-auto-task-plan.test.mjs`
- 在 `聊天` 模式下，composer 也会强制走 façade 的 `task_mode=task_auto`。这里的“聊天”不再等于 `message_only`，而是“按当前 session/task 连续推进”
- 在 `新任务` 模式下，composer 会走 façade 的 `task_mode=task_required`，由 façade 复用原生 `TaskService` 强制 materialize 新任务并启动
- `follow-up` 不再长期占一个显式模式位；现在更偏向通过“生成 follow-up”按钮或已有 parent 绑定自动触发，并仍然透传 `parent_task_id` 来生成新的 follow-up task，而不是继续父任务本身
- `message_only` 这条语义仍保留在 façade/API 层，便于兼容旧客户端与显式 session-note 场景；但它不再是 `/dialogue/` 主 composer 的默认路径
- 这组发送请求体现在也已经抽成独立 contract helper，并有前端 smoke 覆盖 `task_auto / task_required / follow-up parent / task note attach / manual-start` 五类主路径，避免 composer request 语义和 façade continuity contract 漂移
- 如果要做一轮完整的 `/dialogue/` + façade 人工验收，直接参考 [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)
- unified composer 同时暴露 `auto_start` 开关，适合只创建任务、稍后手动 `continue`
- 下一步的主要方向不是再加一层模式，而是继续减默认信息密度：把更多 task-only 控制继续下沉，让 `/dialogue/` 更接近单聊天壳层
- façade 在创建任务前会先写入 `task_brief / task_followup / task_note / user_note` 等 user turn，所以前端不再额外手工 mirror 一次消息
- 任务创建、`pause / resume / continue / escalate / handoff / state update` 之后，后端也会 best-effort 回写 `task_receipt / task_action / task_state` 消息，方便直接在对话流里回看 harness 回执
- 对于 `auto_start / resume / continue / handoff` 这类会重新进入执行链的动作，后端还会尽量根据 `summary / judgment / artifact / active_context` 生成 `task_progress`；若任务已进入 `done / failed`，则会写成 `task_result`
- `task_progress / task_result` 现在会额外带上 `model_mode / route_source / preferred_worker_hint / learning_hint_applied / tool_chain_* / acceptance_result` 等结构化 metadata，方便在消息层直接看当前 route 与实验上下文
- `task_progress / task_result` 的 message body 现在也会优先收成 `summary_preview`，并把 `next_step` 作为第二行提示；`task_action / task_state / task_receipt` 则会优先消费 `action_label` 与当前 `task_status / control_node`，更像 thread 回执，而不是原始长文本广播
- 这条“默认先显示摘要”的副作用已经收口到 message expansion 合同：collapsed 默认看 `summary_preview`，expanded 会优先展开 `full_content / output_text / artifact_content`，失败态没有完整正文时也会把 `failure_summary_readable` 当成第一等展开源
- 当前验证入口：`node --test src/test/js/dialogue-message-expansion-plan.test.mjs`
- 对真实项目页来说，这条约束还要再落一层：如果当前已经选中了 active task，不能只等用户手点刷新；`/dialogue/` 应主动短轮询当前 task 的消息和 live_flow，让最新 `task_progress / task_result` 自动回流到主聊天流
- 对当前选中 task 的最新 `task_progress / task_result`，如果后端已经提供 `metadata.full_content / output_text / artifact_content`，主聊天流里至少要做到两件事：
  - 明确露出“展开完整结果”入口，而不是只给一条模糊摘要
  - 对最新结果卡给予更强的默认可见性，避免用户误判成“没拿到 agent 返回结果”
- 这条“可展开结果”不只看 `full_content / output_text / artifact_content`。如果失败态消息只有 `failure_summary_readable`，主聊天流也会把它当成第一等展开源，至少展开出 `失败摘要`，而不是只留一条 `failed`
- 若当前 worker 返回的是不可读失败输出（例如 provider/runtime 侧 mojibake），主聊天流应优先退化成可读失败摘要，而不是直接把原始乱码暴露给用户；原始 trace 继续下沉到 `details / live_flow / judgment_trace`
- 对真实任务流来说，主聊天流现在不只给一条可读失败摘要；如果系统已经自动重试或自动切 worker，消息卡和 pinned outcome 会露出人话化的 `failure_class / retry / handoff / human_gate` 恢复状态
- 当前验证入口：`node --test src/test/js/dialogue-task-thread-preview-regression.test.mjs`
- 任务气泡和链头会直接显示 `auto-start / manual-start` 标记，信号来自任务元数据里的 `start_mode`
- message card 会额外显示 `trigger / completion / action` 信号标签，便于快速判断这条 assistant 回执是由哪次推进动作触发的
- `/dialogue/` 的 message card 现在也会直接把 route / tool chain / mode / learning hint 转成 badge，不必切到右侧 detail 才能看当前执行上下文；route 应显示 `来源：...`，tool step 应显示 `N 步`，learning hint 的应用状态应显示为 `已应用 / 已观测未应用`，不要把 `via / steps / applied / observed` 继续裸露到主 transcript
- `/dialogue/` route drawer 的 chip 源头也应直接生成中文：`模式 / 偏好 / 学习记忆 / 路由执行一致性`，`mode: / hint: / learning: / route/execution` 只作为旧输入兼容映射，不应再由页面渲染链主动生成
- `GET /api/v1/sessions/{id}/messages` 现在已经能直接返回上述结构化 message metadata，前端不需要再从 `live_flow` 或右侧 inspector 反推 route / next-step / control action
- 右侧任务面板现在默认收起，通过 header 上的 `查看任务面板` 再展开；窄屏下继续以单列方式展示
- 展开的任务面板内部也进一步做成 progressive disclosure：`迭代链 / Related Messages / 连续性摘要` 常驻，`Mounted Context / 路由与判断 / 实验对比 / 最近产物 / 工具轨迹` 默认折叠，避免 inspector 一打开就把整页诊断信息全部铺开
- 右侧 `实验对比` 面板的指标也不应继续露出 `runs / done / learned hint applied / avg tool steps / steps / cost`；对 operator 可见的统计项应显示为 `次运行 / 完成 / 学习偏好已应用 / 平均工具步数 / 步骤 / 成本`
- `路由与判断` 里的 judgment 卡片现在默认也只保留 summary、execution boundary 和少量关键 diagnostics，不再把 alignment/candidate/evidence/unfinished 等所有细项一次性拼成大段文本
- execution boundary 的基础 chip 也应使用 operator 可读中文：`execution / worker` 显示为 `执行回合 / 执行方`，避免 details 里继续出现 `exec / worker` 这类 raw trace 标签
- `路由与判断` 和 cognition timeline 的可见 chip 也应人话化：`next/current/follow-up/action/route/status/aligned/diverged` 不应直接裸露，显示为 `下一步/当前/跟进/动作/路由/状态/一致/不一致`
- `Mounted Context` 对象卡的控制面 chip 也应人话化：`retention / rehydrated / archive retrieval / external refresh / context reopen / refs / targets / next` 不应直接裸露，显示为 `保留状态 / 已从归档恢复 / 需要检索归档 / 需要刷新外部事实 / 需要重开上下文 / 引用 / 候选目标 / 下一步`
- `/dialogue/` 右侧现在也会直接显示当前 task 的 experiment 对比卡片，但默认只常驻当前 mode headline；其余 mode 对比、prompt rollout 和 case 对照已下沉到折叠区
- `/dialogue/` 的 route 卡片也会补 `route_source / preferred_worker_hint / learning_hint_applied / fallback_reason` 等字段，不再只显示一个 selected worker
- 如果当前恢复链已经把某个 provider 判成热失败窗口，`/console/` 与 `/dialogue/` 的 route box 还会直接显示 `恢复阶段会优先避开 <provider>`；这条说明对应的是 recovery 视角，不是普通 route 永久禁用
- route 卡片当前默认常驻只保留 `selected worker / route source / route reason`；candidate workers、route chips 和 cognition timeline 已下沉到折叠区，避免 inspector 默认展开时信息过满；折叠 summary 应显示 `展开路由细节 / 展开路由轨迹`，不要回退成 `展开 route 细节 / route timeline`
- `Related Messages` 和 `连续性摘要` 仍然常驻，但默认只显示小预览；额外消息和 overflow continuity chips 会进内嵌折叠区，避免 details drawer 中段高度过高
- inspector 的“迭代链上下文”卡片现在默认只常驻当前轮和上一轮/下一轮导航；完整链表会下沉到折叠区，避免右侧默认出现整条历史列表
- task details 的控制动作现在也按 chat-first 方向收口：默认常驻只保留一个状态感知的主动作（例如 `继续推进` 或 `恢复`），`暂停 / 升级 / Worker 移交` 等次级控制下沉到 `更多操作`
- 控制动作和恢复任务 chip 里的 worker 相关文案也应统一为 `执行方`：handoff 动作显示 `移交执行方`，恢复 job 目标显示 `执行方 <worker>`，不要再出现 `移交 Worker` 或 `worker <id>`
- task 详情 modal 的 route / 判断诊断也应沿用同一口径：`Worker / 选中 Worker / 候选 Workers` 显示为 `执行方 / 选中执行方 / 候选执行方`，空值显示 `未分配 / 未知 / 暂无`，不要露出 `unassigned / unknown / none / not specified / no result`
- inspector 现在也会显示当前 task 的 `Related Messages`
- `Related Messages` 不再只看 `task_id=当前任务` 的回执，也会有限度并入同 session 的普通连续聊天消息；这些消息会带 `session continuity` badge，而 task 自己的回执/brief 会带 `task-bound` badge
- 当前选中的 `session/task` 会同步到 URL hash，刷新页面或分享链接时能直接落到同一轮任务
- `/dialogue/` 选中任务后会优先消费 `GET /api/v1/tasks/{id}/live_flow` 里的 `related_messages`，只有旧实例或缺字段时才回退到 `/api/v1/sessions/{id}/messages?task_id=...`
- 如果消息数很多，可以先用上半区过滤器只看 `assistant` 回执，或只看绑定了 `task_id` 的 task 级消息
- 如果消息流很长，优先看摘要卡片，再决定是否切到 `assistant` 或 `system` 过滤器看明细
- 如果要看 `/dialogue/` 当前壳层排版效果，现有入口是 `scripts/screenshot.js`；但它当前仍主要覆盖截图与布局观察，不等于前端业务功能 smoke
- 如果要跑轻量前端业务 smoke，当前入口是 `scripts/dialogue-business-smoke.js`；它和 `scripts/screenshot.js` 分层，前者测主交互路径，后者只测壳层与布局
- 如果要验证 failed / human_gate task 的自动恢复入口和异步恢复 job 可见性，入口是 `scripts/recovery-job-ui-probe.js`；它会断言页面发起 `recover?async=true`，并在 details/overview 中看到 `恢复任务` 与请求 id
- 如果要本地复现这两层验证，优先按 `STARTUP_GUIDE.md` 里隔离 DB 的 `/dialogue/` 启动方式起实例，避免本机历史 session/task 污染验证结果
- 如果目标已经从“改 UI”切到“上 GitHub 前页面功能要测完整”，不要只跑这两层；完整发布前矩阵见 `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
- 当前最新一轮 unified fresh 隔离实例验证通过的是 `http://localhost:18386`：`scripts/screenshot.js` 已通过 `desktop / narrow / responses` 三个 profile，`scripts/dialogue-business-smoke.js` 也已通过 create session / default `task_auto` / pinned 最近输出 / manual-start task / continue-current note 五条 light business smoke 路径
- `/dialogue/` 最近这一轮已经继续往 `codex` 壳层收：左侧更像 recent thread rail，header 更薄，details 入口更轻，transcript-first 主区和底部 composer 更明显；顶部状态和右侧 details header 也进一步弱化成次级面
- 这一轮 green run 之后，当前仍以 transcript 为第一视觉中心，且顺序跑 shell screenshot / light business smoke 仍保持绿灯
- 最新一轮默认密度收口又继续压了一层：顶部 `message summary` 更像 transcript 导读，composer mode/meta 条更轻，底部 composer footer 只保留最少 session/task context，不再像小型控制说明区
- 左侧 session rail 卡片现在也更像 thread preview；右侧 details header copy 和顶部 focus/status pills 继续减重，secondary surface 的存在感更低
- details panel 正文卡片和 header 也继续收轻了，默认更像按需展开的 side surface，而不是并列工作台
- 同时收掉了一个真实缺口：`details=open` 不再只是 hash/state 变化，desktop / responses 下右侧 details panel 现在会真正显示出来
- sidebar 顶部和“新建 thread”区继续减高，composer 头部与辅助说明也更薄了，第一屏更接近纯 chat shell
- 当前这一轮又继续把 session card 收向“标题 + 一行 preview + 时间”，并把 composer footer、details header、overview/action 区的默认高度再压低一层
- 这一轮在 fresh empty-state desktop 下已经能直接看见差异：左 rail 更像真正的 recent thread list，composer 下半区和 details 上半区也更接近按需查看的 chat shell，而不是小型工作台
- 同一轮里，composer 空态下的次级 task-only 动作也继续下沉了；没有 task 上下文时不再长期露出 `附着到当前任务 / 生成 follow-up / 清除关联`
- 这轮 bottom edge 也继续收了一层：主发送按钮和 ghost 按钮尺寸更轻，高级参数 summary 更薄，默认更像聊天输入器的尾边，而不是控制台 footer
- 同一轮里，empty-state 下 footer 左侧的 session/task 上下文块也默认隐藏了；只有进入 task 上下文或 closed session 时才重新出现
- 最新这一轮又继续压了一层 task-state footer：task / follow-up 上下文下不再重复显示底部 `messageHint`，只保留更短的一行 task context，更接近单聊天输入器
- 这一轮又继续把 thread rail 和 details 默认密度再压轻一层：session card 默认只保留时间、标题和一行 preview，不再常驻 status/task badge；details 的 header、overview/action 区也继续减高，更像真正的次级 side surface
- 最新这一轮继续压的是 transcript 顶部辅助层：`筛选` drawer 的 summary/chip 更薄，message summary 主卡也收成更窄的上下文卡片，避免它重新长成横幅摘要面板
- 紧接着这轮又继续压 composer 下半区：mode/meta 提示更轻，高级参数 summary 更薄，空态默认文案也进一步收成更像单聊天输入器
- 最新这一轮继续压的是 details 默认密度：header copy 更短，overview/action 区和各 section card 的默认高度进一步减小，更接近真正的次级 side surface
- 紧接着这轮又继续把 rail 与 details 默认解释文案收短了一层：recent rail 只保留极短提示句；details header、更多操作、mounted context、route/judgment、experiment、artifact、tool trace 的 copy 也继续减短
- 最新这一轮又继续压的是 workspace subbar 和 composer head：`Session Transcript` 下方辅助文案更短，composer lede 也收成更像单聊天输入器的默认提示，输入框最小高度进一步下降
- 最新这一轮继续压的是 transcript 下方的 task timeline：`任务上下文` 继续收成更轻的 `任务轨迹` 抽屉，默认更像按需查看的上下文轨迹，而不是第二主时间线
- 紧接着这轮又继续压的是左侧 recent rail：顶部说明、`新 thread` 区、health pill 和 session card 默认高度都进一步减轻，更接近纯 thread list
- 这轮收口依赖四件事：Puppeteer 打开 `/dialogue/` 先等 `/api/v1/health`、再显式等 shell；后台 harness 启动时复制 runtime jar，避免本机重建 `target\\*.jar` 时把静态资源链打坏；后台启动在端口已被占用时直接失败；以及前端收掉当前壳层下的旧 DOM 直接访问
- 当前 `18386` 这轮 shell validator 还额外锁住了几条更强的 shell contract：
- 默认 `/dialogue/` shell 不自动带出 `task=` hash
- session-scoped shell 下，composer 的 task-only 次级动作与上下文块默认隐藏
- default shell 下，details 要么保持折叠，要么保持轻量空态，而不是铺 overview/action 空白块
  - desktop / responses 下，`details=open` 会真实显示右侧 panel，而不是只改 hash/state
  - 窄屏下 `transcript dominates composer vertically`，避免页面再退化回“底部表单比聊天流更高”
- 更窄的 `thread rail + details` 列宽 (`196px / 292px`) 已在 fresh 实例真实生效，不再是源码层假阳性
- 同一轮里，desktop shell 的 transcript / composer 高度当前是 `575px / 284px`
- 同一轮里，details 的 header、empty state、overview/action/card 默认高度也继续压低一层
- 最新这一轮又继续压了右侧 details 默认密度：header、empty state、overview/action/card padding 更小，更像真正的次级 side surface
- 最新这一轮又继续压了 transcript 顶部筛选和 composer 下半区：filter summary 更短更薄，composer 的 label / inline hint / mode bar / 参数 summary 更接近单聊天输入器
- 短 transcript 的大空白问题已经收口到 CSS 与回归测试：`message-panel__body--stream-only` 会让 `message-stream` 作为底部栈贴近 composer，折叠态 `任务轨迹` summary 也被压成薄 footer strip
- 当前验证入口：`node --test src/test/js/dialogue-transcript-layout-plan.test.mjs`
- 若真实页再次出现“消息卡悬在上半区、composer 上方断一截”，优先对照 `task_note_attach.layout_metrics.gapBetweenDrawerAndComposer <= 28` 与 `drawerSummaryHeight <= 28`，再排查是否实例仍在跑旧 JAR
- 时间显示缺口已经收口：后端消息时间可能以 epoch seconds 浮点数返回，`/dialogue/` 与 `/console/` 都会先走 `normalizeTimestampValue(...) / timestampMs(...)` 再渲染聊天流、details、artifact、tool trace 和 session/task rail 时间
- 当前验证入口：`node --test src/test/js/dialogue-time-normalization.test.mjs src/test/js/console-time-normalization.test.mjs`
- 前端资源树现在通过 `src/main/resources/web/package.json` 局部声明 `type=module`，只让 `/dialogue/` 与 `/console/` 的浏览器模块和 Node plan 测试按 ESM 解析；不要把仓库根 `package.json` 直接改成 `type=module`，否则会影响仍使用 CommonJS 的 `scripts/*.js` 页面级探针。
- 2026-05-19 已复跑完整前端 plan 套件：`node --test src/test/js/*.mjs`，结果 `163` 个测试全部通过，并且不再出现 `MODULE_TYPELESS_PACKAGE_JSON` 警告。
- 真实项目排查时还要额外区分“逻辑没修”和“实例没更新”两件事：如果源码里已经统一做了 epoch-seconds 归一化，但 `8080` 页面仍显示旧错误时间或旧摘要卡片，优先怀疑当前实例仍在跑旧/坏的运行 JAR
- 结果可见性缺口已经收口到 `message-expansion-plan.js`：`task_progress / task_result` collapsed 默认看摘要，expanded 可直接展开 `full_content / output_text / artifact_content`，并在旧空壳 `full_content` 遇到 `failure_summary_readable` 时回退到可读失败摘要
- 当前验证入口：`node --test src/test/js/dialogue-message-expansion-plan.test.mjs`
- 失败恢复可见性缺口已经收口：主聊天流不只剩失败摘要，还能看出 `failure_class / auto retry / auto handoff / human_gate` 中的当前恢复阶段，并把原始枚举人话化
- 当前验证入口：`node --test src/test/js/dialogue-task-thread-preview-regression.test.mjs`
- 第四轮收口还把 transcript 顶部 role/scope filter 下沉成默认折叠的 `筛选` drawer，并进一步压薄了窄屏下的 header / statusbar / composer footer；当前 `narrow` profile 单独复跑也保持绿色
- `task_auto` catch-up 也已经收口：default `task_auto` 后端 materialize 新 task 后，前端会先显示 `task_pending`，再通过 pending tracker 主动抓取并选中新 task；`delay()` helper 已补齐，不再保留 `delay is not defined` 缺口
- 当前验证入口：`node --test src/test/js/dialogue-facade-pending-plan.test.mjs src/test/js/dialogue-pending-auto-task-plan.test.mjs`
- shell screenshot 和 business smoke 当前应串行跑，不要并发共用一个实例；否则后者创建的 session/task/hash 会污染前者的壳层报告
- 如果要验证 richer browser 业务路径，优先参考 `Run-DialogueBrowserAcceptanceProbe.ps1`；后续若要把“壳层截图”和“业务交互 smoke”分层，建议按 `docs/DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md` 收口
- 如果要直接执行 `/dialogue/` UI 启动、shell screenshot、light business smoke 的完整顺序，优先参考 `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md`

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

当前运行态总结也应该写实成这样：

- default `task_auto` 已能在后端 materialize 新 task，并且 fresh `18366` 上页面会及时切进这个 task
- 当前已选中 task 且勾选“继续当前任务”时，fresh `18366` 上也已经不会再额外新建 task，而是继续当前 task continuity
- `manual-start` 这条路径现在也补上了更稳的 settle contract：
  - hash task
  - thread active card
  - details title / status
  - 三者要先真正对齐，再进入下一轮 `continue-current`
- 当前 `shell screenshot + light business smoke` 的最新 unified fresh 绿灯样本是：
  - `.tmp/dialogue-shell-report-18386.json`
  - `.tmp/dialogue-business-smoke-18386.json`
- 这轮 console 中仍有一条 headless `404` 资源报错，但没有阻断页面主功能，可先视为低优先级静态资源尾项

## 相关文件

- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/dialogue/index.html`
- `src/main/resources/web/dialogue/app.css`
- `src/main/resources/web/dialogue/app.js`
- `src/main/resources/web/console/index.html`
- `src/main/resources/web/console/app.css`
- `src/main/resources/web/console/app.js`
