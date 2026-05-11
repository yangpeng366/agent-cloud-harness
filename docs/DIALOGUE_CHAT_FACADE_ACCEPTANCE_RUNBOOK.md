# `/dialogue/` Chat-First + Chat Facade 验收 Runbook

这份 runbook 只回答一件事：

> 当前 `Phase 5 / Phase 6` 已经把 `/dialogue/` 收成了多大程度上的 chat-first workspace，`/v1/chat/completions` façade 又已经稳到了什么程度？

它不重复解释架构设计；只提供**面向验收的用户路径清单**、**对应自动化证据**和**手工检查点**。

---

## 1. 验收目标

本轮验收只覆盖以下两类结果：

1. `/dialogue/` 默认交互已经明显是 **transcript-first / chat-first**
2. `/v1/chat/completions` 已经能稳定承接 `/dialogue/` 主发送链，且 continuity contract 不会和原生 control-plane 语义漂移

不在本轮目标内的内容：

- 真正的 token 级 `stream=true`
- 完整 `/v1/responses`
- tool-call delta / multimodal façade

说明：

- 当前代码已经补了**最小** `/v1/responses`
- 但这份 runbook 的主验收面仍以 `/dialogue/` 和 `/v1/chat/completions` 为主
- `/v1/responses` 目前只算“接口面已补齐，语义复用同一套 continuity contract”，还不算完整 Responses API 验收

---

## 2. 自动化证据总览

### 2.1 前端 `/dialogue/` chat-first shell

- `WebConsoleHandlerHttpTest.dialogueRouteServesTranscriptFirstShell()`
  - 锁住 `GET /dialogue/` 真实返回 transcript-first shell，而不是旧 task-first 标记页
  - 锁住主 composer 只暴露 `auto / message / task` 三种显式 mode，不再回退到 `followup` 显式模式位
- `WebConsoleHandlerHttpTest.dialogueRouteServesAppJavascript()`
  - 锁住 `/dialogue/app.js` 可被真实 HTTP 路由取回，避免只剩资源文件存在但页面壳层断链
- `Run-DialogueShellAcceptanceProbe.ps1`
  - 对真实启动的本地 harness 发起 `/dialogue/` 与 `/dialogue/app.js` 请求
  - 锁住 transcript-first shell、details toggle、三态 composer mode 和 `followup` 已下沉
- `dialogue-composer-markup-plan.test.mjs`
  - 锁住主 composer 显式 mode 只剩 `自动 / 聊天 / 新任务`
  - 锁住 `followupButton` 仍存在，说明 follow-up 已下沉成动作入口而不是被删掉
  - 锁住 `composerRouting / composerAdvanced / submitTaskButton`
- `dialogue-composer-plan.test.mjs`
  - 锁住 `自动 -> message_only`
  - 锁住 advanced/task-only override -> `task`
  - 锁住 `followupParentTaskId -> followup`
- `dialogue-composer-request-plan.test.mjs`
  - 锁住 façade request body 的 `message_only / task_required / follow-up parent / task note attach / manual-start`

### 2.2 后端 `/v1/chat/completions` continuity contract

- `ChatFacadeHandlerHttpTest.postChatCompletionMessageOnlyCreatesSessionMessagesWithoutTask()`
- `ChatFacadeHandlerHttpTest.postChatCompletionMessageOnlyWithTaskIdWritesTaskNoteWithoutContinuation()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskRequiredCreatesAndRunsTask()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskRequiredCanReturnTerminalTaskResultReply()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskRequiredCanCreateManualStartFollowupTask()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskIdWithAutoStartFalseOnlyRecordsTaskNote()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskRequiredWithTaskIdAndAutoStartFalseOnlyRecordsTaskNote()`
- `ChatFacadeHandlerHttpTest.postChatCompletionTaskAutoWithActiveTaskAndAutoStartFalseOnlyRecordsTaskNote()`
- `ChatFacadeHandlerHttpTest.postChatCompletionSupportsMinimalSseStream()`
- `ChatFacadeHandlerHttpTest.chatFacadeAcceptanceFlowCoversMessageTaskNoteAndManualFollowupInOneSession()`
  - 把 `message_only -> task_required manual-start -> message_only + task_id -> follow-up manual-start`
    压成一条 composite HTTP acceptance flow
  - 同时锁住 façade 成功 materialize 新 task / child task 后，会把 staging 的
    `task_brief / task_followup` 回填成真正 task-bound message
- `ChatFacadeHandlerHttpTest.postResponsesCreatesTaskRequiredResponseEnvelope()`
- `ChatFacadeHandlerHttpTest.postResponsesSupportsMinimalSseStream()`
  - 锁住最小 `/v1/responses` JSON / SSE 也复用同一套 continuity contract，而不是旁路出第二套执行语义
- `Run-ChatFacadePathMatrixProbe.ps1`
  - 对真实启动的本地 harness 分别跑 `chat_completions` 与 `responses` 两条 deterministic live path matrix
  - 覆盖 `message_only -> manual-start task -> task note attach -> manual-start continuity -> manual-start follow-up`
  - 直接校验真实 session/task/message contract：
    - `message_only` 不物化 task
    - manual-start task / follow-up 返回 `task_receipt`
    - `message_only + task_id` 与 `task_auto + auto_start=false` 都只写 `task_note`
    - child task 的 `parent_task_id` 正确
    - parent/child task 都仍停在 `control_node=intake`
  - 这条 probe 仍然只是 live HTTP 证据，不替代下面 A-H 的真实浏览器手工点验
- `Run-DialogueBrowserAcceptanceProbe.ps1`
  - 使用本机 Edge headless + CDP 驱动真实 `/dialogue/` 页面，而不是只打 HTTP 接口
  - 现在支持可选 `-ScreenshotDir`
    - 例如：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18206 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-18206`
    - `responses` surface 建议分开跑，不要和 `chat` 混在同一目录：
      `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18210 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-18210`
    - probe 会在关键阶段输出 PNG 证据，例如：
      - `chat-message-only.png`
      - `chat-stream-fallback.png`
      - `chat-auto-start-task.png`
      - `chat-manual-start-task.png`
      - `chat-task-note-attach.png`
      - `chat-manual-start-continuity.png`
      - `chat-followup-manual-start.png`
      - `responses-message-only.png`
      - `responses-stream-fallback.png`
      - `responses-auto-start-task.png`
      - `responses-manual-start-task.png`
      - `responses-task-note-attach.png`
      - `responses-manual-start-continuity.png`
      - `responses-followup-manual-start.png`
    - 这些截图不替代真实手工验收，但可以作为第 3 节 A-H 路径回填时的辅助取证
  - 当前已验证 `chat_completions` surface 下两条最小真实页面路径：
    - `message_only`
    - `task_required + auto_start=false`（manual-start task）
  - 在 `18162` fresh harness 上，`chat_completions` surface 还额外跑通了两条 richer browser path：
    - `message_only + task_id`（probe 结果里体现为 `task_note_attach`）
    - `follow-up + manual-start`（probe 结果里体现为 `followup_manual_start`）
  - `responses` surface 现在也已经跑通过最小两条真实页面路径：
    - `#facade=responses + message_only`
    - `#facade=responses + task_required + auto_start=false`
  - 在同一个 `18162` fresh harness 上，`responses` surface 也已经补到两条 richer browser path：
    - `#facade=responses + message_only + task_id`（`task_note_attach`）
    - `#facade=responses + follow-up + manual-start`（`followup_manual_start`）
  - 这条 probe 还能直接抓取真实页面里的 fetch / hash / selected-task 轨迹，因此可用于验证：
    - `/v1/responses` 路径下 browser 真正发的是 `/v1/responses`
    - session messages / task receipts 的 `request_path` 已从历史误写的 `/v1/chat/completions` 收口成 `/v1/responses`
  - 同时这条 probe 已经帮助暴露并锁定一个真实生产回归：
    `/dialogue/app.js` 的 ESM import graph 曾在真实浏览器下因为静态资源白名单过窄而 404
  - 该缺口现已通过 `WebConsoleHandler` 静态资源放行策略收口，并由 `WebConsoleHandlerHttpTest.dialogueRouteServesImportedJavascriptModules()` 回归保护
  - probe 当前还额外要求 manual-start task 页面状态完整收敛后才算通过：
    `task=` hash、active task card、composer inline receipt、detail title 四者需要同时到位
  - 在 `18160` fresh harness 上，`responses` surface 的这条完整收敛判定当前已稳定通过
  - 当前 probe 已补上 `manual-start continuity` 的显式 UI seam：
    - 在 composer `高级参数` 中勾选 `继续当前任务`
    - 同时保留 `auto_start=false`
    - 从真实页面发出 `task_required + task_id + auto_start=false`
  - 在 fresh harness 上，默认 `chat_completions` surface 与 `#facade=responses` surface 现在都已经有这条 richer browser path 的 scripted 证据：
    - inline ack 仍保持“已记录”语义
    - 当前 task 选中态保持不变
    - task card 数量不变
    - session message stream 中真实落成 `task_note`
    - 并带 `metadata.task_mode=task_required`、`metadata.auto_start=false`
  - 在 `18162` fresh harness 上，上述 richer browser path 还进一步证明：
    - task-bound note 会在真实页面里保留当前 task 选中态，并写成 `task_note`
    - manual-start follow-up 会在真实页面里生成 child task，且 `child_parent_task_id` 正确
  - probe 现在还额外覆盖了一条真实页面级 `stream fallback` 路径：
    - 对下一次 façade POST 做一次性的 fetch override
    - 保持响应 `Content-Type: text/event-stream`
    - 但 body 直接返回普通 completion JSON
    - 验证 `/dialogue/` 仍能在**同一次响应**里完成 fallback 解析，并且不会重发第二次 façade 请求
  - 在默认 `chat_completions` surface 和 `#facade=responses` surface 下，这条 scripted browser path 当前都已稳定通过：
    - `request_count_delta = 1`
    - `response_content_type = text/event-stream`
    - `response_text_preview` 仍是 JSON completion / response body
    - UI inline ack 仍保持“已记录”语义
  - 在后续 completion audit 中，`responses` surface 还新增收口了一条更贴近真实长请求的 seam：
    - 当新建 `task_required + auto_start=true` 且 façade 请求尚未返回最终 reply 时
    - `/dialogue/` 会先显示 pending inline：`最近回执：已提交任务，正在推进。`
    - 并短暂跟踪当前 session 的 task 列表；一旦新 task 出现，即提前把它选中
    - 因此 `responses` surface 的 richer browser path 现在可稳定覆盖：
      - `message_only`
      - `stream fallback`
      - `auto-start task`
      - `manual-start task`
      - `task note attach`
      - `manual-start continuity`
      - `manual-start follow-up`
  - 同一轮 completion audit 也暴露了一个仍未收口的运行风险：
    - 默认 `chat_completions` surface 在 richer browser path 下，local harness 仍可能出现 JVM native OOM
    - 页面侧通常先看到 `ERR_INSUFFICIENT_RESOURCES`，随后 `/v1/chat/completions` 与 `/api/v1/health` 变成 `ERR_CONNECTION_REFUSED`
    - 此时应优先检查对应端口的 `server-*.out.log`、`hs_err_pid*.log` 与 `replay_pid*.log`
  - 当前 runbook 已把这层风险部分收口：
    - `Start-DialogueChatFacadeManualAcceptance.ps1`
    - `Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
    现在默认都会以显式 JVM 边界参数启动 local harness：
    - `-Xms128m -Xmx512m`
  - 在 `18180` fresh harness 上复验后，默认 `chat_completions` surface 的 richer browser path 当前也已稳定通过；
    因此若再次出现 `ERR_INSUFFICIENT_RESOURCES / ERR_CONNECTION_REFUSED`，应优先怀疑本机资源瞬时不足或并行旧进程未清理，而不是直接判定 façade contract 回归
  - `Start-DialogueChatFacadeManualAcceptance.ps1` 现在还支持可选 browser probe：
    - `-RunBrowserProbes`
    - `-BrowserProbeSurface chat|responses|both`
  - 但 completion audit 的当前经验是：
    - `chat` 或 `responses` **单独**跑 richer browser probe，已可稳定作为 scripted evidence
    - `both` 在同一 fresh harness 中顺序串跑，仍可能把 local harness 推到 native OOM
  - 因此当前建议是：
    - scripted browser evidence 请按 surface 分开跑
    - 不要把 `-BrowserProbeSurface both` 当作稳定默认门槛
  - 即便如此，这条 probe 仍只算“部分浏览器级验收已完成”，不是下面 A-H 八条真实手工路径已全部完成

### 2.3 前端 façade response / reply affordance

- `dialogue-facade-response-plan.test.mjs`
  - 锁住 chat façade 的 `event-stream / same-response json fallback / normal json / error json`
  - 同时锁住最小 `/v1/responses` 的 JSON / SSE parser contract
- `dialogue-facade-client-plan.test.mjs`
  - 锁住前端 façade client 会按 surface 真正发到 `/v1/chat/completions` 或 `/v1/responses`
  - 同时覆盖 HTTP error payload 到前端异常面的最小 contract
- `dialogue-facade-stream-plan.test.mjs`
  - 锁住最小 SSE 的 chunk drain/merge/finalize
- `dialogue-facade-reply-kind.test.mjs`
  - 锁住 `chat_reply / task_receipt / task_progress / task_result` 的 UI 语义映射
- `dialogue-facade-reply-plan.test.mjs`
  - 锁住 façade reply 反馈对象保留 provenance
- `dialogue-facade-reply-highlight-plan.test.mjs`
  - 锁住 transcript latest reply badge 选择逻辑
- `dialogue-facade-reply-ui-consistency.test.mjs`
  - 锁住 toast / composer inline / transcript badge 三处消费一致
- `dialogue-phase6-path-matrix.test.mjs`
  - 把五条用户路径串成组合级 smoke

当前边界：

- `/dialogue/` 默认主发送链仍然走 `/v1/chat/completions`
- `/v1/responses` 现在已经具备“后端 HTTP contract + 前端 parser contract + 可选 UI 发送面”这一级；默认不会切过去，但可通过 `#facade=responses` 做真实发送验证，而且这个 surface 会稳定写回 URL hash
- 它仍然不是默认 UI 发送面，也还不算完整 Responses API 验收

