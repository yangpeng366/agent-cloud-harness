param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ExperimentName = '',
    [string]$ReportPath = '.tmp\long-stability-smoke-25200s.json',
    [string[]]$CaseKeys = @('long-001'),
    [int]$RequestTimeoutSec = 60,
    [int]$TaskPollIntervalSec = 30,
    [int]$TaskPollTimeoutSec = 25200,
    [int]$MinimumTerminalRuns = 1,
    [int]$MinimumEvaluatedRuns = 1
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

function Safe-InvokeAgentApi {
    param(
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    try {
        $response = Invoke-AgentApi -Method $Method -Path $Path -Body $Body
        return [ordered]@{
            success = $true
            data = if ($null -ne $response -and ($response.PSObject.Properties.Name -contains 'data')) { $response.data } else { $response }
            error = $null
        }
    }
    catch {
        return [ordered]@{
            success = $false
            data = $null
            error = $_.Exception.Message
        }
    }
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

function Is-TerminalTaskState([string]$State) {
    if ([string]::IsNullOrWhiteSpace($State)) {
        return $false
    }
    switch ($State.Trim().ToLowerInvariant()) {
        'done' { return $true }
        'failed' { return $true }
        'waiting_human' { return $true }
        'paused' { return $true }
        'closed' { return $true }
        default { return $false }
    }
}

function Wait-TaskTerminal {
    param([string]$TaskId)

    $deadline = (Get-Date).AddSeconds($TaskPollTimeoutSec)
    $polls = 0
    $lastTask = $null
    while ((Get-Date) -lt $deadline) {
        $taskResponse = Invoke-AgentApi -Method GET -Path "/api/v1/tasks/$TaskId"
        $lastTask = $taskResponse.data
        if (Is-TerminalTaskState ([string]$lastTask.status)) {
            return [ordered]@{
                timed_out = $false
                polls = $polls
                task = $lastTask
            }
        }
        Start-Sleep -Seconds $TaskPollIntervalSec
        $polls++
    }

    return [ordered]@{
        timed_out = $true
        polls = $polls
        task = $lastTask
    }
}

function Get-TaskEvidencePaths([string]$TaskId) {
    return [ordered]@{
        task = "/api/v1/tasks/$TaskId"
        select_worker = "/api/v1/tasks/$TaskId/select_worker"
        live_flow = "/api/v1/tasks/$TaskId/live_flow"
        judgment_trace = "/api/v1/tasks/$TaskId/judgment_trace"
        experiment_run = "/api/v1/tasks/$TaskId/experiment_run"
        harness_trace = "/api/v1/tasks/$TaskId/harness_trace"
        tool_trace = "/api/v1/tasks/$TaskId/tool_trace?limit=10"
    }
}

if ([string]::IsNullOrWhiteSpace($ExperimentName)) {
    $ExperimentName = 'long-stability-25200s-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}

$resolvedReportPath = Resolve-OutputPath $ReportPath
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($resolvedReportPath)) | Out-Null

$health = Invoke-AgentApi -Method GET -Path '/api/v1/health'
Assert-True ($health.status -eq 'up') "harness health check failed at $BaseUrl"

$workersResponse = Invoke-AgentApi -Method GET -Path '/api/v1/workers'
Assert-True ($workersResponse.success -eq $true) 'workers endpoint failed'
$codingWorkers = @($workersResponse.data | Where-Object {
    @($_.capabilities) -contains 'coding' -and $_.suggest_only -ne $true
})

$runBody = @{
    experiment_name = $ExperimentName
    case_keys = $CaseKeys
    modes = @('orchestrated')
    priority = 'high'
    source = 'eval_long_stability'
    auto_start = $false
    metadata = @{
        real_worker_smoke = $true
        long_stability_smoke = $true
        long_stability_target_seconds = 25200
        task_poll_timeout_sec = $TaskPollTimeoutSec
    }
}
$runsResponse = Invoke-AgentApi -Method POST -Path '/api/v1/experiment_matrix/runs' -Body $runBody
Assert-True ($runsResponse.success -eq $true) 'long stability smoke run creation failed'
$batch = $runsResponse.data
$expectedRunCount = $CaseKeys.Count
Assert-True ($batch.created_run_count -eq $expectedRunCount) "expected $expectedRunCount created runs, got $($batch.created_run_count)"

$tasks = @($batch.tasks)
$terminalRunCount = 0
$evaluatedRunCount = 0
$taskReports = @()

foreach ($task in $tasks) {
    $taskId = [string]$task.id
    $paths = Get-TaskEvidencePaths -TaskId $taskId
    $routeResponse = Safe-InvokeAgentApi -Method GET -Path $paths.select_worker

    $continueResponse = Safe-InvokeAgentApi -Method POST -Path "/api/v1/tasks/$taskId/continue"
    $waitResult = Wait-TaskTerminal -TaskId $taskId
    $finalTask = $waitResult.task
    $experimentRunResponse = Safe-InvokeAgentApi -Method GET -Path $paths.experiment_run
    $experimentRun = $experimentRunResponse.data
    $liveFlow = Safe-InvokeAgentApi -Method GET -Path $paths.live_flow
    $judgmentTrace = Safe-InvokeAgentApi -Method GET -Path $paths.judgment_trace
    $toolTrace = Safe-InvokeAgentApi -Method GET -Path $paths.tool_trace
    $harnessTrace = Safe-InvokeAgentApi -Method GET -Path $paths.harness_trace

    $finalStatus = if ($null -ne $finalTask) { [string]$finalTask.status } else { '' }
    if (Is-TerminalTaskState $finalStatus) {
        $terminalRunCount++
    }
    $acceptanceResult = if ($null -ne $experimentRun) { [string]$experimentRun.acceptance_result } else { '' }
    if (-not [string]::IsNullOrWhiteSpace($acceptanceResult) -and $acceptanceResult -ne 'not_evaluated') {
        $evaluatedRunCount++
    }

    $taskReports += [ordered]@{
        task_id = $taskId
        title = [string]$task.title
        model_mode = [string]$task.metadata.model_mode
        task_case_key = [string]$task.metadata.task_case_key
        evidence_paths = $paths
        continue_result = if ($continueResponse.success) { $continueResponse.data } else { [ordered]@{ error = $continueResponse.error } }
        terminal_wait = [ordered]@{
            timed_out = [bool]$waitResult.timed_out
            polls = [int]$waitResult.polls
            final_status = $finalStatus
            final_control_node = if ($null -ne $finalTask) { [string]$finalTask.control_node } else { '' }
            final_assigned_worker = if ($null -ne $finalTask) { [string]$finalTask.assigned_worker } else { '' }
        }
        experiment_run = if ($experimentRunResponse.success -and $null -ne $experimentRun) {
            [ordered]@{
                completion_status = [string]$experimentRun.completion_status
                acceptance_result = [string]$experimentRun.acceptance_result
                total_cost = $experimentRun.total_cost
                handoff_count = $experimentRun.handoff_count
                resume_count = $experimentRun.resume_count
                human_gate_count = $experimentRun.human_gate_count
                failure_reason = [string]$experimentRun.failure_reason
                final_artifact_quality_note = [string]$experimentRun.final_artifact_quality_note
                metadata = $experimentRun.metadata
            }
        } else {
            [ordered]@{ error = $experimentRunResponse.error }
        }
        live_flow_observed = $liveFlow.success
        judgment_trace_observed = $judgmentTrace.success
        tool_trace_observed = $toolTrace.success
        harness_trace_observed = $harnessTrace.success
    }
}

$report = [ordered]@{
    base_url = $BaseUrl
    experiment_name = $ExperimentName
    request_timeout_sec = $RequestTimeoutSec
    case_keys = $CaseKeys
    expected_run_count = $expectedRunCount
    created_run_count = $batch.created_run_count
    task_poll_interval_sec = $TaskPollIntervalSec
    task_poll_timeout_sec = $TaskPollTimeoutSec
    minimum_terminal_runs = $MinimumTerminalRuns
    minimum_evaluated_runs = $MinimumEvaluatedRuns
    generated_at = (Get-Date).ToString('o')
    checks = [ordered]@{
        created_expected_run_count = ($batch.created_run_count -eq $expectedRunCount)
        minimum_terminal_runs_met = ($terminalRunCount -ge $MinimumTerminalRuns)
        minimum_evaluated_runs_met = ($evaluatedRunCount -ge $MinimumEvaluatedRuns)
    }
    terminal_run_count = $terminalRunCount
    evaluated_run_count = $evaluatedRunCount
    task_reports = $taskReports
}

[System.IO.File]::WriteAllText($resolvedReportPath, ($report | ConvertTo-Json -Depth 30), [System.Text.UTF8Encoding]::new($false))

Assert-True ($terminalRunCount -ge $MinimumTerminalRuns) "expected at least $MinimumTerminalRuns terminal runs, got $terminalRunCount"
Assert-True ($evaluatedRunCount -ge $MinimumEvaluatedRuns) "expected at least $MinimumEvaluatedRuns evaluated runs, got $evaluatedRunCount"

Write-Host "Long stability smoke 25200s passed: $resolvedReportPath"