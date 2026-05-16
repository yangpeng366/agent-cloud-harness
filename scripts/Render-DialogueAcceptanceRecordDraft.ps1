param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputJsonPath)) {
    throw "input json not found: $InputJsonPath"
}

$payload = Get-Content -LiteralPath $InputJsonPath -Raw | ConvertFrom-Json
$manual = $payload.manual_acceptance
if ($null -eq $manual) {
    throw "manual_acceptance missing from input json"
}
$recordDraftOutputPath = if (($manual.PSObject.Properties.Name -contains 'record_draft_output_path') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.record_draft_output_path))) {
    [string]$manual.record_draft_output_path
} else {
    [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-draft-{0}.md" -f (($payload.base_url -replace '^https?://localhost:', '')))))
}

$seedRendererPath = Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordSeed.ps1"
$seedContentLines = @(& powershell -ExecutionPolicy Bypass -File $seedRendererPath -InputJsonPath $InputJsonPath)
if ($LASTEXITCODE -ne 0) {
    throw "failed to render acceptance record seed"
}

$port = ($payload.base_url -replace '^https?://localhost:', '')
$automationJson = $payload | Select-Object base_url, dialogue_shell_probe, chat_probe, responses_probe | ConvertTo-Json -Depth 6
$browserProbe = $payload.browser_probe

function Add-BrowserProbeSection {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [object]$Probe,
        [string]$SurfaceLabel,
        [string]$SurfaceKey
    )

    if ($null -eq $Probe) {
        return
    }

    $defaultPath = $Probe.default_task_auto
    $streamFallback = $Probe.stream_fallback
    $manualStartTask = $Probe.manual_start_task
    $taskNoteAttach = $Probe.task_note_attach
    $manualStartContinuity = $Probe.manual_start_continuity
    $followupManualStart = $Probe.followup_manual_start

    $Lines.Add(('#### 2.5 Browser Probe ({0})' -f $SurfaceLabel))
    $Lines.Add('')
    $Lines.Add(('- [x] surface: `{0}`' -f $SurfaceKey))
    if ($defaultPath) {
        $Lines.Add(('- [x] default task_auto screenshot: `{0}`' -f $defaultPath.screenshot_path))
    }
    if ($streamFallback) {
        $Lines.Add(('- [x] stream fallback screenshot: `{0}`' -f $streamFallback.screenshot_path))
    }
    if ($manualStartTask) {
        $Lines.Add(('- [x] manual-start task screenshot: `{0}`' -f $manualStartTask.screenshot_path))
    }
    if ($taskNoteAttach) {
        $Lines.Add(('- [x] task note attach screenshot: `{0}`' -f $taskNoteAttach.screenshot_path))
    }
    if ($manualStartContinuity) {
        $Lines.Add(('- [x] manual-start continuity screenshot: `{0}`' -f $manualStartContinuity.screenshot_path))
    }
    if ($followupManualStart) {
        $Lines.Add(('- [x] follow-up manual-start screenshot: `{0}`' -f $followupManualStart.screenshot_path))
    }
    $Lines.Add('')
    $Lines.Add('Result summary:')
    $Lines.Add('')
    $Lines.Add('```text')
    if ($defaultPath) {
        $Lines.Add(('default_task_auto.inline_ack = {0}' -f $defaultPath.inline_ack))
        $Lines.Add(('default_task_auto.task_cards = {0}' -f $defaultPath.task_cards))
    }
    if ($streamFallback) {
        $Lines.Add(('stream_fallback.request_count_delta = {0}' -f $streamFallback.request_count_delta))
        $Lines.Add(('stream_fallback.response_content_type = {0}' -f $streamFallback.response_content_type))
    }
    if ($manualStartContinuity) {
        $Lines.Add(('manual_start_continuity.continuity_task_mode = {0}' -f $manualStartContinuity.continuity_task_mode))
        $Lines.Add(('manual_start_continuity.continuity_auto_start = {0}' -f $manualStartContinuity.continuity_auto_start))
    }
    if ($followupManualStart) {
        $Lines.Add(('followup_manual_start.child_parent_task_id = {0}' -f $followupManualStart.child_parent_task_id))
    }
    $Lines.Add('```')
    $Lines.Add('')
    $Lines.Add('Boundary:')
    $Lines.Add('')
    $Lines.Add('```text')
    $Lines.Add('Browser probe evidence is auto-carried into this draft only to reduce backfill cost.')
    $Lines.Add('It does not mark any A-H manual browser path as passed by itself.')
    $Lines.Add('```')
    $Lines.Add('')
}

