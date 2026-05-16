# GitHub Release Scope Proposal

> 本文档用于把当前 worktree 收成一个“首发可公开”的提交边界。

## 建议纳入首发

### 仓库基线

- `.github/workflows/ci.yml`
- `.github/ISSUE_TEMPLATE/*`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `README.md`
- `.gitignore`
- `LICENSE`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`

### 主功能主线与自动化验证

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/dialogue/*`
- `src/main/resources/web/console/app.js`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

说明：

- 首发 CI 现在不再只有 Maven Java 测试
- `/dialogue/` 的 `node --check` 与 `node --test src/test/js/*.test.mjs` 也已纳入 `.github/workflows/ci.yml`

### 运行与验收脚本

- `scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
- `scripts/Run-ChatFacadePathMatrixProbe.ps1`
- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`
- `scripts/Render-DialogueAcceptanceRecordSeed.ps1`
- `scripts/Run-DialogueRecordSeedProbe.ps1`

### 必要文档

- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- `docs/WEB_CONSOLE.md`
- `docs/API_CONTRACTS.md`
- `docs/TROUBLESHOOT.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`

## 建议暂不作为首发核心证据

这些文件不是不能公开，而是不建议把它们当成“首发完成度”的核心背书：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

原因：

- 带有强本机路径
- 带有当天端口与截图目录
- 本质是验收工作记录，不是稳定说明文档

更稳的做法是：

- 保留在仓库中，作为开发证据
- 但在首发说明里把它们明确描述为 `working acceptance logs`
- 真正的首发提交范围以 `docs/GITHUB_FIRST_RELEASE_FILESET.md` 的 A/B/C 分类为准
- stage preview 四份文档也应按同一原则处理：
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
  - 它们是 release slicing 的强证据，但仍属于 `working logs`，不应充当“首发已完成”的核心背书

## 当前不建议纳入首发完成宣称

以下事实在公开说明里应保持诚实：

- `/dialogue/` A-H 八条真实人工验收尚未全部完成
- `README.md` 中仍保留 `<your-published-repo-url>` 发布占位，尚未替换成真实公开地址
- 当前 harness 仍然定位于本地 / 单机
- 安全边界尚未收口，不应宣称可直接公网部署

## 推荐首发叙述

建议对外描述为：

> A local, continuity-first agent control plane prototype with a runnable web UI, task/session control APIs, OpenAI-compatible chat façade, packet/checkpoint continuity, and a growing acceptance/test harness.

避免使用会把当前状态抬高成“已可直接公网部署”或“已达到分布式生产级平台成熟度”的表述。
