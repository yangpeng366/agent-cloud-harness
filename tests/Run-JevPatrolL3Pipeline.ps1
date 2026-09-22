# Run-JevPatrolL3Pipeline.ps1
#
# End-to-end smoke test for the Jev-Patrol-L3 pipeline:
#   1. Find latest patrol-last-*.md in .tmp/
#   2. Run extract -> produces patrol-decisions-*.json
#   3. Run render on the new JSON -> produces .md
#   4. Verify markdown contains expected sections (Summary, Key Decisions)
#
# Usage:
#   pwsh -File tests/Run-JevPatrolL3Pipeline.ps1
#   pwsh -File tests/Run-JevPatrolL3Pipeline.ps1 -PatrolMdPath ".tmp/patrol-last-...md"

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

$patrolPath = $null
if ($args -and $args[0] -and (Test-Path -LiteralPath $args[0])) {
    $patrolPath = (Resolve-Path $args[0]).Path
} else {
    $latest = Get-ChildItem -Path (Join-Path $root '.tmp') -Filter 'patrol-last-*.md' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) {
        Write-Host '[SKIP] no patrol-last-*.md under .tmp/'
        exit 0
    }
    $patrolPath = $latest.FullName
}

Write-Host "=== Jev-Patrol-L3 pipeline smoke test ==="
Write-Host "Patrol input: $patrolPath"

# Step 1: extract
$extractScript = Join-Path $root 'scripts\Run-JevPatrolL3Extract.ps1'
Write-Host "Step 1: extract -> $extractScript"
$rawLines = & pwsh -NoProfile -File $extractScript -PatrolMdPath $patrolPath 2>$null
$jsonExit = $LASTEXITCODE
if ($jsonExit -ne 0) { Write-Host "[FAIL] extract exited $jsonExit"; exit 1 }

# extract also prints "patrol-decisions written: ..."; isolate the JSON block.
$jsonStart = -1
$jsonEnd = -1
for ($i = 0; $i -lt $rawLines.Count; $i++) {
    if ($jsonStart -lt 0 -and $rawLines[$i].TrimStart().StartsWith('{')) { $jsonStart = $i }
    if ($jsonStart -ge 0 -and $rawLines[$i].TrimEnd() -eq '}') { $jsonEnd = $i; break }
}
if ($jsonStart -lt 0 -or $jsonEnd -lt 0) {
    Write-Host '[FAIL] could not isolate JSON from extract output'
    exit 1
}
$jsonText = ($rawLines[$jsonStart..$jsonEnd] -join "`n")
$report = $jsonText | ConvertFrom-Json
Write-Host "  schema_version=$($report.schema_version)"
Write-Host "  project=$($report.project)"
Write-Host "  total_decisions=$($report.summary.total_decisions)"
Write-Host "  high_priority=$($report.summary.high_priority)"
Write-Host "  blockers=$($report.summary.blockers)"

if ($report.schema_version -ne 1) {
    Write-Host '[FAIL] schema_version must be 1'
    exit 1
}
if ($report.summary.total_decisions -le 0) {
    Write-Host '[FAIL] total_decisions must be > 0'
    exit 1
}

# Find the produced JSON file (most recent matching)
$latestJson = Get-ChildItem -Path (Join-Path $root '.tmp') -Filter "patrol-decisions-$($report.project)-*.json" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $latestJson) {
    Write-Host '[FAIL] no patrol-decisions JSON produced'
    exit 1
}
Write-Host "JSON produced: $($latestJson.Name)"

# Step 2: render
$renderScript = Join-Path $root 'scripts\Run-JevPatrolL3Render.ps1'
$mdOut = Join-Path $root '.tmp\pipeline-smoke.md'
Write-Host "Step 2: render -> $mdOut"
$mdOutput = & pwsh -NoProfile -File $renderScript -InputJsonPath $latestJson.FullName -OutputMdPath $mdOut 2>$null
$mdExit = $LASTEXITCODE
if ($mdExit -ne 0) { Write-Host "[FAIL] render exited $mdExit"; exit 1 }

if (-not (Test-Path -LiteralPath $mdOut)) {
    Write-Host '[FAIL] markdown not written'
    exit 1
}
$md = Get-Content -LiteralPath $mdOut -Raw

# Step 3: verify markdown sections
Write-Host "Step 3: verify markdown sections"
$expectedSections = @(
    '## Summary',
    '## Key Decisions'
)
$missing = @()
foreach ($sec in $expectedSections) {
    if (-not $md.Contains($sec)) { $missing += $sec }
}
if ($missing.Count -gt 0) {
    Write-Host "[FAIL] missing sections: $($missing -join ', ')"
    exit 1
}

Write-Host "[OK]  pipeline end-to-end: $($report.summary.total_decisions) decisions, $($report.summary.blockers) blockers, markdown rendered with Summary + Key Decisions"
exit 0
