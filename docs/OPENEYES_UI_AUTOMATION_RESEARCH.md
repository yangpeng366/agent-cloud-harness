# OpenEyes UI Automation Research

> 借鉴类调研，对齐 `WBBENCH_BENCH_SKILLS_RESEARCH.md` 与 `JEV_CONTEXT_SCORING_RESEARCH.md` 的写作形态。

## 0. 摘要

调研 `yangpeng366/openeyes`（OpenEyes：UIA + CDP + Vision 三后端统一的桌面 UI 自动化原语层）在 Agent Cloud Harness（ACH）控制面可借鉴 / 可接入的切入点，评估把 ACH 当前 puppeteer-core + Edge headless 单线验收扩展为「browser + native Windows UI 双后端」验收覆盖。

本轮结论：

- **值得立项**，分四档落地（HW-11 / HW-12 / HW-13 / FEAT-04 / FEAT-05），由浅到深逐档签发。
- OpenEyes 是用户自己 repo（full delegation 已授权），不涉及第三方 SDK 接入风险，凭据、版本、协议三方面都自带可控。
- 现有 `Run-DialogueBrowserAcceptanceProbe.ps1` + `Run-ConsoleProviderWindowProbe.ps1` 已经用 puppeteer-core + Edge headless + CDP，加 OpenEyes 后**不替换**而是**叠加**：CDP 路径复用 `seed_user_data` 持久化登录态；UIA 路径补齐 native app 验收。
- 不在本调研阶段引入 OpenEyes 依赖、key、不替换现有 judgment；保持「凭据零暴露、引入可选、回滚 < 1 行配置」。

## 1. 背景

### 1.1 OpenEyes 是什么

来源：https://github.com/yangpeng366/openeyes · 本机 skill: `E:\AI-Portable\codex-home\skills\openeyes\SKILL.md`

- **OpenEyes = AI computer-use primitive layer on this machine**
- 三后端统一 schema：
  - **UIA**（`openeyes.backends.uia`）：Windows / macOS / Linux native apps（pywinauto + ctypes）。覆盖 Any VPN、VS Code、Feishu、Word、Excel、Explorer。
  - **CDP**（`openeyes.backends.cdp`）：Chromium 浏览器页（Edge / Chrome / WebView2），raw websocket-client 直连 Chrome DevTools Protocol；DOM probe 返回结构化 `Element[]`（name / role / bbox / automation_id / class），不依赖视觉模型。
  - **Vision**：Phase 3 fallback（OmniParser / Florence-2），用于 canvas / image-only UI，尚未上线。
- **CLI 入口**：`eyes`（UIA / CDP / Vision 命令），所有副作用操作默认 `dry_run=true`，需 `--go` 才真执行。
- **Python 入口**：`openeyes` 包，提供 `find_window / detect_elements / click_by_selector / cdp.launch_edge / cdp.scan_dom` 等。
- **MCP 入口**：`eyes-mcp` 暴露 13 个 MCP 工具（stdio transport），含 `list_windows / capture_window / detect_elements / click / grid / hotkey / type_text`（UIA）+ `browser_launch / browser_tabs / browser_scan / browser_click / browser_type / browser_shot`（CDP）。

### 1.2 ACH 当前 UI 自动化现状

