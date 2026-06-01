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

$paths = @()
foreach ($entry in @($manual.recommended_order)) {
    if ($null -eq $entry) {
        continue
    }
    $entryId = [string]$entry.id
    $evidenceMode = 'manual_review_required'
    $paths += [pscustomobject]@{
        id = $entryId
        label = [string]$entry.path
        surface = [string]$entry.surface
        entry_url = [string]$entry.entry_url
        passed = $false
        evidence_mode = $evidenceMode
        input = ""
        observed_result = ""
        notes = @()
    }
}

[pscustomobject]@{
    base_url = [string]$payload.base_url
    result_json_path = [string]$manual.result_json_path
    record_path = [string]$payload.record_suggestion
    recommended_screenshot_dir = [string]$manual.recommended_screenshot_dir
    note = "A-H strict manual acceptance must be backfilled only after a human reviews each path and records the observed result. Scripted browser evidence may be used as supporting context, but it cannot mark Passed=true or close the strict manual release gate by itself."
    paths = $paths
} | ConvertTo-Json -Depth 6
