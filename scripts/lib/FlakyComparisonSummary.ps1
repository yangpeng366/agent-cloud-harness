# FlakyComparisonSummary.ps1
#
# Aggregation helper for the FEAT-05 flaky rate comparison
# (docs/FEAT-05_FLAKY_RATE_COMPARISON_DESIGN.md §2.2 / §2.3).
#
# Lives in scripts/lib so the production driver (Run-FlakyComparison.ps1) and the
# contract test (tests/Test-RunFlakyComparison.ps1) exercise the same code path.
# The previous in-test copy silently diverged from production.

function Get-FlakyComparisonSummary {
    param([object]$Report, [string]$Mode)
    if (-not $Report) { return $null }
    $reports = @($Report.ui_assertion_reports)
    $count = $reports.Count
    if ($count -eq 0) { return [ordered]@{ mode = $Mode; pass_count = 0; fail_count = 0; pass_rate = $null } }
    $pass = @($reports | Where-Object { $_.status -eq 'PASS' }).Count
    $fail = $count - $pass
    # Only a real pixel comparison is metric-bearing; phase_1_stub only proves wiring.
    $comparableReports = @($reports | Where-Object { $_.phase -eq 'phase_2_5_real_comparison' })
    $comparable = $comparableReports.Count
    $phaseCounts = [ordered]@{}
    # NB: keep the loop variable away from $Report -- PowerShell variables are
    # case-insensitive, so `foreach ($report in $reports)` would overwrite the
    # parameter and zero out every non-assertion field below.
    foreach ($assertionReport in $reports) {
        # The structured leg has no baseline phases at all, so a blank phase is not a
        # data gap there -- label it by assertion mode instead of the misleading
        # 'unknown' bucket that previously made the structured column look broken.
        $phaseKey = if (-not [string]::IsNullOrWhiteSpace([string]$assertionReport.phase)) {
            [string]$assertionReport.phase
        } elseif (-not [string]::IsNullOrWhiteSpace([string]$assertionReport.assertion_mode)) {
            'structured_assertion'
        } else {
            'unknown'
        }
        if (-not $phaseCounts.Contains($phaseKey)) { $phaseCounts[$phaseKey] = 0 }
        $phaseCounts[$phaseKey] = $phaseCounts[$phaseKey] + 1
    }
    $assertionDurations = @($reports | Where-Object { $null -ne $_.duration_ms } | ForEach-Object { [double]$_.duration_ms })
    $sortedDurations = @($assertionDurations | Sort-Object)
    $percentile = {
        param([double[]]$Values, [double]$P)
        if ($Values.Count -eq 0) { return $null }
        if ($Values.Count -eq 1) { return [int]$Values[0] }
        $index = [int][math]::Ceiling($P * $Values.Count) - 1
        if ($index -lt 0) { $index = 0 }
        if ($index -ge $Values.Count) { $index = $Values.Count - 1 }
        return [int]$Values[$index]
    }
    return [ordered]@{
        mode = $Mode
        pass_count = $pass
        fail_count = $fail
        pass_rate = if ($count -gt 0) { [math]::Round($pass / $count, 4) } else { $null }
        comparable_count = $comparable
        comparable_pass_rate = if ($comparable -gt 0) {
            [math]::Round(@($comparableReports | Where-Object { $_.status -eq 'PASS' }).Count / $comparable, 4)
        } else { $null }
        phase_counts = $phaseCounts
        duration_p50_ms = & $percentile $sortedDurations 0.50
        duration_p95_ms = & $percentile $sortedDurations 0.95
        task_reports_count = @($Report.task_reports).Count
        terminal_run_count = $Report.terminal_run_count
    }
}
