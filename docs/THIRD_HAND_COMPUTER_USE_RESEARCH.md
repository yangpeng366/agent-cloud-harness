# Third Hand 计算机使用借鉴调研（OpenEyes 方向）

> 借鉴类调研，对齐 `OPENEYES_UI_AUTOMATION_RESEARCH.md` 与 `JEV_CONTEXT_SCORING_RESEARCH.md` 的写作形态。

## 0. 摘要

调研 `shhivv/third-hand`（macOS menu bar 计算机使用助手，Accessibility + Vision OCR + CDP + TypeSafe Jev 决策）在 OpenEyes（第 4 条项目方向）可借鉴 / 可吸收的切入点。

本轮结论：

- **值得吸收**，但不是整体照搬：third-hand 是 macOS Swift 单机 app，OpenEyes 是跨平台 Python 原语层，价值在「多后端渐进 fallback + Electron/CDP 探测 + Jev 决策协议 + focus 守护 + 受限文本输入」五个模式。
- 与 ACH 既有 Jev 条目（Jev 上下文评分门控 / OpenEyes UI 自动化接入 / Jev × 飞书巡检）共享同一 TypeSafe Jev API（`https://api.typesafe.ai/v1/systemone`），是同一范式在「计算机使用」场景的第三个样本。
- 建议落地为「OpenEyes 决策层可选叠加」：后端补 Electron/CDP 探测分支，决策层可选接 Jev，默认 `enabled=false`，与现有 UIA/CDP/Vision 三后端不替换只叠加。

## 1. 项目画像

- 仓库：https://github.com/shhivv/third-hand · 作者 Shiv Shanmugam（shiv@tryisle.com）
- 描述：`computer-use assistant w/ decision models`
- 时间线：created 2026-09-19，pushed 2026-09-19；**仅 2 天**，265 stars / 21 forks，MIT
- 技术栈：Swift 5.9 + AppKit + ApplicationServices（AX）+ ScreenCaptureKit + CDP WebSocket；macOS 14+
- 依赖：**TypeSafe API key**（付费，存 macOS Keychain）；无本地模型权重、无第三方 runtime
- 本地快照：`F:\github\third-hand`（master，39KB，27 个 Swift 文件）

## 2. 架构分解

### 2.1 交互入口

- 菜单栏 app，全局热键 **Ctrl+Space**（`HotkeyManager.swift`，CGEventTap 捕获 keyDown，keyCode 49 + maskControl 且无 Cmd/Alt/Shift）。
- 热键触发后读当前 frontmost app 作为 `AppTarget`，开始任务循环。

### 2.2 观测三后端（渐进 fallback）

| 后端 | 文件 | 何时用 | 关键参数 |
|---|---|---|---|
| Accessibility | `AXTreeWalker.swift` | 主路径 | 时间预算 0.8s、maxDepth 30、limit 1200、AXMessagingTimeout 0.1s |
| Apple Vision OCR | `VisionObserver.swift` / `TextExtractor.swift` | A11y 暴露不全时 | 本地 OCR，截图不上传 |
| CDP | `CDPClient.swift` + `ElectronDetector.swift` | Electron app 专用 | WebSocket 直连 debug 端口 |

### 2.3 Electron/CDP 探测（ElectronDetector.swift，三步）

1. `ps -p <pid> -o args=` 扫描 `--remote-debugging-port=`；
2. `lsof -a -p <pid> -iTCP:<port> -sTCP:LISTEN -t` 验端口归属；
3. `GET http://localhost:<port>/json/version` 验返回含 `Browser` / `webSocketDebuggerUrl`。

CDP 连接后：`/json` 拉 target，过滤 `type == "page"` 且唯一，校验 `webSocketDebuggerUrl` host ∈ {localhost,127.0.0.1,[::1]} 且 port 一致，再验 `snapshot.screen` 与 focused window frame 四边误差 < 8px（防多 tab / 多窗口错位）。

### 2.4 Jev 决策协议（JevClient.swift）

