# Test-ScreenshotDiffAssertion.ps1
#
# Contract test for the FEAT-05 screenshot_diff orchestration
# (scripts/lib/ScreenshotDiffAssertion.ps1 + BaselineScreenshotStub.ps1 keying).
#
# Covers the 2026-11-20 baseline-key change: baseline files are keyed by
# case_key (stable across runs), not task_id (new on every run). Also covers
# first-run seeding, real comparison, capture failure (never a silent PASS) and
# capture-source validation. No harness, no eyes CLI, no real window needed.

$ErrorActionPreference = 'Stop'

$libDir = Join-Path $PSScriptRoot '..\scripts\lib'
$assertionLib = Join-Path $libDir 'ScreenshotDiffAssertion.ps1'
if (-not (Test-Path -LiteralPath $assertionLib)) { throw "Lib not found: $assertionLib" }
. $assertionLib

$pass = 0
$fail = 0

function Assert-Eq {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (expected='$Expected' actual='$Actual')"
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

function Assert-Contains {
    param([string]$Haystack, [string]$Needle, [string]$Label)
    if ($Haystack -and $Haystack.Contains($Needle)) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (missing: $Needle in '$Haystack')"
        $script:fail++
    }
}

function New-FakePng {
    param([string]$Path, [int]$Seed = 7, [int]$Length = 1000)
    $bytes = New-Object byte[] $Length
    for ($i = 0; $i -lt $Length; $i++) { $bytes[$i] = [byte](($i + $Seed) % 251) }
    $directory = Split-Path -Parent $Path
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    [System.IO.File]::WriteAllBytes($Path, $bytes)
    return $Path
}

