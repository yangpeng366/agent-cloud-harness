param(
    [string]$BaseUrl = "http://localhost:18081",
    [Parameter(Mandatory = $true)]
    [string]$ScopePath,
    [Parameter(Mandatory = $true)]
    [string]$ReferenceFile,
    [Parameter(Mandatory = $true)]
    [string]$OutputFile,
    [string]$WorkerId = "kimi-local-doc",
    [string]$WorkerType = "kimi",
    [string]$TaskType = "local_doc",
    [string]$Priority = "high",
    [int]$MaxRounds = 4,
    [string]$TaskTitle = "写公众号文章：AI Agent 的下一波壁垒，可能是 continuity，而不是 autonomy",
    [string]$ArticleTitle = "AI Agent 的下一波壁垒，可能是 continuity，而不是 autonomy"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ScopePath)) {
    throw "Scope path does not exist: $ScopePath"
}
if (-not (Test-Path -LiteralPath $ReferenceFile)) {
    throw "Reference file does not exist: $ReferenceFile"
}
if ($MaxRounds -lt 1) {
    throw "MaxRounds must be at least 1"
}

$resolvedScope = (Resolve-Path -LiteralPath $ScopePath).Path
$resolvedReference = (Resolve-Path -LiteralPath $ReferenceFile).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputFile)

$workerPayload = @{
    worker_id = $WorkerId
    worker_type = $WorkerType
    capabilities = @($TaskType, "doc", "research")
    tool_capabilities = @("read_file", "write_file", "list_files", "search_text")
    tool_scope = @($resolvedScope)
    suggest_only = $false
    ready = $true
    dependencies = @{
        api_key = $true
        backend_reachable = $true
    }
    metadata = @{
        scenario = "tool_aware_article_eval"
        reference_file = $resolvedReference
        output_file = $resolvedOutput
    }
}

$intent = @"
Write a Chinese WeChat-public-account article titled '$ArticleTitle'.
You must work in multiple rounds and keep continuity across rounds.
Round 1: use read_file to read the reference file '$resolvedReference', then produce topic judgment, core argument, and a detailed outline only. Do not write the article file yet.
Round 2: write a full first draft to '$resolvedOutput'.
Round 3: overwrite '$resolvedOutput' with a stronger final version, add 3 alternate titles and 1 intro paragraph, and clearly state the next step.
Each round must explicitly state the next step.
Use tools directly when needed. Keep all file access inside '$resolvedScope'.
"@

$goal = "Read '$resolvedReference', iterate in multiple rounds, and write the final article to '$resolvedOutput'."

$taskPayload = @{
    title = $TaskTitle
    task_type = $TaskType
    source = "user"
    priority = $Priority
    intent = $intent
    goal = $goal
    auto_start = $false
    metadata = @{
        article_title = $ArticleTitle
        reference_file = $resolvedReference
        output_file = $resolvedOutput
        target_scope = $resolvedScope
        evaluation_mode = "tool_aware_article"
    }
}

$workerResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/v1/workers" `
    -ContentType "application/json; charset=utf-8" `
    -Body ($workerPayload | ConvertTo-Json -Depth 8)

if (-not $workerResponse.success) {
    throw "Worker registration failed: $($workerResponse.message)"
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

function Get-OutputFileState {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{
            exists = $false
            size = 0
            content = $null
            preview = $null
        }
    }

    $content = Get-Content -Raw -LiteralPath $Path
    $preview = $content
    if ($preview.Length -gt 600) {
        $preview = $preview.Substring(0, 600) + "..."
    }

    return [pscustomobject]@{
        exists = $true
        size = [System.Text.Encoding]::UTF8.GetByteCount($content)
        content = $content
        preview = $preview
    }
}

function Convert-ToolArgumentsSummary {
    param($Arguments)

    if (-not $Arguments) {
        return $null
    }

    $summary = [ordered]@{}
    foreach ($key in @("path", "query", "recursive")) {
        if ($Arguments.PSObject.Properties.Name -contains $key) {
            $summary[$key] = $Arguments.$key
        }
    }

    if ($Arguments.PSObject.Properties.Name -contains "content" -and $null -ne $Arguments.content) {
        $summary["content_length"] = [System.Text.Encoding]::UTF8.GetByteCount([string]$Arguments.content)
    }

    if ($summary.Count -eq 0) {
        return ($Arguments | Select-Object * -ExcludeProperty content)
    }

    return [pscustomobject]$summary
}

function Convert-ToolTraceSummary {
    param($ToolTrace)

    if (-not $ToolTrace) {
        return ,@()
    }

    return ,@(
        $ToolTrace | ForEach-Object {
            [pscustomobject]@{
                tool_name = $_.tool_name
                success = $_.success
                result_summary = $_.result_summary
                created_at = $_.created_at
                arguments = Convert-ToolArgumentsSummary -Arguments $_.arguments
            }
        }
    )
}

function Get-TaskSnapshot {
    param(
        [string]$CurrentBaseUrl,
        [string]$CurrentTaskId,
        [string]$Label,
        [string]$CurrentOutputPath
    )

    $taskView = (Invoke-RestMethod -Uri "$CurrentBaseUrl/api/v1/tasks/$CurrentTaskId").data
    $judgmentTrace = (Invoke-RestMethod -Uri "$CurrentBaseUrl/api/v1/tasks/$CurrentTaskId/judgment_trace").data
    $toolTrace = (Invoke-RestMethod -Uri "$CurrentBaseUrl/api/v1/tasks/$CurrentTaskId/tool_trace?limit=10").data
    $liveFlow = (Invoke-RestMethod -Uri "$CurrentBaseUrl/api/v1/tasks/$CurrentTaskId/live_flow?limit=10").data
    $fileState = Get-OutputFileState -Path $CurrentOutputPath

    $latestTool = $null
    if ($toolTrace -and $toolTrace.Count -gt 0) {
        $latestTool = $toolTrace[0]
    }

    [pscustomobject]@{
        label = $Label
        status = $taskView.status
        control_node = $taskView.control_node
        assigned_worker = $taskView.assigned_worker
        summary = $taskView.summary
        next_step = $taskView.next_step
        execution_action = if ($judgmentTrace.execution_judgment) { $judgmentTrace.execution_judgment.metadata.action } else { $null }
        completion_status = if ($judgmentTrace.completion_judgment) { $judgmentTrace.completion_judgment.metadata.status } else { $null }
        completion_alignment = if ($judgmentTrace.completion_judgment) { $judgmentTrace.completion_judgment.metadata.alignment_level } else { $null }
        latest_output = $judgmentTrace.latest_output
        tool_count = if ($toolTrace) { $toolTrace.Count } else { 0 }
        latest_tool_name = if ($latestTool) { $latestTool.tool_name } else { $null }
        latest_tool_success = if ($latestTool) { $latestTool.success } else { $null }
        latest_tool_summary = if ($latestTool) { $latestTool.result_summary } else { $null }
        latest_tool_arguments = if ($latestTool) { Convert-ToolArgumentsSummary -Arguments $latestTool.arguments } else { $null }
        output_file_exists = $fileState.exists
        output_file_size = $fileState.size
        output_file_preview = $fileState.preview
        live_flow_tool_count = if ($liveFlow.tool_invocations) { $liveFlow.tool_invocations.Count } else { 0 }
    }
}

$rounds = @(
    Get-TaskSnapshot -CurrentBaseUrl $BaseUrl -CurrentTaskId $taskId -Label "after_create_before_round1" -CurrentOutputPath $resolvedOutput
)

for ($round = 1; $round -le $MaxRounds; $round++) {
    $currentTask = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId").data
    if ($currentTask.status -in @("done", "failed", "paused", "waiting_human")) {
        break
    }

    Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/continue" | Out-Null
    $rounds += Get-TaskSnapshot -CurrentBaseUrl $BaseUrl -CurrentTaskId $taskId -Label ("round{0}_after_continue" -f $round) -CurrentOutputPath $resolvedOutput
}

$finalTask = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId").data
$finalToolTrace = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/tool_trace?limit=20").data
$finalLiveFlow = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/live_flow?limit=20").data
$finalFileState = Get-OutputFileState -Path $resolvedOutput
$roundSnapshots = $rounds
$compressedToolTrace = Convert-ToolTraceSummary -ToolTrace $finalToolTrace
$compressedLiveFlowToolInvocations = @()
if ($finalLiveFlow) {
    $compressedLiveFlowToolInvocations = Convert-ToolTraceSummary -ToolTrace $finalLiveFlow.tool_invocations
}

[pscustomobject]@{
    worker_id = $WorkerId
    task_id = $taskId
    session_id = $sessionId
    final_status = $finalTask.status
    final_control_node = $finalTask.control_node
    final_summary = $finalTask.summary
    final_next_step = $finalTask.next_step
    output_file = $resolvedOutput
    output_file_exists = $finalFileState.exists
    output_file_size = $finalFileState.size
    output_file_content = $finalFileState.content
    rounds = $roundSnapshots
    tool_trace = @($compressedToolTrace)
    live_flow_tool_invocations = @($compressedLiveFlowToolInvocations)
} | ConvertTo-Json -Depth 20
