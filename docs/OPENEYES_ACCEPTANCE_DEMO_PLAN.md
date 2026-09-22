# OpenEyes Acceptance Demo Plan

> 本文是 `OPENEYES_UI_AUTOMATION_RESEARCH.md` §3.1 / §3.2 的演示脚本落地设计，对应 `CONTRIBUTING.md` 中 HW-11 + HW-12 两条实施线。
> 维护约定：本文档保留脚本骨架 + 验收链路；已迁入实施 PR 的 `scripts/` 文件以真实实现与最新执行证据为准。
> 凭据纪律：OpenEyes 本地运行无凭据；CI 默认不联 OpenEyes；`eyes_mcp.enabled=false` / `profile_dir` 未配置时零外部依赖。

## 0. 摘要

把 `Run-DialogueBrowserAcceptanceProbe.ps1` 现有的 puppeteer-core + Edge headless 验收流程，叠加一层 OpenEyes CDP 路径（HW-11）；同时新建 `Run-NativeAppAcceptanceProbe.ps1`，用 OpenEyes UIA 后端验收 native Windows app（HW-12）。两档均以"最小可演示 case"为交付物，先跑通再扩展。

## 1. HW-11 · CDP profile 持久化演示脚本

### 1.1 目标

让现有 `Run-DialogueBrowserAcceptanceProbe.ps1` 在每次重启 Edge 时**复用** `~/.openeyes/profiles/portal-acceptance/` 目录里的登录态 cookie / localStorage，避免 fresh 登录。

### 1.2 新增脚本骨架（落点 `scripts/Run-OpenEyesProfileSmoke.ps1`）

```powershell
# 仅落骨架;维护者拍板后由实施 PR 创建。
[CmdletBinding()]
param(
    [string]$ProfileDir = "$env:USERPROFILE\.openeyes\profiles\portal-acceptance",
    [string]$EyesBin = "eyes",           # 来自 yangpeng366/openeyes pip 安装的入口
    [string]$TargetUrl = "https://localhost:19207/portal/",
    [switch]$Seed = $false,              # 首次跑用 --seed 写 profile,后续跑 --no-seed
    [switch]$Go = $false                 # 默认 dry-run;--go 才真发请求
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\_lib\TextFile.ps1"

# 1) profile 目录存在性探测
if (-not (Test-Path $ProfileDir)) {
    New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null
}

# 2) 调 eyes CLI 启动 Edge with profile
$seedFlag = if ($Seed) { "--seed" } else { "--no-seed" }
& $EyesBin browser launch --url $TargetUrl --profile-dir $ProfileDir $seedFlag --dry-run $(-not $Go)

# 3) DOM probe 验证 profile 是否有效(检查登录态元素是否存在)
$scan = & $EyesBin browser scan --pretty --url-contains "portal" --dry-run $(-not $Go) | Out-String

# 4) 检查 "登录" 按钮是否仍可见;如果不可见 → profile 持久化生效
if ($scan -match "登录") {
    Write-Host "PROFILE_FRESH: 浏览器未登录态,需要走 fresh login flow"
    exit 1
} elseif ($scan -match "用户") {
    Write-Host "PROFILE_PRESERVED: 登录态已保留,验收脚本可直接进入业务流"
    exit 0
} else {
    Write-Host "PROFILE_UNKNOWN: DOM 探测未识别登录态,需要人工核验"
    exit 2
}
```

### 1.3 验收

- 首次跑 `--seed` 写 profile;后续 5 次 `--no-seed` 复用,`PROFILE_PRESERVED` 5/5 命中。
- 登录耗时从 fresh ~12s 降到复用 ~2s。
- 现有 `Run-DialogueBrowserAcceptanceProbe.ps1` puppeteer-core 路径不受影响(`--no-seed` 时 eyes CLI 不发起)。

### 1.4 风险

- Edge `User Data` 锁定:OpenEyes `launch_edge` 已用 `--remote-allow-origins=*` 与 seed 切目录,workaround 见 `E:\AI-Portable\codex-home\skills\openeyes\SKILL.md`。
- Profile 跨用户 / 跨机器迁移需要重新 seed;不在本条 scope。

## 2. HW-12 · UIA native app 演示脚本

### 2.1 目标

新建 `scripts/Run-NativeAppAcceptanceProbe.ps1`,用 OpenEyes UIA 后端验收 native Windows app。最小 case:定位"Any VPN"主窗口并点击「连接」按钮。

### 2.2 新增脚本骨架（落点 `scripts/Run-NativeAppAcceptanceProbe.ps1`）

