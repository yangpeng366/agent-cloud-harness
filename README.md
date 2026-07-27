# Agent Cloud Harness

> Continuity-first agent cloud control plane — 面向多智能体协作的轻量控制平面服务。

Agent Cloud Harness 是一个**单进程、零外部依赖**的本地/单机控制平面。它负责会话与任务管理、Worker 自动路由、暂停/恢复/移交时的续跑上下文生成，以及过程数据的本地持久化。当前版本已内置 **Web Console** 和 **Dialogue** 前端，可直接在浏览器中交互式地创建任务、观察执行轨迹、查看路由决策与工具调用链。

**当前版本**：`0.1.0-SNAPSHOT`  
**定位**：本地原型与单机 harness，适合快速验证多 Agent 编排、工具链执行、记忆巩固与实验评估流程。

---

## ✨ 核心能力

- **会话与任务生命周期**：创建会话 → 创建任务 → 自动调度 → 暂停/恢复/移交/升级 → 关闭
- **Worker 自动路由**：按 capability + readiness + learning memory 自动匹配最佳 Worker，保留 fallback
- **Tool-aware 执行**：单轮内支持最多 3 步的受控工具链（本地文件搜索、读取、写入、列表），带路径校验与重复调用守卫
- **续跑与交接上下文**：在 pause / handoff / escalate 等关键节点自动生成 `ResumePacket` / `HandoffPacket`，并写入 checkpoint
- **运行时判断（Judgment）**：基于 LLM 的 execution / completion 判断，输出推荐动作与下一步建议
- **Learning Memory**：在任务执行中自动记录 routing preference 与 completion pattern，用于后续路由优化
- **实验矩阵**：内置 baseline case catalog，支持批量创建 `strong_only / small_only / orchestrated` 三种模式的可比较实验 run
- **Web GUI**：内置 `/console/`（任务/会话/Worker 观测面板）与 `/dialogue/`（对话式任务发布与消息流）

---

## 🚀 快速开始

### 环境要求

- **Java 21**（必须，项目启用了 `--enable-preview`）
- **Maven 3.9+**
- 对 `${user.home}/.agentcloud/` 目录有写权限（SQLite 数据库自动落在此目录）

### 1. 克隆与构建

```bash
git clone https://github.com/yangpeng366/agent-cloud-harness.git
cd agent-cloud-harness
mvn package
```

> 仓库已公开发布（见 [CHANGELOG.md](CHANGELOG.md) 的 `[0.1.0]` 段）。

> Windows 用户如果环境中有多个 JDK，推荐先执行仓库脚本确保使用 Java 21：
> ```powershell
> .\scripts\Use-Java21.ps1
> .\scripts\Build-WithJava21.ps1 -SkipTests
> ```

构建产物：
- `target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` — 推荐运行的 shaded JAR
- `target/agent-cloud-harness-0.1.0-SNAPSHOT.jar` — 当前同样可直接运行
- `target/original-agent-cloud-harness-0.1.0-SNAPSHOT.jar` — shade 前原始 JAR

### 2. 启动服务

```bash
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

默认监听 `8080`。如需换端口：
```bash
java -Dserver.port=9090 --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

Windows PowerShell 建议优先使用仓库脚本，避免 `-D...` 参数被当前 shell/JDK 组合误解析：
```powershell
.\scripts\Run-HarnessWithJava21.ps1 -Port 9090
```

如果你要直接在 PowerShell 里调用 `java`，请先切到 Java 21，并把 `-D...` 参数作为单独字符串传入：
```powershell
. .\scripts\Use-Java21.ps1
java --enable-preview '-Dserver.port=9090' -jar .\target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

启动成功后，控制台会打印所有可用端点，并显示：
```
Web console: http://localhost:8080/console/
```

### 3. 验证运行

```bash
# 健康检查
curl http://localhost:8080/api/v1/health

# 创建一个任务
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"hello world","task_type":"coding","source":"user","priority":"high","intent":"demo"}'

