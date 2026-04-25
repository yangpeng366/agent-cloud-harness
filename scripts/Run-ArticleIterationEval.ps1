param(
    [string]$BaseUrl = "http://localhost:18080",
    [Parameter(Mandatory = $true)]
    [string]$ReferenceFile,
    [string]$TaskTitle = "Write article on continuity vs autonomy for AI agents",
    [string]$ArticleTitle = "AI Agent continuity vs autonomy",
    [string]$TaskType = "research",
    [string]$Priority = "high",
    [int]$MaxRounds = 3
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ReferenceFile)) {
    throw "Reference file does not exist: $ReferenceFile"
}

if ($MaxRounds -lt 1) {
    throw "MaxRounds must be at least 1"
}

$referenceText = Get-Content -Raw -LiteralPath $ReferenceFile

$taskPayload = @{
    title = $TaskTitle
    task_type = $TaskType
    source = "user"
    priority = $Priority
    intent = @"
Write a Chinese WeChat-public-account style article around the title '$ArticleTitle'.
You must progress in multiple rounds:
Round 1: only produce topic judgment, core argument, and a detailed outline. Do not finalize the article.
Round 2: expand the outline into a full first draft.
Round 3: polish the draft into a stronger final version, and add 3 alternate titles plus 1 intro paragraph.
Each round must clearly state the next step.

Reference material:
$referenceText
"@
    goal = "Produce a strong Chinese article for product managers, AI builders, and agent infrastructure engineers, and show task organization through multi-round iteration."
    metadata = @{
        article_title = $ArticleTitle
        audience = "product managers, AI builders, and agent infrastructure engineers"
        style = "Chinese WeChat article, strong opinion, scenario-driven"
        iteration_plan = @("round1_outline", "round2_draft", "round3_polish")
        reference_file = (Resolve-Path -LiteralPath $ReferenceFile).Path
    }
}

$createResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/v1/tasks" `
    -ContentType "application/json; charset=utf-8" `
    -Body ($taskPayload | ConvertTo-Json -Depth 8)

if (-not $createResponse.success) {
    throw "Task creation failed: $($createResponse.message)"
}

$task = $createResponse.data
if (-not $task) {
    throw "Task creation returned no task payload"
}

$taskId = $task.id
$sessionId = $task.session_id

function Get-TaskSnapshot {
    param(
        [string]$BaseUrl,
        [string]$TaskId,
        [string]$Label
    )

    $taskView = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$TaskId").data
    $judgmentTrace = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$TaskId/judgment_trace").data
    $liveFlow = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$TaskId/live_flow?limit=5").data

    $artifact = $null
    if ($liveFlow.runtime_context -and $liveFlow.runtime_context.recent_artifacts) {
        $artifact = $liveFlow.runtime_context.recent_artifacts[0]
    }

    [pscustomobject]@{
        label = $Label
        status = $taskView.status
        control_node = $taskView.control_node
        assigned_worker = $taskView.assigned_worker
        summary = $taskView.summary
        next_step = $taskView.next_step
        execution_action = if ($judgmentTrace.execution_judgment) { $judgmentTrace.execution_judgment.metadata.action } else { $null }
        execution_reason = if ($judgmentTrace.execution_judgment) { $judgmentTrace.execution_judgment.rationale } else { $null }
        completion_status = if ($judgmentTrace.completion_judgment) { $judgmentTrace.completion_judgment.metadata.status } else { $null }
        completion_alignment = if ($judgmentTrace.completion_judgment) { $judgmentTrace.completion_judgment.metadata.alignment_level } else { $null }
        completion_reason = if ($judgmentTrace.completion_judgment) { $judgmentTrace.completion_judgment.rationale } else { $null }
        latest_output = $judgmentTrace.latest_output
        artifact_title = if ($artifact) { $artifact.title } else { $null }
        artifact_summary = if ($artifact) { $artifact.summary } else { $null }
        artifact_content = if ($artifact -and $artifact.metadata) { $artifact.metadata.artifact_content } else { $null }
    }
}

$rounds = New-Object System.Collections.Generic.List[object]
$rounds.Add((Get-TaskSnapshot -BaseUrl $BaseUrl -TaskId $taskId -Label "round1_after_create"))

for ($round = 2; $round -le $MaxRounds; $round++) {
    $currentTask = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId").data
    if ($currentTask.status -in @("done", "failed", "paused", "waiting_human")) {
        break
    }

    Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/continue" | Out-Null
    $rounds.Add((Get-TaskSnapshot -BaseUrl $BaseUrl -TaskId $taskId -Label ("round{0}_after_continue" -f $round)))
}

$finalTask = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId").data

[pscustomobject]@{
    task_id = $taskId
    session_id = $sessionId
    final_status = $finalTask.status
    final_control_node = $finalTask.control_node
    final_summary = $finalTask.summary
    final_next_step = $finalTask.next_step
    rounds = $rounds
} | ConvertTo-Json -Depth 16