---

## 3. 手工验收路径

下面八条路径，对应 `Phase 6` 文档里已经明确列出的路径矩阵。

如果在开始 A-H 人工手点前已经先跑过 scripted browser probe，可先把现成 PNG 当作辅助取证索引：

- A `message_only`
  - `chat-message-only.png`
  - `responses-message-only.png`
- B `message_only + task_id`
  - `chat-task-note-attach.png`
  - `responses-task-note-attach.png`
- C `task_required`
  - `chat-auto-start-task.png`
  - `responses-auto-start-task.png`
- D `follow-up + manual-start`
  - `chat-followup-manual-start.png`
  - `responses-followup-manual-start.png`
- E `manual-start continuity`
  - `chat-manual-start-continuity.png`
  - `responses-manual-start-continuity.png`
- F `stream fallback`
  - `chat-stream-fallback.png`
  - `responses-stream-fallback.png`
- G `#facade=responses + message_only`
  - `responses-message-only.png`
- H `#facade=responses + task_required`
  - `responses-auto-start-task.png`

注意：

- 这些 PNG 只是辅助取证索引，不等于路径已通过
- 只有真实人工手点后，才能把 acceptance record 里的 A-H 勾选改成通过
- 若人工观察与 scripted browser PNG 不一致，应以真实页面手点结果为准，再回头记录偏差

