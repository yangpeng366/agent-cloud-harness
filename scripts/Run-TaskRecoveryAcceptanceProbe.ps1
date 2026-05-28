param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReportPath = '.tmp\task-recovery-acceptance-probe.json',
    [switch]$IncludeResumeExecution,
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
        [object]$Body = $null,
        [switch]$AllowFailure,
        [int]$TimeoutSec = $RequestTimeoutSec
    )

    $uri = "$BaseUrl$Path"
    $headers = @{ 'Content-Type' = 'application/json' }
    try {
        if ($Method -eq 'GET') {
            return Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec $TimeoutSec
        }
        $json = if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Depth 20 -Compress }
        return Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $json -TimeoutSec $TimeoutSec
    } catch {
        if (-not $AllowFailure) {
            throw
        }
        $message = $_.Exception.Message
        $status = $null
        $text = $null
        $response = $_.Exception.Response
        if ($null -ne $response) {
            $statusCode = $response.StatusCode
            if ($null -ne $statusCode) {
                $status = [int]$statusCode
            }
            if ($null -ne $response.Content -and $response.Content.GetType().GetMethod('ReadAsStringAsync')) {
                $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } else {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = [System.IO.StreamReader]::new($stream)
                    $text = $reader.ReadToEnd()
                }
            }
        }
        if ([string]::IsNullOrWhiteSpace($text)) {
            return [pscustomobject]@{
                success = $false
                status = $status
                message = $message
            }
        }
        return $text | ConvertFrom-Json
    }
}

