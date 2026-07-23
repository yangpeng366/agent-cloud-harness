# Agent Cloud Harness 启动命令配置文档

## 概述

本文档记录 Agent Cloud Harness 的启动命令配置，便于快速启动服务并访问 Dialogue 界面。

## 本文边界

本文只回答四类问题：怎么构建、怎么启动、怎么验证服务活着、启动期常见故障怎么排。

如果你的目标不是“把服务跑起来”，请直接走对应入口：

| 当前任务 | 先看哪里 |
|------|------|
| 继续开发、排查代码、整理文档 | `docs/README.md` |
| AI Agent 接手任务 | `WAKE.md` → `AGENTS.md` |
| 看 `/dialogue/`、`/console/`、browser acceptance | `docs/dialogue/README.md` |
| 看 provider、worker、路由、dispatch/readiness | `docs/provider/README.md` |
| 看最近进展和已固定规则 | `STATE.md`、`DECISIONS.md` |

---

## 支持的操作系统

| 操作系统 | 脚本类型 | 构建脚本 | 启动脚本 |
|----------|----------|----------|----------|
| Windows | PowerShell | `scripts/Build-WithJava21.ps1` | `scripts/Run-HarnessWithJava21.ps1` |
| Linux (Ubuntu/Debian) | Bash | `scripts/Build-WithJava21.sh` | `scripts/Run-HarnessWithJava21.sh` |
| macOS | Bash | `scripts/Build-WithJava21.sh` | `scripts/Run-HarnessWithJava21.sh` |

---

## 构建命令

### 前提条件

- **Java 21**（必须，项目启用了 `--enable-preview`）
- **Maven 3.9+**

### Windows (PowerShell)

```powershell
# 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 使用项目脚本构建（自动切换到 Java 21）
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests
```

### Linux/macOS (Bash)

```bash
# 切换到项目目录
cd /path/to/agent-cloud-harness

# 使用项目脚本构建（自动切换到 Java 21）
bash scripts/Build-WithJava21.sh true
```

### 手动构建命令

**Windows:**
```powershell
# 1. 先切换到 Java 21 环境
. .\scripts\Use-Java21.ps1 -Quiet

# 2. 使用 Maven 构建
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests
```

**Linux/macOS:**
```bash
# 1. 先切换到 Java 21 环境
source scripts/Use-Java21.sh

# 2. 使用 Maven 构建
mvn package -DskipTests
```

### 构建产物

构建成功后，生成的 JAR 文件位于 `target/` 目录：

| 文件 | 说明 |
|------|------|
| `agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` | 若存在，优先使用的 shaded JAR |
| `agent-cloud-harness-0.1.0-SNAPSHOT.jar` | 当前同样可直接运行；脚本会在找不到 shaded JAR 时自动回退到它 |
| `original-agent-cloud-harness-0.1.0-SNAPSHOT.jar` | shade 前原始 JAR |

---

## 启动命令

### Windows (PowerShell)


```powershell
# 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 使用脚本启动
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Port 8081 `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\agent_cloud_new.db")
```

### Windows + 星火 glm5.1（推荐用于启用 LLM）

如果 `/dialogue/` 顶部提示：

```text
模型未就绪 LLM 不可用（gpt-4o-mini · https://api.openai.com/v1），仅支持 manual-start。
```

说明启动进程没有读到可用的 `OPENAI_API_KEY`，并且仍在使用默认模型配置。需要在启动前显式配置兼容 OpenAI 的 LLM endpoint。

本仓库提供了星火 glm5.1 便捷启动脚本；API Key 不写入仓库，运行时通过 `-ApiKey` 或环境变量传入：

```powershell
# 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 前台启动
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithXfyunGlm51.ps1 `
  -ApiKey "<your-xfyun-api-key>" `
  -Port 8081
