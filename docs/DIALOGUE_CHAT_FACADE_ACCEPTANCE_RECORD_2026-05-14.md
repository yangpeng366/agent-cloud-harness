# Dialogue Chat Facade Acceptance Record

> Prefilled draft from starter JSON. Keep any unchecked gate unchecked until the underlying verification is actually rerun or manually performed.

Based on template:

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md)

Runbook:

- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)

---

## 1. Basic Metadata

- Date: 2026-05-14
- Operator: Codex
- Branch / Commit: `master / 17b285a`
- Environment:
  - Java: `C:\Program Files\Java\jdk-21.0.9+10`
  - Port: `18276`
  - Facade surfaces: `chat_completions`, `responses`
- Commands:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -RunBrowserProbes -BrowserProbeSurface both -Port 18276`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueShellAcceptanceProbe.ps1 -BaseUrl http://localhost:8080`

---

## 2. Automation Evidence

### 2.1 Local Harness Runner

- [x] `dialogue_shell_probe` passed
- [x] `chat_probe` passed
- [x] `responses_probe` passed

Summary:

```json
{
    "base_url": "http://localhost:18276",
    "dialogue_shell_probe": {
        "shell": "dialogue",
        "base_url": "http://localhost:18276",
        "html_status": 200,
        "js_status": 200,
        "transcript_first": true,
        "details_toggle_present": true,
        "primary_composer_modes": ["auto", "task"],
        "followup_mode_hidden": true
    },
    "chat_probe": {
        "surface": "chat_completions",
        "session_id": "session_736a60ca4a254ea6",
        "message_reply_type": "chat_reply",
        "task_id": "task_eb74460b71e44575",
        "task_reply_type": "task_receipt",
        "task_status": "active",
        "task_auto_start": false,
        "live_flow_available": true
    },
    "responses_probe": {
        "surface": "responses",
        "session_id": "session_19dd2f514351452f",
        "message_reply_type": "chat_reply",
        "task_id": "task_25bba99129364bbc",
        "task_reply_type": "task_receipt",
        "task_status": "active",
        "task_auto_start": false,
        "live_flow_available": true
    }
}
```

### 2.2 Manual Acceptance Prep Chain

- [x] starter JSON saved: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-manual-18276.json`
- [x] A-H seed saved: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-seed-18276.md`
- [x] record draft saved: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-record-draft-18276.md`
- [x] record seed probe embedded
- [x] record draft probe embedded
- [x] starter probe embedded

Result summary:

```text
manual_acceptance.record_seed_generated = true
manual_acceptance.record_draft_generated = true
manual_acceptance.starter_probe.allow_both_in_one_run = true
manual_acceptance.starter_probe.browser_probe_surface = both

record_seed_probe:
- has_run_metadata = true
- has_useful_commands = true
- has_base_url = true
- has_result_json = true
- has_completion_gate = true
- has_section_a = true
- has_section_h = true
- has_entry_urls = true

record_draft_probe:
- has_title = true
- has_automation_section = true
- has_local_harness_section = true
- has_manual_acceptance_section = true
- has_section_a = true
- has_section_h = true
- has_conclusion_section = true
- has_final_gate = true

starter_probe:
- chat_surface_property_count = 9
- responses_surface_property_count = 9
- chat_png_count = 7
- responses_png_count = 7
```

Fresh draft rerender verification:

```text
Run-DialogueRecordDraftProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json
Result: passed.

Observed:
- .tmp\dialogue-record-draft-18276.md now writes embedded
  - record_seed_probe
  - record_draft_probe
  - starter_probe
  summaries directly into section 2.4
```

Fresh seed rerender verification:

```text
Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json
Result: passed.

