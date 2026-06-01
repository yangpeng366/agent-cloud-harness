param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputJsonPath)) {
    throw "starter json not found: $InputJsonPath"
}

$payload = Get-Content -LiteralPath $InputJsonPath -Raw | ConvertFrom-Json
$manual = $payload.manual_acceptance
if ($null -eq $manual) {
    throw "manual_acceptance missing from starter json"
}

$port = ($payload.base_url -replace '^https?://localhost:', '')
$templatePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-manual-backfill-{0}.json" -f $port)))
$recordPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-backfill-probe-{0}.md" -f $port)))

& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordDraft.ps1") -InputJsonPath $InputJsonPath |
    Out-File -FilePath $recordPath -Encoding utf8

& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Render-DialogueAcceptanceManualBackfillTemplate.ps1") -InputJsonPath $InputJsonPath |
    Out-File -FilePath $templatePath -Encoding utf8

$backfill = Get-Content -LiteralPath $templatePath -Raw | ConvertFrom-Json
if (-not ([string]$backfill.note).Contains("must be backfilled only after a human reviews each path")) {
    throw "manual backfill template note does not require human review"
}
if (-not ([string]$backfill.note).Contains("cannot mark Passed=true")) {
    throw "manual backfill template note does not reject scripted evidence as final gate"
}
foreach ($path in @($backfill.paths)) {
    if ([string]$path.evidence_mode -ne 'manual_review_required') {
        throw "manual backfill template path $($path.id) must start as manual_review_required"
    }
    if ($path.passed) {
        throw "manual backfill template path $($path.id) must not start as Passed=true"
    }
}
$first = $backfill.paths[0]
$first.passed = $true
$first.input = "manual probe input"
$first.observed_result = "manual probe observed result"
$first.notes = @("manual probe note")
$backfill | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $templatePath -Encoding utf8

$applyResult = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Apply-DialogueAcceptanceManualBackfill.ps1") `
    -BackfillJsonPath $templatePath `
    -RecordPath $recordPath
if ($LASTEXITCODE -ne 0) {
    throw "manual backfill apply helper failed"
}

$content = Get-Content -LiteralPath $recordPath -Raw
if (-not $content.Contains("- [x] Passed")) {
    throw "manual backfill probe did not update Passed line"
}
if (-not $content.Contains("- Input: manual probe input")) {
    throw "manual backfill probe did not update Input line"
}
if (-not $content.Contains("- Observed result: manual probe observed result")) {
    throw "manual backfill probe did not update Observed result line"
}
if (-not $content.Contains("  - manual probe note")) {
    throw "manual backfill probe did not insert note bullet"
}

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    backfill_template_path = $templatePath
    record_probe_path = $recordPath
    updated_path = [string]$first.id
    applied = $true
} | ConvertTo-Json -Depth 4
