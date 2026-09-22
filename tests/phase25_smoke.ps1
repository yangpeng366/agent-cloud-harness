# Phase 2.5 isolated smoke: directly exercise wiring without harness.
# Verifies: stub fallback when no baseline, stub fallback when baseline without
# current, real comparison when both baseline and current exist.

$ErrorActionPreference = 'Stop'

# Helper: replicate the Invoke-ScreenshotDiffAssertion body in isolation.
$libDir = Join-Path (Get-Location) 'scripts\lib'
. (Join-Path $libDir 'BaselineScreenshotStub.ps1')
. (Join-Path $libDir 'ScreenshotCompare.ps1')
. (Join-Path $libDir 'BaselinePng.ps1')

$tmpDir = Join-Path $env:TEMP 'phase25-smoke2'
if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$baselineDir = Join-Path $tmpDir 'baseline'
$currentDir = Join-Path $tmpDir 'current'
New-Item -ItemType Directory -Force -Path $baselineDir | Out-Null
New-Item -ItemType Directory -Force -Path $currentDir | Out-Null

$pass = 0; $fail = 0
function Assert-True { param([bool]$Condition, [string]$Label)
    if ($Condition) { Write-Host "[OK]  $Label"; $script:pass++ }
    else { Write-Host "[FAIL] $Label"; $script:fail++ }
}
function Assert-Eq { param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) { Write-Host "[OK]  $Label"; $script:pass++ }
    else { Write-Host "[FAIL] $Label expected='$Expected' actual='$Actual'"; $script:fail++ }
}
function Assert-True { param([bool]$Condition, [string]$Label)
    if ($Condition) { Write-Host "[OK]  $Label"; $script:pass++ }
    else { Write-Host "[FAIL] $Label"; $script:fail++ }
}

# Case 1: no baseline, no current -> phase_1_stub
$baseline1 = Resolve-BaselinePngPath -BaselineDirectory $baselineDir -TaskId 'task_a'
$stub1 = New-ScreenshotDiffStubReport -TaskId 'task_a' -BaselineDirectory $baselineDir -BaselinePath $baseline1
Assert-Eq 'phase_1_stub' $stub1.phase 'case1: phase_1_stub when no baseline'

# Case 2: baseline present, no current -> phase_2_with_baseline with Phase 2.5 note
$baselinePath = Join-Path $baselineDir 'task_b.png'
$bytes = New-Object byte[] 1000
for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [byte](($i % 128)) }
[System.IO.File]::WriteAllBytes($baselinePath, $bytes)
$baseline2 = Resolve-BaselinePngPath -BaselineDirectory $baselineDir -TaskId 'task_b'
$current2 = Join-Path $currentDir 'task_b.png'
$stub2 = if ($baseline2 -and -not (Test-Path -LiteralPath $current2)) {
    $stub = New-ScreenshotDiffStubReport -TaskId 'task_b' -BaselineDirectory $baselineDir -BaselinePath $baseline2
    $stub.note = 'Phase 2.5 pending: baseline PNG present but current PNG not captured yet'
    $stub
} else {
    New-ScreenshotDiffStubReport -TaskId 'task_b' -BaselineDirectory $baselineDir -BaselinePath $baseline2
}
Assert-Eq 'phase_2_with_baseline' $stub2.phase 'case2: phase_2_with_baseline when baseline present'
Assert-True ($stub2.note -match 'Phase 2.5 pending') 'case2: note updated to Phase 2.5 pending'

# Case 3: baseline + current (identical bytes) -> phase_2_5 similarity=1.0 PASS
[System.IO.File]::WriteAllBytes($current2, $bytes)
$baseline3 = Resolve-BaselinePngPath -BaselineDirectory $baselineDir -TaskId 'task_b'
$compare3 = Compare-ScreenshotSimilarity -BaselinePath $baseline3 -CurrentPath $current2
$comp3 = New-ScreenshotDiffComparisonReport -TaskId 'task_b' -BaselinePath $baseline3 -CurrentPath $current2 -Similarity $compare3.similarity -Matched $compare3.matched
Assert-Eq 'phase_2_5_real_comparison' $comp3.phase 'case3: phase_2_5 when both present'
Assert-Eq 'PASS' $comp3.status 'case3: PASS for identical bytes'
Assert-Eq 1.0 $comp3.similarity 'case3: similarity=1.0'

# Case 4: baseline + current (different bytes) -> WARN
$differentBytes = New-Object byte[] 1000
for ($i = 0; $i -lt $differentBytes.Length; $i++) { $differentBytes[$i] = [byte](255 - ($i % 128)) }
[System.IO.File]::WriteAllBytes($current2, $differentBytes)
$compare4 = Compare-ScreenshotSimilarity -BaselinePath $baseline3 -CurrentPath $current2
$comp4 = New-ScreenshotDiffComparisonReport -TaskId 'task_b' -BaselinePath $baseline3 -CurrentPath $current2 -Similarity $compare4.similarity -Matched $compare4.matched
Assert-Eq 'WARN' $comp4.status 'case4: WARN for different bytes'
Assert-Eq $false $comp4.matched 'case4: matched=false for different bytes'
Assert-True ($comp4.similarity -lt 0.5) 'case4: low similarity'

Write-Host ''
Write-Host "=== Phase 2.5 wiring smoke: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
