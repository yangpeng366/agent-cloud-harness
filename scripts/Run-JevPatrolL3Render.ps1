# Run-JevPatrolL3Render.ps1
#
# Reads a `patrol-decisions-<project>-<stamp>.json` produced by
# Run-JevPatrolL3Extract and renders a human-readable Markdown summary.
# Closes the Jev-Patrol-L3 extract -> render pipeline so a downstream UI
# can surface key_decisions without parsing the raw JSON.
#
# Usage:
#   pwsh -File scripts/Run-JevPatrolL3Render.ps1 -InputJsonPath ".tmp/patrol-decisions-agent-cloud-harness-20260920-183035.json"
#   pwsh -File scripts/Run-JevPatrolL3Render.ps1 -InputJsonPath "...json" -OutputMdPath "...md"
#   pwsh -File scripts/Run-JevPatrolL3Render.ps1 -Latest -ProjectFilter "agent-cloud-harness"

[CmdletBinding()]
param(
    [string]$InputJsonPath = '',
[switch]$UseLatest,
    [string]$ProjectFilter = '',
    [string]$OutputMdPath = '',
    [string]$OutputDirectory = '.tmp'
)

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot 'lib\JevPatrolL3Markdown.ps1'
if (Test-Path -LiteralPath $libPath) { . $libPath }

if (-not $UseLatest -and [string]::IsNullOrWhiteSpace($InputJsonPath)) {
    throw "Either -InputJsonPath or -Latest must be provided"
}

if ($UseLatest) {
    $pattern = if ($ProjectFilter) { "patrol-decisions-$ProjectFilter-*.json" } else { "patrol-decisions-*.json" }
    $latest = Get-ChildItem -Path $OutputDirectory -Filter $pattern -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) {
        throw "No patrol-decisions files matching $pattern under $OutputDirectory"
    }
    $InputJsonPath = $latest.FullName
} else {
    if (-not (Test-Path -LiteralPath $InputJsonPath)) {
        throw "Input JSON not found: $InputJsonPath"
    }
    $InputJsonPath = (Resolve-Path $InputJsonPath).Path
}

$raw = Get-Content -LiteralPath $InputJsonPath -Raw
if (-not $raw) { throw "Empty input: $InputJsonPath" }
$report = $raw | ConvertFrom-Json
$markdown = Build-JevPatrolL3Markdown -Report $report -SourceFileName ([System.IO.Path]::GetFileName($InputJsonPath))
if (-not [string]::IsNullOrWhiteSpace($OutputMdPath)) {
    $dir = Split-Path $OutputMdPath -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($OutputMdPath, $markdown, [System.Text.UTF8Encoding]::new($false))
}
Write-Output $markdown
if (-not [string]::IsNullOrWhiteSpace($OutputMdPath)) {
    Write-Host "rendered: $OutputMdPath"
}