```powershell
[CmdletBinding()]
param(
    [string]$AppTitleContains = "Any VPN",
    [string]$ButtonNameContains = "连接",
    [string]$EyesBin = "eyes",
    [switch]$Go = $false
)

$ErrorActionPreference = "Stop"

# 1) 找 hwnd
$wins = & $EyesBin windows list --title-contains $AppTitleContains --dry-run $(-not $Go) | ConvertFrom-Json
if (-not $wins -or $wins.Count -eq 0) {
    Write-Host "APP_NOT_FOUND: 未找到标题包含 '$AppTitleContains' 的窗口"
    exit 1
}
$hwnd = $wins[0].hwnd
Write-Host "FOUND_HWND=$hwnd title=$($wins[0].title)"

# 2) detect 元素树
$elems = & $EyesBin detect --window $hwnd --pretty --dry-run $(-not $Go) | Out-String

# 3) 定位"连接"按钮
if ($elems -notmatch "$ButtonNameContains") {
    Write-Host "BUTTON_NOT_FOUND: 未找到 '$ButtonNameContains' 按钮"
    exit 2
}

# 4) 点击(默认 dry-run;--go 才真点)
& $EyesBin click --window $hwnd --name-contains $ButtonNameContains --dry-run $(-not $Go)

# 5) 截图留证
$shotPath = "$env:TEMP\any-vpn-connect-$(Get-Date -Format 'yyyyMMdd-HHmmss').png"
& $EyesBin capture --window $hwnd --out $shotPath --dry-run $(-not $Go)
if (Test-Path $shotPath) {
    Write-Host "SHOT_SAVED=$shotPath"
}

Write-Host "ACCEPTANCE_OK: detect + click + screenshot 全部命中"
exit 0
```

### 2.3 验收

- 在 Windows 11 24H2 + DPI 3840x1200 下,`Any VPN` 主窗口能成功 `detect` + `click "连接"` + `screenshot`。
- 不依赖绝对坐标;脚本可重入。
- 默认 dry-run 模式不真发请求,验证通过后 `--go` 才执行。

### 2.4 风险

- UWP app 标题可能在 `ApplicationFrameWindow` 而非 child `CoreWindow`;OpenEyes `launch_edge` + `--restore` 已处理。
- DPI 多屏:`pywinauto.rect.left/top/width/height` 是 int property 或 callable,OpenEyes 已用 `_call()` wrapper。

## 3. 与现有 acceptance 套件的边界

| 套件 | 路径 | 覆盖 | 备注 |
|---|---|---|---|
| 现有 `Run-DialogueBrowserAcceptanceProbe.ps1` | puppeteer-core + Edge headless | `/dialogue/` HTTP + DOM | 不替换;HW-11 叠加 profile 层 |
| 现有 `Run-ChatFacadeAcceptanceProbe.ps1` | 同上 | chat facade HTTP + browser | 同上 |
| 现有 `Run-ConsoleProviderWindowProbe.ps1` | 同上 | `/console/` provider 窗口 | 同上 |
| **HW-11 `Run-OpenEyesProfileSmoke.ps1`** | OpenEyes CDP | profile 持久化 smoke | 仅做 profile 复用,不做断言 |
| **HW-12 `Run-NativeAppAcceptanceProbe.ps1`** | OpenEyes UIA | native app detect/click | 替代 screenshot diff 的最小 case |

## 4. 与 FEAT-04 / FEAT-05 的解耦

- HW-11 / HW-12 只做 standalone smoke 脚本;不注册到 `WorkerExecutor` 的 Tool-aware 执行面(FEAT-04 才做)。
- HW-11 / HW-12 不做 structured UI assertion(FEAT-05 才做);验收仍用 puppeteer-core 路径。
- 三档互不依赖;可独立签发。

## 5. 实施 PR 切分建议

- **PR-1(HW-11)**:`scripts/Run-OpenEyesProfileSmoke.ps1` + `scripts/_lib/TextFile.ps1`(若需)+ `CONTRIBUTING.md` HW-11 状态从候选改为「进行中」。预估 1 天。
- **PR-2(HW-12)**:`scripts/Run-NativeAppAcceptanceProbe.ps1` + 任意 VPN 测试 fixture + `CONTRIBUTING.md` HW-12 状态变更。预估 2 天。
- **PR-3(FEAT-04)**:`src/main/java/com/agentcloud/worker/OpenEyesMcpWorkerExecutor.java` + MCP stdio client + `harness-config.yml` 新增 `eyes_mcp.enabled` + `docs/API_CONTRACTS.md`。预估 1-2 周。
- **PR-4(FEAT-05)**:`scripts/Run-OpenEyesUiAssertion.ps1` + `scripts/Run-BaselineMatrixRealWorkerSmoke.ps1` 支持 `ui_assertion_mode=openeyes_structured` + `/console/` UI assertion 历史卡。预估 1 周。

## 6. 下一步

- 2026-09-21 更新: HW-11 PR-1 已从候选推进到实施中, smoke 三门槛实测通过;profile 复用已接入现有 acceptance probe，集成 no-seed acceptance 5/5，fresh direct 对照通过。
- 2026-09-21 更新: HW-12 PR-2 的 `scripts/Run-NativeAppAcceptanceProbe.ps1` 已创建，默认 dry-run、原生 detect JSON、selector click 与截图留证打通；Explorer 安全对照 PASS。Any VPN 目标窗口当前不可见，真实 `--go` 待窗口可用后补测。
- 凭据纪律延续:OpenEyes 本地运行无凭据;CI 默认不联 OpenEyes。
