# Local Doc Worker Pilot

## 1. 目标

本 runbook 用于在本机快速验证“受控工具能力层”是否已经可用，重点检查以下链路：

- 动态注册带工具权限的本地文档 worker
- 用自定义 `task_type` 让任务直接路由到该 worker
- 触发一次 `plan -> tool invoke -> finalize`
- 观察 `tool_trace` 与 `live_flow`
- 在限定目录内写回总结文件

当前实现边界：

- 每轮最多一次工具调用
- 已开放工具：`list_files`、`search_text`、`read_file`、`write_file`
- 工具访问范围受 `tool_scope` 限制
- `write_file` 当前支持整文件写入与可选 append，不支持 patch

## 2. 前置条件

- Java 21 环境可用
- 服务已启动
- OpenAI-compatible LLM 已可正常返回 JSON
- 目标目录存在，且希望允许 harness 在该目录下写文件

建议先按 [LIVE_FLOW_RUNBOOK.md](/d:/gitAll/agent-cloud-harness/docs/LIVE_FLOW_RUNBOOK.md) 跑通基础服务，再做本试点。

## 3. 为什么要用自定义 `task_type`

当前内置 worker 里：

- `openclaw-native` 已声明 `doc`
- `codex` 与 `kimi` 也各自有现成 capability

如果直接创建 `task_type=doc` 的任务，路由器可能优先命中内置 worker，而不是你新注册的本地文档 worker。  
为了让试点路径稳定，建议：

- 注册 worker 时增加 capability：`local_doc`
- 创建任务时使用 `task_type=local_doc`

这样当前轮次会优先落到试点 worker，而不是和内置 `doc` worker 抢路由。

## 4. 一键试点脚本

仓库里已新增脚本：[Start-LocalDocPilot.ps1](/d:/gitAll/agent-cloud-harness/scripts/Start-LocalDocPilot.ps1)

最小调用方式：

```powershell
.\scripts\Start-LocalDocPilot.ps1 `
  -BaseUrl 'http://localhost:18080' `
  -ScopePath 'D:\BaiduSyncdisk\Obsidian Vault\当前项目\02_项目推进\agent-cloud-architecture' `
  -OutputFileName 'pilot-summary.md'
```

脚本会自动完成：

1. 注册 `kimi-local-doc` worker
2. 创建 `task_type=local_doc` 的任务
3. 打印 `task_id`
4. 打印观测接口地址

默认行为：

- `worker_id = kimi-local-doc`
- `worker_type = kimi`
- `task_type = local_doc`
- `tool_capabilities = ["list_files","search_text","read_file","write_file"]`
- `suggest_only = false`

## 5. 手工调用方式

### Step 1: 注册 worker

```powershell
$worker = @{
  worker_id = 'kimi-local-doc'
  worker_type = 'kimi'
  capabilities = @('local_doc', 'doc', 'research')
  tool_capabilities = @('list_files', 'search_text', 'read_file', 'write_file')
  tool_scope = @('D:\BaiduSyncdisk\Obsidian Vault\当前项目\02_项目推进\agent-cloud-architecture')
  suggest_only = $false
  ready = $true
  dependencies = @{
    api_key = $true
    backend_reachable = $true
  }
  metadata = @{
    pilot = 'local_doc'
  }
} | ConvertTo-Json -Depth 6

Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/workers' `
  -ContentType 'application/json' `
  -Body $worker
```

### Step 2: 创建任务

```powershell
$task = @{
  title = '整理最近更新并写回总结'
  task_type = 'local_doc'
  source = 'user'
  priority = 'high'
  intent = '查看目录内最近更新的文档，整理要点，并写一份总结到 pilot-summary.md'
  goal = '遍历目标目录，提取最近更新与落地建议，并把总结写回当前目录下的 pilot-summary.md'
  metadata = @{
    desired_output_file = 'pilot-summary.md'
  }
} | ConvertTo-Json -Depth 6

Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/tasks' `
  -ContentType 'application/json' `
  -Body $task
```

验证点：

- 返回的 `data.assigned_worker` 应该是 `kimi-local-doc`
- 返回的 `data.control_node` 已发生推进

## 6. 核心观测接口

任务创建后，至少看这 4 个接口：

```powershell
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/tool_trace?limit=10"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/live_flow?limit=10"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/judgment_trace"
```

当前 `live_flow` 已聚合：

- `task`
- `latest_packet`
- `route_preview`
- `runtime_context`
- `judgment_trace`
- `checkpoints`
- `learning_memories`
- `tool_invocations`

## 7. 成功标准

一次成功试点至少应看到：

1. `task.assigned_worker = kimi-local-doc`
2. `/tool_trace` 返回至少一条 `tool_invocations`
3. `tool_name` 是 `list_files`、`search_text`、`read_file` 或 `write_file`
4. `success = true`
5. 目标目录下出现 `pilot-summary.md` 或你指定的输出文件
6. `/live_flow` 能同时看到 judgment 与 tool trace

## 8. 常见问题

### Q1: 任务没有路由到 `kimi-local-doc`

通常是以下原因：

- `task_type` 不是 `local_doc`
- worker 注册时没带 `local_doc` capability
- `ready=false`
- `suggest_only=true`

### Q2: `/tool_trace` 为空

通常表示：

- 任务走到了普通 `DefaultWorkerExecutor`
- 工具 planning 判定 `needs_tool=false`
- LLM planning/finalization 没按 JSON 协议输出，最后回退到了默认执行路径

建议先看：

- `/api/v1/tasks/{id}/live_flow`
- `.tmp` 或服务日志里 `Tool planning completed` / `Tool invocation recorded`

### Q3: 写文件失败

重点检查：

- `tool_scope` 是否是绝对路径
- 输出路径是否落在 `tool_scope` 内
- LLM 是否生成了越界路径
- 目标目录当前用户是否有写权限

### Q4: 命中了工具但结果仍然很差

这是当前版本的预期限制之一。  
第一版只支持“单工具单轮”，还没有：

- 多工具规划
- patch 级写回
- continuation 驱动的多轮 thread bridge

这个试点的目标是先验证“受控工具能力层 + 可观测性”本身已经打通。
