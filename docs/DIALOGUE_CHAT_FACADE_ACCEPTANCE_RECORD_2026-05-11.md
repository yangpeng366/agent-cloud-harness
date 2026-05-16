# `/dialogue/` Chat-First + Chat Facade 验收记录

基于模板：

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md)

对应 runbook：

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)

---

## 1. 基本信息

- 日期：2026-05-11
- 执行人：Codex
- 分支 / 提交：`master / 5adc42f2c9a2f87011aad33a46aff983943f1268`
- 运行环境：
  - Java：`C:\Program Files\Java\jdk-21.0.9+10`
  - local harness acceptance port：`18196`
  - façade surfaces：`chat_completions`、`responses`
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -Port 18196`

---

## 2. 自动化证据

### 2.1 Local Harness Runner

- [x] `dialogue_shell_probe` 通过
- [x] `chat_probe` 通过
- [x] `responses_probe` 通过

输出摘要：

```json
{
  "base_url": "http://localhost:18196",
  "dialogue_shell_probe": {
    "shell": "dialogue",
    "base_url": "http://localhost:18196",
    "html_status": 200,
    "js_status": 200,
    "transcript_first": true,
    "details_toggle_present": true,
    "primary_composer_modes": ["auto", "task"],
    "followup_mode_hidden": true
  },
  "chat_probe": {
    "surface": "chat_completions",
    "session_id": "session_409d94f573224219",
    "message_reply_type": "chat_reply",
    "task_id": "task_af894c344bcb4751",
    "task_reply_type": "task_receipt",
    "task_status": "active",
    "task_auto_start": false,
    "live_flow_available": true
  },
  "responses_probe": {
    "surface": "responses",
    "session_id": "session_49ec2da65e28464d",
    "message_reply_type": "chat_reply",
    "task_id": "task_dac240e6a3ac4d98",
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

结果摘要：

```text
已于 2026-05-14 重跑：
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'
结果：通过。
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

结果摘要：

```text
已于 2026-05-14 重跑：
node --test src/test/js/dialogue-shell-markup-plan.test.mjs src/test/js/dialogue-composer-markup-plan.test.mjs src/test/js/dialogue-composer-plan.test.mjs src/test/js/dialogue-composer-request-plan.test.mjs src/test/js/dialogue-facade-surface-plan.test.mjs src/test/js/dialogue-facade-client-plan.test.mjs src/test/js/dialogue-facade-response-plan.test.mjs src/test/js/dialogue-facade-stream-plan.test.mjs src/test/js/dialogue-facade-reply-kind.test.mjs src/test/js/dialogue-facade-reply-plan.test.mjs src/test/js/dialogue-facade-reply-highlight-plan.test.mjs src/test/js/dialogue-facade-reply-ui-consistency.test.mjs src/test/js/dialogue-phase6-path-matrix.test.mjs src/test/js/dialogue-responses-path-matrix.test.mjs
结果：57/57 通过。
```

### 2.4 手工验收入口脚本验证

- [x] `Start-DialogueChatFacadeManualAcceptance.ps1` 当前主路径稳定可用
  - `base_url = http://localhost:18196`
  - `dialogue_url = http://localhost:18196/dialogue/`
  - `responses_dialogue_url = http://localhost:18196/dialogue/#facade=responses`
  - `harness_kept_running = false`
  - `harness_keep_reason = auto_shutdown_after_probes`
  - 返回中包含：
    - `manual_acceptance.recommended_order`
    - `manual_acceptance.entry_points`
    - `manual_acceptance.scripted_probe_guidance`
    - `record_suggestion = docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

### 2.5 可选 Browser Probe

- [ ] 本次 starter 调用未执行 browser probe
- 说明：
  - `browser_probe = null`
  - `browser_probe_surface = null`
  - scripted browser evidence 仍以既有记录和 runbook 为准

### 2.6 补充 Scripted Browser 取证

- [x] 已有一轮独立 live harness 的 `chat` surface scripted browser 取证
- 取证环境：
  - base URL：`http://localhost:18206`
  - surface：`chat`
  - screenshot dir：`D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18206`
  - probe JSON：`.tmp/dialogue-browser-probe-18206.json`
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18206 -StdOutPath .tmp\server-18206.out.log -StdErrPath .tmp\server-18206.err.log`
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18206 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-18206`

取证摘要：

```text
message_only:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-message-only.png

stream_fallback:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-stream-fallback.png
- request_count_delta = 1
- request_url = /v1/chat/completions
- response_content_type = text/event-stream

task_required auto-start:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-auto-start-task.png
- selected_task_id = task_e70efcd052074b97
- hash = #session=session_beadcdbc7f9c443e&task=task_e70efcd052074b97

manual-start task:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-manual-start-task.png
- selected_task_id = task_c5d72d87b04f44f6

task note attach:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-task-note-attach.png
- task_note_message_type = task_note

manual-start continuity:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-manual-start-continuity.png
- continuity_message_type = task_note
- continuity_task_mode = task_required
- continuity_auto_start = false

follow-up + manual-start:
- screenshot: .tmp/dialogue-browser-screens-18206/chat-followup-manual-start.png
- latest_task_id = task_ee78e99f40ce4be5
- child_parent_task_id = task_c5d72d87b04f44f6
```

边界说明：

```text
这组 scripted browser PNG/JSON 只能作为第 3 节 A-H 路径的辅助证据，不等于 2026-05-11 当天已经完成真实人工手点。
因此第 3 节 A-H 勾选状态保持不变。
```

- [x] 已有一轮独立 live harness 的 `responses` surface scripted browser 取证
- 取证环境：
  - base URL：`http://localhost:18210`
  - surface：`responses`
  - screenshot dir：`D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18210`
  - probe JSON：`.tmp/dialogue-browser-probe-18210.json`
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18210 -StdOutPath .tmp\server-18210.out.log -StdErrPath .tmp\server-18210.err.log`
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18210 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-18210`

取证摘要：

```text
message_only:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-message-only.png

stream_fallback:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-stream-fallback.png
- request_count_delta = 1
- request_url = /v1/responses
- response_content_type = text/event-stream

task_required auto-start:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-auto-start-task.png
- selected_task_id = task_e0aa4e2e3698457a
- hash = #session=session_01582f5cc23546d7&task=task_e0aa4e2e3698457a&facade=responses

manual-start task:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-manual-start-task.png
- selected_task_id = task_ed2f5b2b5fb34bad

task note attach:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-task-note-attach.png
- task_note_message_type = task_note

manual-start continuity:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-manual-start-continuity.png
- continuity_message_type = task_note
- continuity_task_mode = task_required
- continuity_auto_start = false

follow-up + manual-start:
- screenshot: .tmp/dialogue-browser-screens-18210/responses-followup-manual-start.png
- latest_task_id = task_ae351a6652f944f5
- child_parent_task_id = task_ed2f5b2b5fb34bad
```

边界说明：

```text
这组 responses surface 的 scripted browser PNG/JSON 同样只属于第 3 节 A-H 的辅助证据。
它证明 `/dialogue/#facade=responses` 的 richer browser path 已有 live 取证，但不等于 2026-05-11 当天已完成真实人工逐条手点。
因此第 3 节 A-H 勾选状态继续保持不变。
```

### 2.6.1 2026-05-12 串行 Layer E 预检复验

- [x] 已补一轮更接近 GitHub release gate 的串行 richer browser acceptance 预检
- 说明：
  - 这轮不再把 `chat` 和 `responses` 并发压在同一实例上
  - 改为“单 surface + fresh isolated harness”串行取证
  - 对应预检记录：
    - `docs/DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`

- [x] `chat` surface fresh 单实例 richer browser probe 已通过
  - base URL：`http://localhost:18338`
  - 相关命令：
    - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18338 -StdOutPath .tmp\server-18338.out.log -StdErrPath .tmp\server-18338.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18338.db')`
    - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18338 -Surface chat`
  - 覆盖路径：
    - `message_only`
    - `stream_fallback`
    - `auto_start_task`
    - `manual_start_task`
    - `task_note_attach`
    - `manual_start_continuity`
    - `followup_manual_start`

- [x] `responses` surface fresh 单实例 richer browser probe 已通过
  - base URL：`http://localhost:18340`
  - 相关命令：
    - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18340 -StdOutPath .tmp\server-18340.out.log -StdErrPath .tmp\server-18340.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18340.db')`
    - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18340 -Surface responses`
  - 覆盖路径：
    - `message_only`
    - `stream_fallback`
    - `auto_start_task`
    - `manual_start_task`
    - `task_note_attach`
    - `manual_start_continuity`
    - `followup_manual_start`

边界说明：

```text
这轮 `18338 / 18340` 串行预检把 Layer E scripted browser evidence 从“已有辅助 PNG/JSON”推进成了
“fresh isolated harness + single-surface richer browser acceptance” 绿灯。
但它仍然只是第 3 节 A-H 的自动化辅助证据，不等于 2026-05-11 当天已经完成真实人工逐条手点。
因此第 3 节 A-H 勾选状态继续保持不变。
```

- [x] real `8080` richer browser probe re-validated after task_auto/continuity contract alignment
  - 相关命令：
    - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-8080-chat`
    - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-8080-responses`
  - 运行结果摘要：
    - `chat` surface：通过
    - `responses` surface：通过
    - `stream_fallback`：两边都保持
      - `request_count_delta = 1`
      - `response_content_type = text/event-stream`
      - `override_mode = same_response_json_fallback`
    - `manual_start_continuity`：两边都保持
      - `inline_ack = 最近回执：已写入当前任务上下文。当前选中 task 可作为下一轮 follow-up 起点。`
      - `continuity_message_type = task_note`
      - `continuity_task_mode = task_required`
      - `continuity_auto_start = false`
  - 证据截图：
    - `chat-stream-fallback.png`
    - `chat-manual-start-continuity.png`
    - `responses-stream-fallback.png`
    - `responses-manual-start-continuity.png`

补充说明：

```text
这轮 real 8080 复验不是替代 18338 / 18340 的 isolated precheck，而是补了一层“真实长期运行实例”证据。
它同时证明了两件事：
1. richer browser probe 现已和当前默认 task_auto / continuity contract 对齐
2. continue-current / manual-start continuity 的前端 task-mode regression 已收口
```

### 2.7 Starter 自动 Browser Probe 取证

- [x] 已验证 `Start-DialogueChatFacadeManualAcceptance.ps1` 在启用 browser probe 时，会把截图自动落到 starter 返回的推荐目录
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -RunBrowserProbes -BrowserProbeSurface chat -Port 18222`

输出摘要：

```text
manual_acceptance.recommended_screenshot_dir =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18222

manual_acceptance.browser_probe_screenshot_dir =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18222

browser_probe.screenshot_dir =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18222

manual_acceptance.recommended_order[*].candidate_pngs 已和上述目录保持一致，例如：
- A -> chat-message-only.png
- C -> chat-auto-start-task.png
- F -> chat-stream-fallback.png
- H -> responses-auto-start-task.png

另一次 starter 实跑（`18224`）已确认 `manual_acceptance.command_examples` 也会直接给出可复制命令，例如：
- `keep_running`
- `chat_browser_probe`
- `responses_browser_probe`

再下一次 starter 实跑（`18226`）已确认 `manual_acceptance.record_seed` 也会直接给出 A-H 八条记录骨架所需的最小结构：
- `id`
- `label`
- `entry_url`
- `candidate_pngs`
```

补充说明：

```text
这层验证说明 starter 现在已经不只是“告诉你去哪儿截图”，而是能在 -RunBrowserProbes 时把 scripted browser PNG 直接落到
manual_acceptance.recommended_screenshot_dir。
但这仍然只属于辅助证据自动化，不等于第 3 节 A-H 八条路径已经完成真实人工手点。
```

### 2.8 Record Seed Renderer

- [x] 已验证 `Render-DialogueAcceptanceRecordSeed.ps1` 能从 starter JSON 生成可复制的 A-H markdown 骨架
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath .\.tmp\dialogue-manual-18228.json`

输出摘要：

```text
控制台已成功输出：
- A-H 八条 skeleton heading
- Entry URL
- Candidate PNG

辅助落盘验证：
- .tmp\dialogue-record-seed-18228.md

当前稳定 contract：
- helper 负责把 markdown skeleton 输出到控制台
- 若需要真正写入文件，建议由外层命令或编辑器负责保存

再下一次 starter 实跑（`18230`）已确认 `manual_acceptance.command_examples`
现在会直接带回：
- `render_record_seed`

其真实输出示例为：
- `powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath ".tmp\dialogue-manual-18230.json"`

再下一次 starter 实跑（`18232`）已确认 `manual_acceptance.command_examples`
现在还会直接带回：
- `render_record_seed_to_file`

其真实输出示例为：
- `powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath ".tmp\dialogue-manual-18232.json" > ".tmp\dialogue-record-seed-18232.md"`

再下一次 starter 实跑（`18234`）已确认：
- `manual_acceptance.record_seed_output_path`
- `manual_acceptance.command_examples.render_record_seed_to_file`

两者现在能真实对上：
- `record_seed_output_path = D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18234.md`
- 按返回命令执行后，`.tmp\dialogue-record-seed-18234.md` 已成功生成

再下一次 starter 实跑（`18236`）已确认 `manual_acceptance.command_examples`
现在还会直接带回：
- `probe_record_seed_output`

其真实输出示例为：
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath ".tmp\dialogue-manual-18236.json"`
```

补充说明：

```text
这层验证说明 starter 的 manual_acceptance.record_seed 现在已经足够被一个很薄的 renderer 消费，
后续人工验收时不必再手抄 A-H 骨架。
但这依然只属于“人工验收准备”能力，不等于第 3 节 A-H 八条路径已经完成真实手点。
```

### 2.9 Record Seed Output Probe

- [x] 已验证 `Run-DialogueRecordSeedProbe.ps1` 能检查 starter 的 `record_seed_output_path + render_record_seed_to_file` 半自动链
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18234.json`

输出摘要：

```text
input_json_path = D:\gitAll\agent-cloud-harness\.tmp\dialogue-manual-18234.json
output_path = D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18234.md
bytes = 5716
has_section_a = true
has_section_h = true
has_entry_urls = true
```

同一条 probe 在 `18240` 的复验里还补充确认了：

```text
preview[0] = > Prefilled A-H record skeleton from starter JSON. Keep all items unchecked until a real manual browser check is done.
preview[2] = ### A. message_only
preview[5] = - Entry URL: http://localhost:18240/dialogue/
```

补充说明：

```text
这层 probe 证明 starter 返回的 record_seed_output_path 和 render_record_seed_to_file 现在是能真实配合工作的，
而不是只停留在文档或命令字符串层面。
同时，probe 自己现在也会带回首段 preview，因此不必再依赖后续单独打开 `.md` 文件才能确认骨架内容。
后续这条 probe 还被增强为显式检查：
- `## Run Metadata`
- `## Useful Commands`
- `Base URL / Result JSON / Completion Gate`
- A/H 节与 Entry URL

但它验证的仍然只是“人工验收准备链”，不等于第 3 节 A-H 八条路径已经做完真实手点。
另外，preview[2] 这里保留的是当时旧 starter 产物的原样标签；当前人工验收路径口径已经改成 section A = default task_auto。
```

### 2.10 Starter Auto-Writes Result JSON And Record Seed

- [x] 已验证 starter 现在会自动落：
  - `manual_acceptance.result_json_path`
  - `manual_acceptance.record_seed_output_path`
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -Port 18242`

输出摘要：

```text
manual_acceptance.result_json_path =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-manual-18242.json

manual_acceptance.record_seed_output_path =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18242.md

manual_acceptance.record_seed_generated = true
manual_acceptance.record_seed_error = null
```

辅助取证：

```text
Get-Item:
- .tmp\dialogue-manual-18242.json
- .tmp\dialogue-record-seed-18242.md

骨架首段：
> Prefilled A-H record skeleton from starter JSON. Keep all items unchecked until a real manual browser check is done.
### A. message_only
- [ ] Passed
```

补充说明：

```text
这层验证说明 starter 现在已经能在一次运行里，同时产出：
- 完整返回 JSON
- 一份未勾选的 A-H markdown 骨架

也就是说，后续人工验收可以直接从生成好的 `.md` 骨架开始填。
但它仍然只是“人工验收准备”收口，不等于第 3 节 A-H 八条路径已经完成真实手点。
```

再下一次 starter 实跑（`18244`）已确认：
- 若 `record_seed_generated=true`
- starter 返回值里还会直接内嵌 `manual_acceptance.record_seed_probe`

其真实摘要为：

```text
record_seed_probe.output_path =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18244.md
record_seed_probe.bytes = 9392
record_seed_probe.has_section_a = true
record_seed_probe.has_section_h = true
record_seed_probe.has_entry_urls = true
record_seed_probe.preview[0] = > Prefilled A-H record skeleton from starter JSON. Keep all items unchecked until a real manual browser check is done.
record_seed_probe.preview[2] = ## Run Metadata
record_seed_probe.preview[4] = - Base URL: http://localhost:18244
```

补充说明：

```text
当前 Render-DialogueAcceptanceRecordSeed.ps1 的输出已不再只有 A-H 条目。
它现在会先给出 run metadata：
- base_url
- dialogue_url
- responses_dialogue_url
- result_json_path
- record_seed_output_path
- recommended_screenshot_dir
- completion_gate

同时还会把 keep_running / chat_browser_probe / responses_browser_probe / probe_record_seed_output
一起收进同一份 markdown 头部，因此这份 record seed 已经更接近“可直接拿去做人工回填”的骨架，而不只是路径列表。
这仍然只是人工验收准备证据，不等于第 3 节 A-H 已经完成真实人工手点。
```

再下一次 starter 实跑（`18246`）已确认：
- starter 自动内嵌的 `record_seed_probe` 现在不只验证旧的 `A/H + Entry URL`
- 还会显式返回：
  - `has_run_metadata = true`
  - `has_useful_commands = true`
  - `has_base_url = true`
  - `has_result_json = true`
  - `has_completion_gate = true`

其真实摘要为：

```text
record_seed_probe.output_path =
  D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18246.md
record_seed_probe.bytes = 9654
record_seed_probe.has_run_metadata = true
record_seed_probe.has_useful_commands = true
record_seed_probe.has_base_url = true
record_seed_probe.has_result_json = true
record_seed_probe.has_completion_gate = true
record_seed_probe.has_section_a = true
record_seed_probe.has_section_h = true
record_seed_probe.has_entry_urls = true
record_seed_probe.preview[2] = ## Run Metadata
record_seed markdown now also includes:
- Has Run Metadata: True
- Has Useful Commands: True
- Has Completion Gate: True
```

这证明当前半自动骨架链已经不仅能“生成 A-H 列表”，而且会把同一次 starter run 的关键入口和命令一起带上，
更接近可直接拿去做真实人工回填的工作底稿。

继续沿这条链往前推进后，当前还新增了一层更完整的 draft：

```text
Run-DialogueRecordDraftProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18246.json
=> .tmp\dialogue-record-draft-18246.md 已生成
=> probe 返回：
- has_title = true
- has_automation_section = true
- has_local_harness_section = true
- has_manual_acceptance_section = true
- has_section_a = true
- has_section_h = true
- has_conclusion_section = true
- has_final_gate = true
```

这说明当前 starter 准备链已经不只会给一个 A-H seed，还能半自动拼出一份更接近正式 acceptance record 的 scratch pad；
后续人工主要补 A-H 手点结果即可，不必再重复手拼 Local Harness Runner 区块。
但这份 draft 仍然只是辅助草稿，不等于第 3 节 A-H 已完成真实人工手点。

---

## 3. 真实页面路径验收

> 本次记录只回填今天真实重跑的 starter 入口证据，不把未实际手点的路径伪装成已完成。
>
> 若后续在这份记录上继续补真实人工手点，可优先复用当前已归档的 scripted browser PNG 对照：
> - A `default task_auto`
>   - `.tmp/dialogue-browser-screens-18206/chat-default-task-auto.png`
> - B `message_only + task_id`
>   - `.tmp/dialogue-browser-screens-18206/chat-task-note-attach.png`
>   - `.tmp/dialogue-browser-screens-18210/responses-task-note-attach.png`
> - C `task_required`
>   - `.tmp/dialogue-browser-screens-18206/chat-auto-start-task.png`
>   - `.tmp/dialogue-browser-screens-18210/responses-auto-start-task.png`
> - D `follow-up + manual-start`
>   - `.tmp/dialogue-browser-screens-18206/chat-followup-manual-start.png`
>   - `.tmp/dialogue-browser-screens-18210/responses-followup-manual-start.png`
> - E `manual-start continuity`
>   - `.tmp/dialogue-browser-screens-18206/chat-manual-start-continuity.png`
>   - `.tmp/dialogue-browser-screens-18210/responses-manual-start-continuity.png`
> - F `stream fallback`
>   - `.tmp/dialogue-browser-screens-18206/chat-stream-fallback.png`
>   - `.tmp/dialogue-browser-screens-18210/responses-stream-fallback.png`
> - G `#facade=responses + message_only`
>   - `.tmp/dialogue-browser-screens-18210/responses-message-only.png`
> - H `#facade=responses + task_required`
>   - `.tmp/dialogue-browser-screens-18210/responses-auto-start-task.png`
>
> 注意：这些 PNG 仍然只是辅助证据，不等于路径已经通过；只有真实人工手点后才能修改下面勾选状态。

### A. `default task_auto`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-default-task-auto.png`

### B. `message_only + task_id`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-task-note-attach.png`

### C. `task_required`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-auto-start-task.png`

### D. `follow-up + manual-start`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-followup-manual-start.png`

### E. `manual-start continuity`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-manual-start-continuity.png`

### F. `stream fallback`

- [ ] 通过
- 页面入口：`/dialogue/`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18206/chat-stream-fallback.png`

### G. `#facade=responses + message_only`

- [ ] 通过
- 页面入口：`/dialogue/#facade=responses`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18210/responses-message-only.png`

### H. `#facade=responses + task_required`

- [ ] 通过
- 页面入口：`/dialogue/#facade=responses`
- 备注：本次未做真实页面手点；若后续补点验，可优先挂 `.tmp/dialogue-browser-screens-18210/responses-auto-start-task.png`

---

## 4. 缺口与结论

### 4.1 未覆盖项

- [x] token-level streaming 仍未验收
- [x] 完整 `/v1/responses` item/tool-call surface 仍未验收
- [x] A-H 八条真实页面路径尚未逐条手工点验

### 4.2 最终判断

- [x] 本轮只补了新的 starter 入口实跑证据与当天记录
- [x] 本轮仍缺真实页面验收
- [ ] 本轮真实页面验收已完成，可作为 Phase 5/6 completion evidence

补充说明：

```text
当前最强的自动化、HTTP、scripted browser 证据，除了 2026-05-10 的累计记录和 runbook 外，
现在还包括 2026-05-12 的串行 Layer E 预检：
- `18338` fresh `chat` single-surface richer browser probe
- `18340` fresh `responses` single-surface richer browser probe

今天这份 2026-05-11 记录累计新增了两层现实确认：
- starter 主路径当前仍稳定输出 manual_acceptance / record_suggestion / chat+responses deterministic probes
- Layer E richer browser acceptance 在“单 surface + fresh isolated harness”的串行模式下已可稳定通过

但这不替代 runbook 第 3 节 A-H 八条真实人工手点，因此目标仍未完成。

补充说明：
在继续沿这条“人工验收准备链”推进时，当前宿主机还出现过环境级 PowerShell/CLR 启动失败：
- `Starting the CLR failed with HRESULT 80004005`
- `0x800705AF`
- `The paging file is too small for this operation to complete`

这类现象当前应优先归类为**本机验收环境问题**，而不是 `/dialogue` 或 façade contract 本身的产品回归。
因此若后续某一轮 helper / probe 因这类错误中断，不应把它直接记成 UI/contract regression，而应先排查宿主机内存、分页文件和旧进程残留。
```
