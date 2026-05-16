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
| `agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` | 若存在，优先使用的 shaded JAR |
| `agent-cloud-harness-0.1.0-SNAPSHOT.jar` | 当前同样可直接运行；脚本会在找不到 shaded JAR 时自动回退到它 |
| `original-agent-cloud-harness-0.1.0-SNAPSHOT.jar` | shade 前原始 JAR |

## 启动命令

### PowerShell 启动命令（推荐）

```powershell
# 1. 切换到项目目录
cd d:\gitAll\agent-cloud-harness

# 2. 使用 Java 21 环境并启动服务
. .\scripts\Use-Java21.ps1 -Quiet
java --enable-preview '-Dserver.port=8080' '-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\agent_cloud_new.db' -jar .\target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

### 快捷启动脚本

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Port 8080 `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\agent_cloud_new.db")
```

更推荐直接使用仓库脚本；它现在会自动优先寻找：

1. `target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`
2. `target\agent-cloud-harness-0.1.0-SNAPSHOT.jar`

因此不需要手工判断当前构建目录里到底是哪种可执行 JAR 可用。
另外，`-Background` 模式现在会先把运行 JAR 复制到 `.tmp\runtime-jars\` 再启动，避免你在本机继续 `mvn package` 或重建 `target\*.jar` 时，把正在运行的 `/dialogue/` 静态资源读取链打坏。
另外，后台启动现在会在端口已被占用时直接失败，避免 Puppeteer 验证误打到旧实例。

## `/dialogue/` 本地验证推荐启动方式

如果目的是跑 `/dialogue/` 的 `puppeteer-core` 壳层截图或轻量业务 smoke，推荐始终使用**隔离数据库**启动，避免本机已有 session/task 污染页面状态：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Background `
  -Port 18386 `
  -StdOutPath .tmp\server-18386.out.log `
  -StdErrPath .tmp\server-18386.err.log `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db")
```

启动成功后，可按职责分两层验证：

```powershell
# 1. 壳层/布局验证
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json

# 2. 轻量前端业务 smoke
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json
```

说明：

- `scripts/screenshot.js` 只负责 `/dialogue/` 的 chat shell / layout 断言，不负责 continuity 业务正确性。
- `scripts/dialogue-business-smoke.js` 只负责轻量前端交互 smoke。
- richer browser acceptance、façade continuity、`chat/responses` richer path 仍由 `Run-DialogueBrowserAcceptanceProbe.ps1` 这条 acceptance 工具链承担。
- `scripts/screenshot.js` 和 `scripts/dialogue-business-smoke.js` 不要在同一实例上并发跑；后者会主动创建 session / task 并改写当前 hash，容易把前者的 shell report 污染成“带业务状态的截图”。
- `scripts/screenshot.js` 现在会先显式等待 `/api/v1/health` 再打开 `/dialogue/`；fresh 启动时不要再把短暂的 `ERR_CONNECTION_REFUSED` 误判成页面回归。
- 当前 fresh 绿灯样本可直接参考：
- `.tmp/dialogue-shell-report-18386.json`
- `.tmp/dialogue-business-smoke-18386.json`
  - `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
  - `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
  - `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`
- 当前更稳的本地验证习惯是：**先完成构建，再起 fresh 隔离实例；实例运行期间不要继续覆盖 `target\*.jar`**。虽然后台脚本已改成复制 runtime jar，但保持“build then start”的顺序仍然更容易排障；此前在并行重建和启动时，曾真实出现 fresh 实例启动报 `NoClassDefFoundError: com/fasterxml/jackson/databind/PropertyNamingStrategies` 的时序性故障。
- 当前 `/dialogue/` 的默认 shell contract 也已经进一步收紧：
  - 默认打开 `/dialogue/` 时，不应自动带出 `task=` hash
  - session-scoped shell 下，composer 的 task-only 次级动作与上下文块默认隐藏
- 当前 unified fresh `18386` 还额外确认了三条真实 UI contract：
  - `details=open` 不再只是 hash/state 变化，desktop / responses 下右侧 details panel 会真正出现
  - 更窄的 `thread rail + details` 列宽 (`196px / 292px`) 已经通过 fresh 实例真实生效，不再停留在源码层
  - `desktop / narrow / responses` 三个 profile 都为绿；其中 `narrow` 下当前 `header / transcript / composer` 是 `83px / 462px / 213px`，说明这轮移动端减重后 transcript-first 仍然成立

### 前端改动何时生效

这里要区分两类文件：