### 3.1 路径 A：`message_only`

目标：

- 用户像普通聊天一样发一条消息
- 不应该物化新 task
- assistant 只返回“已记录”类回执

手工步骤：

1. 打开 `/dialogue/`
2. 新建一个 session
3. 保持 composer 在默认 `自动` 模式
4. 输入一条普通 note，例如“先记一条草稿，不要启动任务”
5. 点击 `发送`

预期：

- transcript 里出现一条新的 user message
- 不会自动多出新的 task chain item
- toast / composer inline 显示“已记录”语义
- 若刷新 session messages，能看到 `user_note` 或 task-free message

自动化证据：

- `postChatCompletionMessageOnlyCreatesSessionMessagesWithoutTask()`
- `dialogue-composer-request-plan.test.mjs`
- `dialogue-facade-reply-kind.test.mjs`
- `dialogue-facade-reply-ui-consistency.test.mjs`

### 3.2 路径 B：`message_only + task_id`

目标：

- 普通聊天模式下也可以把一条消息附着到当前 task
- 只写 `task_note`
- 不自动推进执行链

手工步骤：

1. 在同一个 session 里先选中一个 active task
2. composer 保持 `聊天` 或 `自动`
3. 勾选“附着到当前任务”
4. 输入一条补充上下文，例如“这一轮先作为 task note 附着，不推进执行链”
5. 点击 `发送`

