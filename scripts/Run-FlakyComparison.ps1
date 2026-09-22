# Run-FlakyComparison.ps1
#
# Phase 2 data-collection entry point for the FEAT-05 flaky rate comparison
# design (docs/FEAT-05_FLAKY_RATE_COMPARISON_DESIGN.md).
#
# Runs the same baseline matrix twice -- once with screenshot_diff mode and
# once with openeyes_structured -- so the two assertion backends can be
# compared on the same tasks. Aggregates results into a summary JSON.
#
# Usage:
#   pwsh -File scripts/Run-FlakyComparison.ps1
#   pwsh -File scripts/Run-FlakyComparison.ps1 -CaseKeys @('short-001') -Modes @('strong_only')
#   pwsh -File scripts/Run-FlakyComparison.ps1 -UiAssertionAppTitleContains "MyApp"
#   pwsh -File scripts/Run-FlakyComparison.ps1 -Cleanup
#
# Per docs/JEV_PATROL_INTEGRATION_PLAN.md §L3 this is a heuristic-path dry run;
# each invocation produces a fresh <stamp>-flaky-comparison.json report.

[CmdletBinding()]
param(
    [string[]]$CaseKeys = @('short-001', 'medium-001', 'long-001'),
    [string[]]$Modes = @('strong_only', 'small_only', 'orchestrated'),
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$TaskPollTimeoutSec = 240,
    [int]$TaskPollIntervalSec = 5,
    [string]$ReportDirectory = '.tmp\flaky-comparison',
    [string]$UiAssertionAppTitleContains = 'ACH-OpenEyes-Baseline-Target',
    [int]$MinimumTerminalRuns = 1,
    [int]$MinimumEvaluatedRuns = 1,
    [string]$BaselineDirectory = '.tmp\openeyes-ui-assertion\baseline-png',
    [string]$CurrentDirectory = '.tmp\openeyes-ui-assertion\current-png',
    [string[]]$ExpectedElementAutomationId = @(),
    [string[]]$ExpectedElementControlType = @(),
    [string[]]$ExpectedElementNameContains = @(),
    [int]$MinimumElementCount = 1,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ReportDirectory)) {
    New-Item -ItemType Directory -Force -Path $ReportDirectory | Out-Null
}

$stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')

if ($Cleanup) {
    Get-ChildItem -Path $ReportDirectory -Filter '*-flaky-comparison.json' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-7) } |
        Remove-Item -Force
    Write-Host '[cleanup] removed comparison reports older than 7 days'
    exit 0
}

$baselineScript = Join-Path $PSScriptRoot 'Run-BaselineMatrixRealWorkerSmoke.ps1'
if (-not (Test-Path -LiteralPath $baselineScript)) {
    throw "baseline matrix script not found: $baselineScript"
}

$diffExperiment = "flaky-diff-$stamp"
$structuredExperiment = "flaky-structured-$stamp"

$diffReportPath = Join-Path $ReportDirectory ("{0}-flaky-diff.json" -f $stamp)
$structuredReportPath = Join-Path $ReportDirectory ("{0}-flaky-structured.json" -f $stamp)
$summaryPath = Join-Path $ReportDirectory ("{0}-flaky-comparison.json" -f $stamp)

Write-Host "=== Phase 2 flaky comparison ==="
Write-Host "stamp: $stamp"
Write-Host "case_keys: $($CaseKeys -join ', ')"
Write-Host "modes: $($Modes -join ', ')"

Write-Host ''
Write-Host "Run 1/2: screenshot_diff ..."
& pwsh -NoProfile -File $baselineScript `
    -CaseKeys $CaseKeys `
    -Modes $Modes `
    -BaseUrl $BaseUrl `
    -TaskPollTimeoutSec $TaskPollTimeoutSec `
    -TaskPollIntervalSec $TaskPollIntervalSec `
    -UiAssertionMode screenshot_diff `
    -UiAssertionAppTitleContains $UiAssertionAppTitleContains `
    -UiAssertionBaselineDirectory $BaselineDirectory `
    -UiAssertionCurrentDirectory $CurrentDirectory `
    -ExperimentName $diffExperiment `
    -ReportPath $diffReportPath `
    -MinimumTerminalRuns $MinimumTerminalRuns `
    -MinimumEvaluatedRuns $MinimumEvaluatedRuns 2>&1 | Out-Null
$diffExit = $LASTEXITCODE
Write-Host "diff exit=$diffExit"

Write-Host ''
Write-Host "Run 2/2: openeyes_structured ..."
& pwsh -NoProfile -File $baselineScript `
    -CaseKeys $CaseKeys `
    -Modes $Modes `
    -BaseUrl $BaseUrl `
    -TaskPollTimeoutSec $TaskPollTimeoutSec `
    -TaskPollIntervalSec $TaskPollIntervalSec `
    -UiAssertionMode openeyes_structured `
    -UiAssertionAppTitleContains $UiAssertionAppTitleContains `
    -UiAssertionExpectedElementAutomationId $ExpectedElementAutomationId `
    -UiAssertionExpectedElementControlType $ExpectedElementControlType `
    -UiAssertionExpectedElementNameContains $ExpectedElementNameContains `
    -UiAssertionMinimumElementCount $MinimumElementCount `
    -ExperimentName $structuredExperiment `
    -ReportPath $structuredReportPath `
    -MinimumTerminalRuns $MinimumTerminalRuns `
    -MinimumEvaluatedRuns $MinimumEvaluatedRuns 2>&1 | Out-Null
$structuredExit = $LASTEXITCODE
Write-Host "structured exit=$structuredExit"

Write-Host ''
Write-Host "Aggregate summary ..."
$diffReport = if (Test-Path -LiteralPath $diffReportPath) {
    Get-Content -LiteralPath $diffReportPath -Raw | ConvertFrom-Json
} else { $null }
$structuredReport = if (Test-Path -LiteralPath $structuredReportPath) {
    Get-Content -LiteralPath $structuredReportPath -Raw | ConvertFrom-Json
} else { $null }

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

$diffSummary = Get-Summary -Report $diffReport -Mode 'screenshot_diff'
$structuredSummary = Get-Summary -Report $structuredReport -Mode 'openeyes_structured'

$summary = [ordered]@{
    schema_version = 1
    stamp = $stamp
    case_keys = $CaseKeys
    modes = $Modes
    diff = $diffSummary
    structured = $structuredSummary
    pass_rate_lift = if ($diffSummary -and $structuredSummary -and $null -ne $diffSummary.pass_rate -and $null -ne $structuredSummary.pass_rate) {
        [math]::Round($structuredSummary.pass_rate - $diffSummary.pass_rate, 4)
    } else { $null }
    overall = [ordered]@{
        diff_exit_code = $diffExit
        structured_exit_code = $structuredExit
        diff_report_path = $diffReportPath
        structured_report_path = $structuredReportPath
        summary_path = $summaryPath
    }
}

$json = $summary | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($summaryPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Host "summary: $summaryPath"
Write-Host "diff pass_rate: $($diffSummary.pass_rate)"
Write-Host "structured pass_rate: $($structuredSummary.pass_rate)"
if ($null -ne $summary.pass_rate_lift) {
    Write-Host ("pass_rate_lift: {0:+#0.000;-#0.000;0}" -f $summary.pass_rate_lift)
}

if ($diffExit -ne 0 -or $structuredExit -ne 0) { exit 1 } else { exit 0 }