```

后台启动示例：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithXfyunGlm51.ps1 `
  -ApiKey "<your-xfyun-api-key>" `
  -Background `
  -Port 18386 `
  -StdOutPath .tmp\xfyun-glm51-18386.out.log `
  -StdErrPath .tmp\xfyun-glm51-18386.err.log `
  -DisableDispatchPreflightWarmup `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\xfyun-glm51-18386.db")
```

如果不想每次在命令里传 key，可以只在当前 PowerShell 会话中设置环境变量：

```powershell
$env:OPENAI_API_KEY="<your-xfyun-api-key>"
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithXfyunGlm51.ps1 -Port 8081
```

该脚本会自动设置：

| 环境变量 | 值 |
|----------|----|
| `OPENAI_BASE_URL` | `https://maas-coding-api.cn-huabei-1.xf-yun.com/v2` |
| `OPENAI_MODEL` | `xopglm51` |
| `OPENAI_REVIEW_MODEL` | `xopglm51` |
| `OPENAI_WIRE_API` | `chat_completions` |

启动后用健康检查确认主程序已读到配置：

```powershell
Invoke-RestMethod http://localhost:8081/api/v1/health | ConvertTo-Json -Depth 6
```

期望看到：

```json
{
  "llm": {
    "available": true,
    "base_url": "https://maas-coding-api.cn-huabei-1.xf-yun.com/v2",
    "model": "xopglm51",
    "review_model": "xopglm51",
    "wire_api": "chat_completions",
    "api_key_configured": true
  }
}
```

### Windows + OmniRoute（中等推荐：本地自动回退网关）

如果你的目标不是直连某一家模型，而是想先在本机接一个 OpenAI-compatible 网关，再把免费 / 低价 / 付费上游通过自动回退收在一个入口里，OmniRoute 是当前可用的中等推荐方案。

这里的“中等推荐”含义是：

- 优点：本地统一入口、可以承接自动回退、适合把免费 openapi 和低价/付费上游混到同一个 `OPENAI_BASE_URL`。
- 边界：它当前更适合作为 Harness 的 LLM 上游网关，不是仓库内置的 `Worker` / `Provider` lane；真正的 worker/provider 路由语义仍以 Harness 自己的 `WorkerRegistry` / `WorkerRouter` 为准。

本仓库提供了 OmniRoute 启动包装脚本：

```powershell
# 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 前台启动；如果本机 20128 端口还没起 OmniRoute，会自动尝试拉起
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1 `
  -Port 8081
```

默认行为：

| 环境变量 | 值 |
|----------|----|
| `OPENAI_BASE_URL` | `http://localhost:20128/v1` |
| `OPENAI_MODEL` | `auto/coding` |
| `OPENAI_REVIEW_MODEL` | `auto` |
| `OPENAI_WIRE_API` | `chat_completions` |
| `OPENAI_API_KEY` | 优先取 `OMNIROUTE_API_KEY`；未设置时回落到 `sk-omniroute` |

后台启动示例：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1 `
  -Background `
  -Port 18387 `
  -StdOutPath .tmp\omniroute-harness-18387.out.log `
  -StdErrPath .tmp\omniroute-harness-18387.err.log `
  -OmniRouteStdOutPath .tmp\omniroute-18387.out.log `
  -OmniRouteStdErrPath .tmp\omniroute-18387.err.log `
  -DisableDispatchPreflightWarmup `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\omniroute-18387.db")
```

如果 OmniRoute Dashboard 配了专用本地 key，可以显式传入：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1 `
  -ApiKey "<your-omniroute-local-key>" `
  -Port 8081
```

该脚本默认会先检查：

```powershell
Invoke-RestMethod http://localhost:20128/v1/models `
  -Headers @{ Authorization = "Bearer sk-omniroute" } |
  ConvertTo-Json -Depth 4