来源：`D:\gitAll\agent-cloud-harness\scripts\` + `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`

| 工具 | 路径 | 覆盖 |
|---|---|---|
| `Run-DialogueBrowserAcceptanceProbe.ps1` + `dialogue-browser-acceptance-probe-runner.cjs` | puppeteer-core + Edge headless + CDP | `/dialogue/` 真实页面 |
| `Run-ChatFacadeAcceptanceProbe.ps1` | 同上 | chat facade HTTP + 浏览器双通道 |
| `Run-ConsoleProviderWindowProbe.ps1` | 同上 | `/console/` provider 窗口 |
| `Run-TaskRecoveryAcceptanceProbe.ps1` | 同上 | task recovery 流 |
| `recovery-job-ui-probe.js` | 同上 | recovery UI 探测 |
| `Run-BaselineMatrixRealWorkerSmoke.ps1` / `Run-BaselineMatrixGateProbe.ps1` | HTTP only | baseline matrix |

**覆盖盲区**：

1. 每次启动浏览器都要重新登录（无 profile 持久化），影响 `/dialogue/` 中需要登录态的验收；
2. 无法验收 native Windows app（Any VPN / Feishu native / VS Code / Word / Excel / Explorer）；
3. 无 Vimium-style letter hint 辅助，agent 必须描述坐标或 selector；
4. MCP 工具注册面未铺到 ACH worker，worker tool 仍以本地文件操作 + shell + REST 为主。

## 2. 借鉴边界（不做什么）

- **不替换** puppeteer-core 路径；现有 probe 在 CI / precheck 场景仍可用，OpenEyes 走「可选叠加」语义。
- **不引入** OpenEyes 作为 ACH 强依赖；`enabled=false` 时零外部依赖、零网络调用。
- **不写** OpenEyes skill（`E:\AI-Portable\codex-home\skills\openeyes\` 已存在），ACH 不复制 skill 内容；只在脚本与文档里消费其 CLI / Python / MCP 接口。
- **不碰** OpenEeyes 凭据；OpenEyes 本地运行无凭据，但若启用 MCP server（stdio transport）需确认本地 socket 权限。

## 3. ACH × OpenEyes 耦合点评估

### 3.1 HW-11 · 浅 · 1 天 · ACH acceptance 套件接入 OpenEyes CDP

- **范围**：`Run-DialogueBrowserAcceptanceProbe.ps1` 当前每次重启 Edge 都 fresh 登录；接入 OpenEyes `cdp.launch_edge(seed=False)` + `seed_user_data` profile copy 后，登录态可跨脚本保留，验收 flaky 率下降。
- **入口**：`scripts/Run-DialogueBrowserAcceptanceProbe.ps1`（首次调用 `eyes browser launch --no-seed` 时探测 `~/.openeyes/profiles/<name>/` 是否存在）。
- **验收**：同一 profile 跨 5 次 acceptance 跑，`user_session.cookies_loaded=true` 全部命中；登录耗时由 ~12s 降到 ~2s。

### 3.2 HW-12 · 浅 · 2 天 · ACH acceptance 套件接入 OpenEyes UIA

- **范围**：新建 `scripts/Run-NativeAppAcceptanceProbe.ps1`，调用 `eyes windows list --title-contains "<app>"` + `eyes detect --window <hwnd> --pretty` + `eyes click --window <hwnd> --name-contains "<target>" --go`；用于 native app 验收（Any VPN 弹窗 / Feishu native 通知 / VS Code 状态栏等）。
- **入口**：`scripts/Run-NativeAppAcceptanceProbe.ps1`（与现有 browser probe 同目录）。
- **验收**：能在 Windows 11 24H2 + DPI 3840x1200 下成功 `detect` + `click` + `screenshot` Any VPN 主窗口的「连接」按钮；不依赖绝对坐标。

### 3.3 HW-13 · 中 · 3 天 · ACH 启动 OpenEyes MCP 可达性 precheck

- **范围**：ACH 启动时（`Run-HarnessWithJava21.ps1`）自动检测 `eyes-mcp` 可达性；不可达时提示用户启动；检测结果写到 `harness-state.json.ccxChannels`。
- **入口**：`scripts/Use-Java21.ps1` + `Run-HarnessWithJava21.ps1`，新增 `eyes_mcp.health_check_on_startup: true` 配置项（与现有 `ccx.health_check_on_startup` 对齐）。
- **验收**：启动日志包含 `eyes_mcp: healthy / unhealthy: <reason>`；`/console/` 看到 OpenEyes 渠道健康。

### 3.4 FEAT-04 · 中 · 1-2 周 · ACH control plane 把 OpenEyes MCP tool 注册为可选 worker tool

- **范围**：把 OpenEyes 13 个 MCP tool 注册到 ACH `WorkerExecutor` 的 Tool-aware 执行面。worker 在 task 描述包含「打开 X 应用 / 点击 Y / 输入 Z」类指令时，能自动调用对应 MCP tool。
- **入口**：`src/main/java/com/agentcloud/worker/OpenEyesMcpWorkerExecutor.java`（新建）+ `docs/API_CONTRACTS.md` MCP tool 段。
- **验收**：
  - ACH 通过 MCP stdio 启动并发现 13 个 tool（`list_windows / click / type_text / browser_launch / browser_scan` 等）；
  - 至少一条 end-to-end 任务链可验证「open Feishu → click 消息框 → type hello → screenshot」；
  - `runtime_facts.openeyes_tool_calls` 记录调用次数、延迟、成功率；
  - 关闭 `eyes_mcp.enabled` 时所有指标回到当前基线，回滚成本 < 1 行配置。

### 3.5 FEAT-05 · 深 · 1 周 · ACH control plane 利用 OpenEyes 做 structured UI assertion

- **范围**：在 baseline matrix smoke 验证里加一条 OpenEyes 驱动的 UI assertion：worker 完成任务后，自动调用 `eyes detect --window <hwnd>` 拿到目标元素结构化描述，跟期望 contract 对比（不等像素、断言结构）。
- **入口**：`scripts/Run-BaselineMatrixRealWorkerSmoke.ps1` + 新增 `scripts/Run-OpenEyesUiAssertion.ps1`。
- **验收**：
  - baseline matrix 加 1 条 `ui_assertion_mode=openeyes_structured` 模式；
  - 替代脆弱的 screenshot diff，CI flaky 率下降；
  - `/console/` 看到 UI assertion 历史（pass / fail / reason）。

## 4. 风险与依赖

| 维度 | 评估 |
|---|---|---|
| 可落地性 | 高。OpenEyes 是用户自己 repo（full delegation），CLI / Python / MCP 三入口稳定，参考 skill 已就位 |
| 依赖风险 | 低。OpenEyes 本地运行无外部凭据；MCP server 用 stdio transport，不暴露端口；唯一外部风险是 Edge `User Data` 锁定（已有 workaround） |
| 可观察性 | 高。`runtime_facts.openeyes_*` 与 `/console/` OpenEyes 渠道健康可观测 |
| 回滚成本 | 极低。`eyes_mcp.enabled=false` 即可全量关闭，恢复到当前基线 |
| 跟现有 OSS 的差异 | 大。ACH 当前没有任何 UI 自动化层面的 native / structured assertion 能力；引入后可直接替代脆弱 screenshot diff |

## 5. 立项清单

> 本调研驱动以下 5 条候选；按 HW → FEAT 顺序逐档签发。维护者按 `CONTRIBUTING.md` 流程开 issue 对齐字段设计后再写 `plan.md`。

- **HW-11 · OpenEyes CDP profile 持久化接入 acceptance 套件**（浅，1 天）
- **HW-12 · OpenEyes UIA native app acceptance probe**（浅，2 天）
- **HW-13 · OpenEyes MCP 启动可达性 precheck**（中，3 天）
- **FEAT-04 · OpenEyes MCP tool 注册为可选 worker tool**（中深，1-2 周）
- **FEAT-05 · OpenEyes 驱动的 structured UI assertion**（深，1 周）

## 6. 凭据 / 依赖纪律（强制）

- OpenEyes 本地运行无外部凭据；若启用 MCP server 走 stdio transport，**不需要** token / API key。
- ACH 仓库**不引入** `pip install openeyes` 硬依赖；走 `python -c "from openeyes import ..."` 调用；若未安装则 graceful fallback（probe 输出 `openeyes_not_available`）。
- CI / precheck 默认不联 OpenEyes；只在 `eyes_mcp.enabled=true` 的本地或带 UI 探针节点上跑。
- `enabled=false` 时零外部依赖、零网络调用，**保证 baseline matrix 不漂移**。

## 7. 与现有 evaluation / provider 主题的对齐

- 本文档登记到 `docs/evaluation/README.md` 的"对标 / 借鉴 / 产品化"区。
- HW-11 / HW-12 / HW-13 落 `docs/dialogue/README.md`（acceptance / probe 主题）或 `scripts/` 同级。
- FEAT-04 / FEAT-05 落 `docs/continuity/README.md`（control plane / worker tool 主题）。
- 与现有 `Run-DialogueBrowserAcceptanceProbe.ps1` 共存，不替换；FEAT-05 一旦落地可以作为 screenshot diff 的可选替代。

## 8. 下一步

- 等维护者拍板；按 HW-11 → HW-12 → HW-13 → FEAT-04 → FEAT-05 顺序签发。
- 不在本调研阶段安装 OpenEyes Python 包、不引入 MCP server、不动 production 路径。
- OpenEyes 是用户自己 repo，可直接 git clone 到 `${user.home}/openeyes/` 或消费 skill 现成接口。

## 9. 参考链接

- https://github.com/yangpeng366/openeyes
- `E:\AI-Portable\codex-home\skills\openeyes\SKILL.md`（本机 skill 入口）
- `D:\gitAll\agent-cloud-harness\scripts\Run-DialogueBrowserAcceptanceProbe.ps1`（现有 browser acceptance probe）
- `D:\gitAll\agent-cloud-harness\docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`（现有 acceptance runbook）

> 维护约定：本文是借鉴类调研，立项后由对应 FEAT / HW 的 plan.md 接管主线，本调研转为「立项依据」；未立项前只在 evaluation 主题下被引用，不进入产品叙事。