# 查看 worker 列表
curl http://localhost:8080/api/v1/workers
```

---

## 🖥 GUI 页面使用

启动服务后，直接在浏览器打开以下地址即可使用内置前端（无需额外安装 Node 或构建步骤）：

### Dialogue — 对话式任务交互
**地址**：`http://localhost:8080/dialogue/`

- 左侧切换/创建会话
- 中间以聊天形式发送任务、追加消息、查看 assistant 回执（task_receipt / task_progress / task_result）
- 右侧实时展示当前任务的 **Route**（路由决策）、**Judgment**（执行/完成判断）、**Artifacts**（产物）、**Tool Trace**（工具调用链）

### Console — 运行观测面板
**地址**：`http://localhost:8080/console/`

- 查看所有会话与任务列表
- 点击任务进入详情：状态、assigned worker、control node、summary
- 直接触发控制动作：Pause / Resume / Continue / Escalate / Handoff
- 查看 Live Flow 聚合诊断面：packet、runtime context、checkpoints、learning memories、tool invocations 一站式聚合

---

## ⚙️ 可选：配置 LLM（用于 Judgment 与 Tool-aware Execution）

如果你希望启用基于 LLM 的 judgment 和 tool-aware worker 执行，需在启动前配置环境变量：

```bash
export OPENAI_API_KEY="sk-..."
export OPENAI_BASE_URL="https://api.openai.com/v1"  # 如有自定义 endpoint 可修改
export OPENAI_MODEL="gpt-4o-mini"                    # 或其他兼容模型
export OPENAI_REVIEW_MODEL="gpt-4o-mini"             # 可选；未设置时默认回落到 OPENAI_MODEL
export OPENAI_WIRE_API="chat_completions"            # 可选：chat_completions | responses
```

Windows PowerShell：
```powershell
$env:OPENAI_API_KEY="sk-..."
$env:OPENAI_BASE_URL="https://api.openai.com/v1"
$env:OPENAI_MODEL="gpt-4o-mini"
$env:OPENAI_REVIEW_MODEL="gpt-4o-mini"
$env:OPENAI_WIRE_API="chat_completions"
```

说明：

- Worker 执行默认使用 `OPENAI_MODEL`
- Judgment / Completion Review 默认使用 `OPENAI_REVIEW_MODEL`；未设置时回落到 `OPENAI_MODEL`
- `OPENAI_WIRE_API=responses` 时，客户端会走 OpenAI-compatible `POST /v1/responses`

未配置时，LLM 层会以 `available=false` 降级运行，部分功能（如 prompt-based judgment）将回退到规则判断或默认执行器。

可用性检查：

```bash
curl http://localhost:8080/api/v1/health
```

`llm.available=true` 表示启动进程已读取到非空 `OPENAI_API_KEY`；响应只返回 `api_key_configured` 布尔值，不回显密钥内容。

如果你更想用一个本地 OpenAI-compatible 网关承接自动回退和免费/低价组合，而不是直连单一上游，可以直接用仓库脚本：

```powershell
.\scripts\Run-HarnessWithOmniRoute.ps1 -Port 8081
```

该脚本会默认把 Harness 指到 `http://localhost:20128/v1`，使用 `OPENAI_MODEL=auto/coding`、`OPENAI_REVIEW_MODEL=auto`，并在本机 `omniroute` 未启动时自动拉起本地网关。它默认还会要求 `/v1/models` 返回非空列表，避免“网关已起来，但 Dashboard 里还没配任何上游模型/Combo”的假绿状态。

## ⚙️ 可选：配置本地 Agent Provider

启动时会轻量读取 `providers.yaml` / `providers.yml` / `providers.json`，用于给本地 provider 覆盖或补充 CLI protocol 执行计划：

- 当前目录、`config/`
- `${user.home}/.agentcloud/`
- `/etc/agentcloud/`

当前动态发现只覆盖 generic native CLI provider，支持 `protocol: native_cli_text|native_cli_json|native_cli_lines|native_cli_stream_json`、`command`、`binary`、`args`、`env`、`capabilities`。`command` 会按完整命令执行；`binary + args` 会使用 `binary` 作为启动目标，并自动把 task prompt 追加到参数末尾。新 `id` 会在启动期注册到 `/api/v1/agents` 和 `/api/v1/workers`，并进入 provider-native 路由候选；是否 ready 仍取决于本机 binary、认证和 dispatch preflight。`native_cli_stream_json` 在 generic 配置里按行保留输出；Codex app-server 仍走内置 Codex 执行链，不通过 generic discovery 动态注册。

边界：动态 provider inventory 仍是内存态，未独立持久化；配置文件只在启动时读取，修改后需要重启 harness。`app_server_json_rpc`、`mcp` 和未声明 protocol 的自动探测还不属于当前 generic discovery 能力。

示例 `providers.yaml`：

```yaml
providers:
  - id: trae
    protocol: native_cli_text
    binary: trae
    args: ["chat", "--mode", "agent"]
    env:
      TRAE_MODE: local
```

Provider 输出和 Codex app-server 运行文件默认写入 `.tmp/provider-runs/`，可通过 `-Dagentcloud.provider_runs.dir=...` 或 `AGENTCLOUD_PROVIDER_RUNS_DIR` 覆盖。

---

## 📁 项目结构速览

```
agent-cloud-harness/
├── src/main/java/com/agentcloud/
│   ├── cli/           # 启动入口 Main.java
│   ├── agent/         # Provider discovery / registry / runtime support
│   ├── server/        # HTTP Handler（Task/Session/Worker/Skill/Checkpoint/Experiment/LearningMemory/WebConsole）
│   ├── engine/        # 业务核心（TaskService/SessionService/ControlNodeGraph/ConsolidationService/Experiment）
│   ├── engine/router/ # Worker 注册与路由
│   ├── engine/memory/ # PacketBuilder / ContextReconstructor
│   ├── runtime/       # TaskRuntimeContext / ActiveContext 构建
│   ├── judgment/      # Execution / Completion 判断
│   ├── llm/           # OpenAI 兼容 LLM 客户端
│   ├── tool/          # 受控本地文件工具（ListFiles/ReadFile/SearchText/WriteFile）
│   ├── worker/        # DefaultWorkerExecutor / ToolAwareWorkerExecutor
│   ├── model/         # 领域模型（Java Record）
│   └── store/         # DAO / DatabaseManager / Mappers（Jdbi + SQLite）
├── src/main/resources/
│   ├── schema.sql     # 数据库表结构
│   ├── logback.xml    # 日志配置
│   └── web/           # 内置前端（console + dialogue）
├── docs/              # 架构文档、API 契约、排查指南、路线图
├── scripts/           # Windows PowerShell 便捷脚本（Build/Run/Test/Use-Java21）
└── pom.xml
```

---

## 📚 文档导航

先按你当前要做的事选入口，不要一上来就在 `docs/` 根目录长名单里找：

| 现在要做什么 | 先看哪里 | 说明 |
|------|------|------|
| 只想构建、启动、换端口、换数据库路径 | [`STARTUP_GUIDE.md`](STARTUP_GUIDE.md) | 只看运行命令和启动排障，不必先读架构文档 |
| 继续开发、排查问题、整理文档 | [`docs/README.md`](docs/README.md) | 先按主题分流，再进入具体 plan / runbook / record |
| 做文档结构治理、索引审计、命名合同整理 | [`docs/meta/README.md`](docs/meta/README.md) | 先看治理专题入口，再下钻 `DOCS_GOVERNANCE.md` 与活跃进度 |
| AI Agent 接手任务 | [`WAKE.md`](WAKE.md) → [`AGENTS.md`](AGENTS.md) | 先建立上下文，再进入 `docs/README.md` |
| 看最近进展和固定规则 | [`STATE.md`](STATE.md)、[`DECISIONS.md`](DECISIONS.md) | 一个看短进度，一个看稳定取舍 |

根目录文档职责固定如下：

