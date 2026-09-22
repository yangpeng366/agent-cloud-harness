# Test-JevHeuristicComparison.ps1
#
# Contract test for scripts/lib/JevHeuristicComparison.ps1.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\JevHeuristicComparison.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath

$pass = 0
$fail = 0

function Assert-Eq {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label expected=$Expected actual=$Actual"
        $script:fail++
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Label)
    if ($Condition) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label"
        $script:fail++
    }
}

function Assert-Null {
    param($Value, [string]$Label)
    if ($null -eq $Value) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (expected null)"
        $script:fail++
    }
}

$testPatrol = Join-Path $env:TEMP 'jev-heuristic-test'
if (Test-Path $testPatrol) { Remove-Item -Recurse -Force $testPatrol }
New-Item -ItemType Directory -Force -Path $testPatrol | Out-Null

$patrolMd = Join-Path $testPatrol 'patrol-last-test.md'
$content = @"
# agent-cloud-harness heuristic vs jev test

## 本轮推进

- fix: merge harness-config defaults into LlmConfig for local CCX
- blocked: result file still missing due to stream_stalled
- master HEAD = 633110c stable
"@
[System.IO.File]::WriteAllText($patrolMd, $content)

# heuristic-only run (no key)
$heuristicResult = Invoke-JevHeuristicComparison -PatrolMdPath $patrolMd -OutputDirectory $testPatrol -ApiKey ''
Assert-Null $heuristicResult.error 'heuristic: no error'
Assert-True $heuristicResult.passed 'heuristic: passed'
Assert-True ($heuristicResult.heuristic_decisions -gt 0) 'heuristic: heuristic decisions'
Assert-True ($heuristicResult.jev_decisions -eq 0) 'heuristic: jev skipped (no key)'
Assert-True (Test-Path -LiteralPath $heuristicResult.summary_path) 'heuristic: summary path exists'

# missing patrol md
$missingResult = Invoke-JevHeuristicComparison -PatrolMdPath "$testPatrol\nonexistent.md"
Assert-Eq 'patrol_md_not_found' $missingResult.error 'missing: error'
Assert-True (-not $missingResult.passed) 'missing: not passed'

# summary file content
$summaryJson = Get-Content -LiteralPath $heuristicResult.summary_path -Raw | ConvertFrom-Json
Assert-Eq 1 $summaryJson.schema_version 'summary: schema_version'
Assert-Eq 'heuristic' $summaryJson.heuristic.mode 'summary: heuristic mode'
Assert-Eq 'api_key_missing' $summaryJson.jev.error 'summary: jev api_key_missing recorded'

# live with key
if ($env:JEV_LIVE_TEST -eq 'true' -and $env:TYPESAFE_API_KEY) {
    Write-Host '[live] running jev+heuristic comparison'
    $liveResult = Invoke-JevHeuristicComparison -PatrolMdPath $patrolMd -OutputDirectory $testPatrol
    Assert-Null $liveResult.error 'live: no error'
    Assert-True ($liveResult.jev_decisions -gt 0) 'live: jev_decisions > 0'
    Write-Host "[live] heuristic=$($liveResult.heuristic_decisions) jev=$($liveResult.jev_decisions)"
} else {
    Write-Host '[skip-live] JEV_LIVE_TEST not set'
}

Write-Host ''
Write-Host "=== Test-JevHeuristicComparison summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }