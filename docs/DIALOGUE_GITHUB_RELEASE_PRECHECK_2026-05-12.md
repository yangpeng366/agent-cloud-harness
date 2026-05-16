# `/dialogue/` GitHub Release Precheck 2026-05-12

> Purpose: capture one real pre-GitHub-release page-function run for `/dialogue/`, including what passed, what was initially noisy, and what still remains open.

## Scope

This record only covers the `/dialogue/` page-function matrix defined in:

- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`

It still does **not** claim:

- that the full GitHub release gate has been closed
- that `/dialogue/` is production-ready beyond the current local harness boundary

## Executed Commands

### 1. Java HTTP regression

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

Result:

- Passed

### 2. Dialogue frontend syntax + Node tests

```powershell
node --check src\main\resources\web\dialogue\app.js
node --test src\test\js\*.test.mjs
```

Result:

- `node --check` passed
- Node test suite passed after aligning stale test expectations to the current `/dialogue/` shell contract

### 3. Fresh build + fresh isolated harness for shell/layout + light business smoke

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -QuietMaven -SkipTests
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18330 -StdOutPath .tmp\server-18330.out.log -StdErrPath .tmp\server-18330.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18330.db')
node .\scripts\screenshot.js --base-url http://localhost:18330 --report .tmp\dialogue-shell-report-18330.json
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18330 --report .tmp\dialogue-business-smoke-18330.json
```

Artifacts:

- `.tmp/dialogue-shell-report-18330.json`
- `.tmp/dialogue-business-smoke-18330.json`
- `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`

Result:

- shell/layout validator passed
- light business smoke passed

Observed fresh shell metrics:

- `rail/details = 196px / 292px`
- transcript remained dominant over composer
- default shell did not auto-select a task

### 4. Richer browser acceptance, invalid parallel attempt

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18330 -Surface chat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18330 -Surface responses
```

Result:

- Not valid release-gate evidence
- `chat` and `responses` were incorrectly launched in parallel during investigation
- that introduced probe-side noise:
  - Node V8 heap OOM
  - `net::ERR_INSUFFICIENT_RESOURCES`
  - harness-side JVM native OOM on the stressed instance

Conclusion:

- richer browser acceptance must be treated as a **serial** step
- do not use parallel runs as evidence

### 5. Probe drift alignment and follow-up event fix

During this precheck, the richer browser acceptance probe and current `/dialogue/` shell were aligned:

- shell render gate no longer waits for the old `messageHint` wording `发送面：...`
- initial surface detection now tolerates the current empty-state hint model
- stale `data-composer-mode="message"` selection was removed from the probe path
- selected-task attach / continuity path was aligned to the current `auto/task` shell
- follow-up draft detection was updated away from the old `已绑定 follow-up` wording
- `Run-DialogueBrowserAcceptanceProbe.ps1` now uses a higher Node heap ceiling via `--max-old-space-size`
- `src/main/resources/web/dialogue/app.js` now explicitly binds:
  - `#followupButton -> onFollowupDraft()`
  - `#clearFollowupButton -> onClearFollowup()`

This last point closed the remaining reproducible Layer E blocker that previously surfaced as:

- `follow-up draft was not prepared`

### 6. Fresh single-surface `chat` probe after follow-up fix

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18338 -StdOutPath .tmp\server-18338.out.log -StdErrPath .tmp\server-18338.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18338.db')
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18338 -Surface chat
```

Result:

- Passed

Observed richer `chat` paths:

- `message_only`
- `stream_fallback`
- `auto_start_task`
- `manual_start_task`
- `task_note_attach`
- `manual_start_continuity`
- `followup_manual_start`

### 7. Fresh single-surface `responses` probe on separate isolated instance

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18340 -StdOutPath .tmp\server-18340.out.log -StdErrPath .tmp\server-18340.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18340.db')
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18340 -Surface responses
```

Result:

- Passed

Observed richer `responses` paths:

- `message_only`
- `stream_fallback`
- `auto_start_task`
- `manual_start_task`
- `task_note_attach`
- `manual_start_continuity`
- `followup_manual_start`