- 端点：`https://api.typesafe.ai/v1/systemone`。
- 输入：goal + 候选 targets（CLICK / TYPE_TEXT / CLICK_TEXT 三类，按 label 与 goal 词交集相关性排序，cap 255）+ appName + 最近 action history。
- 输出：`decision`（operation + targetIndex/textValue/x/y/key）+ `done`（阈值 **0.70**）+ `absent`（阈值 **0.50**）+ `pickedNone`。
- 操作集：`CLICK / CLICK_TEXT / DOUBLE_CLICK / RIGHT_CLICK / TYPE_TEXT / KEY_PRESS / SCROLL_UP / SCROLL_DOWN / WAIT / DONE / BLOCKED`。
- `done >= 0.70` 强制转 `DONE`；`pickedNone` → `BLOCKED`。

### 2.5 任务编排与守护（TaskRunner.swift）

- `maxSteps = 30`，外层观察预算 40 次（含重试），整任务 180 秒超时。
- `checkFocus()` 每步校验：目标进程未终止 **且** `NSWorkspace.frontmostApplication.processIdentifier == target.pid`，否则立即中止——用户切走就停，防在错误窗口乱点。
- `AXManualAccessibility` + `AXEnhancedUserInterface` 打开后，先 A11y 观测，`targets` 为空则 `recover()` 一次。

### 2.6 安全护栏：受限文本输入（TextEntryPlan.swift）

- **不接受自由写作 / 任意命令生成**，只从当前 goal 提取候选短语：引号内字符串优先 + N-gram 滑窗（≤12 词，≤100 条）。
- 终端模式 strict：精确命令字面，`isTerminal` 按 bundle ID 白名单（Terminal/iTerm2/Ghostty/Warp/kitty/alacritty）；禁止从导航请求构建命令、禁止追加 verify 命令、禁止自动重打。
- `TYPE_TEXT` 目标必须 AXTextField/AXTextArea/AXComboBox，text ≤ 12000 UTF-16；`KEY_PRESS` 仅白名单键 + 修饰键。

## 3. OpenEyes 可吸收点（草案）

1. **后端补 Electron/CDP 分支**：OpenEyes 现有 CDP 后端只针对 Chromium browser；借鉴 `ElectronDetector` 三步探测，给 VS Code / Slack / Feishu / Discord 等 Electron app 提供高保真控制分支（对齐 ACH `OPENEYES_UI_AUTOMATION_RESEARCH.md` 的 HW-11/HW-12 语义）。
2. **决策层可选接 Jev**：OpenEyes 目前是纯原语层（无 LLM 决策）；可借鉴 `JevClient` 加一个可选 `decision` 层，复用 ACH FEAT-03/FEAT-04 的 `enabled=false` 默认关闭、零凭据、fallback 保留现状语义。
3. **focus 守护**：`TaskRunner.checkFocus` 移植为 `eyes` CLI / `eyes-mcp` 的 per-step precondition，防 agent 在焦点丢失后继续操作错误窗口。
4. **受限输入护栏**：`TextEntryPlan` 移植为 OpenEyes 的 `type_text` 前置校验（目标必须是可编辑控件、文本来自用户原请求而非 LLM 自由生成）。
5. **观测预算参数**：借鉴 `30 steps / 40 observation budget / 180s timeout / maxDepth 30 / limit 1200` 作为 OpenEyes task runner 的默认边界。

## 4. 风险 / 注意

- macOS Swift 专用；Windows 落地需在 OpenEyes Python 层重写（UIA 用 pywinauto 已有，缺的是 Electron/CDP 探测 + focus 守护 + 受限输入三件套）。
- **非完全离线**：用户请求 / app 名 / 屏幕 label / 最近动作历史会发到 TypeSafe；接入前需评估凭据与数据边界（对齐 AGENTS.md 凭据纪律：key 仅 env 读、仓库零暴露）。
- 项目仅 2 天、README 自述 experimental；absorb 以「模式借鉴」为主，不做硬依赖。
---

## 5. 落地决策（结合 auto-deploy 现有 JS CDP + puppeteer 栈）

### 5.1 结论：不二选一，走「多后端 + 统一决策层」分层

- **执行/观测层：三后端并存，各用成熟栈，不互相重写。**
- **决策层：只做一个 backend-agnostic 的 Jev 薄层**，吃统一 `Element[]`，输出统一 `Action`（对齐 third-hand 11 操作 + done/absent/pickedNone 三信号），默认 `enabled=false`。

### 5.2 后端分工（对照现状）

