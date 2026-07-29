# Examples

本目录提供 Agent Cloud Harness 的端到端可运行示例脚本，帮助首次使用者在 1 分钟内跑通控制面 happy path。

## 前置条件

1. 已按根目录 [README](../README.md) 完成构建：`mvn package`
2. 服务已启动（默认监听 8080）：
   ```bash
   java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
   ```
   Windows 用户可改用仓库脚本：`.\scripts\Run-HarnessWithJava21.ps1`
3. 示例脚本只调用控制面 REST API，**不依赖 LLM 配置**（LLM 仅用于 judgment 与 tool-aware 执行，属可选能力）。

## 脚本

| 脚本 | 平台 | 依赖 | 说明 |
| --- | --- | --- | --- |
| `quickstart.sh` | Linux / macOS | `curl`、`jq` | 端到端 happy path（bash） |
| `quickstart.ps1` | Windows | PowerShell 5.1+ / 7 | 同等流程（Invoke-RestMethod，无需额外依赖） |

## 跑通流程

两个脚本走同一条控制面 happy path：

1. **健康检查** `GET /api/v1/health`
2. **创建会话** `POST /api/v1/sessions`
3. **在会话中创建任务** `POST /api/v1/tasks`（带 `session_id`）
4. **查看 worker 列表** `GET /api/v1/workers`（路由候选）
5. **轮询任务状态** `GET /api/v1/tasks/{id}`（观察 `status` 与 `assigned_worker` 变化）
6. **取 live_flow 聚合诊断** `GET /api/v1/tasks/{id}/live_flow`
7. **关闭会话** `POST /api/v1/sessions/{id}/close`

### 用法

```bash
# Linux / macOS
BASE_URL=http://localhost:8080 ./examples/quickstart.sh
```

```powershell
# Windows PowerShell
$env:BASE_URL = 'http://localhost:8080'
.\examples\quickstart.ps1
```

> 默认 `BASE_URL=http://localhost:8080`，可通过环境变量或 `-BaseUrl` 参数覆盖。

## 预期结果

- 步骤 1 返回 `status: up`。
- 步骤 2/3 返回 `success: true` 及对应的会话 / 任务 ID。
- 步骤 5 中任务 `status` 可能为 `active` / `waiting`（未配置 LLM 时 worker 不会真正完成执行）；配置 `OPENAI_API_KEY` 等环境变量后可观察到向 `done` 推进。
- 步骤 6 返回该任务的聚合诊断面（`checkpoints` / `tool_invocations` / `learning_memories` 计数）。
- 步骤 7 会话状态置为关闭。

## 排错

- **连接被拒绝**：确认服务已启动且端口正确，`curl http://localhost:8080/api/v1/health` 能返回 `up`。
- **`jq: command not found`**（仅 .sh）：安装 jq，或改用 `quickstart.ps1`。
- **端口被占用**：启动时用 `-Dserver.port=9090`（PowerShell 用 `.\scripts\Run-HarnessWithJava21.ps1 -Port 9090`），并把 `BASE_URL` 设为对应地址。

更多端点与字段定义见根目录 [README](../README.md) 与 [docs/](../docs/)。