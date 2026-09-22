# Agent-Desktop x OpenEyes 学习笔记

> 对照 `lahfir/agent-desktop` (Apache-2.0) 与 ACH OpenEyes worker tool，沉淀可供 FEAT-04 / FEAT-05 复用的设计模式。

## 0. 摘要

- `lahfir/agent-desktop` 已被抓取到 `D:\gitAll\agent-desktop`（GitHub TCP 不通，改走 `codeload`）。
- 核心吸收：skeleton + drill 观察、headless 默认 policy、风险闸门 (act/confirm/abstain)、ref stable 标识、bounded loop。
- 现状差异：agent-desktop 的 Windows 适配器仍 `fail-closed`，ACH OpenEyes 是其在 Windows 上的等价实现（UIA + CDP + Vision 三后端），需补足 OpenEyes 自身在 FEAT-04 path B 缺失的部分。

## 1. 借鉴要点

1. **Skeleton/Drill 观察**：当前 `eyes detect` 仍一次性吐整树；后续可在 ACH 之上提供 `depth/skeleton` 包装，让 worker 第一次只看 head，再次按需 drill。
2. **风险闸门**：agent-desktop 用 `BARS = { floor:0.55, act:0.7, risky:0.9 }`；ACH 当前 `ToolAwareWorkerExecutor` 走 `needs_tool=true/false` 二元判定，没有 confidence/destructive 维度。可在 FEAT-05 structured UI assertion 之前加一档 `route({targetConfidence,destructive})`。
3. **Actionability preflight**：在执行 click/type/capture 前先跑 visibility / focus / lease 校验；ACH 现 OpenEyesTool 直接转发 CLI，未来可加 wrapper。
4. **Bounded loop**：agent-desktop 用 `MAX_STEPS / MAX_CALLS / STALLED_TURNS`；ACH `DEFAULT_MAX_TOOL_ROUNDS=4`，可观察吞吐。
5. **Idempotent 操作**：agent-desktop 用 `CHECK / UNCHECK` 取代 toggle，ACH `eyes type` 默认非幂等，依赖 planner 判定。
6. **Ref stable 标识**：agent-desktop 用 `@s8f3k2p9:e1` 而非坐标；ACH 目前 planner 直接吃坐标，可逐步迁移。

## 2. 待落地项

- FEAT-04 path B: `windows -> click -> type -> capture` 4 步 UI e2e 测试已落地（`OpenEyesToolChainE2ETest`，`-Dopeneyes.e2e.go=true` 才执行，隔离 PowerShell WinForms 窗口避免污染）。
- FEAT-05: 借鉴 `agent-desktop` 的 actionability preflight / structured assertion 思路，让 OpenEyes 在 baseline matrix 中产出结构化 UI 断言代替脆弱 screenshot diff。
- 长期: 若 `agent-desktop` 后续补齐 Windows adapter，可双轨兼容；现阶段 ACH 继续走 OpenEyes。

## 3. 验证记录

- `cargo check -p agent-desktop-windows` 失败：本机 `link.exe` 未安装（缺 Visual Studio C++），仅完成 `Cargo.lock` 拉取与 core 编译准备；不影响 OpenEyes 复用。
- `eyes type` 当前只上报派发成功，不保证目标窗口已获焦；本机探针中焦点守卫偶发 `focus guard: target window is not foreground; click aborted` 且退出码仍可为 0。FEAT-05 必须补 actionability / delivery verification。

## 4. 引用

- repo: https://github.com/lahfir/agent-desktop
- 落地版本：`lahfir/agent-desktop@main` (`agent-desktop = 0.9.2`)
- ACH 已落地：`src/test/java/com/agentcloud/worker/OpenEyesToolChainE2ETest.java`、`src/main/java/com/agentcloud/tool/OpenEyesTool.java`、`scripts/Run-NativeAppAcceptanceProbe.ps1`（HW-12）
- OpenEyes 仓库：`E:\gitAll\openeyes`（已装 `openeyes==0.1.0`）