| 目标 | 后端 | 语言/栈 | 现状 | 要补的 |
|---|---|---|---|---|
| 浏览器/网页（Axis、Portal、MCH 编辑器） | puppeteer + CDP | JS（auto-deploy `edge-harness.js`） | 已成熟：`puppeteer.launch`（Edge + `--remote-debugging-port` + 临时 profile 种子）+ `puppeteer.connect({browserURL})` + `page.evaluate` + WS 拦截 | 无需补；third-hand 的 CDPClient 对浏览器页无增量 |
| Electron 原生 app（VS Code / Feishu 桌面 / Slack / Discord） | 原生 CDP probe | JS（放 auto-deploy `_lib`） | 无（third-hand `ElectronDetector` 是唯一现成样本） | 新增 `electron-cdp-probe.js`：`ps args` 扫 `--remote-debugging-port=` → 验 LISTEN 归属 → `GET /json/version` 验 Browser → 复用 `puppeteer.connect` |
| 原生非 Electron Windows app（AnyVPN / Word / Excel / Explorer） | UIA | Python（OpenEyes `openeyes.backends.uia`） | 已有 pywinauto + ctypes | 无需补 |

### 5.3 决策层薄化

- JevClient 协议（`api.typesafe.ai/v1/systemone`，done≥0.70 / absent≥0.50 / pickedNone）照搬，但只做**一个**薄客户端，喂统一 `Element[]`。
- JS（auto-deploy）与 Python（OpenEyes）各出薄映射即可，或只出一侧、另一侧 HTTP 调用；避免两份 Jev 协议实现漂移。
- 默认 `enabled=false`，fallback 保留现有 scripted 流程（auto-deploy 的硬编码脚本 / OpenEyes 的纯原语），对齐 ACH FEAT-03/FEAT-04「可选叠加」语义。

### 5.4 落地顺序（平行、不阻塞）

1. `D:\gitAll\auto-deploy\_lib\electron-cdp-probe.js`：Electron debug port 三步探测 + `puppeteer.connect` 复用（可立即用于 Feishu 桌面 / VS Code 验收）。
2. `E:\gitAll\openeyes` 加可选 `decision` 层：吃统一 Element[]，默认关，输出统一 Action。
3. 两侧各接一个 Jev 薄客户端（或单点 HTTP 服务），保留零凭据 / env-only key。

> 说明：third-hand 的 `TaskRunner.checkFocus`（focus 守护）与 `TextEntryPlan`（受限输入）属于「任务编排/安全」层，与后端语言无关，建议落在决策层或 eyes CLI / eyes-mcp 的前置校验里，而非分别塞进 JS / Python 两端。


---

## 6. 与 Jev-OpenEyes 经验的融合（来自 `.tmp/Jev-OpenEyes-experience.md`）

> 该文件位于 `D:\BaiduSyncdisk\Obsidian Vault\当前项目\.tmp\Jev-OpenEyes-experience.md`，由用户整理，按"事实 / 数据 / 模板 / 避坑"四类分组。third-hand 借鉴要落在已有 Jev 借鉴骨架上，避免另起炉灶。

### 6.1 third-hand 在 Jev-OpenEyes 借鉴图谱里的位置

Jev-OpenEyes 三层借鉴（OpenEyes 结构层 done / 调用层 in-progress / 验证层 brainstorm × Jev 决策层 done / 过滤层 done / 评分层 done）的融合路径：

```
Jev 做语义 gate（要不要调用 OpenEyes） → OpenEyes 做结构性 UI 操作 → Jev 评分结果（OpenEyes 在 mounted_context_view panel 里的 relevance）
```

third-hand 属于"结构层"补充，但**不是替换 OpenEyes**：

| 借鉴对象 | 现有等价物 | third-hand 是否带来增量 | 借鉴决策 |
|---|---|---|---|
| 多后端渐进 fallback | OpenEyes 已有 UIA + CDP + Vision 三后端 | 否，三后端已覆盖 | 不补 |
| Electron/CDP 探测 | 无（OpenEyes CDP 只覆盖 Chromium browser） | **是**——`ElectronDetector` 三步（ps args → lsof LISTEN → /json/version）是现有 codebase 唯一现成样本 | **吸**：新增 `_lib\electron-cdp-probe.js` |
| Jev 决策协议 | ACH 已有 JevClient + JevDecisionTreeTest（FEAT-03 path B 已落 src/） | 否；协议字段（done/absent/pickedNone + 11 操作）ACH 已覆盖 | 不补 |
| checkFocus 守护 | 无（现有 eyes CLI / eyes-mcp 无每步 precondition） | **是** | **吸**：落 `eyes` CLI / `eyes-mcp` 前置校验 |
| TextEntryPlan 受限输入 | 无（auto-deploy JS 脚本硬编码 target.type_text；OpenEyes `type_text` 无 schema 校验） | **是**——引号短语 + N-gram 滑窗 + 终端 bundle ID 白名单 + multi-line 拒绝 | **吸**：落 OpenEyes `type_text` 前置校验 |
| 观测预算参数 | 无（auto-deploy JS 脚本无统一 task runner 边界） | **是**——30 steps / 40 observation / 180s timeout / maxDepth 30 / limit 1200 | **吸**：作为 OpenEyes task runner 默认边界 |