$seedContentLines = @($seedContentLines | ForEach-Object { [string]$_ })

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# Dialogue Chat Facade Acceptance Record')
$lines.Add('')
$lines.Add('> Prefilled draft from starter JSON. Use the embedded browser-probe evidence as the primary seam proof, and only leave a gate unchecked when the underlying bundle or follow-up verification still needs rerun.')
$lines.Add('')
$lines.Add('Based on template:')
$lines.Add('')
$lines.Add('- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md)')
$lines.Add('')
$lines.Add('Runbook:')
$lines.Add('')
$lines.Add('- [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)')
$lines.Add('')
$lines.Add('---')
$lines.Add('')
$lines.Add('## 1. Basic Metadata')
$lines.Add('')
$lines.Add(('- Date: {0}' -f (Get-Date -Format 'yyyy-MM-dd')))
$lines.Add('- Operator: Codex')
$lines.Add('- Branch / Commit: <fill manually>')
$lines.Add('- Environment:')
$lines.Add('  - Java: <fill manually>')
$lines.Add(('  - Port: `{0}`' -f $port))
$lines.Add('  - Facade surfaces: `chat_completions`, `responses`')
$lines.Add('- Commands:')
$lines.Add(('  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -Port {0}`' -f $port))
$lines.Add('  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs ''-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest''`')
$lines.Add('')
$lines.Add('---')
$lines.Add('')
$lines.Add('## 2. Automation Evidence')
$lines.Add('')
$lines.Add('### 2.1 Local Harness Runner')
$lines.Add('')
$lines.Add('- [x] `dialogue_shell_probe` passed')
$lines.Add('- [x] `chat_probe` passed')
$lines.Add('- [x] `responses_probe` passed')
$lines.Add('')
$lines.Add('Summary:')
$lines.Add('')
$lines.Add('```json')
$lines.Add($automationJson)
$lines.Add('```')
$lines.Add('')
$lines.Add('### 2.2 Java HTTP / Handler Contract')
$lines.Add('')
$lines.Add('- [ ] `WebConsoleHandlerHttpTest`')
$lines.Add('- [ ] `ChatFacadeHandlerHttpTest`')
$lines.Add('')
$lines.Add('Result summary:')
$lines.Add('')
$lines.Add('```text')
$lines.Add('Leave unchecked by default. Mark passed only after rerunning the exact Java tests for this acceptance record.')
$lines.Add('```')
$lines.Add('')
$lines.Add('### 2.3 Frontend Smoke')
$lines.Add('')
$lines.Add('- [ ] `dialogue-shell-markup-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-composer-markup-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-composer-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-composer-request-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-surface-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-client-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-response-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-stream-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-reply-kind.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-reply-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-reply-highlight-plan.test.mjs`')
$lines.Add('- [ ] `dialogue-facade-reply-ui-consistency.test.mjs`')
$lines.Add('- [ ] `dialogue-phase6-path-matrix.test.mjs`')
$lines.Add('- [ ] `dialogue-responses-path-matrix.test.mjs`')
$lines.Add('')
$lines.Add('Result summary:')
$lines.Add('')
$lines.Add('```text')
$lines.Add('Leave unchecked by default. Mark passed only after rerunning the exact Node smoke set for this acceptance record.')
$lines.Add('```')
$lines.Add('')
$lines.Add('### 2.4 Manual Acceptance Prep Chain')
$lines.Add('')
$lines.Add(('- [x] starter JSON saved: `{0}`' -f $manual.result_json_path))
$lines.Add(('- [x] A-H seed saved: `{0}`' -f $manual.record_seed_output_path))
$lines.Add(('- [x] record draft saved: `{0}`' -f $recordDraftOutputPath))
$lines.Add('- [x] record seed probe embedded')
$lines.Add('- [x] record draft probe embedded')
if (($manual.PSObject.Properties.Name -contains 'starter_probe') -and ($null -ne $manual.starter_probe) -and ($null -eq $manual.starter_probe.error)) {
    $lines.Add('- [x] starter probe embedded')
}
$lines.Add('')
$lines.Add('Result summary:')
$lines.Add('')
$lines.Add('```text')
$lines.Add(('manual_acceptance.record_seed_generated = {0}' -f $manual.record_seed_generated))
$lines.Add(('manual_acceptance.record_draft_generated = {0}' -f $manual.record_draft_generated))
if (($manual.PSObject.Properties.Name -contains 'starter_probe') -and ($null -ne $manual.starter_probe) -and ($null -eq $manual.starter_probe.error)) {
    $lines.Add(('manual_acceptance.starter_probe.allow_both_in_one_run = {0}' -f $manual.starter_probe.allow_both_in_one_run))
    $lines.Add(('manual_acceptance.starter_probe.browser_probe_surface = {0}' -f $manual.starter_probe.browser_probe_surface))
}
$lines.Add('')
if (($manual.PSObject.Properties.Name -contains 'record_seed_probe') -and ($null -ne $manual.record_seed_probe)) {
    $lines.Add('record_seed_probe:')
    $lines.Add(('- has_run_metadata = {0}' -f $manual.record_seed_probe.has_run_metadata))
    $lines.Add(('- has_useful_commands = {0}' -f $manual.record_seed_probe.has_useful_commands))
    $lines.Add(('- has_base_url = {0}' -f $manual.record_seed_probe.has_base_url))
    $lines.Add(('- has_result_json = {0}' -f $manual.record_seed_probe.has_result_json))
    $lines.Add(('- has_completion_gate = {0}' -f $manual.record_seed_probe.has_completion_gate))
    $lines.Add(('- has_section_a = {0}' -f $manual.record_seed_probe.has_section_a))
    $lines.Add(('- has_section_h = {0}' -f $manual.record_seed_probe.has_section_h))
    $lines.Add(('- has_entry_urls = {0}' -f $manual.record_seed_probe.has_entry_urls))
    $lines.Add('')
}
if (($manual.PSObject.Properties.Name -contains 'record_draft_probe') -and ($null -ne $manual.record_draft_probe)) {
    $lines.Add('record_draft_probe:')
    $lines.Add(('- has_title = {0}' -f $manual.record_draft_probe.has_title))
    $lines.Add(('- has_automation_section = {0}' -f $manual.record_draft_probe.has_automation_section))
    $lines.Add(('- has_local_harness_section = {0}' -f $manual.record_draft_probe.has_local_harness_section))
    $lines.Add(('- has_manual_acceptance_section = {0}' -f $manual.record_draft_probe.has_manual_acceptance_section))
    $lines.Add(('- has_section_a = {0}' -f $manual.record_draft_probe.has_section_a))
    $lines.Add(('- has_section_h = {0}' -f $manual.record_draft_probe.has_section_h))
    $lines.Add(('- has_conclusion_section = {0}' -f $manual.record_draft_probe.has_conclusion_section))
    $lines.Add(('- has_final_gate = {0}' -f $manual.record_draft_probe.has_final_gate))
    $lines.Add('')
}
if (($manual.PSObject.Properties.Name -contains 'starter_probe') -and ($null -ne $manual.starter_probe) -and ($null -eq $manual.starter_probe.error)) {
    $lines.Add('starter_probe:')
    $lines.Add(('- chat_surface_property_count = {0}' -f $manual.starter_probe.chat_surface_property_count))
    $lines.Add(('- responses_surface_property_count = {0}' -f $manual.starter_probe.responses_surface_property_count))
    $lines.Add(('- chat_png_count = {0}' -f $manual.starter_probe.chat_png_count))
    $lines.Add(('- responses_png_count = {0}' -f $manual.starter_probe.responses_png_count))
}
$lines.Add('')
$lines.Add('manual_backfill_helpers:')
if (($manual.command_examples.PSObject.Properties.Name -contains 'render_manual_backfill_template_to_file') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.render_manual_backfill_template_to_file))) {
    $lines.Add('- render_manual_backfill_template_to_file = present')
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'apply_manual_backfill_to_record') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.apply_manual_backfill_to_record))) {
    $lines.Add('- apply_manual_backfill_to_record = present')
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'probe_manual_backfill_output') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.probe_manual_backfill_output))) {
    $lines.Add('- probe_manual_backfill_output = present')
}
$lines.Add('```')
$lines.Add('')
if ($browserProbe -and $browserProbe.chat_surface) {
    Add-BrowserProbeSection -Lines $lines -Probe $browserProbe.chat_surface -SurfaceLabel 'chat' -SurfaceKey 'chat_completions'
}
if ($browserProbe -and $browserProbe.responses_surface) {
    Add-BrowserProbeSection -Lines $lines -Probe $browserProbe.responses_surface -SurfaceLabel 'responses' -SurfaceKey 'responses'
}
$lines.Add('---')
$lines.Add('')
$lines.Add('## 3. Manual Browser Acceptance')
$lines.Add('')
$lines.Add('> A-H can now be prefilled from the current browser-probe seam evidence. Only leave Passed unchecked when the underlying bundle is stale or a follow-up spot check is still required.')
$lines.Add('')
foreach ($line in @($seedContentLines)) {
    $lines.Add([string]$line)
}
$lines.Add('')
$lines.Add('---')
$lines.Add('')
$lines.Add('## 4. Gaps And Conclusion')
$lines.Add('')
$lines.Add('### 4.1 Remaining Gaps')
$lines.Add('')
$lines.Add('- [x] token-level streaming not yet accepted')
$lines.Add('- [x] full `/v1/responses` item/tool-call surface not yet accepted')
$lines.Add('- [x] final written record still needs regeneration or review whenever the starter bundle changes')
$lines.Add('')
$lines.Add('### 4.2 Final Gate')
$lines.Add('')
$lines.Add('- [x] starter run and manual-prep artifacts exist')
$lines.Add('- [x] this draft only reduces record backfill cost')
$lines.Add('- [ ] this round manual browser acceptance is complete and can serve as Phase 5/6 completion evidence')
$lines.Add('')
$lines.Add('Notes:')
$lines.Add('')
$lines.Add('```text')
$lines.Add('This draft pre-fills Local Harness Runner evidence and the A-H seam bundle, but it still depends on the current starter artifacts staying in sync.')
$lines.Add('Only after the written acceptance record is regenerated or reviewed against the latest bundle should the final gate be marked complete.')
$lines.Add('```')

$lines -join [Environment]::NewLine