预期：

- transcript 中看到 user note 和“已记录到当前任务上下文”的 ack
- 当前 task 状态 / control node 不应因为这次发送发生推进
- related messages 中能看到新的 task-bound note

自动化证据：

- `postChatCompletionMessageOnlyWithTaskIdWritesTaskNoteWithoutContinuation()`
- `dialogue-composer-request-plan.test.mjs`
- `dialogue-phase6-path-matrix.test.mjs`

### 3.3 路径 C：`task_required`

目标：

- 用户显式以“新任务”方式发送
- façade 物化 task，并返回 progress 或 result 回执

手工步骤：

1. composer 切到 `新任务`
2. 输入明确任务意图
3. 保持 `auto_start=true`
4. 点击 `发送`

预期：

- transcript 中会出现 task-oriented user turn
- 产生新 task
- toast / composer inline / transcript latest badge 显示“已推进”或“已完成”
- task details 中能看到对应 `live_flow`

自动化证据：

- `postChatCompletionTaskRequiredCreatesAndRunsTask()`
- `postChatCompletionTaskRequiredCanReturnTerminalTaskResultReply()`
- `dialogue-composer-request-plan.test.mjs`
- `dialogue-facade-reply-kind.test.mjs`
- `dialogue-facade-reply-highlight-plan.test.mjs`

### 3.4 路径 D：`follow-up` + manual-start

目标：

- follow-up 不靠显式模式位，而靠 `生成 follow-up` 或 parent 绑定触发
- 生成子任务，但不自动继续执行

手工步骤：

1. 选中一个已有 task
2. 点击 `生成 follow-up`
3. 确认 composer 仍然只显示 `自动 / 聊天 / 新任务`
4. 保持 `auto_start=false`
5. 点击 `发送`

预期：

- 产生新的 child task，`parent_task_id = 当前 task`
- transcript 或 task 详情中可辨认 manual-start receipt
- 不会立刻出现新的 execution progress

自动化证据：

- `postChatCompletionTaskRequiredCanCreateManualStartFollowupTask()`
- `dialogue-composer-markup-plan.test.mjs`
- `dialogue-composer-plan.test.mjs`
- `dialogue-phase6-path-matrix.test.mjs`

### 3.5 路径 E：manual-start continuity

目标：

- 对已有 task 的 continuity turn，在 `auto_start=false` 时只记录 note，不推进执行链

手工步骤：

1. 选中一个 active task
2. 以 `聊天` 或 `自动` 模式附着消息，或以已有 task continuation 方式继续
3. 把 `auto_start` 调成 false
4. 点击 `发送`

预期：

- 只出现 note/ack
- 不应立刻出现新的 progress/result

自动化证据：

- `postChatCompletionTaskIdWithAutoStartFalseOnlyRecordsTaskNote()`
- `postChatCompletionTaskRequiredWithTaskIdAndAutoStartFalseOnlyRecordsTaskNote()`
- `postChatCompletionTaskAutoWithActiveTaskAndAutoStartFalseOnlyRecordsTaskNote()`
- `dialogue-phase6-path-matrix.test.mjs`

### 3.6 路径 F：`stream fallback`

目标：

- `/dialogue/` 优先走最小 SSE
- 如果响应不是完整 SSE，但实际上是普通 JSON completion，也能在**同一次响应**里回退解析
- 不重发第二次请求

手工步骤：

1. 正常在 `/dialogue/` 发送一条消息
2. 打开浏览器 Network
3. 观察 `/v1/chat/completions`

预期：

- 只应看到一次请求
- 前端能消费 `text/event-stream`
- 如果当前服务实例回的是普通 completion JSON，也应在同一响应内正常完成，不再额外补发一次请求

自动化证据：

- `postChatCompletionSupportsMinimalSseStream()`
- `dialogue-facade-stream-plan.test.mjs`
- `dialogue-facade-response-plan.test.mjs`
- `dialogue-phase6-path-matrix.test.mjs`

### 3.7 路径 G：`#facade=responses` + `message_only`

目标：

- 不改变默认 chat-first UI
- 但可显式把发送面切到最小 `/v1/responses`
- 普通 `message_only` 仍然返回“已记录”类回执

手工步骤：

