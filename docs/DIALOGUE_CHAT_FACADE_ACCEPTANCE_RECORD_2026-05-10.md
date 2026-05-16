# `/dialogue/` Chat-First + Chat Facade 验收记录

基于模板：

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md)

对应 runbook：

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)

---

## 1. 基本信息

- 日期：2026-05-10
- 执行人：Codex
- 分支 / 提交：`cdba554a84bf9618c8042661330552d2fde63ea2`
- 运行环境：
  - Java：`C:\Program Files\Java\jdk-21.0.9+10`
  - local harness acceptance port：`18128`
  - façade surfaces：`chat_completions`、`responses`

---

## 2. 自动化证据

### 2.1 Local Harness Runner

- [x] `dialogue_shell_probe` 通过
- [x] `chat_probe` 通过
- [x] `responses_probe` 通过

输出摘要：

```json
{
  "base_url": "http://localhost:18128",
  "dialogue_shell_probe": {
    "shell": "dialogue",
    "base_url": "http://localhost:18128",
    "html_status": 200,
    "js_status": 200,
    "transcript_first": true,
    "details_toggle_present": true,
    "primary_composer_modes": ["auto", "task"],
    "followup_mode_hidden": true
  },
  "chat_probe": {
    "surface": "chat_completions",
    "session_id": "session_d4a6e9f094964488",
    "message_reply_type": "chat_reply",
    "task_id": "task_27f87df616e94d80",
    "task_reply_type": "task_receipt",
    "task_status": "active",
    "task_auto_start": false,
    "live_flow_available": true
  },
  "responses_probe": {
    "surface": "responses",
    "session_id": "session_bf98cb34dd9140ce",
    "message_reply_type": "chat_reply",
    "task_id": "task_050e0aa8bc044895",
    "task_reply_type": "task_receipt",
    "task_status": "active",
    "task_auto_start": false,
    "live_flow_available": true
  }
}
```

### 2.2 Java HTTP / Handler 合同

- [x] `WebConsoleHandlerHttpTest`
- [x] `ChatFacadeHandlerHttpTest`

命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'
```

结果摘要：

```text
通过。/dialogue/ shell、/dialogue/app.js、/v1/chat/completions、/v1/responses 的核心 handler contract 均为 green。
```

### 2.3 前端 smoke

- [x] `dialogue-shell-markup-plan.test.mjs`
- [x] `dialogue-composer-markup-plan.test.mjs`
- [x] `dialogue-composer-plan.test.mjs`
- [x] `dialogue-composer-request-plan.test.mjs`
- [x] `dialogue-facade-surface-plan.test.mjs`
- [x] `dialogue-facade-client-plan.test.mjs`
- [x] `dialogue-facade-response-plan.test.mjs`
- [x] `dialogue-facade-stream-plan.test.mjs`
- [x] `dialogue-facade-reply-kind.test.mjs`
- [x] `dialogue-facade-reply-plan.test.mjs`
- [x] `dialogue-facade-reply-highlight-plan.test.mjs`
- [x] `dialogue-facade-reply-ui-consistency.test.mjs`
- [x] `dialogue-phase6-path-matrix.test.mjs`
- [x] `dialogue-responses-path-matrix.test.mjs`

命令：

```powershell
node --test src\test\js\dialogue-shell-markup-plan.test.mjs `
  src\test\js\dialogue-composer-markup-plan.test.mjs `
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
```

结果摘要：

```text
通过。共 52 个 Node test 全绿。
```

### 2.4 手工验收入口脚本验证

- [x] `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -Port 18130`
  - 返回完整 JSON：`base_url / dialogue_url / responses_dialogue_url / runbook / record_template / dialogue_shell_probe / chat_probe / responses_probe`
  - `harness_kept_running=false`
  - `harness_keep_reason=auto_shutdown_after_probes`
  - probes 结束后 `18130` 端口已释放，无残留 harness 进程

- [x] `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -KeepHarnessRunning -Port 18134`
  - probes 正常通过
  - `18134` 端口在脚本退出后仍保持监听
  - 证明该脚本可作为后续真实浏览器 A-H 八条路径的本地入口

### 2.5 Deterministic Live Path Matrix

- [x] `Run-ChatFacadePathMatrixProbe.ps1 -BaseUrl http://localhost:18136`
- [x] `Run-ChatFacadePathMatrixProbe.ps1 -BaseUrl http://localhost:18136 -UseResponsesSurface`

