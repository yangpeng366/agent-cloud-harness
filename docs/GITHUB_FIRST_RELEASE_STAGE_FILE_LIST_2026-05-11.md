# GitHub First Release Stage File List

> 本文档把当前 worktree 下 **三段首发提交** 的真实命中项收成一份可直接执行的 staged file list。它不是新的分层规则，而是把现有 `commit dry-run` / `stage preview` 结果压缩成更接近实际 `git add` 的清单。

## 1. 证据来源

本文件基于以下真实证据整理：

- `git status --short`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`

## 2. Commit 1: Repository Baseline

### 2.1 当前真实 staged file list

- `.github/ISSUE_TEMPLATE/bug_report.yml`
- `.github/ISSUE_TEMPLATE/config.yml`
- `.github/ISSUE_TEMPLATE/feature_request.yml`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `.github/workflows/ci.yml`
- `.gitignore`
- `CODE_OF_CONDUCT.md`
- `CONTRIBUTING.md`
- `README.md`
- `SECURITY.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`

### 2.2 对应命令块

```powershell
git add .gitignore README.md LICENSE CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md
git add .github
git add docs/GITHUB_RELEASE_CHECKLIST.md
git add docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md
git add docs/GITHUB_FIRST_RELEASE_FILESET.md
git add docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md
git add docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md
git add docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md
git add docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md
git add docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md
git add docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md
```

## 3. Commit 2: chat-first / facade product line

### 3.1 当前真实 staged file list

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/app.css`
- `src/main/resources/web/dialogue/app.js`
- `src/main/resources/web/dialogue/composer-plan.js`
- `src/main/resources/web/dialogue/composer-request-plan.js`
- `src/main/resources/web/dialogue/execution-boundary-plan.js`
- `src/main/resources/web/dialogue/facade-pending-plan.js`
- `src/main/resources/web/dialogue/index.html`
- `src/main/resources/web/dialogue/mounted-object-plan.js`
- `src/main/resources/web/dialogue/pending-auto-task-plan.js`
- `src/main/resources/web/dialogue/task-selection-plan.js`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-composer-inline-render-plan.test.mjs`
- `src/test/js/dialogue-composer-plan.test.mjs`
- `src/test/js/dialogue-composer-request-plan.test.mjs`
- `src/test/js/dialogue-execution-boundary-plan.test.mjs`
- `src/test/js/dialogue-facade-pending-plan.test.mjs`
- `src/test/js/dialogue-facade-reply-plan.test.mjs`
- `src/test/js/dialogue-mounted-object-plan.test.mjs`
- `src/test/js/dialogue-pending-auto-task-plan.test.mjs`
- `src/test/js/dialogue-task-selection-plan.test.mjs`

### 3.2 对应命令块

```powershell
git add src/main/java/com/agentcloud/engine/ChatFacadeService.java
git add src/main/java/com/agentcloud/server/WebConsoleHandler.java
git add src/main/resources/web/console/app.js
git add src/main/resources/web/dialogue
git add src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java
git add src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java
git add src/test/js
```

## 4. Commit 3: acceptance harness and operator docs

### 4.1 当前真实 staged file list

- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `scripts/Render-DialogueAcceptanceRecordSeed.ps1`
- `scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
- `scripts/Run-ChatFacadePathMatrixProbe.ps1`
- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/Run-DialogueRecordSeedProbe.ps1`
- `scripts/Run-GitHubFirstReleaseCommitDryRun.ps1`
- `scripts/Run-GitHubFirstReleaseDryRun.ps1`
- `scripts/Run-GitHubFirstReleasePrecheck.ps1`
- `scripts/Run-GitHubFirstReleaseStagePreview.ps1`
- `scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`

### 4.2 对应命令块

```powershell
git add scripts/Start-DialogueChatFacadeManualAcceptance.ps1
git add scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1
git add scripts/Run-ChatFacadePathMatrixProbe.ps1
git add scripts/Run-DialogueBrowserAcceptanceProbe.ps1
git add scripts/dialogue-browser-acceptance-probe-runner.cjs
git add scripts/Render-DialogueAcceptanceRecordSeed.ps1
git add scripts/Run-DialogueRecordSeedProbe.ps1
git add scripts/Run-GitHubFirstReleaseDryRun.ps1
git add scripts/Run-GitHubFirstReleaseCommitDryRun.ps1
git add scripts/Run-GitHubFirstReleaseStagePreview.ps1
git add scripts/Run-GitHubFirstReleasePrecheck.ps1
git add docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
```

## 5. 仍不应混入三段主提交的文件

这些文件当前应继续留在 `evidence_only` / `defer`，不要混进上面三段主提交：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

## 6. 当前仍未完成的 gate

即使以上三段 staged file list 都已经明确，当前仍不能宣称：

- `README.md` 中的 `<your-published-repo-url>` 已替换成真实公开地址
- `/dialogue/` A-H 八条真实人工验收已完成并回填
- GitHub Actions 已在真实远端仓库跑绿
- 项目已达到 production-ready distributed platform 水平