- `src/main/resources/web/dialogue/*`
  - 这是实际随 JAR 分发的前端资源
  - 改完后**不会**被当前正在运行的服务热加载
  - 要看到改动，必须至少重新构建一次，再重启实例
- `scripts/*.js`
  - 这是本地验证脚本
  - 改完后，下次直接运行脚本就会生效

原因是当前 `/dialogue/` 静态资源由 `WebConsoleHandler` 从运行时 JAR 的 classpath 读取，不是直接从源码目录读取；而 `Run-HarnessWithJava21.ps1 -Background` 还会先复制运行 JAR 到 `.tmp\runtime-jars\` 再启动，所以：

- 你后面重新 `mvn package` 或 `Build-WithJava21.ps1`，**不会**自动把新前端资源注入到已运行实例
- 想验证新的 `/dialogue/` HTML/CSS/JS，仍应按“先 build，再起 fresh 实例”的顺序走

### 工作区源码和当前运行实例可能不一致

真实调试时，还要额外注意一类很容易误判的问题：

- 你当前工作区里的 `src/main/resources/web/dialogue/*` 已经改了
- 但 `http://localhost:8080/dialogue/` 仍然可能在跑旧构建

常见表现：

- 源码里默认聊天已经应该 materialize 成 task
- 真实页面上却还提示“已记录到当前会话。如需进入 harness 执行，请使用 task_auto 或 task_required”
- 源码里已经把某些乱码失败输出降级成可读摘要
- 真实页面上却还直接显示 mojibake

这通常不代表“新代码没写对”，而是说明：

1. 当前实例启动时复制进去的 runtime JAR 仍是旧构建
2. 或者本机构建工具没有真正执行成功，fresh 实例实际上没有吃到新资源

推荐排查顺序：

1. 先确认 `Build-WithJava21.ps1` 是否真的成功完成
2. 再确认 fresh 实例是否是在构建完成之后才启动
3. 再看当前运行进程的命令行是不是你预期的那一份 JAR
4. 最后再判断是真功能回归，还是运行时仍在看旧版本

如果页面行为和工作区源码明显不一致，优先按“旧 runtime / stale build”处理，不要先把锅甩给前端逻辑本身。

### 本机没有 `mvn` 进 PATH 时怎么办

当前仓库脚本优先假设本机能直接调用 `mvn`。如果你看到：

- `The term 'mvn' is not recognized`

要先处理这个环境问题，否则：

- 前端源码虽然改了，但不会重新打进新的 JAR
- fresh 实例也就不会真正吃到新页面资源

当前更稳的做法是：

1. 先用仓库脚本切到 Java 21
2. 再让构建脚本自动解析本机 Maven 可执行路径
3. 只有构建成功后，再起 fresh 实例跑 `/dialogue/` 验证

### `/dialogue/` 相关文档职责

如果要继续做 `/dialogue/` 的 UI 收口或本地验证，建议按下面的职责分工看文档：

- `STARTUP_GUIDE.md`
  - 统一启动入口；负责怎么构建、怎么起隔离实例、怎么跑本地 shell screenshot / light business smoke
- `docs/DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
  - 负责说明为什么往 `codex` 的 chat shell 靠、借什么、不借什么
- `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md`
  - 负责 `/dialogue/` UI 的具体验证顺序与证据分层
- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
  - 负责上 GitHub 前，页面功能应该按哪些层做比较完整的发布前测试与调试
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
  - 负责 richer continuity / façade acceptance，不替代前面的 UI 壳层验证
- `docs/TEXT_ENCODING_COMPATIBILITY_PLAN.md`
  - 负责区分“仓库内部 UTF-8”与“外部进程输出编码兼容”

当前更稳的执行顺序是：

1. 先按这份 `STARTUP_GUIDE.md` 起 fresh 隔离实例。
2. 先跑 `scripts/screenshot.js` 看 shell / layout。
3. 再跑 `scripts/dialogue-business-smoke.js` 看 light business smoke。
4. 只有前两层稳定后，再跑 richer browser acceptance。

如果当前目标是“GitHub 上架前把页面功能测完整”，不要只停在上面四步；还要补：

5. Java HTTP 回归
6. Node 单测
7. browser acceptance 的 `chat / responses` surface
8. A-H 真实人工手点与验收记录回填

完整矩阵见：

- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`

另外，当前仓库默认约定是：**调研、排查、方案设计、验收结论整理这类任务优先先落文档，再继续代码或验证**。如果只是口头在对话里确认，而没有把结论写回最贴近主题的 `docs/*.md` / runbook / record，后续协作很容易重复劳动。

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