结果摘要：

```text
通过。两条 surface 都真实覆盖了：
- message_only 不物化 task
- manual-start task -> task_receipt -> control_node=intake
- message_only + task_id -> chat_reply/session_ack，且仍绑定原 task
- task_auto + auto_start=false -> chat_reply/session_ack，且不推进 control node
- manual-start follow-up -> task_receipt，child.parent_task_id 正确
- session message stream 同时包含 user_note / task_brief / task_note / task_followup
```

补充说明：

```text
这层 probe 属于 deterministic live HTTP evidence，用来收紧 Phase 6 path matrix contract。
它不等于 A-H 八条真实页面路径手工验收，因此第 3 节的未勾选状态保持不变。
```

### 2.6 Browser-Level `/dialogue/` Probe

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18146`
  - 已真实跑通 `chat_completions` surface 下两条页面路径：
    - `message_only`
    - `task_required + auto_start=false`（manual-start task）
  - 运行结果摘要：
    - `message_only`：inline ack 为“已记录为会话消息”，task thread 仍为空
    - `manual-start task`：thread 中出现 `manual-start` task card，hash 带 `session=` 与 `task=`

- [x] 浏览器 probe 触发并定位了一个真实生产回归
  - 旧实现下，`/dialogue/app.js` 的 ESM imports 在真实浏览器里因静态资源白名单过窄而 404
  - 已通过 `WebConsoleHandler` 修复，并新增 `WebConsoleHandlerHttpTest.dialogueRouteServesImportedJavascriptModules()`

补充说明：

```text
这一层已经不再只是 shell/probe/HTTP contract，而是开始驱动真实 /dialogue/ 页面。
但当前只收住了 chat_completions surface 下的两条最小路径；responses surface 的浏览器级路径当时仍未单独回填到本记录的“真实页面路径”勾选项。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18150 -Surface responses`
  - 已真实跑通 `responses` surface 下两条最小页面路径：
    - `#facade=responses + message_only`
    - `#facade=responses + task_required + auto_start=false`
  - 运行结果摘要：
    - `message_only`：inline ack 为“已记录为会话消息”，task thread 仍为空
    - `manual-start task`：thread 中出现 `manual-start` task card，`selected_task_id` 命中，hash 已至少一次稳定带出 `session=...&task=...&facade=responses`
  - 这轮还顺手收口了一个真实 façade provenance 漏标：
    - 旧实现下，`/v1/responses` 真实页面路径虽然走的是 responses façade，但 session message / task receipt metadata 的 `request_path` 仍写成 `/v1/chat/completions`
    - 现已在 `ChatFacadeService` 修复，并由 `ChatFacadeHandlerHttpTest.postResponsesCreatesTaskRequiredResponseEnvelope()` 回归保护

补充说明：

```text
responses surface 的最小浏览器路径现在已经有真实页面证据，不再只是 HTTP / parser / request helper 级别。
但 A-H 八条真实页面路径仍然没有逐条人工点验，所以第 3 节仍保持未勾选状态。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18160 -Surface responses`
  - 在 fresh harness 上再次复验了 `responses` surface 的两条最小真实页面路径
  - 运行结果摘要：
    - `message_only`：inline ack 仍为“已记录为会话消息”，task thread 为空
    - `manual-start task`：`selected_task_id`、`task=` hash、composer inline receipt、detail title 现在可同时稳定收敛
  - 这轮还顺手收了两层技术结论：
    - `/dialogue/` 前端 task 选择新增 sticky selection，避免 façade 回包已带 `task_id` 时被中间一次 `loadTasks()` 刷新冲掉
    - 浏览器 probe 的 manual-start 判定已改成等待“active task + hash + inline receipt + detail title”全部到位，避免把正常异步收敛误判成 UI 缺陷