```

如果 `data` 为空列表，说明本地 OmniRoute 网关已经起来了，但 Dashboard 里还没配置任何上游 provider / combo；脚本会默认直接报错，避免 Harness 误显示“LLM 已就绪”。只有在你明确只想做本地冒烟时，才建议加：

```powershell
.\scripts\Run-HarnessWithOmniRoute.ps1 -SkipModelCatalogCheck -Port 8081
```

推荐启动后同时做两层验证：

```powershell
# 1. 确认 Harness 已读到 OmniRoute 配置
Invoke-RestMethod http://localhost:8081/api/v1/health | ConvertTo-Json -Depth 6

# 2. 确认 OmniRoute 本身不是空目录
Invoke-RestMethod http://localhost:20128/v1/models `
  -Headers @{ Authorization = "Bearer sk-omniroute" } |
  ConvertTo-Json -Depth 4
```

期望至少满足：

- `llm.available=true`
- `llm.base_url=http://localhost:20128/v1`
- `/v1/models` 返回非空 `data[]`

### Linux/macOS (Bash)

```bash
# 切换到项目目录
cd /path/to/agent-cloud-harness

# 使用脚本启动
bash scripts/Run-HarnessWithJava21.sh \
  -p 8081 \
  -a "-Ddb.path=/path/to/agent-cloud-harness/.tmp/agent_cloud_new.db"
```

### 后台启动模式

**Windows:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Background `
  -Port 8081 `
  -StdOutPath .tmp\server.out.log `
  -StdErrPath .tmp\server.err.log
```

**Linux/macOS:**
```bash
bash scripts/Run-HarnessWithJava21.sh \
  -b \
  -p 8081 \
  -o .tmp/server.out.log \
  -e .tmp/server.err.log
```

> **注意**：后台启动会先把运行 JAR 复制到 `.tmp/runtime-jars/` 再启动，避免在运行期间 `mvn package` 覆盖正在使用的 JAR 文件。

---

## `/dialogue/` 本地验证推荐启动方式

### Windows

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Background `
  -Port 18386 `
  -StdOutPath .tmp\server-18386.out.log `
  -StdErrPath .tmp\server-18386.err.log `
  -DisableDispatchPreflightWarmup `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db")
```

### Linux/macOS

```bash
bash scripts/Run-HarnessWithJava21.sh \
  -b \
  -p 18386 \
  -o .tmp/server-18386.out.log \
  -e .tmp/server-18386.err.log \
  -a "-Ddb.path=/path/to/agent-cloud-harness/.tmp/dialogue-smoke-18386.db"
```

启动成功后，可按职责分两层验证：

```bash
# 1. 壳层/布局验证
node scripts/screenshot.js --base-url http://localhost:18386 --report .tmp/dialogue-shell-report-18386.json

# 2. 轻量前端业务 smoke
node scripts/dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp/dialogue-business-smoke-18386.json
```

---

## 访问地址

| 服务 | 地址 |
|------|------|
| 对话界面（Dialogue） | `http://localhost:8081/dialogue/` |
| Web 控制台（Console） | `http://localhost:8081/console/` |
| API 健康检查 | `http://localhost:8081/api/v1/health` |

---

## 环境变量与系统属性

### LLM 环境变量

主程序的 LLM client 只从启动进程环境变量读取配置；如果没有设置 `OPENAI_API_KEY`，健康检查会显示 `llm.available=false`，前端会降级为 manual-start。

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `OPENAI_API_KEY` | OpenAI-compatible API Key；必须非空才算 LLM available | 无 |
| `OPENAI_BASE_URL` | OpenAI-compatible API Base URL | `https://api.openai.com/v1` |
| `OPENAI_MODEL` | 默认执行模型 | `gpt-4o-mini` |
| `OPENAI_REVIEW_MODEL` | judgment / completion review 模型；未设置时回落到 `OPENAI_MODEL` | 无 |
| `OPENAI_WIRE_API` | wire 协议：`chat_completions` 或 `responses` | `chat_completions` |
| `OPENAI_TIMEOUT_SECONDS` | LLM 请求超时秒数 | 60 |
| `OPENAI_MAX_RETRIES` | LLM 请求重试次数 | 2 |
| `OPENAI_MAX_TOKENS` | 可选 max_tokens；未设置则不传 | 无 |