$root = Join-Path $env:TEMP ('screenshot-diff-assertion-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$baselineDir = Join-Path $root 'baseline'
$currentDir = Join-Path $root 'current'
$sourceDir = Join-Path $root 'source'
New-Item -ItemType Directory -Force -Path $sourceDir | Out-Null

try {
    # --- key resolution (case_key wins, task_id fallback, sanitization) ------
    Assert-Eq 'short-001' (Resolve-ScreenshotDiffKey -TaskCaseKey 'short-001' -TaskId 'task_abc') 'key: case_key wins over task_id'
    Assert-Eq 'task_abc' (Resolve-ScreenshotDiffKey -TaskCaseKey '' -TaskId 'task_abc') 'key: falls back to task_id'
    Assert-Eq 'short-001' (Resolve-ScreenshotDiffKey -TaskCaseKey '  short-001  ' -TaskId 'task_abc') 'key: trims whitespace'
    Assert-Eq 'short_001' (ConvertTo-ScreenshotDiffFileKey -Key 'short/001') 'key: path separators sanitized'
    Assert-Eq 'medium_001' (ConvertTo-ScreenshotDiffFileKey -Key 'medium*001') 'key: invalid filename chars sanitized'

    # --- run 1: no baseline -> seed from capture ---------------------------
    $run1Source = New-FakePng -Path (Join-Path $sourceDir 'run1.png') -Seed 3
    $report1 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run1' -TaskCaseKey 'short-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng $run1Source

    Assert-Eq 'PASS' $report1.status 'run1: status PASS (seeding is not a failure)'
    Assert-Eq 'phase_2_seeded_baseline' $report1.phase 'run1: phase is phase_2_seeded_baseline'
    Assert-Eq $true $report1.baseline_seeded 'run1: baseline_seeded=true'
    Assert-Eq 'short-001' $report1.baseline_key 'run1: baseline_key is the case key'
    Assert-Eq 'task_run1' $report1.task_id 'run1: task_id still recorded for traceability'
    Assert-Eq $null $report1.error 'run1: no error'

    $seedPath = Join-Path $baselineDir 'short-001.png'
    Assert-True (Test-Path -LiteralPath $seedPath) 'run1: baseline PNG created at <baselineDir>/short-001.png'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $baselineDir 'task_run1.png'))) 'run1: no task-id-keyed baseline written'
    Assert-Eq (Get-Item -LiteralPath $seedPath).Length (Get-Item -LiteralPath $run1Source).Length 'run1: seeded bytes equal captured bytes'

    # --- run 2: same case key, new task id -> real comparison --------------
    $run2Source = New-FakePng -Path (Join-Path $sourceDir 'run2.png') -Seed 3
    $report2 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run2' -TaskCaseKey 'short-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng $run2Source

    Assert-Eq 'phase_2_5_real_comparison' $report2.phase 'run2: phase is phase_2_5_real_comparison (case-key baseline found)'
    Assert-Eq 'PASS' $report2.status 'run2: identical capture -> PASS'
    Assert-Eq 1.0 $report2.similarity 'run2: similarity 1.0'
    Assert-Eq $true $report2.matched 'run2: matched=true'
    Assert-Eq 'short-001' $report2.baseline_key 'run2: baseline key stable across task ids'
    Assert-Eq 'source_png' $report2.capture_mode 'run2: capture_mode=source_png'

    # --- run 3: different pixels -> WARN, not PASS -------------------------
    $run3Source = New-FakePng -Path (Join-Path $sourceDir 'run3.png') -Seed 200
    $report3 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run3' -TaskCaseKey 'short-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng $run3Source

    Assert-Eq 'phase_2_5_real_comparison' $report3.phase 'run3: still a real comparison'
    Assert-Eq 'WARN' $report3.status 'run3: drifted capture -> WARN'
    Assert-Eq $false $report3.matched 'run3: matched=false'
    Assert-True ($report3.similarity -lt 0.95) 'run3: similarity below 0.95'

    # --- run 4: capture unavailable -> FAIL with explicit error ------------
    $report4 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run4' -TaskCaseKey 'medium-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir
    Assert-Eq 'FAIL' $report4.status 'run4: missing capture target -> FAIL (never silent PASS)'
    Assert-Eq 'phase_0_capture_failed' $report4.phase 'run4: phase_0_capture_failed'
    Assert-Contains ([string]$report4.error) 'capture_target_unavailable' 'run4: error names capture_target_unavailable'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $baselineDir 'medium-001.png'))) 'run4: no baseline seeded from a failed capture'

    # --- run 5: capture source missing -> FAIL -----------------------------
    $report5 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run5' -TaskCaseKey 'medium-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng (Join-Path $sourceDir 'does-not-exist.png')
    Assert-Eq 'FAIL' $report5.status 'run5: missing capture source -> FAIL'
    Assert-Contains ([string]$report5.error) 'capture_source_not_found' 'run5: error names capture_source_not_found'

    # --- run 6: capture scriptblock throwing -> FAIL -----------------------
    $report6 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run6' -TaskCaseKey 'medium-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -Capture { param($outPath) throw 'window closed' }
    Assert-Eq 'FAIL' $report6.status 'run6: throwing capture callback -> FAIL'
    Assert-Contains ([string]$report6.error) 'capture_failed' 'run6: error names capture_failed'

    # --- run 7: capture scriptblock succeeds -> seeds ----------------------
    $scriptSource = New-FakePng -Path (Join-Path $sourceDir 'script-capture.png') -Seed 11
    $report7 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run7' -TaskCaseKey 'long-001' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -Capture { param($outPath) Copy-Item -LiteralPath $scriptSource -Destination $outPath -Force; $outPath }.GetNewClosure()
    Assert-Eq 'phase_2_seeded_baseline' $report7.phase 'run7: scriptblock capture seeds baseline'
    Assert-Eq 'scriptblock' $report7.capture_mode 'run7: capture_mode=scriptblock'

    # --- run 8: size mismatch -> FAIL (no false PASS) ----------------------
    New-FakePng -Path (Join-Path $baselineDir 'long-002.png') -Seed 5 -Length 400 | Out-Null
    $shortSource = New-FakePng -Path (Join-Path $sourceDir 'short-size.png') -Seed 5 -Length 800
    $report8 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run8' -TaskCaseKey 'long-002' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng $shortSource
    Assert-Eq 'FAIL' $report8.status 'run8: size mismatch -> FAIL'
    Assert-Eq 'size_mismatch' $report8.error 'run8: error=size_mismatch'

    # --- run 9: -NoSeed keeps a missing baseline as phase_1_stub -----------
    $ninth = New-FakePng -Path (Join-Path $sourceDir 'noseed.png') -Seed 9
    $report9 = Invoke-ScreenshotDiffAssertion -TaskId 'task_run9' -TaskCaseKey 'long-003' -BaselineDirectory $baselineDir -CurrentDirectory $currentDir -CaptureSourcePng $ninth -NoSeed
    Assert-Eq 'phase_1_stub' $report9.phase 'run9: -NoSeed keeps phase_1_stub'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $baselineDir 'long-003.png'))) 'run9: -NoSeed writes no baseline'

    # --- schema invariants -------------------------------------------------
    Assert-Eq 1 $report1.schema_version 'schema: schema_version stays 1'
    Assert-Eq 'screenshot_diff' $report2.assertion_mode 'schema: assertion_mode stays screenshot_diff'
    Assert-True ($null -ne $report2.duration_ms) 'schema: duration_ms recorded'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -Recurse -Force $root }
}

Write-Host ''
Write-Host "=== Test-ScreenshotDiffAssertion summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
