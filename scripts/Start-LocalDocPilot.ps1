param(
    [string]$BaseUrl = "http://localhost:18080",
    [Parameter(Mandatory = $true)]
    [string]$ScopePath,
    [string]$WorkerId = "kimi-local-doc",
    [string]$WorkerType = "kimi",
    [string]$TaskType = "local_doc",
    [string]$OutputFileName = "pilot-summary.md",
    [string]$TaskTitle = "整理最近更新并写回总结",
    [string]$Intent = "查看目录内最近更新的文档，整理要点，并写一份总结文件",
    [string]$Priority = "high"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ScopePath)) {
    throw "Scope path does not exist: $ScopePath"
}

$resolvedScope = (Resolve-Path -LiteralPath $ScopePath).Path
$outputPath = Join-Path $resolvedScope $OutputFileName

$workerPayload = @{
    worker_id = $WorkerId
    worker_type = $WorkerType
    capabilities = @($TaskType, "doc", "research")
    tool_capabilities = @("list_files", "search_text", "read_file", "write_file", "patch_file")
    tool_scope = @($resolvedScope)
    suggest_only = $false
    ready = $true
    dependencies = @{
        api_key = $true
        backend_reachable = $true
    }
    metadata = @{
        pilot = "local_doc"
        output_hint = $outputPath
    }
}

$taskPayload = @{
    title = $TaskTitle
    task_type = $TaskType
    source = "user"
    priority = $Priority
    intent = "$Intent。输出文件：$outputPath"
    goal = "遍历目录 '$resolvedScope' 内相关文档，整理最近更新与落地建议，并写入 '$outputPath'。"
    metadata = @{
        target_scope = $resolvedScope
        desired_output_file = $outputPath
    }
}

$workerResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/v1/workers" `
    -ContentType "application/json" `
    -Body ($workerPayload | ConvertTo-Json -Depth 6)

if (-not $workerResponse.success) {
    throw "Worker registration failed: $($workerResponse.message)"
}

$taskResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/v1/tasks" `
    -ContentType "application/json" `
    -Body ($taskPayload | ConvertTo-Json -Depth 6)

if (-not $taskResponse.success) {
    throw "Task creation failed: $($taskResponse.message)"
}

$task = $taskResponse.data
if (-not $task) {
    throw "Task creation returned no task payload"
}

$taskId = $task.id

Write-Host ""
Write-Host "Local doc pilot started."
Write-Host "Task ID: $taskId"
Write-Host "Session ID: $($task.session_id)"
Write-Host "Assigned Worker: $($task.assigned_worker)"
Write-Host "Status: $($task.status)"
Write-Host "Control Node: $($task.control_node)"
Write-Host "Scope: $resolvedScope"
Write-Host "Suggested Output File: $outputPath"
Write-Host ""
Write-Host "Inspect URLs:"
Write-Host "  $BaseUrl/api/v1/tasks/$taskId"
Write-Host "  $BaseUrl/api/v1/tasks/$taskId/tool_trace?limit=10"
Write-Host "  $BaseUrl/api/v1/tasks/$taskId/live_flow?limit=10"
Write-Host "  $BaseUrl/api/v1/tasks/$taskId/judgment_trace"