星火 glm5.1 推荐优先使用：

```powershell
.\scripts\Run-HarnessWithXfyunGlm51.ps1 -ApiKey "<your-xfyun-api-key>" -Port 8081
```

OmniRoute 本地自动回退网关推荐优先使用：

```powershell
.\scripts\Run-HarnessWithOmniRoute.ps1 -Port 8081
```

也可以手工设置同一组环境变量后再用通用启动脚本：

```powershell
$env:OPENAI_API_KEY="<your-xfyun-api-key>"
$env:OPENAI_BASE_URL="https://maas-coding-api.cn-huabei-1.xf-yun.com/v2"
$env:OPENAI_MODEL="xopglm51"
$env:OPENAI_REVIEW_MODEL="xopglm51"
$env:OPENAI_WIRE_API="chat_completions"

.\scripts\Run-HarnessWithJava21.ps1 -Port 8081
```

> `OPENAI_API_KEY` 是敏感信息，不要写入仓库文档、脚本默认值、`providers.yaml` 或 git tracked 配置文件。

### Java 系统属性

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-Dserver.port` | HTTP 服务端口 | 8081 |
| `-Ddb.path` | SQLite 数据库文件路径 | `${user.home}/.agentcloud/agent_cloud.db` |
| `-Dagentcloud.provider_runs.dir` | provider/Codex run 文件目录，用于保存 prompt、stdout/events、last_message、metadata | `.tmp/provider-runs` |
| `-Dagentcloud.provider_runs.max_per_task` | 每个 provider/task 保留的 run 目录数量 | 20 |
| `-Dagentcloud.provider_runs.max_age_hours` | provider run 目录最大保留小时数 | 168 |

Provider run 目录也可用环境变量覆盖：

| 环境变量 | 说明 |
|----------|------|
| `AGENTCLOUD_PROVIDER_RUNS_DIR` | 同 `-Dagentcloud.provider_runs.dir` |
| `AGENTCLOUD_PROVIDER_RUNS_MAX_PER_TASK` | 同 `-Dagentcloud.provider_runs.max_per_task` |
| `AGENTCLOUD_PROVIDER_RUNS_MAX_AGE_HOURS` | 同 `-Dagentcloud.provider_runs.max_age_hours` |

### 本地 Agent Provider 配置

启动时会读取以下位置的 `providers.yaml` / `providers.yml` / `providers.json`，后面的配置会覆盖默认 protocol registry 中同 id 的 provider protocol：

- 当前工作目录
- `config/`
- `${user.home}/.agentcloud/`
- `/etc/agentcloud/`

当前动态发现只覆盖 generic native CLI provider。支持 `command` 完整命令，也支持 `binary + args` 形态；后者会使用 `binary` 作为启动目标，并把 task prompt 自动追加到参数末尾。新 `id` 会在启动期注册到 `/api/v1/agents` 和 `/api/v1/workers`，并进入 provider-native 路由候选；是否 ready 仍取决于本机 binary、认证和 dispatch preflight。支持的 protocol：

- `native_cli_text`
- `native_cli_json`
- `native_cli_lines`
- `native_cli_stream_json`，当前按行保留输出，不做 provider-specific event 解析

示例：

```yaml
providers:
  - id: trae
    protocol: native_cli_text
    binary: trae
    args: ["chat", "--mode", "agent"]
    env:
      TRAE_MODE: local