### 6.2 工程化契约测试经验（直接套）

按 `.tmp/Jev-OpenEyes-experience.md` §4 五条工程化经验：

- **A. Contract-Additive 路径**——`MountedContextView` / `HandoffPacket` 不破 record signature，OpenEyes 决策层加字段用 `metadata Map<String,Object> jev_decisions` 形态；不要新增构造函数。
- **B. 多 constructor 风险**——`ExecutionDecision` 5 个简化 constructor 用错会编译错误。OpenEyes 决策层如加 Python dataclass，仿照 ACH 风格集中一处构造。
- **C. 3 段 confidence 阈值路由**——FEAT-03 §1.1a：`>= 0.85` act / `>= 0.60` flag_for_review / `< 0.60` fallback；OpenEyes 决策层落地时复用同一阈值表，**避免再发明一套**。
- **D. MountedContextView normalize**——7 个默认 panel（PINNED/ACTIVE/ANCESTOR/SIBLING/EVIDENCE/INDEX/ARCHIVE_HANDLES）；OpenEyes 决策层的"统一 Element[]"输出 schema 对齐这 7 panel 命名。
- **E. fake Jev null/empty**——`Boolean.TRUE.equals(obj)` 替代 `(Boolean) obj`；OpenEyes 决策层 fake 端到端 contract test 套同一模板。

### 6.3 避坑（不要重复踩）

- **JDK 21 + Surefire 3.2.5 + Windows sandbox fork JVM bug**：`DocsIndexAuditScriptTest` 必须 `-Dtest=!DocsIndexAuditScriptTest` 排除。本轮不直接涉及，但 OpenEyes 决策层如引入 Java 端单测，记一笔。
- **fake contract 数字边界**：`0.5 == 0.5` 命中 keep 分支——设计 threshold 时 ambiguous 走 fall through；OpenEyes 决策层 fake contract 同模板。
- **结构 + 语义 hybrid**：Jev 在 top-K 召回稳定但 Kendall τ 在 critical 区间差——OpenEyes 决策层不要把 Jev 当精确排序 oracle 用，保持 hybrid α + γ 形态。

### 6.4 落地顺序（不变，与第 5 节同步）

1. `D:\gitAll\auto-deploy\_lib\electron-cdp-probe.js`：Electron debug port 三步探测 + `puppeteer.connect` 复用。
2. OpenEyes 决策层可选叠加：吃统一 Element[] → 输出统一 Action（done/absent/pickedNone 三信号），**3 段 threshold 表与 FEAT-03 §1.1a 对齐**。
3. `eyes` CLI / `eyes-mcp` 加 checkFocus 前置校验 + TextEntryPlan 受限输入前置校验。
4. fake contract test 套 `.tmp/Jev-OpenEyes-experience.md` §3 模板 + §4 五条经验。


---

## 7. 实测状态（2026-09-21 15:45）

### 7.1 工具落盘

- `D:\gitAll\auto-deploy\_lib\electron-cdp-probe.js`（6.4KB，UTF-8 无 BOM）
- `D:\gitAll\auto-deploy\_lib\__tests__\electron-cdp-probe.unit.js`（1.7KB）
- `D:\gitAll\auto-deploy\_lib\__tests__\electron-cdp-probe.e2e.js`（3.2KB，含启 chrome → probe → connect → goto/click/type 全链路）

### 7.2 关键工程教训（落 Jev-OpenEyes §4 第 E 条：fake 端 null/empty 处理）