Observed:
- .tmp\dialogue-record-seed-18276.md now writes `Starter aggregate probe`
- seed header no longer lags behind starter JSON `command_examples.probe_starter_output`
```

Path-label realignment rerun:

- fresh `18256` rerun also revalidated the prep renderers:
  - `Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18256.json`
  - `Run-DialogueRecordDraftProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18256.json`
- current stable contract is now:
  - record seed stays multiline
  - record draft stays multiline
  - each A-H path keeps exactly one `Path Note`
- fresh `18266` rerun also revalidated the starter-level unified prep bundle:
  - `Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -RunBrowserProbes -BrowserProbeSurface both -Port 18266`
  - current stable contract is now:
    - starter internally runs `chat` / `responses` browser probes serially
    - `browser_probe.chat_surface` and `browser_probe.responses_surface` are both non-empty
    - `.tmp\dialogue-browser-screens-18266\` contains both `chat-*.png` and `responses-*.png`
- fresh `18270` rerun additionally confirmed the returned starter guidance is now aligned too:
  - `manual_acceptance.scripted_probe_guidance.allow_both_in_one_run = true`
  - note now explicitly distinguishes raw `-Surface both` from starter-level aggregated `BrowserProbeSurface=both`
- fresh `18270` starter probe now also passes, and starter now embeds the same evidence:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18270.json`
  - `manual_acceptance.starter_probe.allow_both_in_one_run = true`
  - `manual_acceptance.starter_probe.browser_probe_surface = both`
  - `manual_acceptance.starter_probe.chat_surface_property_count = 9`
  - `manual_acceptance.starter_probe.responses_surface_property_count = 9`
  - `manual_acceptance.starter_probe.chat_png_count = 7`
  - `manual_acceptance.starter_probe.responses_png_count = 7`
- fresh `18276` rerun then confirmed the starter JSON / seed / draft synchronization seam is now closed too:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordDraftProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - current stable contract is now:
    - `manual_acceptance.record_seed_generated = true`
    - `manual_acceptance.record_draft_generated = true`
    - `manual_acceptance.record_draft_probe` is embedded in starter JSON
    - `manual_acceptance.starter_probe` is embedded in starter JSON
    - `.tmp\dialogue-record-draft-18276.md` writes embedded `record_seed_probe / record_draft_probe / starter_probe`
- fresh `18276` rerun now also confirmed the manual-backfill helper chain is usable:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - current stable contract is now:
    - `.tmp\dialogue-manual-backfill-18276.json` is generated
    - `paths[].passed / input / observed_result / notes` are the only required editable fields
    - `.tmp\dialogue-record-backfill-probe-18276.md` is updated by markdown merge helper
    - helper updates A-H path content only; it does not auto-close the final gate

```text
Fresh 18250 starter rerun on 2026-05-14:
powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -Port 18250

Observed:
- manual_acceptance.recommended_order[0].path = default task_auto
- manual_acceptance.record_seed[0].label = default task_auto
- .tmp\dialogue-record-seed-18250.md contains: ### A. default task_auto
- .tmp\dialogue-record-draft-18250.md contains: ### A. default task_auto
```

Screenshot-name and candidate-PNG realignment rerun:

```text
Fresh 18252 starter rerun on 2026-05-14:
powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -NoOpenBrowser -Port 18252

Observed:
- manual_acceptance.recommended_order[0].candidate_pngs[0] = ...\chat-default-task-auto.png
- manual_acceptance.record_seed[0].candidate_pngs = [ ...\chat-default-task-auto.png ]
- A path no longer mixes in responses-message-only.png
```

Browser-probe structure-key realignment:

```text
Real 8080 browser probe rerun on 2026-05-14:
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-8080-chat-structure

Observed:
- chat_surface.default_task_auto exists
- chat_surface.default_task_auto.screenshot_path = ...\chat-default-task-auto.png
- old top-level key message_only is no longer emitted for the default path
```

Fresh-restart real `8080` richer browser rerun:

```text
2026-05-14, after fresh restart of the real 8080 harness:
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-8080-chat-rerun7
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-8080-responses-rerun4

Observed:
- chat surface: passed
  - task_note_attach.selected_task_id stayed on the manual-start task
  - manual_start_continuity.continuity_message_type = task_note
  - manual_start_continuity.continuity_task_mode = task_required
  - manual_start_continuity.continuity_auto_start = false
  - followup_manual_start.followup_message_type = task_followup
- responses surface: passed with the same continuity contracts
```

Boundary:

```text
This section started as acceptance-prep proof, but the current `18276` bundle goes further:
- A-H all have current scripted browser evidence and screenshot bundles.
- B/G still keep their old path labels, but the live seam is now task_note_attach on chat / responses surfaces.
- A scripted backfill helper now exists for the full A-H bundle:
  - `Render-DialogueAcceptanceScriptedBackfillTemplate.ps1`
  - `Run-DialogueAcceptanceScriptedBackfillProbe.ps1`

Current boundary is narrower:
- this closes the current A-H seam evidence chain for `/dialogue/`
- it does not by itself close the wider project goal or the entire GitHub release
```

### 2.3 Java HTTP / Handler Contract

- [x] `WebConsoleHandlerHttpTest`
- [x] `ChatFacadeHandlerHttpTest`

Result summary:

```text
Rerun on 2026-05-14:
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'
Result: passed.
```

### 2.4 Frontend Smoke

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

Result summary:

```text
Rerun on 2026-05-14:
node --test src/test/js/dialogue-shell-markup-plan.test.mjs src/test/js/dialogue-composer-markup-plan.test.mjs src/test/js/dialogue-composer-plan.test.mjs src/test/js/dialogue-composer-request-plan.test.mjs src/test/js/dialogue-facade-surface-plan.test.mjs src/test/js/dialogue-facade-client-plan.test.mjs src/test/js/dialogue-facade-response-plan.test.mjs src/test/js/dialogue-facade-stream-plan.test.mjs src/test/js/dialogue-facade-reply-kind.test.mjs src/test/js/dialogue-facade-reply-plan.test.mjs src/test/js/dialogue-facade-reply-highlight-plan.test.mjs src/test/js/dialogue-facade-reply-ui-consistency.test.mjs src/test/js/dialogue-phase6-path-matrix.test.mjs src/test/js/dialogue-responses-path-matrix.test.mjs
Result: 57/57 passed.
```

### 2.5 Later Unified Fresh Shell + Light Smoke Sample

- [x] fresh isolated shell/layout validator green
- [x] fresh isolated light business smoke green

Result summary:

```text
Later unified fresh sample rerun on 2026-05-14:
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18386 -StdOutPath .tmp\server-18386.out.log -StdErrPath .tmp\server-18386.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db')
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json

Observed:
- shell/layout validator: passed
- desktop / narrow / responses: all green
- light business smoke: passed
  - create session
  - default task_auto
  - default task_auto pinned latest round output
  - manual-start task
  - continue-current note
- narrow metrics:
  - header = 83px
  - transcript = 462px
  - composer = 213px
```

Boundary:

```text
This fresh 18386 sample materially strengthens the current shell/light-smoke evidence chain,
but it still does not by itself close the wider project or GitHub release.
```

---

## 3. Manual Browser Acceptance

> The current `18276` bundle already covers the reachable A-H seam with scripted browser evidence.
> Keep real manual spot-checks only for extra first-screen UX review or if scripted evidence and live behavior disagree.

> Prefilled A-H record skeleton below is now aligned to the latest unified prep bundle:
> - Base URL: `http://localhost:18276`
> - Result JSON: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-manual-18276.json`
> - Screenshot Dir: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276`

### A. default task_auto

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Keep the default auto mode. It may materialize a new task and should expose first-screen worker or outcome signals.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已提交任务，正在推进。; task_cards=1
- Notes:
  - Evidence source: browser_probe.chat_surface.default_task_auto
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-default-task-auto.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-default-task-auto.png`

### B. message_only + task_id

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Attach to the current task. Write a task_note only.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已写入当前任务上下文。; selected_task_id=task_cf0b3196ef1546eb; task_cards=3; task_note_message_type=task_note
- Notes:
  - Evidence source: browser_probe.chat_surface.task_note_attach
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-task-note-attach.png
  - This path currently maps to the task_note_attach seam rather than the older always-visible attach checkbox flow.
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-task-note-attach.png`
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-task-note-attach.png`
  - Later fresh layout regression guard on `18390` also stayed green for the same seam:
    - `browser_probe.chat_surface.task_note_attach.layout_metrics.gapBetweenDrawerAndComposer = 17`
    - `browser_probe.chat_surface.task_note_attach.layout_metrics.drawerSummaryHeight = 23`
    - screenshot: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18390-chat-layout-v3\chat-task-note-attach.png`

