# Test-BaselineScreenshotStub.ps1
#
# Contract test for scripts/lib/BaselineScreenshotStub.ps1.
# Verifies the Phase 1 stub shape and that it documents itself as not-yet-real.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\BaselineScreenshotStub.ps1'
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
        Write-Host "[FAIL] $Label (missing: $Needle)"
        $script:fail++
    }
}

# Basic shape
$report = New-ScreenshotDiffStubReport -TaskId 'task_abc123' -BaselineDirectory '.tmp/baseline'
Assert-Eq 1 $report.schema_version 'schema_version is 1'
Assert-Eq 'screenshot_diff' $report.assertion_mode 'assertion_mode is screenshot_diff'
Assert-Eq 'PASS' $report.status 'status is PASS (Phase 1 stub always passes wiring test)'
Assert-Eq 'task_abc123' $report.task_id 'task_id passes through'
Assert-Eq '.tmp/baseline' $report.baseline_directory 'baseline_directory passes through'
Assert-Contains $report.note 'Phase 1' 'note explicitly mentions Phase 1'

# Different taskId
$report2 = New-ScreenshotDiffStubReport -TaskId 'task_xyz789' -BaselineDirectory '/var/baselines'
Assert-Eq 'task_xyz789' $report2.task_id 'different task_id passes through'
Assert-Eq '/var/baselines' $report2.baseline_directory 'different baseline_directory passes through'
Assert-Eq 'PASS' $report2.status 'PASS regardless of inputs (stub contract)'

# Phase 1 invariant: schema_version must remain 1 (so consumers don't break)
Assert-Eq 1 $report2.schema_version 'schema_version is consistent across calls'

# --- Resolve-BaselinePngPath ---
$tmpBaselineDir = Join-Path $env:TEMP 'baseline-stub-resolve'
if (Test-Path $tmpBaselineDir) { Remove-Item -Recurse -Force $tmpBaselineDir }
New-Item -ItemType Directory -Force -Path $tmpBaselineDir | Out-Null

$resolvedNone = Resolve-BaselinePngPath -BaselineDirectory $tmpBaselineDir -TaskId 'task_abc'
Assert-Eq $null $resolvedNone 'resolve: empty directory returns null'

$fakePath = Join-Path $tmpBaselineDir 'task_abc.png'
[System.IO.File]::WriteAllBytes($fakePath, [byte[]](1..10))
$resolvedExact = Resolve-BaselinePngPath -BaselineDirectory $tmpBaselineDir -TaskId 'task_abc'
Assert-Eq $fakePath $resolvedExact 'resolve: exact match (task_id.png)'

$altPath = Join-Path $tmpBaselineDir 'task_xyz-baseline.png'
[System.IO.File]::WriteAllBytes($altPath, [byte[]](1..10))
$resolvedAlt = Resolve-BaselinePngPath -BaselineDirectory $tmpBaselineDir -TaskId 'task_xyz'
Assert-Eq $altPath $resolvedAlt 'resolve: alternative match (task_id-baseline.png)'

# Exact match takes priority over alternative
$bothExact = Join-Path $tmpBaselineDir 'task_both.png'
$bothAlt = Join-Path $tmpBaselineDir 'task_both-baseline.png'
[System.IO.File]::WriteAllBytes($bothExact, [byte[]](1..5))
[System.IO.File]::WriteAllBytes($bothAlt, [byte[]](1..10))
$resolvedBoth = Resolve-BaselinePngPath -BaselineDirectory $tmpBaselineDir -TaskId 'task_both'
Assert-Eq $bothExact $resolvedBoth 'resolve: exact match wins over alternative'

# --- New-ScreenshotDiffStubReport with baseline ---
$reportWithBaseline = New-ScreenshotDiffStubReport -TaskId 'task_x' -BaselineDirectory $tmpBaselineDir -BaselinePath $bothExact
Assert-Eq 'phase_2_with_baseline' $reportWithBaseline.phase 'with-baseline: phase field'
Assert-Eq $bothExact $reportWithBaseline.baseline_path 'with-baseline: baseline_path reflected'
Assert-Contains $reportWithBaseline.note 'Phase 2 ready' 'with-baseline: note signals Phase 2'

$reportNoBaseline = New-ScreenshotDiffStubReport -TaskId 'task_y' -BaselineDirectory $tmpBaselineDir
Assert-Eq 'phase_1_stub' $reportNoBaseline.phase 'no-baseline: phase field'
Assert-Contains $reportNoBaseline.note 'Save-BaselinePng' 'no-baseline: note points to Save-BaselinePng'

# --- New-ScreenshotDiffComparisonReport ---
$compPass = New-ScreenshotDiffComparisonReport -TaskId 'task_z' -BaselinePath '/tmp/baseline.png' -CurrentPath '/tmp/current.png' -Similarity 0.99 -Matched $true
Assert-Eq 'phase_2_5_real_comparison' $compPass.phase 'comparison: phase'
Assert-Eq 'PASS' $compPass.status 'comparison: PASS when matched'
Assert-Eq 0.99 $compPass.similarity 'comparison: similarity reflected'
Assert-Eq '/tmp/baseline.png' $compPass.baseline_path 'comparison: baseline_path reflected'
Assert-Eq '/tmp/current.png' $compPass.current_path 'comparison: current_path reflected'
Assert-Contains $compPass.note 'real Compare-ScreenshotSimilarity' 'comparison: note flags real comparison'

$compFail = New-ScreenshotDiffComparisonReport -TaskId 'task_w' -BaselinePath '/b.png' -CurrentPath '/c.png' -Similarity 0.42 -Matched $false
Assert-Eq 'WARN' $compFail.status 'comparison: WARN when not matched'
Assert-Eq $false $compFail.matched 'comparison: matched reflected'

$compError = New-ScreenshotDiffComparisonReport -TaskId 'task_err' -BaselinePath '/b.png' -CurrentPath '/c.png' -Error 'baseline_not_found'
Assert-Eq 'FAIL' $compError.status 'comparison: FAIL when error'
Assert-Eq 'baseline_not_found' $compError.error 'comparison: error reflected'

$compNoData = New-ScreenshotDiffComparisonReport -TaskId 'task_nodata' -BaselinePath '/b.png' -CurrentPath '/c.png'
Assert-Eq 'WARN' $compNoData.status 'comparison: WARN when neither matched nor error'

Write-Host ''
Write-Host "=== Test-BaselineScreenshotStub summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
