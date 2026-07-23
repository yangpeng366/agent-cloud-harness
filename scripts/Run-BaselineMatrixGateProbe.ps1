param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ExperimentName = '',
    [string]$ReportPath = '.tmp\baseline-matrix-gate-probe.json',
    [string[]]$CaseKeys = @('short-001', 'medium-001', 'long-001'),
    [string[]]$Modes = @('strong_only', 'small_only', 'orchestrated'),
    [int]$RequestTimeoutSec = 30
)

$ErrorActionPreference = 'Stop'

function Resolve-OutputPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

function Invoke-AgentApi {
    param(
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    $uri = "$BaseUrl$Path"
    $headers = @{ 'Content-Type' = 'application/json' }
    if ($Method -eq 'GET') {
        return Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec $RequestTimeoutSec
    }
    $json = if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Depth 20 -Compress }
    return Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $json -TimeoutSec $RequestTimeoutSec
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Has-TextArray([object]$Value) {
    if ($null -eq $Value) {
        return $false
    }
    $items = @($Value)
    if ($items.Count -eq 0) {
        return $false
    }
    foreach ($item in $items) {
        if ([string]::IsNullOrWhiteSpace([string]$item)) {
            return $false
        }
    }
    return $true
}
function Get-MapCount {
    param(
        [object]$Value,
        [string]$Key
    )
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace($Key)) {
        return 0
    }
    $observed = $Value.$Key
    if ($null -eq $observed) {
        return 0
    }
    return [int]$observed
}

$experimentNameWasProvided = -not [string]::IsNullOrWhiteSpace($ExperimentName)
if ([string]::IsNullOrWhiteSpace($ExperimentName)) {
    $ExperimentName = 'baseline-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}

$resolvedReportPath = Resolve-OutputPath $ReportPath
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($resolvedReportPath)) | Out-Null

$health = Invoke-AgentApi -Method GET -Path '/api/v1/health'
Assert-True ($health.status -eq 'up') "harness health check failed at $BaseUrl"

if ($experimentNameWasProvided) {
    $existingSummaryResponse = Invoke-AgentApi -Method GET -Path "/api/v1/experiment_matrix/summary?experiment_name=$([System.Uri]::EscapeDataString($ExperimentName))"
    Assert-True ($existingSummaryResponse.success -eq $true) 'experiment matrix summary precheck failed'
    $existingTotalRuns = [int]$existingSummaryResponse.data.total_runs
    $existingExperimentMessage = "experiment_name already contains $existingTotalRuns runs: $ExperimentName; use a unique name or omit -ExperimentName"
    Assert-True ($existingTotalRuns -eq 0) $existingExperimentMessage
}

$casesResponse = Invoke-AgentApi -Method GET -Path '/api/v1/experiment_matrix/cases'
Assert-True ($casesResponse.success -eq $true) 'experiment matrix cases endpoint failed'
$cases = @($casesResponse.data)
Assert-True ($cases.Count -eq 9) "expected 9 baseline cases, got $($cases.Count)"
Assert-True ((@($cases | Where-Object { $_.task_length_bucket -eq 'short' })).Count -eq 3) 'expected 3 short cases'
Assert-True ((@($cases | Where-Object { $_.task_length_bucket -eq 'medium' })).Count -eq 3) 'expected 3 medium cases'
Assert-True ((@($cases | Where-Object { $_.task_length_bucket -eq 'long' })).Count -eq 3) 'expected 3 long cases'

foreach ($case in $cases) {
    Assert-True (Has-TextArray $case.workspace_preconditions) "case $($case.case_key) missing workspace_preconditions"
    Assert-True (Has-TextArray $case.acceptance_criteria) "case $($case.case_key) missing acceptance_criteria"
    Assert-True (Has-TextArray $case.expected_artifacts) "case $($case.case_key) missing expected_artifacts"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$case.recovery_policy)) "case $($case.case_key) missing recovery_policy"
}

$knownCaseKeys = @{}
foreach ($case in $cases) {
    $knownCaseKeys[[string]$case.case_key] = $true
}
foreach ($caseKey in $CaseKeys) {
    Assert-True ($knownCaseKeys.ContainsKey($caseKey)) "requested case key not found in catalog: $caseKey"
}

$runBody = @{
    experiment_name = $ExperimentName
    case_keys = $CaseKeys
    modes = $Modes
    priority = 'high'
    source = 'eval_gate'
    auto_start = $false
    metadata = @{
        gate_probe = $true
        gate_probe_name = 'baseline_matrix_v1_minimal'
    }
}
$runsResponse = Invoke-AgentApi -Method POST -Path '/api/v1/experiment_matrix/runs' -Body $runBody
Assert-True ($runsResponse.success -eq $true) 'experiment matrix run creation failed'
$batch = $runsResponse.data
$expectedRunCount = $CaseKeys.Count * $Modes.Count
Assert-True ($batch.created_run_count -eq $expectedRunCount) "expected $expectedRunCount created runs, got $($batch.created_run_count)"