### C. task_required

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Create a new task with auto_start=true and watch the progress or result affordance.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已提交任务，正在推进。当前选中 task 可作为下一轮 follow-up 起点。; selected_task_id=task_443e4dbc8dc94243; selected_status=active / scheduler / worker codex
- Notes:
  - Evidence source: browser_probe.chat_surface.auto_start_task
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-auto-start-task.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-auto-start-task.png`
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-auto-start-task.png`

### D. follow-up + manual-start

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Create a child task but stop at a manual-start receipt.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：任务已记录，当前 active。; child_parent_task_id=task_561747852a424680; followup_message_type=task_followup
- Notes:
  - Evidence source: browser_probe.chat_surface.followup_manual_start
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-followup-manual-start.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-followup-manual-start.png`
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-followup-manual-start.png`

### E. manual-start continuity

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Continue the current task with auto_start=false and record note or ack only.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已写入当前任务上下文。当前选中 task 可作为下一轮 follow-up 起点。; continuity_message_type=task_note; continuity_task_mode=task_required; continuity_auto_start=False
- Notes:
  - Evidence source: browser_probe.chat_surface.manual_start_continuity
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-manual-start-continuity.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-manual-start-continuity.png`
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-manual-start-continuity.png`

### F. stream fallback

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/`
- Path Note: Open Network and confirm event-stream to JSON fallback still uses a single request.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已提交任务，正在推进 task_443e4dbc8dc94243。; request_count_delta=1; response_content_type=text/event-stream; override_mode=same_response_json_fallback
- Notes:
  - Evidence source: browser_probe.chat_surface.stream_fallback
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-stream-fallback.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\chat-stream-fallback.png`
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-stream-fallback.png`

### G. #facade=responses + message_only

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/#facade=responses`
- Path Note: Verify the responses surface can still attach a note to the current task without creating a new task.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已写入当前任务上下文。; selected_task_id=task_91ee237be32a4f68; task_cards=3; task_note_message_type=task_note
- Notes:
  - Evidence source: browser_probe.responses_surface.task_note_attach
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-task-note-attach.png
  - This path currently maps to the responses-surface task_note_attach seam rather than a separate responses-message-only screenshot.
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-task-note-attach.png`

### H. #facade=responses + task_required

- [x] Passed
- Entry URL: `http://localhost:18276/dialogue/#facade=responses`
- Path Note: Verify the responses surface can also create or advance a task and keep the hash.
- Input: scripted browser probe bundle
- Observed result: inline_ack=最近回执：已提交任务，正在推进。当前选中 task 可作为下一轮 follow-up 起点。; selected_task_id=task_4bf512ad9d044954; selected_status=active / scheduler / worker codex
- Notes:
  - Evidence source: browser_probe.responses_surface.auto_start_task
  - Screenshot: D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-auto-start-task.png
  - Candidate PNG: `D:\gitAll\agent-cloud-harness\.tmp\dialogue-browser-screens-18276\responses-auto-start-task.png`

---

## 4. Gaps And Conclusion

### 4.1 Remaining Gaps

- [ ] token-level streaming not yet accepted
- [ ] full `/v1/responses` item/tool-call surface not yet accepted
- [ ] strict manual A-H click-through is not accepted by this historical record; use the 2026-06-02 record and runbook for the current boundary

### 4.2 Final Gate

- [x] starter run and manual-prep artifacts exist
- [x] this record captures the new seed + draft helper chain
- [x] this round browser acceptance bundle covers the current reachable A-H seam and can serve as Phase 5/6 evidence

Notes:

```text
The main new evidence in this record is not more browser-path coverage; it is that the acceptance prep chain now emits:
- starter JSON
- A-H seed markdown
- fuller record draft markdown
- embedded probes for both outputs

That closes the current A-H seam evidence chain for `/dialogue/`, but it still does not mean the wider project or GitHub release is fully closed.
```
