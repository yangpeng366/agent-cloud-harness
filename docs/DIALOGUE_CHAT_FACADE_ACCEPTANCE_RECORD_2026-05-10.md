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
    "primary_composer_modes": ["auto", "message", "task"],
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

---

## 3. 真实页面路径验收

> 本次记录只回填自动化与 live probe 证据，不把未实际手点的页面路径伪装成已完成。

### A. `message_only`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### B. `message_only + task_id`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### C. `task_required`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### D. `follow-up + manual-start`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### E. `manual-start continuity`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### F. `stream fallback`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### G. `#facade=responses + message_only`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

### H. `#facade=responses + task_required`

- [ ] 通过
- 备注：仅有自动化与 probe 证据；未做真实页面点验

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
