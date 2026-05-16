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
    if (-not $entry.passed) {
        throw "scripted path $scriptedId should be prefilled as passed"
    }
    if ([string]$entry.evidence_mode -ne 'scripted_browser_evidence_available') {
        throw "scripted path $scriptedId has wrong evidence_mode: $($entry.evidence_mode)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$entry.observed_result)) {
        throw "scripted path $scriptedId is missing observed_result"
    }
}

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    scripted_prefilled = @('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')
    residual_human = @()
    ok = $true
} | ConvertTo-Json -Depth 4
