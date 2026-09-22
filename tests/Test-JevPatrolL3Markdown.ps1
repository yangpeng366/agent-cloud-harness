# Test-JevPatrolL3Markdown.ps1
#
# Contract test for scripts/lib/JevPatrolL3Markdown.ps1 Build-JevPatrolL3Markdown.
# Uses vanilla PowerShell asserts (no Pester dependency) so it runs in any
# pwsh 7.x install.
#
# Usage:
#   pwsh -File tests/Test-JevPatrolL3Markdown.ps1
#
# Exit 0 = all checks passed, exit 1 = at least one failed.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\JevPatrolL3Markdown.ps1'
if (-not (Test-Path -LiteralPath $libPath)) {
    throw "Lib not found: $libPath"
}
. $libPath

$pass = 0
$fail = 0

function Assert-Contains {
    param([string]$Haystack, [string]$Needle, [string]$Label)
    if ($Haystack -and $Haystack.Contains($Needle)) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (missing: $Needle)"
        $script:fail++
    }
}

function Assert-NotContains {
    param([string]$Haystack, [string]$Needle, [string]$Label)
    if ($Haystack -and -not $Haystack.Contains($Needle)) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (unexpected: $Needle)"
        $script:fail++
    }
}

# ---- Case 1: full report with 11 decisions, 1 blocker ----
$report1 = [PSCustomObject]@{
    schema_version = 1
    source_file = 'patrol-decisions-agent-cloud-harness-20260915-214139.json'
    project = 'agent-cloud-harness'
    extraction_mode = 'heuristic'
    generated_at = '2026-09-22T03:15:26+00:00'
    summary = [PSCustomObject]@{ total_decisions = 11; high_priority = 1; blockers = 1 }
    key_decisions = @(
        [PSCustomObject]@{ id = 'kd-001'; category = 'observation'; summary = 'first line'; evidence = '1. 本轮推进'; priority = 'low' },
        [PSCustomObject]@{ id = 'kd-002'; category = 'progress'; summary = 'master HEAD pinned'; evidence = '1. 本轮推进'; priority = 'medium' },
        [PSCustomObject]@{ id = 'kd-007'; category = 'blocker'; summary = 'status=blocked persists'; evidence = '1. 本轮推进'; priority = 'high' }
    )
}
$md1 = Build-JevPatrolL3Markdown -Report $report1 -SourceFileName 'patrol-decisions-agent-cloud-harness-20260915-214139.json'
Assert-Contains $md1 '# Patrol Decisions — agent-cloud-harness' 'header includes project name'
Assert-Contains $md1 '> Source: `patrol-decisions-agent-cloud-harness-20260915-214139.json`' 'source filename in meta'
Assert-Contains $md1 '> Schema: v1' 'schema_version meta'
Assert-Contains $md1 '> Extraction: heuristic' 'extraction mode meta'
Assert-Contains $md1 '## Summary' 'summary section header'
Assert-Contains $md1 'Total decisions: **11**' 'total decisions stat'
Assert-Contains $md1 'High priority: **1**' 'high priority stat'
Assert-Contains $md1 'Blockers: **1**' 'blockers stat'
Assert-Contains $md1 '## Key Decisions' 'decisions table header'
Assert-Contains $md1 '| kd-001 | observation | low | first line | 1. 本轮推进 |' 'row 1 of decisions table'
Assert-Contains $md1 '| kd-007 | blocker | high |' 'blocker row'
Assert-Contains $md1 '## Blockers (drill down)' 'blocker drill-down section'
Assert-Contains $md1 '**kd-007** — status=blocked persists' 'blocker drill entry'

# ---- Case 2: empty decisions ----
$report2 = [PSCustomObject]@{
    schema_version = 1
    source_file = 'patrol-decisions-empty.json'
    project = 'empty-project'
    extraction_mode = 'heuristic'
    generated_at = '2026-09-22T04:00:00+00:00'
    summary = [PSCustomObject]@{ total_decisions = 0; high_priority = 0; blockers = 0 }
    key_decisions = @()
}
$md2 = Build-JevPatrolL3Markdown -Report $report2 -SourceFileName 'patrol-decisions-empty.json'
Assert-Contains $md2 'No key_decisions extracted from this patrol round.' 'empty case placeholder text'
Assert-NotContains $md2 '## Key Decisions' 'empty case omits table'
Assert-NotContains $md2 '## Blockers (drill down)' 'empty case omits blockers'

# ---- Case 3: pipe escaping in summary ----
$report3 = [PSCustomObject]@{
    schema_version = 1
    source_file = 'test.json'
    project = 'pipe-test'
    extraction_mode = 'heuristic'
    generated_at = '2026-09-22T05:00:00+00:00'
    summary = [PSCustomObject]@{ total_decisions = 1; high_priority = 0; blockers = 0 }
    key_decisions = @(
        [PSCustomObject]@{ id = 'kd-001'; category = 'observation'; summary = 'value | with | pipes'; evidence = 'section'; priority = 'low' }
    )
}
$md3 = Build-JevPatrolL3Markdown -Report $report3 -SourceFileName 'test.json'
Assert-Contains $md3 'value \| with \| pipes' 'pipes in summary are escaped'

# ---- Case 4: schema_version guard ----
$report4 = [PSCustomObject]@{ schema_version = 2; project = 'x'; key_decisions = @() }
try {
    $null = Build-JevPatrolL3Markdown -Report $report4 -SourceFileName 'x.json'
    Write-Host '[FAIL] schema_version 2 must throw'
    $script:fail++
} catch {
    if ($_.Exception.Message -match 'Unsupported schema_version') {
        Write-Host '[OK]  schema_version 2 throws Unsupported schema_version'
        $script:pass++
    } else {
        Write-Host "[FAIL] unexpected error: $($_.Exception.Message)"
        $script:fail++
    }
}

$report5 = [PSCustomObject]@{ project = 'x'; key_decisions = @() }
try {
    $null = Build-JevPatrolL3Markdown -Report $report5 -SourceFileName 'x.json'
    Write-Host '[FAIL] missing schema_version must throw'
    $script:fail++
} catch {
    if ($_.Exception.Message -match 'Missing schema_version') {
        Write-Host '[OK]  missing schema_version throws Missing schema_version'
        $script:pass++
    } else {
        Write-Host "[FAIL] unexpected error: $($_.Exception.Message)"
        $script:fail++
    }
}

Write-Host ""
Write-Host "=== Test-JevPatrolL3Markdown summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
