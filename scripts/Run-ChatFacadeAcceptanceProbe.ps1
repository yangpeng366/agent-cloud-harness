param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$UseResponsesSurface,
    [switch]$AutoStartTask
)

$ErrorActionPreference = "Stop"

function Assert-FacadeReady {
    $healthUp = $false
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -TimeoutSec 5
        if ($health.status -eq "up" -or $health.data.status -eq "up") {
            $healthUp = $true
        }
    } catch {
    }

    try {
        $models = Invoke-RestMethod -Uri "$BaseUrl/v1/models" -TimeoutSec 10
        if ($null -eq $models) {
            throw "empty /v1/models response"
        }
        return
    } catch {
        $message = $_.Exception.Message
        if ($healthUp) {
            throw "facade routes are not ready at $BaseUrl. /api/v1/health is up, but /v1/models did not respond correctly. You are likely connected to a stale or non-shaded harness instance. Restart with scripts\\Run-HarnessWithJava21.ps1 or scripts\\Run-ChatFacadeAcceptanceWithLocalHarness.ps1. Underlying error: $message"
        }
        throw "harness is not reachable at $BaseUrl. Start it first with scripts\\Run-HarnessWithJava21.ps1 or scripts\\Run-ChatFacadeAcceptanceWithLocalHarness.ps1, then rerun this probe. Underlying error: $message"
    }
}

function Invoke-FacadePost {
    param(
        [string]$Path,
        [hashtable]$Body
    )

    Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl$Path" `
        -ContentType "application/json; charset=utf-8" `
        -Body ($Body | ConvertTo-Json -Depth 10)
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

$surfacePath = if ($UseResponsesSurface) { "/v1/responses" } else { "/v1/chat/completions" }
$surfaceLabel = if ($UseResponsesSurface) { "responses" } else { "chat_completions" }

Assert-FacadeReady

$sessionTitle = "facade acceptance $surfaceLabel $(Get-Date -Format 'yyyyMMdd-HHmmss')"
$sessionResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/v1/sessions" `
    -ContentType "application/json; charset=utf-8" `
    -Body (@{ title = $sessionTitle } | ConvertTo-Json)

$sessionId = $sessionResponse.data.id
Assert-True -Condition ([string]::IsNullOrWhiteSpace($sessionId) -eq $false) -Message "failed to create session"

if ($UseResponsesSurface) {
    $messageBody = @{
        model = "agentcloud-default"
        input = "record a note through responses"
        stream = $false
        metadata = @{
            task_mode = "message_only"
            session_id = $sessionId
        }
    }
    $taskBody = @{
        model = "agentcloud-default"
        input = "create a task through responses and continue it"
        stream = $false
        metadata = @{
            task_mode = "task_required"
            session_id = $sessionId
            title = "responses acceptance task"
            task_type = "continuation"
            priority = "high"
            auto_start = $AutoStartTask.IsPresent
        }
    }
}
else {
    $messageBody = @{
        model = "agentcloud-default"
        stream = $false
        messages = @(
            @{
                role = "user"
                content = "record a note through chat completions"
            }
        )
        metadata = @{
            task_mode = "message_only"
            session_id = $sessionId
        }
    }
    $taskBody = @{
        model = "agentcloud-default"
        stream = $false
        messages = @(
            @{
                role = "user"
                content = "create a task through chat completions and continue it"
            }
        )
        metadata = @{
            task_mode = "task_required"
            session_id = $sessionId
            title = "chat acceptance task"
            task_type = "continuation"
            priority = "high"
            auto_start = $AutoStartTask.IsPresent
        }
    }
}

$messageResponse = Invoke-FacadePost -Path $surfacePath -Body $messageBody
$messageAgentcloud = $messageResponse.agentcloud
Assert-True -Condition ($messageAgentcloud.reply_type -eq "chat_reply") -Message "message_only did not return chat_reply on $surfaceLabel"
Assert-True -Condition ($messageAgentcloud.reply_source -eq "session_ack") -Message "message_only did not return session_ack on $surfaceLabel"

$taskResponse = Invoke-FacadePost -Path $surfacePath -Body $taskBody
$taskAgentcloud = $taskResponse.agentcloud
$taskId = $taskAgentcloud.task_id

Assert-True -Condition ([string]::IsNullOrWhiteSpace($taskId) -eq $false) -Message "task_required did not return task_id on $surfaceLabel"
if ($AutoStartTask) {
    Assert-True -Condition ($taskAgentcloud.reply_type -in @("task_progress", "task_result")) -Message "task_required did not return task progress/result reply on $surfaceLabel"
} else {
    Assert-True -Condition ($taskAgentcloud.reply_type -eq "task_receipt") -Message "manual-start task_required did not return task_receipt on $surfaceLabel"
}

$liveFlow = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/live_flow?limit=5").data

[pscustomobject]@{
    surface = $surfaceLabel
    session_id = $sessionId
    message_reply_type = $messageAgentcloud.reply_type
    task_id = $taskId
    task_reply_type = $taskAgentcloud.reply_type
    task_status = $taskAgentcloud.task_status
    task_auto_start = $AutoStartTask.IsPresent
    live_flow_available = ($null -ne $liveFlow.task)
} | ConvertTo-Json -Depth 5
