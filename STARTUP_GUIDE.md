# Agent Cloud Harness 启动命令配置文档

## 概述

本文档记录 Agent Cloud Harness 的启动命令配置，便于快速启动服务并访问 Dialogue 界面。

## 构建命令

### 前提条件

- **Java 21**（必须，项目启用了 `--enable-preview`）
- **Maven 3.9+**

### PowerShell 构建命令（推荐）

```powershell
# 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 使用项目脚本构建（自动切换到 Java 21）
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests
```

### 手动构建命令

```powershell
# 1. 先切换到 Java 21 环境
. .\scripts\Use-Java21.ps1 -Quiet

# 2. 使用 Maven 构建
mvn package -DskipTests
```

### 构建产物

构建成功后，生成的 JAR 文件位于 `target/` 目录：

| 文件 | 说明 |
|------|------|
| `agent-cloud-harness-0.1.0-SNAPSHOT.jar` | 可执行的 uber JAR（推荐使用） |
| `original-agent-cloud-harness-0.1.0-SNAPSHOT.jar` | shade 前的原始 JAR |

## 启动命令

### PowerShell 启动命令（推荐）

```powershell
# 1. 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 2. 使用 Java 21 环境并启动服务
. .\scripts\Use-Java21.ps1 -Quiet
java --enable-preview '-Dserver.port=8080' '-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\agent_cloud_new.db' -jar .\target\agent-cloud-harness-0.1.0-SNAPSHOT.jar
```

### 快捷启动脚本

```powershell
powershell -ExecutionPolicy Bypass -Command ". .\scripts\Use-Java21.ps1 -Quiet; java --enable-preview '-Dserver.port=8080' '-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\agent_cloud_new.db' -jar .\target\agent-cloud-harness-0.1.0-SNAPSHOT.jar"
```

## 环境变量与系统属性

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-Dserver.port` | HTTP 服务端口 | 8080 |
| `-Ddb.path` | SQLite 数据库文件路径 | `${user.home}/.agentcloud/agent_cloud.db` |

## 访问地址

### 对话界面（Dialogue）
**地址**: `http://localhost:8080/dialogue/`

### Web 控制台（Console）
**地址**: `http://localhost:8080/console/`

### API 健康检查
**地址**: `http://localhost:8080/api/v1/health`

## 启动验证

启动成功后，控制台会输出：
- 数据库初始化信息
- Worker 注册信息（12个内置 Worker）
- API 端点列表

服务状态示例输出：
```json
{"status":"up","virtual_threads":true,"version":"0.2.0"}
```

## 常见问题

### 端口被占用

```powershell
# 查找占用端口的进程
Get-NetTCPConnection -LocalPort 8080 | Select-Object OwningProcess

# 终止占用进程（替换 PID）
Stop-Process -Id <PID> -Force
```

### 数据库锁定问题

使用 `-Ddb.path` 参数指定新的数据库路径，避免原数据库文件被锁定的问题。

## 服务停止

直接在控制台按 `Ctrl + C` 停止服务。