- **PowerShell `-Command` 多行 + `$_` + `|` 在 execFile argv 下被错切**（第一次 e2e hits=0 的根因）：改用临时 `.ps1` + `-File` 后稳定。
- **`Get-CimInstance` 数组空时** 必须 `if ($null -eq $rows) { '[]' } elseif ($rows.Count) { ... } else { @($rows) ... }`，避免 `ConvertTo-Json -Compress` 返回空导致 JSON.parse 失败。
- **Powershell 管道无结果时 exit code 非 0**，但 stdout 含 NO 仍应视为成功——`ownsListener` 改为 `(err && !stdout && !stderr) → reject`，否则 `||` 路径会被误判。

### 7.3 实测数据

| 指标 | 数值 |
|---|---|
| probe 命中 chrome.exe（9224，主进程 PID 25608） | hits=1 |
| probe 漏掉子进程 PID 20068 / 19144 | owned=false（LISTEN 不在它们名下） ✓ 正确行为 |
| `puppeteer.connect` 端到端 | pages=1, url=about:blank |
| page.goto + title + h1 + click + type | 全 PASS |
| e2e 端到端耗时 | ~12s（含 chrome 启动） |


---

## 8. OpenEyes 决策层骨架（2026-09-21 16:25 落地）

### 8.1 落地物

- `E:\gitAll\openeyes\openeyes\decision\__init__.py`（1.6KB）
- `E:\gitAll\openeyes\openeyes\decision\threshold.py`（2.5KB）— `ThresholdConfig` + `classify_confidence`
- `E:\gitAll\openeyes\openeyes\decision\action.py`（1.1KB）— `Action` + `ActionKind`
- `E:\gitAll\openeyes\openeyes\decision\scorer.py`（4.4KB）— `JevScorer` ABC + `FakeJevScorer` + `JevScore`/`JevAskError`
- `E:\gitAll\openeyes\openeyes\decision\router.py`（3.6KB）— `decide()` + `DecisionContext`
- `E:\gitAll\openeyes\tests\decision\test_decision.py`（8.5KB）— 24/24 PASS

### 8.2 设计要点

- **3 段阈值表**与 ACH FEAT-03 §1.1a 一致（`0.85` ACT / `0.60` FLAG_FOR_REVIEW / `<0.60` FALLBACK + uncertain band `[0.30, 0.70]` 二次确认）。
- **`enabled=False` 默认关闭**，`decide()` 返回 `None`，调用方走原 `click_by_selector` 脚本路径。
- **网络护栏**：当 `enabled=True` 但未传 `scorer`，默认 `FakeJevScorer`，**永不静默联网**；真用 Jev 必须显式传 `JevScorer` 实现。
- **`FakeJevScorer` null-safe**：Python 版 `Boolean.TRUE.equals` 即 `(state.get(key) is True)` 形态（`.tmp/Jev-OpenEyes-experience.md` §4.E）。
- **Contract-Additive**（§4.A）：新字段不破 `openeyes.Element` / `Action` dataclass，`metadata jev_decisions` 留给上层。
- **不重复造 JevClient**：scoer 模块只暴露 ABC + fake；真客户端要么走 ACH subprocess/HTTP gateway，要么直接对接 `https://api.typesafe.ai/v1/systemone`（协议见 `JEV_OFFICIAL_SKILL_ABSORPTION.md`）。

### 8.3 测试覆盖

| 测试类 | 用例数 | 状态 |
|---|---|---|
| `threshold` 默认 / 边界 / 校验 / uncertain 短路 | 7 | PASS |
| `action` dataclass + to_dict | 1 | PASS |
| `scorer` FakeJevScorer + JevScore 校验 | 7 | PASS |
| `router` decide() 7 种路径（disabled / empty / ACT / FLAG_FOR_REVIEW / FALLBACK / UNCERTAIN / picked_none / jev_ask_error） | 9 | PASS |
| 全仓库现有 93 个测试 | 93 | PASS（无回归） |

### 8.4 用法示例

```python
from openeyes.decision import decide, DecisionContext, FakeJevScorer
from openeyes.core.selector import detect_elements

elements = detect_elements(hwnd)
ctx = DecisionContext(enabled=True, scorer=FakeJevScorer(), app_name="MyApp")
action = decide(elements=elements, goal="submit", ctx=ctx)
if action is None:
    # decision disabled — call existing click_by_selector
elif action.kind.name == "BLOCKED":
    # stop or fallback
else:
    target = elements[action.target_index]
    click_xy(target.center.x, target.center.y)
```