function Invoke-AgentApiBounded {
    param(
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [int]$TimeoutSec = $RequestTimeoutSec
    )

    $baseUrlSnapshot = $BaseUrl
    $job = Start-Job -ScriptBlock {
        param($BaseUrlValue, $MethodValue, $PathValue, $BodyValue, $TimeoutSecValue)
        $uri = "$BaseUrlValue$PathValue"
        $headers = @{ 'Content-Type' = 'application/json' }
        if ($MethodValue -eq 'GET') {
            return Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec $TimeoutSecValue
        }
        $json = if ($null -eq $BodyValue) { '{}' } else { $BodyValue | ConvertTo-Json -Depth 20 -Compress }
        return Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $json -TimeoutSec $TimeoutSecValue
    } -ArgumentList $baseUrlSnapshot, $Method, $Path, $Body, $TimeoutSec

    try {
        if (Wait-Job -Job $job -Timeout $TimeoutSec) {
            return Receive-Job -Job $job -ErrorAction Stop
        }
        Stop-Job -Job $job -ErrorAction SilentlyContinue
        throw [System.TimeoutException]::new("client bounded timeout after $TimeoutSec seconds")
    } finally {
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
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

function New-ProbeTask {
    param(
        [string]$Title,
        [hashtable]$Metadata
    )
    $body = @{
        title = $Title
        task_type = 'coding'
        source = 'user'
        priority = 'medium'
        intent = $Title
        auto_start = $false
        metadata = $Metadata
    }
    $created = Invoke-AgentApi -Method POST -Path '/api/v1/tasks' -Body $body
    Assert-True ($created.success -eq $true) "failed to create task: $Title"
    return $created.data
}

function Set-ProbeTaskFailed {
    param(
        [string]$TaskId,
        [string]$Reason
    )
    $updated = Invoke-AgentApi -Method POST -Path "/api/v1/tasks/$TaskId/state" -Body @{
        state = 'failed'
        reason = $Reason
    }
    Assert-True ($updated.success -eq $true) "failed to mark task failed: $TaskId"
    return $updated.data
}

function Find-RecoveryPlan {
    param(
        [string]$TaskId
    )
    $plans = Invoke-AgentApi -Method GET -Path '/api/v1/tasks/recoverable?limit=20'
    Assert-True ($plans.success -eq $true) 'recoverable endpoint failed'
    foreach ($plan in $plans.data) {
        if ($plan.task_id -eq $TaskId) {
            return $plan
        }
    }
    throw "recoverable plan not found for task $TaskId"
}

$resolvedReportPath = Resolve-OutputPath $ReportPath
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($resolvedReportPath)) | Out-Null

$health = Invoke-AgentApi -Method GET -Path '/api/v1/health'
Assert-True ($health.status -eq 'up') "harness health check failed at $BaseUrl"

$results = [ordered]@{
    base_url = $BaseUrl
    request_timeout_sec = $RequestTimeoutSec
    started_at = (Get-Date).ToString('o')
    scenarios = @()
}

$threadTask = New-ProbeTask -Title 'probe recoverable thread-not-found' -Metadata @{
    failure_class = 'worker_runtime_transient'
    provider_failure_class = 'provider_runtime_transient'
}
Set-ProbeTaskFailed -TaskId $threadTask.id -Reason 'probe: thread not found' | Out-Null
$threadPlan = Find-RecoveryPlan -TaskId $threadTask.id
Assert-True ($threadPlan.recoverable -eq $true) 'thread-not-found task should be recoverable'
Assert-True ($threadPlan.provider_failure_class -eq 'provider_runtime_transient') 'thread-not-found provider class mismatch'
Assert-True ($threadPlan.recovery_execution_mode -eq 'fresh_session') 'thread-not-found recovery mode mismatch'
$results.scenarios += [ordered]@{
    name = 'recoverable_provider_runtime_transient'
    task_id = $threadTask.id
    provider_failure_class = $threadPlan.provider_failure_class
    recommended_action = $threadPlan.recommended_action
    recovery_execution_mode = $threadPlan.recovery_execution_mode
}

$handoffTask = New-ProbeTask -Title 'probe recover auto handoff' -Metadata @{
    failure_class = 'worker_runtime_transient'
    provider_failure_class = 'provider_runtime_transient'
    auto_handoff_target = 'claude'
}
Set-ProbeTaskFailed -TaskId $handoffTask.id -Reason 'probe: switch worker' | Out-Null
$handoffResult = Invoke-AgentApi -Method POST -Path "/api/v1/tasks/$($handoffTask.id)/recover" -Body @{
    mode = 'auto'
    reason = 'task recovery acceptance probe'
}
Assert-True ($handoffResult.success -eq $true) 'auto handoff recovery failed'
Assert-True ($handoffResult.data.plan.recommended_action -eq 'handoff') 'auto handoff plan did not choose handoff'
Assert-True ($handoffResult.data.handoff_result.assigned_worker -eq 'claude') 'auto handoff did not assign target worker'
$results.scenarios += [ordered]@{
    name = 'auto_handoff_recovery'
    task_id = $handoffTask.id
    recommended_action = $handoffResult.data.plan.recommended_action
    assigned_worker = $handoffResult.data.handoff_result.assigned_worker
}

$blockedTask = New-ProbeTask -Title 'probe recover blocked auth' -Metadata @{
    provider_failure_class = 'provider_auth_failed'
}
Set-ProbeTaskFailed -TaskId $blockedTask.id -Reason 'probe: auth failed' | Out-Null
$blockedResult = Invoke-AgentApi -Method POST -Path "/api/v1/tasks/$($blockedTask.id)/recover" -Body @{
    mode = 'auto'
    reason = 'task recovery acceptance probe'
} -AllowFailure
Assert-True ($blockedResult.success -eq $false) 'environment-blocked recovery should fail'
$blockedMessage = [string]$blockedResult.message
if (-not [string]::IsNullOrWhiteSpace($blockedMessage)) {
    Assert-True ($blockedMessage -like '*provider_auth_failed*' -or $blockedMessage -like '*400*') 'environment-blocked failure reason should mention provider_auth_failed or HTTP 400'
}
$results.scenarios += [ordered]@{
    name = 'environment_blocked_recovery_rejected'
    task_id = $blockedTask.id
    expected_provider_failure_class = 'provider_auth_failed'
    success = $blockedResult.success
    status = $blockedResult.status
    message = $blockedMessage
}

if ($IncludeResumeExecution) {
    $resumeTask = New-ProbeTask -Title 'probe recover resume execution' -Metadata @{
        failure_class = 'worker_runtime_transient'
        provider_failure_class = 'provider_runtime_transient'
        provider_session_id = 'old-session'
        provider_thread_id = 'old-thread'
        codex_thread_id = 'old-codex-thread'
    }
    Set-ProbeTaskFailed -TaskId $resumeTask.id -Reason 'probe: fresh session resume' | Out-Null
    $resumeResult = Invoke-AgentApi -Method POST -Path "/api/v1/tasks/$($resumeTask.id)/recover?async=true" -Body @{
        mode = 'auto'
        reason = 'task recovery acceptance probe'
    }
    Assert-True ($resumeResult.success -eq $true) 'fresh-session async resume recovery failed'
    Assert-True ($resumeResult.data.accepted -eq $true) 'fresh-session async resume should be accepted'
    Assert-True ($resumeResult.data.async -eq $true) 'fresh-session async resume should report async=true'
    Assert-True ($resumeResult.data.plan.recovery_execution_mode -eq 'fresh_session') 'fresh-session async resume plan mode mismatch'
    $jobResult = Invoke-AgentApi -Method GET -Path "/api/v1/tasks/$($resumeTask.id)/recovery_jobs?limit=5"
    Assert-True ($jobResult.success -eq $true) 'recovery jobs endpoint failed'
    $matchingJob = $null
    foreach ($job in $jobResult.data) {
        if ($job.id -eq $resumeResult.data.request_id) {
            $matchingJob = $job
            break
        }
    }
    Assert-True ($matchingJob -ne $null) 'async recovery job not found by request_id'
    $results.scenarios += [ordered]@{
        name = 'fresh_session_async_resume_recovery'
        task_id = $resumeTask.id
        accepted = $resumeResult.data.accepted
        async = $resumeResult.data.async
        request_id = $resumeResult.data.request_id
        job_status = $matchingJob.status
        status_url = $resumeResult.data.status_url
        recovery_execution_mode = $resumeResult.data.plan.recovery_execution_mode
    }
}

$results.completed_at = (Get-Date).ToString('o')
$results | ConvertTo-Json -Depth 20 | Set-Content -Path $resolvedReportPath -Encoding UTF8

Write-Host "Task recovery acceptance probe passed"
Write-Host "Report: $resolvedReportPath"
