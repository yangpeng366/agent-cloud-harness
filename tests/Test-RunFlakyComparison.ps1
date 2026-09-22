# Test-RunFlakyComparison.ps1
#
# Contract test for scripts/Run-FlakyComparison.ps1 helper functions.
# We test the script's Get-Summary helper in isolation by re-sourcing
# the function body. The full script invocation requires a running harness.

$ErrorActionPreference = 'Stop'

# Extract the Get-Summary helper logic for testing without harness dependency.
function Get-Summary {
    param([object]$Report, [string]$Mode)
    if (-not $Report) { return $null }
    $reports = @($Report.ui_assertion_reports)
    $count = $reports.Count
    if ($count -eq 0) { return [ordered]@{ mode = $Mode; pass_count = 0; fail_count = 0; pass_rate = $null } }
    $pass = @($reports | Where-Object { $_.status -eq 'PASS' }).Count
    $fail = $count - $pass
    return [ordered]@{
        mode = $Mode
        pass_count = $pass
        fail_count = $fail
        pass_rate = if ($count -gt 0) { [math]::Round($pass / $count, 4) } else { $null }
        task_reports_count = $Report.task_reports.Count
        terminal_run_count = $Report.terminal_run_count
    }
}

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
$nullSummary = Get-Summary -Report $null -Mode 'screenshot_diff'
Assert-Null $nullSummary 'null report returns null'

# empty report
$emptyReport = [pscustomobject]@{
    ui_assertion_reports = @()
    task_reports = @()
    terminal_run_count = 0
}
$emptySummary = Get-Summary -Report $emptyReport -Mode 'openeyes_structured'
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
$passSummary = Get-Summary -Report $passReport -Mode 'openeyes_structured'
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
$mixedSummary = Get-Summary -Report $mixedReport -Mode 'screenshot_diff'
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

Write-Host ''
Write-Host "=== Test-RunFlakyComparison summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }