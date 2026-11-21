# Test-RunFlakyComparison.ps1
#
# Contract test for scripts/Run-FlakyComparison.ps1 helper functions.
# We test the script's Get-FlakyComparisonSummary helper in isolation by re-sourcing
# the function body. The full script invocation requires a running harness.

$ErrorActionPreference = 'Stop'

# Use the production helper so this test cannot drift from the script it covers.
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\lib\FlakyComparisonSummary.ps1')
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\lib\FlakyComparisonArgs.ps1')

$pass = 0
$fail = 0
function Assert-Eq {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label expected='$Expected' actual='$Actual'"
        $script:fail++
    }
}
function Assert-Null {
    param($Actual, [string]$Label)
    if ($null -eq $Actual) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (expected null)"
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

# null report
$nullSummary = Get-FlakyComparisonSummary -Report $null -Mode 'screenshot_diff'
Assert-Null $nullSummary 'null report returns null'

# empty report
$emptyReport = [pscustomobject]@{
    ui_assertion_reports = @()
    task_reports = @()
    terminal_run_count = 0
}
$emptySummary = Get-FlakyComparisonSummary -Report $emptyReport -Mode 'openeyes_structured'
Assert-Eq 0 $emptySummary.pass_count 'empty report pass_count'
Assert-Eq 0 $emptySummary.fail_count 'empty report fail_count'
Assert-Null $emptySummary.pass_rate 'empty report pass_rate null'
Assert-Eq 'openeyes_structured' $emptySummary.mode 'empty report mode reflected'

# all pass
$passReport = [pscustomobject]@{
    ui_assertion_reports = @(
        [pscustomobject]@{ status = 'PASS'; phase = 'phase_2_5_real_comparison' }
        [pscustomobject]@{ status = 'PASS'; phase = 'phase_2_5_real_comparison' }
        [pscustomobject]@{ status = 'PASS'; phase = 'phase_2_5_real_comparison' }
    )
    task_reports = @('a', 'b', 'c')
    terminal_run_count = 3
}
$passSummary = Get-FlakyComparisonSummary -Report $passReport -Mode 'openeyes_structured'
Assert-Eq 3 $passSummary.pass_count 'all-pass pass_count'
Assert-Eq 0 $passSummary.fail_count 'all-pass fail_count'
Assert-Eq 1.0 $passSummary.pass_rate 'all-pass rate=1.0'

# mixed
$mixedReport = [pscustomobject]@{
    ui_assertion_reports = @(
        [pscustomobject]@{ status = 'PASS' }
        [pscustomobject]@{ status = 'WARN' }
        [pscustomobject]@{ status = 'PASS' }
        [pscustomobject]@{ status = 'FAIL' }
    )
    task_reports = @('a', 'b', 'c', 'd')
    terminal_run_count = 4
}
$mixedSummary = Get-FlakyComparisonSummary -Report $mixedReport -Mode 'screenshot_diff'
Assert-Eq 2 $mixedSummary.pass_count 'mixed pass_count (only PASS)'
Assert-Eq 2 $mixedSummary.fail_count 'mixed fail_count (WARN + FAIL)'
Assert-Eq 0.5 $mixedSummary.pass_rate 'mixed rate=0.5'

# JSON roundtrip schema check (mimic Run-FlakyComparison output)
$summary = [ordered]@{
    schema_version = 1
    stamp = '20260922-130000'
    case_keys = @('short-001')
    modes = @('strong_only')
    diff = [ordered]@{ mode = 'screenshot_diff'; pass_count = 2; fail_count = 1; pass_rate = 0.667; task_reports_count = 3; terminal_run_count = 3 }
    structured = [ordered]@{ mode = 'openeyes_structured'; pass_count = 3; fail_count = 0; pass_rate = 1.0; task_reports_count = 3; terminal_run_count = 3 }
    pass_rate_lift = 0.333
    overall = [ordered]@{
        diff_exit_code = 0
        structured_exit_code = 0
        diff_report_path = '/tmp/diff.json'
        structured_report_path = '/tmp/struct.json'
        summary_path = '/tmp/summary.json'
    }
}
$json = $summary | ConvertTo-Json -Depth 8
$roundtrip = $json | ConvertFrom-Json
Assert-Eq 1 $roundtrip.schema_version 'roundtrip schema_version'
Assert-Eq '20260922-130000' $roundtrip.stamp 'roundtrip stamp'
Assert-True ($roundtrip.pass_rate_lift -gt 0) 'roundtrip pass_rate_lift positive'
Assert-Eq 0.667 $roundtrip.diff.pass_rate 'roundtrip diff pass_rate'
Assert-Eq 1.0 $roundtrip.structured.pass_rate 'roundtrip structured pass_rate'

# Regression: a loop variable named $report used to clobber the $Report parameter
# (PowerShell is case-insensitive), silently zeroing task_reports_count / terminal_run_count
# and attributing every phase to the last assertion. Keep an assertion-heavy report here.
$fieldReport = [pscustomobject]@{
    terminal_run_count = 2
    task_reports = @([pscustomobject]@{ task_id = 't1' }, [pscustomobject]@{ task_id = 't2' })
    ui_assertion_reports = @(
        [pscustomobject]@{ status = 'PASS'; phase = 'phase_2_5_real_comparison'; duration_ms = 800 }
        [pscustomobject]@{ status = 'WARN'; phase = 'phase_2_5_real_comparison'; duration_ms = 1200 }
        [pscustomobject]@{ status = 'PASS'; phase = 'phase_1_stub' }
    )
}
$fieldSummary = Get-FlakyComparisonSummary -Report $fieldReport -Mode 'screenshot_diff'
Assert-Eq 2 $fieldSummary.task_reports_count 'field report task_reports_count survives helper'
Assert-Eq 2 $fieldSummary.terminal_run_count 'field report terminal_run_count survives helper'
Assert-Eq 2 $fieldSummary.comparable_count 'field report comparable_count counts real comparisons only'
Assert-Eq 0.5 $fieldSummary.comparable_pass_rate 'field report comparable_pass_rate uses comparable denominator'
Assert-Eq 2 $fieldSummary.phase_counts['phase_2_5_real_comparison'] 'field report phase_counts real comparison'
Assert-Eq 1 $fieldSummary.phase_counts['phase_1_stub'] 'field report phase_counts stub'
Assert-Eq 800 $fieldSummary.duration_p50_ms 'field report duration_p50_ms'
Assert-Eq 1200 $fieldSummary.duration_p95_ms 'field report duration_p95_ms'

# The structured leg has no baseline phases; a blank phase must not surface as the
# misleading 'unknown' bucket in phase_counts (it made a healthy structured run look
# like unclassified data).
$structuredReport = [pscustomobject]@{
    terminal_run_count = 3
    task_reports = @([pscustomobject]@{ task_id = 't3' }, [pscustomobject]@{ task_id = 't4' }, [pscustomobject]@{ task_id = 't5' })
    ui_assertion_reports = @(
        [pscustomobject]@{ status = 'PASS'; assertion_mode = 'openeyes_structured'; duration_ms = 3017 }
        [pscustomobject]@{ status = 'PASS'; assertion_mode = 'openeyes_structured'; duration_ms = 2918 }
        [pscustomobject]@{ status = 'PASS'; assertion_mode = 'openeyes_structured'; duration_ms = 2805 }
    )
}
$structuredSummary = Get-FlakyComparisonSummary -Report $structuredReport -Mode 'openeyes_structured'
Assert-Eq 3 $structuredSummary.pass_count 'structured leg pass_count'
Assert-Eq 1.0 $structuredSummary.pass_rate 'structured leg pass_rate'
Assert-Eq 3 $structuredSummary.phase_counts['structured_assertion'] 'structured leg labels phase by assertion mode'
Assert-True (-not $structuredSummary.phase_counts.Contains('unknown')) 'structured leg has no unknown phase bucket'
Assert-Eq 2918 $structuredSummary.duration_p50_ms 'structured leg duration_p50_ms'
Assert-Eq 3017 $structuredSummary.duration_p95_ms 'structured leg duration_p95_ms'
Assert-Eq 0 $structuredSummary.comparable_count 'structured leg has no pixel-comparable samples'

# Regression: `pwsh -File ... -CaseKeys short-001,short-002` binds the whole comma list
# as one [string[]] element. Without normalisation the harness rejects it as
# `unknown baseline case: short-001,short-002` (HTTP 400) after the run name is spent.
$commaExpanded = @(Expand-FlakyComparisonArgValues -Values @('short-001,short-002', 'long-001'))
Assert-Eq 3 $commaExpanded.Count 'comma arg expands to 3 entries'
Assert-Eq 'short-001' $commaExpanded[0] 'comma arg first entry'
Assert-Eq 'short-002' $commaExpanded[1] 'comma arg second entry'
Assert-Eq 'long-001' $commaExpanded[2] 'comma arg keeps already-separate entry'

$alreadySplit = @(Expand-FlakyComparisonArgValues -Values @('strong_only'))
Assert-Eq 1 $alreadySplit.Count 'single value stays single'
Assert-Eq 'strong_only' $alreadySplit[0] 'single value preserved'

$whitespaceTrimmed = @(Expand-FlakyComparisonArgValues -Values @(' short-001 , medium-001 '))
Assert-Eq 'short-001' $whitespaceTrimmed[0] 'comma arg trims leading space'
Assert-Eq 'medium-001' $whitespaceTrimmed[1] 'comma arg trims trailing space'

$emptyExpanded = @(Expand-FlakyComparisonArgValues -Values @('', '  ', $null))
Assert-Eq 0 $emptyExpanded.Count 'blank values drop out'

$arrayType = @(Expand-FlakyComparisonArgValues -Values @('short-001,short-002'))
Assert-Eq 2 $arrayType.Count 'helper emits entries, not a nested array'
Assert-True ($arrayType[0] -is [string]) 'helper emits plain strings'

Write-Host ''
Write-Host "=== Test-RunFlakyComparison summary: pass=$pass fail=$fail ==="if ($fail -gt 0) { exit 1 } else { exit 0 }