1. 打开 `/dialogue/#facade=responses`
2. 新建一个 session
3. 保持 composer 在默认 `自动` 模式
4. 输入一条普通 note，例如“先用 responses 记一条消息”
5. 点击 `发送`

预期：

- `messageHint` 中会显示 `发送面：Responses façade`
- transcript 中出现新的 user message
- 不会自动物化新 task
- toast / composer inline 仍然是“已记录”语义
- 刷新后 URL hash 仍保留 `facade=responses`

自动化证据：

- `dialogue-facade-surface-plan.test.mjs`
- `dialogue-facade-client-plan.test.mjs`
- `dialogue-responses-path-matrix.test.mjs`

### 3.8 路径 H：`#facade=responses` + `task_required`

目标：

- 在可选 Responses surface 下，也能真实创建/推进任务
- reply affordance 仍与 chat façade 一致

手工步骤：

1. 保持页面在 `/dialogue/#facade=responses`
2. composer 切到 `新任务`
3. 输入明确任务意图
4. 保持 `auto_start=true`
5. 点击 `发送`

预期：

- `messageHint` 仍显示 `发送面：Responses façade`
- 产生新的 task
- toast / composer inline / transcript latest badge 仍显示“已推进”或“已完成”
- 刷新后 URL hash 仍保留 `facade=responses`

自动化证据：

- `ChatFacadeHandlerHttpTest.postResponsesCreatesTaskRequiredResponseEnvelope()`
- `ChatFacadeHandlerHttpTest.postResponsesSupportsMinimalSseStream()`
- `dialogue-facade-client-plan.test.mjs`
- `dialogue-responses-path-matrix.test.mjs`

### 3.9 手工验收最小取证

为了让 A-H 八条路径的手工验收结果可以真正回填到 acceptance record，而不是只停留在“点过了”，每条路径至少应保留下面这类最小证据：

- A `message_only`
  - transcript 中 user turn + “已记录”类 inline 或 toast 回执
  - task thread 未新增 item 的截图或文字说明
- B `message_only + task_id`
  - 当前 task 仍保持选中
  - transcript 或 related messages 中能指出这是 task-bound note
- C `task_required`
  - 新 task 出现的截图或 task id
  - inline / badge 至少一处体现 `task_progress` 或 `task_result`
- D `follow-up + manual-start`
  - child task id
  - `parent_task_id` 或明显可辨认的 parent-child 关系
  - manual-start receipt 的页面证据
- E `manual-start continuity`
  - 当前 task 未变化的证据
  - note/ack 已写入，但没有新 task card 的证据
- F `stream fallback`
  - Network 面中 façade 请求只出现一次
  - 该请求仍为 `text/event-stream`，但页面正常完成回复
- G `#facade=responses + message_only`
  - 地址栏保留 `#facade=responses`
  - transcript 中普通消息已记录，但没有新 task
- H `#facade=responses + task_required`
  - 地址栏保留 `#facade=responses`
  - 新 task 出现的截图或 task id
  - inline / badge 至少一处体现 task receipt/progress/result

如果当次手工点验没有留下这些最低限度的证据，建议不要把对应路径勾成通过。

---

## 4. 建议执行顺序

建议先跑自动化，再做手工：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild

powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueShellAcceptanceProbe.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceProbe.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceProbe.ps1 -UseResponsesSurface

