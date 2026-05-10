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
  - 若显式传 `-NoOpenBrowser`，默认会在 probes 结束后自动退出 harness
  - 若允许脚本直接打开浏览器，当前脚本会自动保留 harness；也可显式追加 `-KeepHarnessRunning`
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