## Current Status by Layer

- Layer A Java HTTP regression: green
- Layer B Node tests: green
- Layer C shell/layout validator: green
- Layer D light business smoke: green
- Layer E richer browser acceptance: **green when run serially, one surface per fresh instance**
- Layer F A-H seam coverage: **now covered by richer browser probe bundles and latest formal record rewrite**

## Additional Fresh Evidence on Real `8080`

After the earlier isolated `18338 / 18340` precheck, the richer browser probe was re-run on the real long-lived `8080` harness after:

- aligning the browser probe to the current default `task_auto` / continuity contract
- fixing the frontend `continue-current` regression so `manual-start continuity` again emits
  `task_required + task_id + auto_start=false`

Commands:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-8080-chat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-8080-responses
```

Results:

- `chat` surface: passed
- `responses` surface: passed

Observed key contracts on real `8080`:

- `stream_fallback`
  - `request_count_delta = 1`
  - `response_content_type = text/event-stream`
  - `override_mode = same_response_json_fallback`
- `manual_start_continuity`
  - `inline_ack = 最近回执：已写入当前任务上下文。当前选中 task 可作为下一轮 follow-up 起点。`
  - `continuity_message_type = task_note`
  - `continuity_task_mode = task_required`
  - `continuity_auto_start = false`

Evidence paths:

- `chat`
  - `.tmp\dialogue-browser-screens-8080-chat\chat-stream-fallback.png`
  - `.tmp\dialogue-browser-screens-8080-chat\chat-manual-start-continuity.png`
- `responses`
  - `.tmp\dialogue-browser-screens-8080-responses\responses-stream-fallback.png`
  - `.tmp\dialogue-browser-screens-8080-responses\responses-manual-start-continuity.png`

Later 2026-05-14 rerun note:

- During a later long-lived `8080` investigation, the richer probe temporarily re-exposed a real page seam:
  - old `auto-start` task progress could steal selection back from a newly selected `manual-start` task
  - that in turn could misbind `task note attach / manual-start continuity / follow-up` to the wrong task on the page
- This was fixed in the frontend by adding short-lived explicit selected-task stickiness and then revalidated after a fresh `8080` restart.

Fresh-restart real `8080` rerun commands:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp\dialogue-browser-screens-8080-chat-rerun7
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface responses -ScreenshotDir .tmp\dialogue-browser-screens-8080-responses-rerun4
```

Observed on the fresh-restart real `8080` rerun:

- `chat`
  - `task_note_attach.selected_task_id` stayed on the manual-start task
  - `manual_start_continuity.continuity_task_mode = task_required`
  - `manual_start_continuity.continuity_auto_start = false`
  - `followup_manual_start.followup_message_type = task_followup`
- `responses`
  - same continuity contracts also passed after restart

Fresh-restart real `8080` evidence paths:

- `chat`
  - `.tmp\dialogue-browser-screens-8080-chat-rerun7\chat-task-note-attach.png`
  - `.tmp\dialogue-browser-screens-8080-chat-rerun7\chat-manual-start-continuity.png`
  - `.tmp\dialogue-browser-screens-8080-chat-rerun7\chat-followup-manual-start.png`
- `responses`
  - `.tmp\dialogue-browser-screens-8080-responses-rerun4\responses-task-note-attach.png`
  - `.tmp\dialogue-browser-screens-8080-responses-rerun4\responses-manual-start-continuity.png`
  - `.tmp\dialogue-browser-screens-8080-responses-rerun4\responses-followup-manual-start.png`

## Later Unified Fresh Sample

After the earlier dated precheck records, `/dialogue/` also gained a later unified fresh isolated sample where shell/layout and light business smoke were run in the correct serial order on the same instance.

