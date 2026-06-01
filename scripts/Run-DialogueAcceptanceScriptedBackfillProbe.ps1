param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputJsonPath)) {
    throw "starter json not found: $InputJsonPath"
}

$backfillJson = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Render-DialogueAcceptanceScriptedBackfillTemplate.ps1") -InputJsonPath $InputJsonPath
if ($LASTEXITCODE -ne 0) {
    throw "failed to render scripted backfill template"
}

$backfill = $backfillJson | ConvertFrom-Json
$paths = @($backfill.paths)
if ($paths.Count -eq 0) {
    throw "scripted backfill template did not produce any paths"
}

$byId = @{}
foreach ($path in $paths) {
    $byId[[string]$path.id] = $path
}

foreach ($requiredId in @('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')) {
    if (-not $byId.ContainsKey($requiredId)) {
        throw "scripted backfill template is missing path $requiredId"
    }
}

foreach ($scriptedId in @('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')) {
    $entry = $byId[$scriptedId]
    if ($entry.passed) {
        throw "scripted path $scriptedId must not mark the strict manual passed gate"
    }
    if (-not $entry.scripted_coverage_passed) {
        throw "scripted path $scriptedId should be prefilled as scripted_coverage_passed"
    }
    if ([string]$entry.evidence_mode -ne 'scripted_browser_evidence_available') {
        throw "scripted path $scriptedId has wrong evidence_mode: $($entry.evidence_mode)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$entry.observed_result)) {
        throw "scripted path $scriptedId is missing observed_result"
    }
}

$port = ($backfill.base_url -replace '^https?://localhost:', '')
$scriptedMisusePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-scripted-backfill-misuse-{0}.json" -f $port)))
$recordProbePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-scripted-backfill-misuse-record-{0}.md" -f $port)))

& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordDraft.ps1") -InputJsonPath $InputJsonPath |
    Out-File -FilePath $recordProbePath -Encoding utf8

$misuse = $backfill | ConvertTo-Json -Depth 6 | ConvertFrom-Json
$misuse.paths[0].passed = $true
$misuse | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $scriptedMisusePath -Encoding utf8

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $applyOutput = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Apply-DialogueAcceptanceManualBackfill.ps1") `
        -BackfillJsonPath $scriptedMisusePath `
        -RecordPath $recordProbePath 2>&1
    $applyExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($applyExitCode -eq 0) {
    throw "apply helper accepted scripted evidence as manual Passed=true"
}
$applyText = ($applyOutput | Out-String)
if (-not $applyText.Contains("scripted browser evidence cannot mark manual Passed=true")) {
    throw "apply helper rejected scripted misuse with unexpected error: $applyText"
}

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    scripted_coverage_prefilled = @('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')
    residual_human = @('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')
    scripted_misuse_rejected = $true
    ok = $true
} | ConvertTo-Json -Depth 4
