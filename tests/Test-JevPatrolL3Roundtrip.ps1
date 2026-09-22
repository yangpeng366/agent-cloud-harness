# Test-JevPatrolL3Roundtrip.ps1
#
# Contract test for scripts/lib/JevPatrolL3Roundtrip.ps1.
# Live jev mode requires JEV_LIVE_TEST=true + TYPESAFE_API_KEY.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\JevPatrolL3Roundtrip.ps1'
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
        Write-Host "[FAIL] $Label (expected=$Expected actual=$Actual)"
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

$testPatrol = Join-Path $env:TEMP 'jev-patrol-roundtrip-test'
if (Test-Path $testPatrol) { Remove-Item -Recurse -Force $testPatrol }
New-Item -ItemType Directory -Force -Path $testPatrol | Out-Null

$patrolMd = Join-Path $testPatrol 'patrol-last-test.md'
$patrolContent = @"
# agent-cloud-harness roundtrip test

## 本轮推进

- fix: merge harness-config defaults into LlmConfig for local CCX
- blocked: result file still missing due to stream_stalled
- master HEAD = 633110c stable
"@
[System.IO.File]::WriteAllText($patrolMd, $patrolContent)

# heuristic mode (no key needed)
$heuristicResult = Test-JevPatrolL3Roundtrip -PatrolMdPath $patrolMd -Mode 'heuristic'
Assert-Null $heuristicResult.error 'heuristic: no error'
Assert-True $heuristicResult.passed 'heuristic: passed'
Assert-Eq 'heuristic' $heuristicResult.extraction_mode 'heuristic: extraction_mode'
Assert-Null $heuristicResult.jev_api_base 'heuristic: jev_api_base null'
Assert-True ($heuristicResult.decisions_count -gt 0) 'heuristic: has decisions'
Assert-True (Test-Path -LiteralPath $heuristicResult.markdown_path) 'heuristic: markdown exists'

# missing patrol md
$missingResult = Test-JevPatrolL3Roundtrip -PatrolMdPath "$testPatrol\nonexistent.md"
Assert-Eq 'patrol_md_not_found' $missingResult.error 'missing: error'
Assert-True (-not $missingResult.passed) 'missing: not passed'

# live jev mode (only with key)
if ($env:JEV_LIVE_TEST -eq 'true' -and $env:TYPESAFE_API_KEY) {
    Write-Host '[live] running live jev roundtrip'
    $jevResult = Test-JevPatrolL3Roundtrip -PatrolMdPath $patrolMd -Mode 'jev'
    Assert-Null $jevResult.error "live jev: no error (got $($jevResult.error))"
    Assert-True $jevResult.passed 'live jev: passed'
    Assert-Eq 'jev' $jevResult.extraction_mode 'live jev: extraction_mode'
    Assert-Eq 'https://api.typesafe.ai/v1/systemone' $jevResult.jev_api_base 'live jev: jev_api_base'
    Assert-True ($jevResult.decisions_count -gt 0) 'live jev: has decisions'
    Write-Host "[live] jev_roundtrip decisions=$($jevResult.decisions_count) blockers=$($jevResult.blockers)"
} else {
    Write-Host '[skip-live] JEV_LIVE_TEST not set'
}

Write-Host ''
Write-Host "=== Test-JevPatrolL3Roundtrip summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }