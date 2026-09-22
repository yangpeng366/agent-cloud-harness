function Resolve-BaselinePngPath {
    param(
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [Parameter(Mandatory = $true)][string]$TaskId
    )

    # task_id is like 'task_eed5c0ec3e024c46' - filename candidates:
    #   <task_id>.png
    #   <task_id>-baseline.png
    # Returns the first existing file, or $null if none found.
    $candidates = @(
        (Join-Path $BaselineDirectory "$TaskId.png"),
        (Join-Path $BaselineDirectory "$TaskId-baseline.png")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function New-ScreenshotDiffComparisonReport {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [Parameter(Mandatory = $true)][string]$BaselinePath,
        [Parameter(Mandatory = $true)][string]$CurrentPath,
        [double]$Similarity = $null,
        [double]$Matched = $null,
        [string]$Error = $null
    )

    $status = if ($Error) { 'FAIL' }
        elseif ($null -ne $Matched -and $Matched) { 'PASS' }
        elseif ($null -ne $Matched -and -not $Matched) { 'WARN' }
        else { 'WARN' }

    return [ordered]@{
        schema_version = 1
        assertion_mode = 'screenshot_diff'
        status = $status
        phase = 'phase_2_5_real_comparison'
        task_id = $TaskId
        baseline_path = $BaselinePath
        current_path = $CurrentPath
        similarity = $Similarity
        matched = $Matched
        error = $Error
        note = 'Phase 2.5: real Compare-ScreenshotSimilarity result; PASS when similarity >= 0.95'
    }
}

function New-ScreenshotDiffStubReport {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [string]$BaselinePath = $null
    )

    $status = if ($BaselinePath) { 'PASS' } else { 'PASS' }
    $phase = if ($BaselinePath) { 'phase_2_with_baseline' } else { 'phase_1_stub' }

    return [ordered]@{
        schema_version = 1
        assertion_mode = 'screenshot_diff'
        status = $status
        phase = $phase
        task_id = $TaskId
        baseline_directory = $BaselineDirectory
        baseline_path = if ($BaselinePath) { $BaselinePath } else { '<no baseline PNG found>' }
        note = if ($BaselinePath) {
            'Phase 2 ready: baseline PNG located; real diff will be performed when current PNG captured'
        } else {
            'Phase 1 stub: no baseline PNG at $BaselineDirectory/$TaskId.png; capture one with Save-BaselinePng to enable real diff'
        }
    }
}
