# ScreenshotDiffAssertion.ps1
#
# FEAT-05 screenshot_diff assertion orchestration, extracted from
# Run-BaselineMatrixRealWorkerSmoke.ps1 so it can be unit-tested without a
# running harness.
#
# Baseline key policy: `case_key` first (stable across runs), `task_id` only as
# a legacy fallback. Without this, every run created a new task id, so
# Resolve-BaselinePngPath never found a baseline and screenshot_diff stayed in
# phase_1_stub forever (wiring-only PASS, zero metric value).
#
# First run for a key seeds the baseline from the freshly captured PNG
# (phase_2_seeded_baseline). The next run compares against it
# (phase_2_5_real_comparison). A capture problem is never a silent PASS.

# Dependencies are dot-sourced at load time so callers (smoke script, tests) get
# the helper functions without repeating the lib list.
. (Join-Path $PSScriptRoot 'BaselineScreenshotStub.ps1')
. (Join-Path $PSScriptRoot 'ScreenshotCompare.ps1')
. (Join-Path $PSScriptRoot 'BaselinePng.ps1')
. (Join-Path $PSScriptRoot 'UiScreenshotCapture.ps1')

function Resolve-ScreenshotDiffDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Path))
}

function Invoke-ScreenshotDiffAssertion {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [string]$TaskCaseKey = '',
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [Parameter(Mandatory = $true)][string]$CurrentDirectory,
        [string]$AppTitleContains = '',
        [int]$WindowIndex = 0,
        [string]$EyesBin = 'eyes',
        [int]$CaptureTimeoutSec = 60,
        [string[]]$OpenEyesSourceRootCandidates = @(),
        # Pre-captured PNG (external capture / tests); copied into CurrentDirectory.
        [string]$CaptureSourcePng = '',
        # Custom capture callback: param($outPath) -> path string or $null
        [scriptblock]$Capture = $null,
        [switch]$SkipCliEnvSetup,
        [switch]$NoSeed
    )

    $startedAt = [DateTimeOffset]::UtcNow

    $baselineKey = Resolve-ScreenshotDiffKey -TaskCaseKey $TaskCaseKey -TaskId $TaskId
    if ([string]::IsNullOrWhiteSpace($baselineKey)) {
        $baselineKey = ConvertTo-ScreenshotDiffFileKey -Key $TaskId
    }
    $baselineDirectory = Resolve-ScreenshotDiffDirectory -Path $BaselineDirectory
    $currentDirectory = Resolve-ScreenshotDiffDirectory -Path $CurrentDirectory
    New-Item -ItemType Directory -Force -Path $baselineDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path $currentDirectory | Out-Null

    $currentPath = Join-Path $currentDirectory "$baselineKey.png"
    $baselinePath = Resolve-BaselinePngPath -BaselineDirectory $baselineDirectory -BaselineKey $baselineKey

    # --- 1. capture the current PNG -----------------------------------------
    $captureMode = 'none'
    $captureError = $null
    if (-not [string]::IsNullOrWhiteSpace($CaptureSourcePng)) {
        $captureMode = 'source_png'
        if (-not (Test-Path -LiteralPath $CaptureSourcePng)) {
            $captureError = "capture_source_not_found: $CaptureSourcePng"
        }
        else {
            $sourceFull = [System.IO.Path]::GetFullPath($CaptureSourcePng)
            if ($sourceFull -ne [System.IO.Path]::GetFullPath($currentPath)) {
                if (Test-Path -LiteralPath $currentPath) { Remove-Item -LiteralPath $currentPath -Force }
                [System.IO.File]::Copy($sourceFull, $currentPath, $true)
            }
        }
    }
    elseif ($null -ne $Capture) {
        $captureMode = 'scriptblock'
        try {
            $produced = & $Capture $currentPath
            if (-not [string]::IsNullOrWhiteSpace([string]$produced)) {
                $producedPath = [System.IO.Path]::GetFullPath([string]$produced)
                if ($producedPath -ne [System.IO.Path]::GetFullPath($currentPath)) {
                    if (-not (Test-Path -LiteralPath $producedPath)) { throw "capture scriptblock returned a missing path: $producedPath" }
                    [System.IO.File]::Copy($producedPath, $currentPath, $true)
                }
            }
        }
        catch {
            $captureError = "capture_failed: $($_.Exception.Message)"
        }
    }
    elseif (-not [string]::IsNullOrWhiteSpace($AppTitleContains)) {
        $captureMode = 'eyes_cli'
        $captureResult = New-UiWindowScreenshot -OutPath $currentPath -AppTitleContains $AppTitleContains -WindowIndex $WindowIndex -EyesBin $EyesBin -TimeoutSec $CaptureTimeoutSec -SourceRootCandidates $OpenEyesSourceRootCandidates -SkipCliEnvSetup:$SkipCliEnvSetup
        if (-not $captureResult.ok) { $captureError = "capture_failed: $($captureResult.error)" }
    }
    else {
        $captureError = 'capture_target_unavailable: no CaptureSourcePng / Capture callback / AppTitleContains provided'
    }

    if ([string]::IsNullOrWhiteSpace($captureError) -and -not (Test-Path -LiteralPath $currentPath)) {
        $captureError = "capture_missing_output: $currentPath"
    }
    if ([string]::IsNullOrWhiteSpace($captureError) -and (Get-Item -LiteralPath $currentPath).Length -le 0) {
        $captureError = "capture_empty_output: $currentPath"
    }

    if ($captureError) {
        $report = New-ScreenshotDiffComparisonReport -TaskId $TaskId -BaselinePath $(if ($baselinePath) { $baselinePath } else { '<no baseline PNG found>' }) -CurrentPath $currentPath -BaselineKey $baselineKey -CaseKey $TaskCaseKey -Error $captureError -Phase 'phase_0_capture_failed'
        $report['capture_mode'] = $captureMode
        $report['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
        return $report
    }

    $currentInfo = Get-Item -LiteralPath $currentPath

    # --- 2. seed the baseline on the first run for this key ------------------
    if (-not $baselinePath) {
        if ($NoSeed) {
            $stub = New-ScreenshotDiffStubReport -TaskId $TaskId -BaselineDirectory $baselineDirectory -BaselineKey $baselineKey -CaseKey $TaskCaseKey -Note "Seeding disabled: no baseline PNG for key '$baselineKey'; current capture kept at $currentPath"
            $stub['current_path'] = $currentPath
            $stub['capture_mode'] = $captureMode
            $stub['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
            return $stub
        }

        $seedPath = Join-Path $baselineDirectory "$baselineKey.png"
        $seed = Save-BaselinePng -SourcePath $currentPath -BaselinePath $seedPath
        if (-not $seed.saved) {
            $report = New-ScreenshotDiffComparisonReport -TaskId $TaskId -BaselinePath $seedPath -CurrentPath $currentPath -BaselineKey $baselineKey -CaseKey $TaskCaseKey -Error "baseline_seed_failed: $($seed.error)" -Phase 'phase_2_seed_failed'
            $report['capture_mode'] = $captureMode
            $report['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
            return $report
        }

        $seedReport = New-ScreenshotDiffSeedReport -TaskId $TaskId -BaselineDirectory $baselineDirectory -BaselineKey $baselineKey -BaselinePath $seedPath -CurrentPath $currentPath -CaseKey $TaskCaseKey -BaselineSize $seed.baseline_size
        $seedReport['capture_mode'] = $captureMode
        $seedReport['current_png_bytes'] = $currentInfo.Length
        $seedReport['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
        return $seedReport
    }

    # --- 3. real comparison against the keyed baseline -----------------------
    $compare = Compare-ScreenshotSimilarity -BaselinePath $baselinePath -CurrentPath $currentPath
    if ($compare.error) {
        $report = New-ScreenshotDiffComparisonReport -TaskId $TaskId -BaselinePath $baselinePath -CurrentPath $currentPath -BaselineKey $baselineKey -CaseKey $TaskCaseKey -Error $compare.error
        $report['baseline_size'] = $compare.baseline_size
        $report['current_size'] = $compare.current_size
        $report['capture_mode'] = $captureMode
        $report['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
        return $report
    }

    $report = New-ScreenshotDiffComparisonReport -TaskId $TaskId -BaselinePath $baselinePath -CurrentPath $currentPath -BaselineKey $baselineKey -CaseKey $TaskCaseKey -Similarity $compare.similarity -Matched $compare.matched
    $report['max_byte_diff'] = $compare.max_byte_diff
    $report['bytes_sampled'] = $compare.bytes_sampled
    $report['bytes_over_tolerance'] = $compare.bytes_over_tolerance
    $report['tolerance_bytes_per_pixel'] = $compare.tolerance_bytes_per_pixel
    $report['capture_mode'] = $captureMode
    $report['duration_ms'] = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
    return $report
}