```

边界：动态 provider inventory 仍是内存态，未独立持久化；配置文件只在启动时读取，修改后需要重启 harness。Codex app-server 仍走内置 `CodexAppServerWorkerExecutor`；`app_server_json_rpc`、`mcp` 和未声明 protocol 的自动探测不属于当前 generic discovery 能力。

---

## 启动验证

启动成功后，控制台会输出：
- 数据库初始化信息
- Worker 注册信息（12个内置 Worker）
- API 端点列表

服务状态示例输出：
```json
{"status":"up","virtual_threads":true,"version":"0.2.0"}
```

---

## 常见问题与解决方案

### 1. Java 环境问题

**问题**: `Java 21 not found` 或 `JAVA_HOME not set`

**解决方案**:

**Windows:**
```powershell
# 安装 Java 21 后，指定正确路径
. .\scripts\Use-Java21.ps1 -JdkHome "C:\Program Files\Java\jdk-21.0.9+10"
```

**Linux:**
```bash
# Ubuntu/Debian
sudo apt install openjdk-21-jdk

# 设置环境变量
source scripts/Use-Java21.sh /usr/lib/jvm/java-21-openjdk
```

**macOS:**
```bash
# 使用 Homebrew
brew install openjdk@21

# 设置环境变量
source scripts/Use-Java21.sh /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

### 2. Maven 未找到

**问题**: `mvn is not recognized` 或 `command not found: mvn`

**解决方案**:

**Windows:**
```powershell
# 使用 Chocolatey
choco install maven

# 或使用 Scoop
scoop install maven
```

**Linux:**
```bash
# Ubuntu/Debian
sudo apt install maven
```

**macOS:**
```bash
# 使用 Homebrew
brew install maven
```

### 3. 端口被占用

**问题**: `port 8081 is already in use`

**解决方案**:

**Windows:**
```powershell
# 查找占用端口的进程
Get-NetTCPConnection -LocalPort 8081 | Select-Object OwningProcess

# 终止占用进程（替换 PID）
Stop-Process -Id <PID> -Force

# 或使用其他端口
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 8081
```

**Linux/macOS:**
```bash
# 查找占用端口的进程
lsof -Pi :8081

# 终止占用进程（替换 PID）
kill -9 <PID>

# 或使用其他端口
bash scripts/Run-HarnessWithJava21.sh -p 8081
```

### 4. 构建失败

**问题**: Maven 构建失败，出现各种错误

**解决方案**:

```bash
# 清理并重试
mvn clean package -DskipTests

# 增加内存
export MAVEN_OPTS="-Xmx2048m"
mvn package -DskipTests

# 检查网络连接
# 确保可以访问 Maven 中央仓库或配置镜像
```

### 5. 数据库锁定问题

**问题**: SQLite 数据库文件被锁定

**解决方案**:
```bash
# 指定新的数据库路径
# Windows
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -JavaArgs @("-Ddb.path=.tmp/new_database.db")

# Linux/macOS
bash scripts/Run-HarnessWithJava21.sh -a "-Ddb.path=.tmp/new_database.db"
```

### 6. 模型未就绪 / 仅支持 manual-start

**问题**: `/dialogue/` 显示：

```text
模型未就绪 LLM 不可用（gpt-4o-mini · https://api.openai.com/v1），仅支持 manual-start。
```

**原因**: 启动进程没有读到 `OPENAI_API_KEY`，所以 LLM health 为 unavailable；`gpt-4o-mini · https://api.openai.com/v1` 是默认配置，不代表已经连上模型。

**解决方案**:

```powershell
# 推荐：星火 glm5.1
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithXfyunGlm51.ps1 `
  -ApiKey "<your-xfyun-api-key>" `
  -Port 8081

# 或：OmniRoute 本地自动回退网关
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithOmniRoute.ps1 `
  -Port 8081

# 验证
Invoke-RestMethod http://localhost:8081/api/v1/health | ConvertTo-Json -Depth 6
```

确认 `llm.available=true`、`llm.api_key_configured=true`，且 `llm.model=xopglm51`。

如果你选的是 OmniRoute，还要再确认：

```powershell
Invoke-RestMethod http://localhost:20128/v1/models `
  -Headers @{ Authorization = "Bearer sk-omniroute" } |
  ConvertTo-Json -Depth 4