补充说明：

```text
此前观察到的 responses surface“hash/selected task/detail 未完全同步”，现已更准确归类为
probe 过早截断 + 前端过渡态收敛不足，而不是仍在持续复现的稳定生产缺陷。
在 18160 端口的 fresh harness 上，这两条 responses 最小页面路径已稳定通过。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18162 -Surface chat`
  - 在 fresh harness 上把 `chat_completions` surface 的浏览器级证据从“最小两条路径”推进到了四条：
    - `message_only`
    - `task_required + auto_start=false`
    - `message_only + task_id`（probe 结果里的 `task_note_attach`）
    - `follow-up + manual-start`（probe 结果里的 `followup_manual_start`）
  - 运行结果摘要：
    - `task_note_attach`：inline ack 为“已写入当前任务上下文”，`selected_task_id` 保持 parent task，message type 为 `task_note`
    - `followup_manual_start`：生成新的 child task，`child_parent_task_id` 指回上一轮 manual-start task，message type 为 `task_followup`

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18162 -Surface responses`
  - 在同一个 fresh harness 上把 `responses` surface 的浏览器级证据也推进到了四条：
    - `#facade=responses + message_only`
    - `#facade=responses + task_required + auto_start=false`
    - `#facade=responses + message_only + task_id`（`task_note_attach`）
    - `#facade=responses + follow-up + manual-start`（`followup_manual_start`）
  - 运行结果摘要：
    - `task_note_attach`：inline ack 为“已写入当前任务上下文”，`selected_task_id` 保持 parent task，message type 为 `task_note`
    - `followup_manual_start`：生成新的 child task，`child_parent_task_id` 指回 parent，message type 为 `task_followup`

补充说明：

```text
到 18162 这轮 fresh harness 为止，浏览器级 probe 已经把默认 chat surface 和 responses surface 都推进到了
“message_only / manual-start task / task note attach / manual-start follow-up” 四条真实页面路径证据。
但这仍然是 scripted browser probe，不等于 runbook 第 3 节 A-H 八条路径已经完成逐条人工点验，
因此下面勾选状态仍保持未完成。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18168 -Surface responses`
  - 在更新过的 `/dialogue/` advanced composer seam 上补跑了 `manual-start continuity`
  - 当前 richer browser path 已推进到五条：
    - `#facade=responses + message_only`
    - `#facade=responses + task_required + auto_start=false`
    - `#facade=responses + message_only + task_id`
    - `#facade=responses + task_required + task_id + auto_start=false`（`manual_start_continuity`）
    - `#facade=responses + follow-up + manual-start`
  - 运行结果摘要：
    - `manual_start_continuity.inline_ack` 为“已记录为会话消息”，保持 thread-first 反馈语义
    - `selected_task_id` 保持 parent task，不会物化新 task
    - `continuity_message_type = task_note`
    - `continuity_task_mode = task_required`
    - `continuity_auto_start = false`

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18168 -Surface chat`
  - 在同一轮 UI seam 更新后，默认 `chat_completions` surface 的 `manual-start continuity` 也已补成 scripted browser 证据
  - 运行结果摘要：
    - `manual_start_continuity` 同样保持当前 task 选中态
    - 不新增 task card
    - session message stream 中真实写成 `task_note`
    - 且 `metadata.task_mode = task_required`、`metadata.auto_start = false`

补充说明：

```text
到 18168 这轮更新为止，默认 chat surface 和 responses surface 的 scripted browser probe
都已经覆盖到五条 richer page path：
- message_only
- manual-start task
- task note attach
- manual-start continuity
- manual-start follow-up

其中新增的 manual-start continuity 不再只是 HTTP/path-matrix 证据；
现在已有真实 /dialogue/ 页面上的 advanced-only UI seam 与浏览器级证据。
但这仍然不等于 runbook 第 3 节 A-H 八条路径已经做完逐条人工手点，因此勾选状态保持未完成。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18168 -Surface chat`
  - 当前 scripted browser probe 已额外覆盖一条真实页面级 `stream fallback`
  - 运行结果摘要：
    - `stream_fallback.request_count_delta = 1`
    - `stream_fallback.request_url = /v1/chat/completions`
    - `stream_fallback.response_content_type = text/event-stream`
    - `stream_fallback.response_text_preview` 仍然是普通 JSON completion body
    - UI inline ack 保持“已记录为会话消息”

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18168 -Surface responses`
  - `responses` surface 下也补上了同一条 scripted browser `stream fallback`
  - 运行结果摘要：
    - `stream_fallback.request_count_delta = 1`
    - `stream_fallback.request_url = /v1/responses`
    - `stream_fallback.response_content_type = text/event-stream`
    - `stream_fallback.response_text_preview` 仍然是普通 JSON response body
    - UI inline ack 保持“已记录为会话消息”

补充说明：

```text
到这轮更新为止，路径 F `stream fallback` 已经不再只有 helper / parser / smoke 证据，
而是具备了两条 façade surface 下的 scripted browser 级真实页面证据：
- 页面内只发出一次 façade POST
- 返回头仍是 text/event-stream
- body 可以是普通 JSON completion / response
- /dialogue/ 会在同一次响应里完成 fallback 解析

但这仍然不等于 runbook 第 3 节路径 F 已经做完真实人工手点，所以勾选状态继续保持未完成。
```

- [x] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18178 -Surface responses`
  - 在 fresh harness 上把 `responses` surface 的 richer browser path 再复验了一轮，并顺手收口了 auto-start pending seam
  - 运行结果摘要：
    - `message_only` 仍保持 thread-only：`task_cards = 0`
    - `stream_fallback` 仍保持 single-request same-response fallback：`request_count_delta = 1`
    - `auto_start_task` 现在会稳定落到 pending seam：
      - `inline_ack = 最近回执：已提交任务，正在推进。当前选中 task 可作为下一轮 follow-up 起点。`
      - `task_cards = 1`
      - `selected_task_id` 与 `task=` hash 均已收敛
    - `manual_start_task / task_note_attach / manual_start_continuity / followup_manual_start` 也均通过
  - 这轮技术上新增了一条前端 seam：
    - `/dialogue/` 在“新建 auto-start task 且 façade 尚未返回最终 reply”时，会临时跟踪当前 session 的 task 列表
    - 一旦新 task 出现，即提前选中它，而不是必须等到 `applyChatFacadeCompletion(...)` 完整返回

- [ ] `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18178 -Surface chat`
  - 默认 `chat_completions` surface 在同一轮 fresh harness 上未稳定通过
  - 当前失败并非先由 `/dialogue/` UI seam 触发，而是 harness 进程在 probe 执行过程中 native OOM 退出
  - 运行时信号摘要：
    - 浏览器先见到 `net::ERR_INSUFFICIENT_RESOURCES`
    - 随后出现 `/v1/chat/completions` 与 `/api/v1/health` 的 `ERR_CONNECTION_REFUSED`
    - `server-18178.out.log` 最终落出：
      - `There is insufficient memory for the Java Runtime Environment to continue.`
      - `Native memory allocation (malloc) failed`
      - `hs_err_pid30452.log` / `replay_pid30452.log`
  - 结论：
    - 当前 `chat` surface 的 scripted browser richer path 在这轮 fresh harness 上被 JVM 内存问题打断
    - 因此它不能作为“已稳定通过”的 completion evidence 回填到第 3 节手工验收勾选

- [x] `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -KeepHarnessRunning -Port 18180`
  - acceptance harness 现在默认带 JVM 边界参数启动：
    - `-Xms128m -Xmx512m`
  - 在这个 fresh harness 上重新跑：
    - `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18180 -Surface chat`
  - 运行结果摘要：
    - `message_only / stream_fallback / auto_start_task / manual_start_task / task_note_attach / manual_start_continuity / followup_manual_start`
      七条 scripted browser path 全部通过
    - probe 结束后：
      - `GET /api/v1/health` 仍为 `status=up`
      - `dialogue-manual-acceptance.err.log` 为空
      - `dialogue-manual-acceptance.out.log` 未再出现 JVM native OOM

补充说明：

```text
这轮确认了此前 chat surface 的 richer browser path 阻塞主要是 acceptance harness 的 JVM 内存边界不现实，
而不是 /dialogue/ 或 chat façade contract 本身继续存在确定性逻辑缺口。
在给本地 acceptance harness 增加显式 -Xms128m -Xmx512m 后，
默认 chat surface 与 responses surface 的 richer scripted browser evidence 现在都可稳定收敛。
但这仍然不等于第 3 节 A-H 八条真实人工手点已经完成，因此下面勾选状态继续保持未完成。
```

- [x] `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -RunBrowserProbes -BrowserProbeSurface chat -Port 18180`
  - 说明：单独 `chat` browser probe 可作为 scripted browser evidence 入口

- [ ] `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -RunBrowserProbes -BrowserProbeSurface both -Port 18182`
  - 当前不作为稳定 green gate
  - 运行结果摘要：
    - shell probe / chat façade probe / responses façade probe 仍可先通过
    - 但当同一 fresh harness 上继续串跑 chat + responses richer browser probe 时，harness 仍可能被推到 JVM native OOM
    - 现象是：
      - 页面侧出现 `ERR_INSUFFICIENT_RESOURCES`
      - 随后 `/api/v1/health` 拒连
      - `dialogue-manual-acceptance.out.log` 落出 `hs_err_pid32944.log`

补充说明：

```text
当前 scripted browser evidence 已经足够强，但更合理的运行方式是“按 surface 分开跑”：
- chat: 单独跑
- responses: 单独跑

