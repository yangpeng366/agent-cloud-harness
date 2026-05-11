param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$UseResponsesSurface
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-FacadeReady {
    $healthUp = $false
    try {
        $health = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/health') -TimeoutSec 5
        $status = if ($null -ne $health.data) { $health.data.status } else { $health.status }
        if ($status -eq 'up') {
            $healthUp = $true
        }
    } catch {
    }

    try {
        $models = Invoke-RestMethod -Uri ($BaseUrl + '/v1/models') -TimeoutSec 10
        Assert-True -Condition ($null -ne $models) -Message 'empty /v1/models response'
    } catch {
        $message = $_.Exception.Message
        if ($healthUp) {
            throw "facade routes are not ready at $BaseUrl. Underlying error: $message"
        }
        throw "harness is not reachable at $BaseUrl. Underlying error: $message"
    }
}

function Invoke-FacadePost {
    param(
        [string]$Path,
        [hashtable]$Body
    )

    Invoke-RestMethod -Method Post `
        -Uri ($BaseUrl + $Path) `
        -ContentType 'application/json; charset=utf-8' `
        -Body ($Body | ConvertTo-Json -Depth 12)
}

function New-FacadeBody {
    param(
        [string]$Text,
        [hashtable]$Metadata
    )

    if ($UseResponsesSurface.IsPresent) {
        return @{
            model = 'agentcloud-default'
            input = $Text
            stream = $false
            metadata = $Metadata
        }
    }

    return @{
        model = 'agentcloud-default'
        stream = $false
        messages = @(
            @{
                role = 'user'
                content = $Text
            }
        )
        metadata = $Metadata
    }
}

function Get-SessionMessages {
    param(
        [string]$SessionId,
        [int]$Limit = 40,
        [string]$TaskId = ''
    )

    $uri = $BaseUrl +
        '/api/v1/sessions/' +
        [System.Uri]::EscapeDataString($SessionId) +
        '/messages?limit=' + $Limit
    if (-not [string]::IsNullOrWhiteSpace($TaskId)) {
        $uri += '&task_id=' + [System.Uri]::EscapeDataString($TaskId)
    }
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    return @($response.data)
}

function Get-SessionTasks {
    param([string]$SessionId)

    $uri = $BaseUrl + '/api/v1/sessions/' + [System.Uri]::EscapeDataString($SessionId) + '/tasks'
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    return @($response.data)
}

function Get-Task {
    param([string]$TaskId)

    $uri = $BaseUrl + '/api/v1/tasks/' + [System.Uri]::EscapeDataString($TaskId)
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    return $response.data
}

Assert-FacadeReady

$surfacePath = if ($UseResponsesSurface.IsPresent) { '/v1/responses' } else { '/v1/chat/completions' }
$surfaceLabel = if ($UseResponsesSurface.IsPresent) { 'responses' } else { 'chat_completions' }

$sessionTitle = 'path matrix ' + $surfaceLabel + ' ' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$sessionResponse = Invoke-RestMethod -Method Post `
    -Uri ($BaseUrl + '/api/v1/sessions') `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{ title = $sessionTitle } | ConvertTo-Json)
$sessionId = [string]$sessionResponse.data.id
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($sessionId)) -Message 'failed to create session'

$messageOnlyResponse = Invoke-FacadePost -Path $surfacePath -Body (New-FacadeBody `
    -Text 'Record a session draft only. Do not materialize a task yet.' `
    -Metadata @{
        task_mode = 'message_only'
        session_id = $sessionId
    })
$messageOnlyAgentcloud = $messageOnlyResponse.agentcloud
Assert-True -Condition ($messageOnlyAgentcloud.reply_type -eq 'chat_reply') -Message 'message_only reply_type mismatch'
Assert-True -Condition ($messageOnlyAgentcloud.reply_source -eq 'session_ack') -Message 'message_only reply_source mismatch'
Assert-True -Condition ([string]::IsNullOrWhiteSpace([string]$messageOnlyAgentcloud.task_id)) -Message 'message_only unexpectedly materialized a task'

$manualTaskResponse = Invoke-FacadePost -Path $surfacePath -Body (New-FacadeBody `
    -Text 'Turn the previous draft into a manual-start task.' `
    -Metadata @{
        task_mode = 'task_required'
        session_id = $sessionId
        title = $surfaceLabel + ' manual start task'
        task_type = 'continuation'
        priority = 'high'
        auto_start = $false
    })
$manualTaskAgentcloud = $manualTaskResponse.agentcloud
$taskId = [string]$manualTaskAgentcloud.task_id
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($taskId)) -Message 'manual-start task did not return task_id'
Assert-True -Condition ($manualTaskAgentcloud.reply_type -eq 'task_receipt') -Message 'manual-start task reply_type mismatch'
Assert-True -Condition ($manualTaskAgentcloud.reply_source -eq 'task_receipt') -Message 'manual-start task reply_source mismatch'

$taskNoteResponse = Invoke-FacadePost -Path $surfacePath -Body (New-FacadeBody `
    -Text 'Attach this turn as a task note only. Do not progress execution.' `
    -Metadata @{
        task_mode = 'message_only'
        session_id = $sessionId
        task_id = $taskId
    })
$taskNoteAgentcloud = $taskNoteResponse.agentcloud
Assert-True -Condition ($taskNoteAgentcloud.reply_type -eq 'chat_reply') -Message 'task note reply_type mismatch'
Assert-True -Condition ($taskNoteAgentcloud.reply_source -eq 'session_ack') -Message 'task note reply_source mismatch'
Assert-True -Condition ([string]$taskNoteAgentcloud.task_id -eq $taskId) -Message 'task note did not remain attached to existing task'

$manualContinuationResponse = Invoke-FacadePost -Path $surfacePath -Body (New-FacadeBody `
    -Text 'Keep the current task context, but do not auto-continue execution.' `
    -Metadata @{
        task_mode = 'task_auto'
        session_id = $sessionId
        task_id = $taskId
        auto_start = $false
    })
$manualContinuationAgentcloud = $manualContinuationResponse.agentcloud
Assert-True -Condition ($manualContinuationAgentcloud.reply_type -eq 'chat_reply') -Message 'manual continuity reply_type mismatch'
Assert-True -Condition ($manualContinuationAgentcloud.reply_source -eq 'session_ack') -Message 'manual continuity reply_source mismatch'
Assert-True -Condition ([string]$manualContinuationAgentcloud.task_id -eq $taskId) -Message 'manual continuity reply was not bound to existing task'

$followupResponse = Invoke-FacadePost -Path $surfacePath -Body (New-FacadeBody `
    -Text 'Create a manual-start follow-up from the current task.' `
    -Metadata @{
        task_mode = 'task_required'
        session_id = $sessionId
        parent_task_id = $taskId
        title = $surfaceLabel + ' child followup'
        task_type = 'continuation'
        priority = 'medium'
        auto_start = $false
    })
$followupAgentcloud = $followupResponse.agentcloud
$followupTaskId = [string]$followupAgentcloud.task_id
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($followupTaskId)) -Message 'manual follow-up did not return child task_id'
Assert-True -Condition ($followupAgentcloud.reply_type -eq 'task_receipt') -Message 'manual follow-up reply_type mismatch'
Assert-True -Condition ($followupAgentcloud.reply_source -eq 'task_receipt') -Message 'manual follow-up reply_source mismatch'

$sessionTasks = Get-SessionTasks -SessionId $sessionId
Assert-True -Condition ($sessionTasks.Count -eq 2) -Message 'expected exactly two materialized tasks in session'
$parentTask = $sessionTasks | Where-Object { $_.id -eq $taskId } | Select-Object -First 1
$childTask = $sessionTasks | Where-Object { $_.id -eq $followupTaskId } | Select-Object -First 1
Assert-True -Condition ($null -ne $parentTask) -Message 'parent task not found in session task list'
Assert-True -Condition ($null -ne $childTask) -Message 'child task not found in session task list'
Assert-True -Condition ([string]$childTask.parent_task_id -eq $taskId) -Message 'child task parent_task_id mismatch'

$allMessages = Get-SessionMessages -SessionId $sessionId
Assert-True -Condition ($allMessages.Count -ge 8) -Message 'session message stream is unexpectedly short'
$allMessageTypes = @($allMessages | ForEach-Object { $_.message_type })
Assert-True -Condition ($allMessageTypes -contains 'user_note') -Message 'user_note missing from session message stream'
Assert-True -Condition ($allMessageTypes -contains 'task_brief') -Message 'task_brief missing from session message stream'
Assert-True -Condition ($allMessageTypes -contains 'task_note') -Message 'task_note missing from session message stream'
Assert-True -Condition ($allMessageTypes -contains 'task_followup') -Message 'task_followup missing from session message stream'

$taskMessages = Get-SessionMessages -SessionId $sessionId -TaskId $taskId
$attachCount = @(
    $taskMessages | Where-Object {
        $_.message_type -eq 'task_note' -and $_.metadata.task_mode -eq 'message_only'
    }
).Count
$manualCount = @(
    $taskMessages | Where-Object {
        $_.message_type -eq 'task_note' -and $_.metadata.auto_start -eq $false
    }
).Count
Assert-True -Condition ($attachCount -ge 1) -Message 'task-bound attach note missing'
Assert-True -Condition ($manualCount -ge 1) -Message 'manual-start continuity note missing'

$followupMessages = Get-SessionMessages -SessionId $sessionId -TaskId $followupTaskId
$followupCount = @(
    $followupMessages | Where-Object { $_.message_type -eq 'task_followup' }
).Count
$followupProgressCount = @(
    $followupMessages | Where-Object { $_.message_type -eq 'task_progress' }
).Count
Assert-True -Condition ($followupCount -ge 1) -Message 'task_followup message missing from child task view'
Assert-True -Condition ($followupProgressCount -eq 0) -Message 'manual-start follow-up unexpectedly progressed'

$parentTaskDetail = Get-Task -TaskId $taskId
$childTaskDetail = Get-Task -TaskId $followupTaskId
Assert-True -Condition ([string]$parentTaskDetail.control_node -eq 'intake') -Message 'parent task control node drifted after manual continuity'
Assert-True -Condition ([string]$childTaskDetail.control_node -eq 'intake') -Message 'child task control node drifted after manual follow-up'

[pscustomobject]@{
    surface = $surfaceLabel
    session_id = $sessionId
    message_only = [pscustomobject]@{
        reply_type = $messageOnlyAgentcloud.reply_type
        reply_source = $messageOnlyAgentcloud.reply_source
        task_materialized = $false
    }
    manual_start_task = [pscustomobject]@{
        task_id = $taskId
        reply_type = $manualTaskAgentcloud.reply_type
        reply_source = $manualTaskAgentcloud.reply_source
        control_node = $parentTaskDetail.control_node
    }
    task_note_attach = [pscustomobject]@{
        task_id = $taskId
        reply_type = $taskNoteAgentcloud.reply_type
        reply_source = $taskNoteAgentcloud.reply_source
    }
    manual_start_continuity = [pscustomobject]@{
        task_id = $taskId
        reply_type = $manualContinuationAgentcloud.reply_type
        reply_source = $manualContinuationAgentcloud.reply_source
    }
    manual_followup = [pscustomobject]@{
        task_id = $followupTaskId
        parent_task_id = $taskId
        reply_type = $followupAgentcloud.reply_type
        reply_source = $followupAgentcloud.reply_source
        control_node = $childTaskDetail.control_node
    }
    session_task_count = $sessionTasks.Count
    session_message_types = $allMessageTypes
} | ConvertTo-Json -Depth 8