node --test src\test\js\dialogue-composer-markup-plan.test.mjs `
  src\test\js\dialogue-composer-plan.test.mjs `
  src\test\js\dialogue-composer-request-plan.test.mjs `
  src\test\js\dialogue-facade-surface-plan.test.mjs `
  src\test\js\dialogue-facade-client-plan.test.mjs `
  src\test\js\dialogue-facade-response-plan.test.mjs `
  src\test\js\dialogue-facade-stream-plan.test.mjs `
  src\test\js\dialogue-facade-reply-kind.test.mjs `
  src\test\js\dialogue-facade-reply-plan.test.mjs `
  src\test\js\dialogue-facade-reply-highlight-plan.test.mjs `
  src\test\js\dialogue-facade-reply-ui-consistency.test.mjs `
  src\test\js\dialogue-phase6-path-matrix.test.mjs `
  src\test\js\dialogue-responses-path-matrix.test.mjs

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest'
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest'
```

如果这组 probe / 自动化都通过，再按上面 8 条手工路径点验一次真实页面。

补充说明：

- `Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 会自动构建、启动本地 harness、等待健康检查、再把 chat/responses 两条 probe 跑完，适合作为比手工更强的一层 acceptance evidence
  - 它现在也会同步跑 `Run-DialogueShellAcceptanceProbe.ps1`，因此返回结果会同时包含 `dialogue_shell_probe + chat_probe + responses_probe`
  - 当前脚本已改成直接启动 shaded JAR，不再依赖解析 `Run-HarnessWithJava21.ps1` 的控制台输出
  - 当前真实验证结果：`-SkipBuild -Port 18090/18091/18093/18094/18104/18106` 已能稳定返回 chat/responses 两条 probe 的 JSON 摘要
  - `-KeepServerLogs` 时会显式返回这次运行对应的唯一日志文件名，例如 `chat-facade-acceptance-18102-*.log`
  - 默认不传 `-KeepServerLogs` 时，当前脚本不会再为新运行遗留日志文件；若 `.tmp` 下仍看到固定名或旧时间戳日志，通常是早期版本留下的历史文件，可手动清理，不代表当前 runner 仍然泄漏日志
- `Start-DialogueChatFacadeManualAcceptance.ps1`
  - 默认会启动本地 harness、跑完 `dialogue_shell_probe + chat_probe + responses_probe` 后自动退出 harness，并返回可供人工验收的 URL / runbook / record template
  - 返回 JSON 里现在还会附带 `manual_acceptance` 摘要，直接给出：
    - A-H 八条真实页面路径的推荐执行顺序
    - `chat` / `responses` 两个入口 URL
    - scripted browser evidence 的推荐跑法
    - `recommended_screenshot_dir`
    - `result_json_path`
    - `record_seed_output_path`
    - 每条 A-H 路径各自的 `candidate_pngs`
    - 可直接复制执行的 `command_examples`
    - 可直接用于回填记录骨架的 `record_seed`
    - 若骨架自动生成成功，还会内嵌 `record_seed_probe`
    - 建议回填的 acceptance record 路径
    - `command_examples.render_record_seed` 可直接生成一段可复制的 A-H markdown 骨架
      - 当前骨架顶部还会带出 `base_url / dialogue_url / responses_dialogue_url / result_json_path / record_seed_output_path / recommended_screenshot_dir / completion_gate`
      - 同时会把 `keep_running / chat_browser_probe / responses_browser_probe / probe_record_seed_output` 也收进同一份 markdown 头部
    - `command_examples.render_record_seed_to_file` 可把这段骨架落到 `record_seed_output_path`
    - `command_examples.probe_record_seed_output` 可直接验证这条半自动骨架链
  - 如需把 starter 的 `record_seed` 变成一段可直接粘贴进记录的 A-H markdown 骨架，可使用：
    ```powershell
    powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 `
      -InputJsonPath .\.tmp\dialogue-manual-18228.json
    ```
    或：
    ```powershell
    powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 `
      -InputJsonPath .\.tmp\dialogue-manual-18228.json > .\.tmp\dialogue-record-seed-18228.md
    ```
    - 当前 helper 的稳定 contract 是“输出可复制的 markdown 骨架到控制台”
      - 现在输出不只包含 A-H 条目，还会先给出一段 run metadata 和关键命令，便于直接把这份骨架拿去做人工回填
    - 若需要真正写入文件，建议由外层命令或调用方负责落盘，不要假设 helper 自己会创建 record 文件
  - 如需验证 starter 的 `record_seed_output_path + render_record_seed_to_file` 这条半自动链是否可用，可使用：
    ```powershell
    powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 `
      -InputJsonPath .\.tmp\dialogue-manual-18234.json
    ```
    - 当前 probe 会验证骨架 `.md` 是否能生成，并检查：
      - `## Run Metadata`
      - `## Useful Commands`
      - `- Base URL:`
      - `- Result JSON:`
      - `- Completion Gate:`
      - A/H 节
      - Entry URL
    - probe 输出里还会附带 `preview`，因此后续不必再单独打开 `.md` 才能确认首段内容
    - 它不等于真实人工验收，也不会自动更新正式 acceptance record
  - 当前 starter **不会**自动写 acceptance record 草稿；它只返回 `record_suggestion`
    - 但当前 starter 现在会自动把完整返回 JSON 同步写到 `manual_acceptance.result_json_path`
    - 同时还会尝试自动生成一份未勾选的 A-H markdown 骨架到 `manual_acceptance.record_seed_output_path`
      - 成功与否会 reflected 在：
        - `manual_acceptance.record_seed_generated`
        - `manual_acceptance.record_seed_error`
      - 若生成成功，starter 当前还会直接内嵌一份 `manual_acceptance.record_seed_probe`
        - 含 `output_path / bytes / has_section_a / has_section_h / has_entry_urls / preview`
    - 因此 `render_record_seed*` / `probe_record_seed_output` 这组命令不再依赖调用方额外先做 stdout 重定向
    - 建议把这条路径作为当天记录文件名
    - 再按模板手动创建 / 回填
    - 这样可以避免把未实际执行的 Java/Node 测试或 A-H 手工验收误写成已完成
  - 若显式加 `-RunBrowserProbes`，当前 starter 会默认把 browser probe 截图直接落到
    `manual_acceptance.recommended_screenshot_dir`
    - 返回 JSON 中也会带回：
      - `manual_acceptance.browser_probe_screenshot_dir`
      - `browser_probe.screenshot_dir`
      - 各路径的 `screenshot_path`
  - 若显式传 `-NoOpenBrowser`，默认会在 probes 结束后自动退出 harness
  - 若允许脚本直接打开浏览器，当前脚本会自动保留 harness；也可显式追加 `-KeepHarnessRunning`
  - `manual_acceptance.scripted_probe_guidance` 当前应按写实口径理解：
    - scripted browser evidence 请优先按 `chat` / `responses` **分开**跑
    - 不要把 `BrowserProbeSurface=both` 当作稳定默认 gate