`both` 在同一 harness 内顺序串跑 richer browser path 仍会把压力叠加到 provider 执行与 probe 本身，
因此当前只把它当作探索性入口，不把它当成 completion 所需的稳定门槛。
```

---

## 3. 真实页面路径验收

> 本次记录只回填自动化与 live probe 证据，不把未实际手点的页面路径伪装成已完成。

### A. `default task_auto`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### B. `message_only + task_id`

- [ ] 通过
- 备注：已有 HTTP、live path matrix 与 browser probe 证据；未做真实页面手工点验

### C. `task_required`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### D. `follow-up + manual-start`

- [ ] 通过
- 备注：已有 HTTP、live path matrix 与 browser probe 证据；未做真实页面手工点验

### E. `manual-start continuity`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### F. `stream fallback`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### G. `#facade=responses + message_only`

- [ ] 通过
- 备注：已有 responses surface browser probe 证据；未做真实页面手工点验

### H. `#facade=responses + task_required`

- [ ] 通过
- 备注：已有 responses surface browser probe 证据；未做真实页面手工点验

---

## 4. 缺口与结论

### 4.1 未覆盖项

- [x] token-level streaming 仍未验收
- [x] 完整 `/v1/responses` item/tool-call surface 仍未验收
- [x] 8 条真实页面路径尚未逐条手工点验

### 4.2 最终判断

- [x] 本轮仅自动化通过，仍缺真实页面验收
- [ ] 本轮真实页面验收已完成，可作为 Phase 5/6 completion evidence

补充说明：

```text
当前 /dialogue/ shell、chat façade、responses façade 已具备较强自动化和 live probe 证据。
真正还未闭环的是 runbook 自身定义的 8 条真实页面路径手工点验，因此这条主线目前处于
“自动化与文档收口基本完成，但 completion 仍未达成”的状态。
```
