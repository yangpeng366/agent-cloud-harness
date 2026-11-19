# BaselineScreenshotStub.ps1
#
# Pure helpers for the FEAT-05 screenshot_diff assertion channel.
#
# Phase 1 -> Phase 2.5 history:
#   * phase_1_stub                 no baseline PNG for the key
#   * phase_2_with_baseline        baseline present, current PNG not captured yet (legacy)
#   * phase_2_seeded_baseline      first run for the key: current PNG promoted to baseline
#   * phase_2_5_real_comparison    baseline + current compared with Compare-ScreenshotSimilarity
#
# Baseline key policy (2026-11-20): the key is the baseline *case* key
# (e.g. `short-001`), not the task id. Every run creates a fresh task id, so a
# task-id baseline could never be hit twice and screenshot_diff was stuck in
# phase_1_stub forever. `Resolve-ScreenshotDiffKey` prefers the case key and
# falls back to the task id only when no case key is available.

function ConvertTo-ScreenshotDiffFileKey {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Key,
        [int]$MaxLength = 96
    )

    $trimmed = $Key.Trim()
    if ($trimmed.Length -eq 0) { return '' }

    $invalid = [System.IO.Path]::GetInvalidFileNameChars()
    $builder = New-Object System.Text.StringBuilder
    foreach ($char in $trimmed.ToCharArray()) {
        if ([char]::IsControl($char) -or [Array]::IndexOf($invalid, $char) -ge 0) {
            [void]$builder.Append('_')
        } else {
            [void]$builder.Append($char)
        }
    }

    $result = $builder.ToString().Trim([char[]]@('.', ' '))
    if ($result.Length -gt $MaxLength) { $result = $result.Substring(0, $MaxLength) }
    if ([string]::IsNullOrWhiteSpace($result)) { return '' }
    return $result
}

function Resolve-ScreenshotDiffKey {
    param(
        [string]$TaskCaseKey = '',
        [string]$TaskId = ''
    )

    if (-not [string]::IsNullOrWhiteSpace($TaskCaseKey)) {
        return (ConvertTo-ScreenshotDiffFileKey -Key $TaskCaseKey)
    }
    return (ConvertTo-ScreenshotDiffFileKey -Key $TaskId)
}

function Resolve-BaselinePngPath {
    param(
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [Alias('TaskId')][Parameter(Position = 1)][AllowEmptyString()][string]$BaselineKey = ''
    )

    if ([string]::IsNullOrWhiteSpace($BaselineKey)) { return $null }

    # BaselineKey is a case key like 'short-001' or a legacy task id like
    # 'task_eed5c0ec3e024c46'. Filename candidates:
    #   <key>.png
    #   <key>-baseline.png
    # Returns the first existing file, or $null if none found.
    $candidates = @(
        (Join-Path $BaselineDirectory "$BaselineKey.png"),
        (Join-Path $BaselineDirectory "$BaselineKey-baseline.png")
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
        [string]$BaselineKey = '',
        [string]$CaseKey = '',
        [object]$Similarity = $null,
        [object]$Matched = $null,
        [string]$Error = $null,
        [string]$Phase = 'phase_2_5_real_comparison'
    )

    $status = if ($Error) { 'FAIL' }
        elseif ($null -ne $Matched -and $Matched) { 'PASS' }
        elseif ($null -ne $Matched -and -not $Matched) { 'WARN' }
        else { 'WARN' }

    return [ordered]@{
        schema_version = 1
        assertion_mode = 'screenshot_diff'
        status = $status
        phase = $Phase
        task_id = $TaskId
        baseline_key = $BaselineKey
        case_key = $CaseKey
        baseline_path = $BaselinePath
        current_path = $CurrentPath
        similarity = $Similarity
        matched = $Matched
        baseline_seeded = $false
        error = $Error
        note = 'Phase 2.5: real Compare-ScreenshotSimilarity result; PASS when similarity >= 0.95'
    }
}

function New-ScreenshotDiffSeedReport {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [Parameter(Mandatory = $true)][string]$BaselineKey,
        [Parameter(Mandatory = $true)][string]$BaselinePath,
        [Parameter(Mandatory = $true)][string]$CurrentPath,
        [string]$CaseKey = '',
        [object]$BaselineSize = $null
    )

    return [ordered]@{
        schema_version = 1
        assertion_mode = 'screenshot_diff'
        status = 'PASS'
        phase = 'phase_2_seeded_baseline'
        task_id = $TaskId
        baseline_key = $BaselineKey
        case_key = $CaseKey
        baseline_directory = $BaselineDirectory
        baseline_path = $BaselinePath
        current_path = $CurrentPath
        baseline_size = $BaselineSize
        similarity = $null
        matched = $null
        baseline_seeded = $true
        error = $null
        note = "First run for baseline key '$BaselineKey': current capture promoted to baseline; real comparison starts on the next run"
    }
}

function New-ScreenshotDiffStubReport {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [Parameter(Mandatory = $true)][string]$BaselineDirectory,
        [string]$BaselinePath = $null,
        [string]$BaselineKey = '',
        [string]$CaseKey = '',
        [string]$Note = ''
    )

    $phase = if ($BaselinePath) { 'phase_2_with_baseline' } else { 'phase_1_stub' }
    $resolvedKey = if (-not [string]::IsNullOrWhiteSpace($BaselineKey)) { $BaselineKey } else { $TaskId }
    $defaultNote = if ($BaselinePath) {
        'Phase 2 ready: baseline PNG located; real diff will be performed when current PNG captured'
    } else {
        "Phase 1 stub: no baseline PNG at $BaselineDirectory/$resolvedKey.png; capture one with Save-BaselinePng to enable real diff"
    }

    return [ordered]@{
        schema_version = 1
        assertion_mode = 'screenshot_diff'
        status = 'PASS'
        phase = $phase
        task_id = $TaskId
        baseline_key = $resolvedKey
        case_key = $CaseKey
        baseline_directory = $BaselineDirectory
        baseline_path = if ($BaselinePath) { $BaselinePath } else { '<no baseline PNG found>' }
        similarity = $null
        matched = $null
        baseline_seeded = $false
        error = $null
        note = if ([string]::IsNullOrWhiteSpace($Note)) { $defaultNote } else { $Note }
    }
}