```

如果这里返回 `{"object":"list","data":[]}`，说明不是 Harness 没连上，而是 OmniRoute 本地网关虽然已启动，但还没在 Dashboard 配好任何上游 provider / combo。

---

## 服务停止

### 前台模式
直接在控制台按 `Ctrl + C` 停止服务。

### 后台模式

**Windows:**
```powershell
# 查看进程 ID
Get-Process java | Where-Object { $_.CommandLine -match "agent-cloud-harness" }

# 终止进程
Stop-Process -Id <PID> -Force
```

**Linux/macOS:**
```bash
# 查看进程 ID
ps aux | grep agent-cloud-harness

# 终止进程
kill -9 <PID>

# 或使用保存的 PID 文件
kill -9 $(cat .tmp/harness.pid)
```

---

## 脚本参数说明

### Build-WithJava21 脚本

**PowerShell:**
| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-JdkHome` | JDK 安装路径 | `C:\Program Files\Java\jdk-21.0.9+10` |
| `-SkipTests` | 是否跳过测试 | `false` |
| `-QuietMaven` | Maven 是否静默模式 | `false` |

**Bash:**
| 参数 | 说明 | 默认值 |
|------|------|--------|
| `$1` (JDK_HOME) | JDK 安装路径 | `/usr/lib/jvm/java-21-openjdk` |
| `$2` (SKIP_TESTS) | 是否跳过测试 | `false` |
| `$3` (QUIET_MAVEN) | Maven 是否静默模式 | `false` |

### Run-HarnessWithJava21 脚本

**PowerShell:**
| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-JdkHome` | JDK 安装路径 | `C:\Program Files\Java\jdk-21.0.9+10` |
| `-JarPath` | 指定 JAR 文件路径 | 自动查找 |
| `-Port` | 服务端口 | `8081` |
| `-Background` | 是否后台运行 | `false` |
| `-StdOutPath` | 标准输出日志路径 | `.tmp\server.out.log` |
| `-StdErrPath` | 标准错误日志路径 | `.tmp\server.err.log` |
| `-JavaArgs` | 额外的 Java 参数 | `@()` |
| `-DisableDispatchPreflightWarmup` | 跳过启动时 worker dispatch preflight 预热，适合浏览器验收和隔离调试；不改变运行中 dispatch readiness 语义 | `false` |

**Bash:**
| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-j, --jar` | 指定 JAR 文件路径 | 自动查找 |
| `-p, --port` | 服务端口 | `8081` |
| `-b, --background` | 是否后台运行 | `false` |
| `-o, --stdout` | 标准输出日志路径 | `.tmp/server.out.log` |
| `-e, --stderr` | 标准错误日志路径 | `.tmp/server.err.log` |
| `-a, --java-args` | 额外的 Java 参数 | 无 |
| `-h, --help` | 显示帮助信息 | - |

### Run-HarnessWithXfyunGlm51 脚本

该脚本是 `Run-HarnessWithJava21.ps1` 的包装器，专门用于用星火 glm5.1 启动 harness。

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-ApiKey` | 星火 OpenAI-compatible API Key；未传时读取当前进程 `OPENAI_API_KEY` | `$env:OPENAI_API_KEY` |
| `-BaseUrl` | 星火 API Base URL | `https://maas-coding-api.cn-huabei-1.xf-yun.com/v2` |
| `-Model` | 默认执行模型 | `xopglm51` |
| `-ReviewModel` | judgment / completion review 模型 | `xopglm51` |
| `-WireApi` | wire 协议 | `chat_completions` |
| `-JdkHome` | JDK 安装路径 | `C:\Program Files\Java\jdk-21.0.9+10` |
| `-JarPath` | 指定 JAR 文件路径 | 自动查找 |
| `-Port` | 服务端口 | `8080` |
| `-Background` | 是否后台运行 | `false` |
| `-StdOutPath` | 标准输出日志路径 | `.tmp\xfyun-glm51.out.log` |
| `-StdErrPath` | 标准错误日志路径 | `.tmp\xfyun-glm51.err.log` |
| `-JavaArgs` | 额外的 Java 参数 | `@()` |
| `-DisableDispatchPreflightWarmup` | 跳过启动时 worker dispatch preflight 预热 | `false` |
| `-AutoStop` | 端口已占用时是否自动停止占用进程 | `true` |