- `README.md`：对外概览、能力说明、快速开始。
- `STARTUP_GUIDE.md`：构建、启动、运行验证、启动期排障。
- `docs/README.md`：开发/排查/文档整理总索引，负责按主题分流。
- `docs/meta/README.md` + `docs/DOCS_GOVERNANCE.md`：文档治理、结构审计、命名合同、专题工作区规则。
- `WAKE.md`、`AGENTS.md`：Agent 开工入口与协作规则。
- `STATE.md`、`DECISIONS.md`：跨主题进度摘要与稳定设计决策。

如果已经确定任务主题，再读对应专题入口：

- 文档治理 / 结构审计：[`docs/meta/README.md`](docs/meta/README.md)
- 控制面主链 / continuity：[`docs/continuity/README.md`](docs/continuity/README.md)
- Provider / Worker / Recovery：[`docs/provider/README.md`](docs/provider/README.md)
- Dialogue / Console / Facade：[`docs/dialogue/README.md`](docs/dialogue/README.md)
- Evaluation / Priorities / Multi-round：[`docs/evaluation/README.md`](docs/evaluation/README.md)
- Release / GitHub：[`docs/release/README.md`](docs/release/README.md)

要看稳定事实和长期边界，再回到这些基线文档：

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/API_CONTRACTS.md`](docs/API_CONTRACTS.md)
- [`docs/SPEC.md`](docs/SPEC.md)
- [`docs/TROUBLESHOOT.md`](docs/TROUBLESHOOT.md)
- [`docs/WEB_CONSOLE.md`](docs/WEB_CONSOLE.md)

贡献与安全说明见 [`CONTRIBUTING.md`](CONTRIBUTING.md) 和 [`SECURITY.md`](SECURITY.md)。版本变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。项目路线见 [`ROADMAP.md`](ROADMAP.md)。

---

## 🧪 运行测试

```bash
# 推荐先确保使用 Java 21
.\scripts\Test-WithJava21.ps1        # Windows
# 或 Linux/macOS
mvn test
```

当前已有 27+ 测试类覆盖 packet 协议、控制图编排、tool-aware 执行、消息投影、live flow、experiment lifecycle 等核心链路。

---

## 🚧 项目状况与已知限制

**已就绪**：
- ✅ 完整的任务生命周期控制图（intake → scheduler → continue → packet / human_gate / handoff）
- ✅ 内置 Web Console 与 Dialogue 前端
- ✅ Tool-aware 多步执行与工具调用轨迹
- ✅ Learning Memory 与实验矩阵评估基础设施
- ✅ 错误响应脱敏、API 参数校验、状态迁移事件投影

**当前限制**（适合本地或受控环境）：
- 🔒 所有端点匿名可访问，**暂无认证/授权/租户隔离**
- 🖥 单进程 + 本地 SQLite，**非分布式架构**
- 📊 列表接口暂无真正分页（固定最近 100 条）
- 🧠 LLM Judgment 质量取决于所配置模型与 prompt 调优

**下一步方向**（持续迭代中）：
- 增加最小认证层与 API Key 管理
- 引入异步 consolidation 与限流
- 完善多节点 Worker 远程注册与心跳
- 更丰富的 baseline case 与自动评估 pipeline

---

## 🤝 贡献与迭代

本项目处于**积极迭代**阶段，欢迎通过 Issue 和 PR 参与：

1. **发现问题**：先查阅 [`docs/TROUBLESHOOT.md`](docs/TROUBLESHOOT.md)，若未解决请提 Issue
2. **新增功能**：参考 [`AGENTS.md`](AGENTS.md) 中的代码风格与架构约定
3. **评估与反馈**：使用 [`docs/LOCAL_DOC_WORKER_PILOT.md`](docs/LOCAL_DOC_WORKER_PILOT.md) 或 experiment matrix 进行本地验证，并分享结果

---

## 📄 License

[MIT](LICENSE) — 自由使用，欢迎共建。

---

> **提示**：README 是公开文档，请勿在代码或文档中提交 API Key、密码等敏感凭证。建议在本地通过环境变量或独立配置文件管理敏感信息。
