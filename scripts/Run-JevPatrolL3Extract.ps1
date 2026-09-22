# Run-JevPatrolL3Extract.ps1
#
# Jev-Patrol-L3: extracts key_decisions from patrol-last-*.md into a
# structured JSON with a locked schema. Works in two modes:
#   - heuristic (default): regex-based extraction, no API key needed
#   - jev: calls the Jev API via TYPESAFE_API_KEY and records score/action/error
#
# Output: .tmp/patrol-decisions-<project>-<stamp>.json
# Schema is versioned (schema_version=1); consumers should check the version.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PatrolMdPath,
    [string]$OutputDirectory = '.tmp',
    [ValidateSet('heuristic', 'jev')]
    [string]$Mode = 'heuristic'
)

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot 'lib\JevPatrolL3Extract.ps1'
if (Test-Path -LiteralPath $libPath) { . $libPath }
$jevApiPath = Join-Path $PSScriptRoot 'lib\JevApi.ps1'
if (Test-Path -LiteralPath $jevApiPath) { . $jevApiPath }

if (-not (Test-Path -LiteralPath $PatrolMdPath)) {
    throw "Patrol markdown not found: $PatrolMdPath"
}

$content = [System.IO.File]::ReadAllText((Resolve-Path $PatrolMdPath), [System.Text.UTF8Encoding]::new($false))
$fileName = [System.IO.Path]::GetFileNameWithoutExtension($PatrolMdPath)
$project = Resolve-JevPatrolL3Project -FileName $fileName -Content $content
$stamp = Resolve-JevPatrolL3Stamp -FileName $fileName
$decisions = Build-JevPatrolL3Decisions -Content $content -MaxDecisions 15

if ($Mode -eq 'jev') {
    foreach ($d in $decisions) {
        if ([string]::IsNullOrWhiteSpace($env:TYPESAFE_API_KEY)) {
            $d | Add-Member -NotePropertyName 'jev_probability' -NotePropertyValue $null
            $d | Add-Member -NotePropertyName 'jev_action' -NotePropertyValue 'skipped_no_key'
            $d | Add-Member -NotePropertyName 'jev_error' -NotePropertyValue 'TYPESAFE_API_KEY not set'
            continue
        }
        $jev = Invoke-JevDecision -Text ($d.summary + ' [' + $d.category + ']') -Id ($d.id)
        $d | Add-Member -NotePropertyName 'jev_probability' -NotePropertyValue $jev.probability
        $d | Add-Member -NotePropertyName 'jev_action' -NotePropertyValue $jev.action
        $d | Add-Member -NotePropertyName 'jev_error' -NotePropertyValue $jev.error
    }
}

$highCount = @($decisions | Where-Object { $_.priority -eq 'high' }).Count
$blockerCount = @($decisions | Where-Object { $_.category -eq 'blocker' }).Count

$result = [ordered]@{
    schema_version = 1
    source_file = [System.IO.Path]::GetFileName($PatrolMdPath)
    project = $project
    extraction_mode = $Mode
    generated_at = [DateTimeOffset]::UtcNow.ToString('o')
    key_decisions = $decisions
    jev_api_base = if ($Mode -eq 'jev') { 'https://api.typesafe.ai/v1/systemone' } else { $null }
    summary = [ordered]@{
        total_decisions = $decisions.Count
        high_priority = $highCount
        blockers = $blockerCount
    }
}

$outputPath = Join-Path $OutputDirectory ("patrol-decisions-{0}-{1}.json" -f $project, $stamp)
if (-not [System.IO.Path]::IsPathRooted($outputPath)) {
    $outputPath = Join-Path (Get-Location).Path $outputPath
}
$dir = Split-Path $outputPath -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$json = $result | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($outputPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Output $json
Write-Host "patrol-decisions written: $outputPath"
