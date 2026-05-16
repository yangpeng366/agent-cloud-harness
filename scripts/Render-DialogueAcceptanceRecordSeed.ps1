param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputJsonPath)) {
    throw "input json not found: $InputJsonPath"
}

$raw = Get-Content -LiteralPath $InputJsonPath -Raw
$payload = $raw | ConvertFrom-Json
$manual = $payload.manual_acceptance
if ($null -eq $manual) {
    throw "manual_acceptance missing from input json"
}

$seedItems = @($manual.record_seed)
if ($seedItems.Count -eq 0) {
    throw "manual_acceptance.record_seed is empty"
}
$recommendedOrderById = @{}
foreach ($entry in @($manual.recommended_order)) {
    if ($null -ne $entry -and -not [string]::IsNullOrWhiteSpace([string]$entry.id)) {
        $recommendedOrderById[[string]$entry.id] = $entry
    }
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('> Prefilled A-H record skeleton from starter JSON. Current browser-probe evidence can prefill the A-H seam coverage, but regenerate or hand-edit the final record whenever the evidence bundle changes.')
$lines.Add('')
$lines.Add('## Run Metadata')
$lines.Add('')
$lines.Add(("- Base URL: {0}" -f $payload.base_url))
$lines.Add(("- Dialogue URL: {0}" -f $payload.dialogue_url))
$lines.Add(("- Responses Dialogue URL: {0}" -f $payload.responses_dialogue_url))
$lines.Add(("- Result JSON: {0}" -f $manual.result_json_path))
$lines.Add(("- Record Seed Output: {0}" -f $manual.record_seed_output_path))
$lines.Add(("- Screenshot Dir: {0}" -f $manual.recommended_screenshot_dir))
$lines.Add(("- Record Suggestion: {0}" -f $payload.record_suggestion))
$lines.Add(("- Completion Gate: {0}" -f $manual.completion_gate))
$lines.Add('')

$commandExamples = $manual.command_examples
if ($null -ne $commandExamples) {
    $lines.Add('## Useful Commands')
    $lines.Add('')
    if ($commandExamples.keep_running) {
        $lines.Add('```powershell')
        $lines.Add($commandExamples.keep_running)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.chat_browser_probe) {
        $lines.Add('- Chat browser probe:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.chat_browser_probe)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.responses_browser_probe) {
        $lines.Add('- Responses browser probe:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.responses_browser_probe)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.probe_record_seed_output) {
        $lines.Add('- Record seed probe:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.probe_record_seed_output)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.probe_starter_output) {
        $lines.Add('- Starter aggregate probe:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.probe_starter_output)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.render_manual_backfill_template_to_file) {
        $lines.Add('- Manual backfill template:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.render_manual_backfill_template_to_file)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.apply_manual_backfill_to_record) {
        $lines.Add('- Apply manual backfill:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.apply_manual_backfill_to_record)
        $lines.Add('```')
        $lines.Add('')
    }
    if ($commandExamples.probe_manual_backfill_output) {
        $lines.Add('- Manual backfill probe:')
        $lines.Add('```powershell')
        $lines.Add($commandExamples.probe_manual_backfill_output)
        $lines.Add('```')
        $lines.Add('')
    }
}

$recordSeedProbe = $manual.record_seed_probe
if ($null -ne $recordSeedProbe) {
    $lines.Add('## Record Seed Probe')
    $lines.Add('')
    $lines.Add(("- Output Path: {0}" -f $recordSeedProbe.output_path))
    if ($null -ne $recordSeedProbe.has_run_metadata) {
        $lines.Add(("- Has Run Metadata: {0}" -f $recordSeedProbe.has_run_metadata))
    }
    if ($null -ne $recordSeedProbe.has_useful_commands) {
        $lines.Add(("- Has Useful Commands: {0}" -f $recordSeedProbe.has_useful_commands))
    }
    if ($null -ne $recordSeedProbe.has_base_url) {
        $lines.Add(("- Has Base URL: {0}" -f $recordSeedProbe.has_base_url))
    }
    if ($null -ne $recordSeedProbe.has_result_json) {
        $lines.Add(("- Has Result JSON: {0}" -f $recordSeedProbe.has_result_json))
    }
    if ($null -ne $recordSeedProbe.has_completion_gate) {
        $lines.Add(("- Has Completion Gate: {0}" -f $recordSeedProbe.has_completion_gate))
    }
    $lines.Add(("- Has Section A: {0}" -f $recordSeedProbe.has_section_a))
    $lines.Add(("- Has Section H: {0}" -f $recordSeedProbe.has_section_h))
    $lines.Add(("- Has Entry URLs: {0}" -f $recordSeedProbe.has_entry_urls))
    $lines.Add('')
}

if ($manual.record_seed_generated -eq $true) {
    $lines.Add('## Acceptance Paths')
    $lines.Add('')
}

foreach ($item in $seedItems) {
    $lines.Add(("### {0}. {1}" -f $item.id, $item.label))
    $lines.Add('')
    $lines.Add('- [ ] Passed')
    $lines.Add(("- Entry URL: {0}" -f $item.entry_url))
    $noteEntry = $null
    if ($recommendedOrderById.ContainsKey([string]$item.id)) {
        $noteEntry = $recommendedOrderById[[string]$item.id]
    }
    if ($null -ne $noteEntry -and -not [string]::IsNullOrWhiteSpace([string]$noteEntry.note)) {
        $lines.Add(("- Path Note: {0}" -f [string]$noteEntry.note))
    }
    $lines.Add('- Evidence Mode: scripted_browser_evidence_available')
    $lines.Add('- Input:')
    $lines.Add('- Observed result:')
    $lines.Add('- Notes:')
    foreach ($png in @($item.candidate_pngs)) {
        $lines.Add(("  - Candidate PNG: {0}" -f $png))
    }
    $lines.Add('')
}

$lines -join [Environment]::NewLine