### Run-HarnessWithOmniRoute 脚本

该脚本是 `Run-HarnessWithJava21.ps1` 的包装器，专门用于把 Harness 指到本机 OmniRoute OpenAI-compatible 网关；默认会在端口未监听时尝试拉起 `omniroute`，并在启动 Harness 前验证 `/v1/models`。

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-ApiKey` | OmniRoute 本地 API Key；优先建议走 `OMNIROUTE_API_KEY`，未传时回落到 `sk-omniroute` | `$env:OMNIROUTE_API_KEY` |
| `-BaseUrl` | OmniRoute API Base URL；未传时按端口拼成 `http://localhost:<port>/v1` | `http://localhost:20128/v1` |
| `-Model` | 默认执行模型 | `auto/coding` |
| `-ReviewModel` | judgment / completion review 模型 | `auto` |
| `-WireApi` | wire 协议 | `chat_completions` |
| `-OmniRouteCommand` | 本地 OmniRoute 启动命令 | `omniroute` |
| `-OmniRoutePort` | 本地 OmniRoute 端口 | `20128` |
| `-OmniRouteStartupTimeoutSeconds` | 等待 OmniRoute 就绪的超时时间 | `30` |
| `-EnsureOmniRoute` | 端口未监听时是否自动尝试拉起 OmniRoute | `true` |
| `-SkipModelCatalogCheck` | 是否跳过 `/v1/models` 非空校验 | `false` |
| `-OmniRouteStdOutPath` | OmniRoute 标准输出日志路径 | `.tmp\omniroute.out.log` |
| `-OmniRouteStdErrPath` | OmniRoute 标准错误日志路径 | `.tmp\omniroute.err.log` |
| `-JdkHome` | JDK 安装路径 | `C:\Program Files\Java\jdk-21.0.9+10` |
| `-JarPath` | 指定 JAR 文件路径 | 自动查找 |
| `-Port` | Harness 服务端口 | `8080` |
| `-Background` | 是否后台运行 Harness | `false` |
| `-StdOutPath` | Harness 标准输出日志路径 | `.tmp\omniroute-harness.out.log` |
| `-StdErrPath` | Harness 标准错误日志路径 | `.tmp\omniroute-harness.err.log` |
| `-JavaArgs` | 额外的 Java 参数 | `@()` |
| `-DisableDispatchPreflightWarmup` | 跳过启动时 worker dispatch preflight 预热 | `false` |
| `-AutoStop` | Harness 端口已占用时是否自动停止占用进程 | `true` |

---

## 推荐学习路径

1. **初学者**: 从 `Build-WithJava21.*` 和 `Run-HarnessWithJava21.*` 脚本开始
2. **进阶用户**: 了解 Maven 构建流程和 Java 环境配置
3. **开发者**: 探索源码结构和测试框架

---

## 相关文档

- `docs/README.md` - 文档总索引、任务分流与写回规则
- `WAKE.md` / `AGENTS.md` - 如果你不是单纯启动，而是要继续开发、排查或整理文档，先从这里建立上下文
- `docs/dialogue/README.md` - `/dialogue/`、`/console/`、chat facade、浏览器验收入口
- `docs/provider/README.md` - provider、worker、路由、dispatch/readiness、profile routing 入口
- `docs/DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md` - UI 适配说明
- `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md` - UI 验证指南
- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md` - GitHub 发布测试矩阵
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md` - Chat Facade 验收指南