$createdTasks = @($batch.tasks)
Assert-True ($createdTasks.Count -eq $expectedRunCount) "expected $expectedRunCount returned tasks, got $($createdTasks.Count)"
foreach ($task in $createdTasks) {
    $metadata = $task.metadata
    Assert-True ($metadata.experiment_name -eq $ExperimentName) "task $($task.id) experiment_name mismatch"
    Assert-True ($metadata.baseline_matrix_source -eq 'baseline_v1') "task $($task.id) baseline source mismatch"
    Assert-True (Has-TextArray $metadata.baseline_workspace_preconditions) "task $($task.id) missing baseline_workspace_preconditions"
    Assert-True (Has-TextArray $metadata.baseline_acceptance_criteria) "task $($task.id) missing baseline_acceptance_criteria"
    Assert-True (Has-TextArray $metadata.baseline_expected_artifacts) "task $($task.id) missing baseline_expected_artifacts"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$metadata.baseline_recovery_policy)) "task $($task.id) missing baseline_recovery_policy"
    Assert-True ([double]$metadata.baseline_cost_threshold_units -gt 0.0) "task $($task.id) missing baseline_cost_threshold_units"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$metadata.cost_gate_basis)) "task $($task.id) missing cost_gate_basis"
}

$summaryResponse = Invoke-AgentApi -Method GET -Path "/api/v1/experiment_matrix/summary?experiment_name=$([System.Uri]::EscapeDataString($ExperimentName))"
Assert-True ($summaryResponse.success -eq $true) 'experiment matrix summary endpoint failed'
$summary = $summaryResponse.data
Assert-True ($summary.experiment_name -eq $ExperimentName) 'summary experiment_name mismatch'
Assert-True ($summary.total_runs -eq $expectedRunCount) "expected summary total_runs $expectedRunCount, got $($summary.total_runs)"
Assert-True ((@($summary.mode_summaries)).Count -eq 3) 'expected 3 mode summaries'
Assert-True ((@($summary.case_comparisons)).Count -eq $CaseKeys.Count) "expected $($CaseKeys.Count) case comparisons"

$modeGateRollup = [ordered]@{}
foreach ($mode in $Modes) {
    $modeSummary = @($summary.mode_summaries | Where-Object { $_.model_mode -eq $mode }) | Select-Object -First 1
    Assert-True ($null -ne $modeSummary) "missing mode summary: $mode"
    Assert-True ($modeSummary.run_count -eq $CaseKeys.Count) "mode $mode expected run_count $($CaseKeys.Count), got $($modeSummary.run_count)"
    $acceptanceGateNotEvaluatedCount = Get-MapCount $modeSummary.acceptance_gate_result_counts 'not_evaluated'
    $artifactQualityNotEvaluatedCount = Get-MapCount $modeSummary.artifact_quality_gate_status_counts 'not_evaluated'
    $costGateWithinThresholdCount = Get-MapCount $modeSummary.cost_gate_status_counts 'within_threshold'
    Assert-True ($acceptanceGateNotEvaluatedCount -eq $CaseKeys.Count) "mode $mode expected acceptance_gate_result_counts.not_evaluated $($CaseKeys.Count), got $acceptanceGateNotEvaluatedCount"
    Assert-True ($artifactQualityNotEvaluatedCount -eq $CaseKeys.Count) "mode $mode expected artifact_quality_gate_status_counts.not_evaluated $($CaseKeys.Count), got $artifactQualityNotEvaluatedCount"
    Assert-True ($costGateWithinThresholdCount -eq $CaseKeys.Count) "mode $mode expected cost_gate_status_counts.within_threshold $($CaseKeys.Count), got $costGateWithinThresholdCount"
    Assert-True (([int]$modeSummary.runs_with_failure_reason) -eq 0) "mode $mode expected runs_with_failure_reason 0, got $($modeSummary.runs_with_failure_reason)"
    $modeGateRollup[$mode] = [ordered]@{
        acceptance_gate_result_counts = $modeSummary.acceptance_gate_result_counts
        artifact_quality_gate_status_counts = $modeSummary.artifact_quality_gate_status_counts
        cost_gate_status_counts = $modeSummary.cost_gate_status_counts
        runs_with_failure_reason = [int]$modeSummary.runs_with_failure_reason
    }
}

foreach ($caseKey in $CaseKeys) {
    $caseComparison = @($summary.case_comparisons | Where-Object { $_.task_case_key -eq $caseKey }) | Select-Object -First 1
    Assert-True ($null -ne $caseComparison) "missing case comparison: $caseKey"
    foreach ($mode in $Modes) {
        Assert-True ($null -ne $caseComparison.runs_by_mode.$mode) "case $caseKey missing run for mode $mode"
    }
}

$report = [ordered]@{
    base_url = $BaseUrl
    experiment_name = $ExperimentName
    request_timeout_sec = $RequestTimeoutSec
    case_keys = $CaseKeys
    modes = $Modes
    expected_run_count = $expectedRunCount
    catalog_case_count = $cases.Count
    created_run_count = $batch.created_run_count
    summary_total_runs = $summary.total_runs
    generated_at = (Get-Date).ToString('o')
    checks = [ordered]@{
        health_up = $true
        catalog_has_3x3_cases = $true
        catalog_cases_have_contract = $true
        created_tasks_have_baseline_contract = $true
        summary_has_mode_summaries = $true
        summary_has_case_comparisons = $true
        summary_has_acceptance_gate_counts = $true
        summary_has_artifact_quality_gate_counts = $true
        summary_has_cost_gate_counts = $true
        summary_has_failure_reason_rollup = $true
    }
    mode_gate_rollup = $modeGateRollup
    created_task_ids = @($createdTasks | ForEach-Object { $_.id })
}

[System.IO.File]::WriteAllText($resolvedReportPath, ($report | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
Write-Host "Baseline matrix gate probe passed: $resolvedReportPath"