Commands:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18386 -StdOutPath .tmp\server-18386.out.log -StdErrPath .tmp\server-18386.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db')
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json
```

Artifacts:

- `.tmp/dialogue-shell-report-18386.json`
- `.tmp/dialogue-business-smoke-18386.json`

Observed results:

- shell/layout validator: passed
- light business smoke: passed
- `desktop / narrow / responses` shell profiles all green
- `continue-current note` remained green after the recent submit-context stabilization
- narrow shell metrics were:
  - `header = 83px`
  - `transcript = 462px`
  - `composer = 213px`

This later `18386` sample is the better current reference for unified shell + light-smoke evidence, superseding the earlier unified `18384` sample while the original sections above remain the dated record of the 2026-05-12 precheck rollout.

## Release Impact

As of this record:

- `/dialogue/` page-function confidence is materially stronger than before because Layers A-E now all have fresh evidence
- the manual-acceptance prep chain is also stronger than before:
  - `18276` starter now emits:
    - `.tmp\dialogue-manual-18276.json`
    - `.tmp\dialogue-record-seed-18276.md`
    - `.tmp\dialogue-record-draft-18276.md`
  - and embeds all three:
    - `record_seed_probe`
    - `record_draft_probe`
    - `starter_probe`
- a new formal record file now exists for this prep-chain progress:
  - `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-14.md`
- fresh `18256` rerun additionally confirmed the seed/draft renderers no longer collapse section 3 and no longer duplicate per-path `Path Note`
- fresh `18266` rerun additionally confirmed starter-level `BrowserProbeSurface=both` now produces a unified prep bundle by internally running `chat` / `responses` serially
  - `.tmp/dialogue-manual-18266.json`
  - `.tmp/dialogue-browser-screens-18266`
  - both `chat_surface` and `responses_surface` are now non-empty
- fresh `18270` rerun then confirmed the same bundle after guidance text was aligned too:
  - `.tmp/dialogue-manual-18270.json`
  - `manual_acceptance.scripted_probe_guidance.allow_both_in_one_run = true`
  - `browser_probe.chat_surface` / `browser_probe.responses_surface` remain non-empty
- `Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18270.json` is now green too:
  - `manual_acceptance.starter_probe.allow_both_in_one_run = true`
  - `manual_acceptance.starter_probe.browser_probe_surface = both`
  - `manual_acceptance.starter_probe.chat_surface_property_count = 9`
  - `manual_acceptance.starter_probe.responses_surface_property_count = 9`
  - `manual_acceptance.starter_probe.chat_png_count = 7`
  - `manual_acceptance.starter_probe.responses_png_count = 7`
- fresh `18276` rerun then confirmed the sequencing seam between starter JSON, seed, and draft is now closed too:
  - `.tmp/dialogue-manual-18276.json`
  - `Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `Run-DialogueRecordDraftProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `.tmp\dialogue-record-seed-18276.md` now writes `Starter aggregate probe`
  - `.tmp\dialogue-record-draft-18276.md` now writes embedded `record_seed_probe / record_draft_probe / starter_probe` summaries into section `2.4 Manual Acceptance Prep Chain`
  - starter JSON now keeps `record_seed_generated = true`, `record_draft_generated = true`, and embeds both `record_draft_probe` and `starter_probe`
- fresh `18276` rerun now also confirmed the A-H manual-backfill helper chain:
  - `Run-DialogueAcceptanceManualBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
  - `.tmp\dialogue-manual-backfill-18276.json` is generated as a structured hand-fill template
  - `.tmp\dialogue-record-backfill-probe-18276.md` is updated by markdown merge helper
  - helper scope is limited to A-H `Passed / Input / Observed result / Notes`; it does not close the final gate
- but `/dialogue/` page functionality is **still not fully release-closed**
- the remaining open release work is no longer “redo A-H manual clicks”
- the GitHub release gate remains open until:
  - the formal acceptance record and release docs stay in sync with the latest starter bundle
  - the remaining non-acceptance release items are also closed

## Recommended Next Step

Do not keep expanding docs first.

The next useful work item is:

1. keep the formal acceptance record and release docs synchronized with the latest starter bundle
2. continue real `/dialogue/` product-line work instead of re-expanding acceptance-prep docs
3. only then treat the `/dialogue/` page-function release gate as fully closed
