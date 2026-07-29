<#
.SYNOPSIS
    Agent Cloud Harness · 端到端快速验证脚本（PowerShell）
.DESCRIPTION
    走一条完整的控制面 happy path：健康检查 -> 创建会话 -> 创建任务 -> 查看 worker ->
    轮询任务状态 -> 取 live_flow -> 关闭会话。全程只调用 REST API，不依赖 LLM 配置。
.PARAMETER BaseUrl
    Harness 服务地址，默认 http://localhost:8080（也可用 $env:BASE_URL 覆盖）。
.EXAMPLE
    .\examples\quickstart.ps1
.EXAMPLE
    .\examples\quickstart.ps1 -BaseUrl http://localhost:9090
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' })
)

$ErrorActionPreference = 'Stop'

function Write-Step([int]$n, [string]$title) {
    Write-Host "`n==> $n/7 $title" -ForegroundColor Cyan
}

# 1. 健康检查
Write-Step 1 '健康检查'
$health = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -Method Get
$health | ConvertTo-Json -Depth 6

# 2. 创建会话
Write-Step 2 '创建会话'
$session = Invoke-RestMethod -Uri "$BaseUrl/api/v1/sessions" -Method Post `
    -ContentType 'application/json' -Body '{"title":"quickstart demo session"}'
$session | ConvertTo-Json -Depth 6
$sessionId = $session.data.id
Write-Host "会话 ID：$sessionId"

# 3. 在会话中创建任务
Write-Step 3 '在会话中创建任务'
$body = @{
    title      = 'hello world'
    task_type  = 'coding'
    source     = 'user'
    priority   = 'high'
    intent     = 'demo'
    session_id = $sessionId
} | ConvertTo-Json -Compress
$task = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks" -Method Post -ContentType 'application/json' -Body $body
$task | ConvertTo-Json -Depth 6
$taskId = $task.data.id
Write-Host "任务 ID：$taskId"

# 4. 查看 worker 列表（路由候选）
Write-Step 4 '查看 worker 列表（路由候选）'
$workers = Invoke-RestMethod -Uri "$BaseUrl/api/v1/workers" -Method Get
$workers.data | ConvertTo-Json -Depth 6

# 5. 轮询任务状态（最多 6 次，每次间隔 2s）
Write-Step 5 '轮询任务状态（最多 6 次，每次间隔 2s）'
for ($i = 1; $i -le 6; $i++) {
    $t = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId" -Method Get
    $status = $t.data.status
    $worker = if ($t.data.assigned_worker) { $t.data.assigned_worker } else { '未分配' }
    Write-Host "  [$i] status=$status  assigned_worker=$worker"
    if ($status -in 'done', 'failed', 'closed', 'cancelled') { break }
    Start-Sleep -Seconds 2
}

# 6. 取 live_flow 聚合诊断
Write-Step 6 '取 live_flow 聚合诊断'
$flow = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$taskId/live_flow" -Method Get
[PSCustomObject]@{
    task_id           = $flow.data.task.id
    status            = $flow.data.task.status
    assigned_worker   = if ($flow.data.task.assigned_worker) { $flow.data.task.assigned_worker } else { '未分配' }
    checkpoints       = @($flow.data.checkpoints).Count
    tool_invocations  = @($flow.data.tool_invocations).Count
    learning_memories = @($flow.data.learning_memories).Count
} | Format-List

# 7. 关闭会话
Write-Step 7 '关闭会话'
$closed = Invoke-RestMethod -Uri "$BaseUrl/api/v1/sessions/$sessionId/close" -Method Post
[PSCustomObject]@{ id = $closed.data.id; status = $closed.data.status } | Format-List

Write-Host "`n完成。可用浏览器打开 $BaseUrl/console/ 查看图形化面板。" -ForegroundColor Green