- 这两支 probe 默认只跑 deterministic 的 `message_only + manual-start task_required`，避免在本地未配置可用 LLM/provider 时因为真实执行链卡住；如果要覆盖 auto-start，请显式给 `Run-ChatFacadeAcceptanceProbe.ps1` 传 `-AutoStartTask`
- `Run-ChatFacadeAcceptanceProbe.ps1` 不会帮你启动 harness，它假设本地 `http://localhost:8080` 已经有可用服务
- probe 现在会先检查 `/v1/models`
  - 如果 `/api/v1/health` 正常但 `/v1/models` 失败或返回 `404`，会明确提示你连到的是 stale / non-shaded 旧实例
  - 例如把 probe 指到旧的 `18080` 实例时，当前就会直接给出这条 stale-instance 诊断
  - 如果连 `/api/v1/health` 都打不通，则会明确提示当前 harness 根本没启动

---

## 5. 当前未覆盖的剩余项

这份 runbook 也明确当前还**没有**完成什么：

- 没有真正的 token-level streaming 验收
- 没有**完整** `/v1/responses` 路径验收；当前已补到“前端可选发送面 + client/parser smoke + 后端 HTTP contract”，但还缺真实页面手工点验
- 没有真实浏览器自动化，只是“可执行手工 runbook + 现有 smoke/HTTP contract”

也就是说，当前更接近：

- **Phase 5/6 已基本实现并有较强自动化证据**
- **但最终 completion 仍需至少一轮真实页面手工验收**

补充说明：

- `task_brief / task_followup` 现在不是永久停留在 task-free session message
- 当 façade 成功创建 task / child task 后，会把对应 user turn 回填 `task_id`
- 这样 `related_messages`、task-bound session view 和 continuity replay 读到的是同一条真正绑定到 task 的消息，而不是需要 UI 自己再猜归属的镜像记录
- 若在执行 acceptance helper / browser probe / record-seed helper 时，宿主机出现：
  - `Starting the CLR failed with HRESULT 80004005`
  - `0x800705AF` / `The paging file is too small for this operation to complete`
  - PowerShell 子进程刚启动就退出
  这应优先视为**本机验收环境问题**，而不是 `/dialogue/` 或 façade contract 本身的产品回归。
  当前建议处理顺序：
  1. 清理并发的旧 harness / browser probe 进程
  2. 重新打开一个新的 shell
  3. 检查主机分页文件 / 可用内存
  4. 再重跑 `Start-DialogueChatFacadeManualAcceptance.ps1` 或相关 probe
