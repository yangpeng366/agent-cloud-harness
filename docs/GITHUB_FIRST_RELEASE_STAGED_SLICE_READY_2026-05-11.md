# GitHub First Release Staged Slice Ready

> 本文档把 **当前 worktree** 直接收成一份可执行的 staged slice 清单。它不要求立刻 `git add`，但目标是让下一步真正开始收首发提交时，不再需要先把 dry-run 输出手工翻译一遍。

## 1. 适用前提

当前结论基于以下真实状态：

- `git status --short`
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`

本文件只描述 **当前 2026-05-11 这一轮 worktree** 的 staged slice，后续如果 worktree 再变，应重新跑 dry-run 再更新。

## 2. Commit 1: Repository Baseline

### 2.0 当前状态

截至当前 worktree，`Repository Baseline` 已经不再只是建议 slice，而是已真实进入暂存区。

真实核对命令：

```powershell
git diff --cached --stat
git diff --cached --name-only
git status --short
```

当前 staged 结果与本节建议命令一致，且未混入：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_*_2026-05-11.md` 这类 working snapshots

### 2.1 当前命中项

- ` M .gitignore`
- ` M README.md`
- `?? .github/`
- `?? CODE_OF_CONDUCT.md`
- `?? CONTRIBUTING.md`
- `?? SECURITY.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `?? docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `?? docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `?? docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `?? docs/GITHUB_RELEASE_CHECKLIST.md`
- `?? docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`

### 2.2 建议 stage 命令

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

### 3.1 当前命中项

- ` M src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- ` M src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- ` M src/main/resources/web/console/app.js`
- ` M src/main/resources/web/dialogue/app.css`
- ` M src/main/resources/web/dialogue/app.js`
- ` M src/main/resources/web/dialogue/composer-plan.js`
- ` M src/main/resources/web/dialogue/composer-request-plan.js`
- ` M src/main/resources/web/dialogue/index.html`
- ` M src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- ` M src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- ` M src/test/js/dialogue-composer-inline-render-plan.test.mjs`
- ` M src/test/js/dialogue-composer-plan.test.mjs`
- ` M src/test/js/dialogue-composer-request-plan.test.mjs`
- ` M src/test/js/dialogue-facade-reply-plan.test.mjs`
- `?? src/main/resources/web/dialogue/execution-boundary-plan.js`
- `?? src/main/resources/web/dialogue/facade-pending-plan.js`
- `?? src/main/resources/web/dialogue/mounted-object-plan.js`
- `?? src/main/resources/web/dialogue/pending-auto-task-plan.js`
- `?? src/main/resources/web/dialogue/task-selection-plan.js`
- `?? src/test/js/dialogue-execution-boundary-plan.test.mjs`
- `?? src/test/js/dialogue-facade-pending-plan.test.mjs`
- `?? src/test/js/dialogue-mounted-object-plan.test.mjs`
- `?? src/test/js/dialogue-pending-auto-task-plan.test.mjs`
- `?? src/test/js/dialogue-task-selection-plan.test.mjs`

### 3.2 建议 stage 命令

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

### 4.1 当前命中项

- ` M docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- ` M docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- ` M docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- ` M scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
- ` M scripts/Run-ChatFacadePathMatrixProbe.ps1`
- ` M scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `?? scripts/Render-DialogueAcceptanceRecordSeed.ps1`
- `?? scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `?? scripts/Run-DialogueRecordSeedProbe.ps1`
- `?? scripts/Run-GitHubFirstReleaseCommitDryRun.ps1`
- `?? scripts/Run-GitHubFirstReleaseDryRun.ps1`
- `?? scripts/Run-GitHubFirstReleaseStagePreview.ps1`
- `?? scripts/Run-GitHubFirstReleasePrecheck.ps1`
- `?? scripts/dialogue-browser-acceptance-probe-runner.cjs`

### 4.2 建议 stage 命令

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

## 5. Keep, but do not use as release completion proof

这些文件当前仍建议单独保留，不要混进前三个主提交里充当“首发已经验收完成”的背书：

- ` M docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `?? docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`

## 6. Defer or exclude

- `?? docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

## 7. 当前结论

按最新一轮真实 dry-run：

- `baseline` 已与当前 worktree 对齐
- `product` 已与当前 worktree 对齐
- `harness` 已与当前 worktree 对齐
- `baseline` stage preview 已与当前 worktree 对齐
- `product` stage preview 已与当前 worktree 对齐
- `harness` stage preview 已与当前 worktree 对齐
- `all` 结果中：
  - 当前新增的首发工具链文件也应保持 `review / unmatched = none`
  - `include / evidence_only / defer` 已完整分层

而按当前真实 index 状态：

- `Repository Baseline` 已真实 staged
- `chat-first / facade product line` 已真实 staged
- `acceptance harness and operator docs` 已真实 staged
- `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md` 已能直接作为当前 staged file list 证据

所以如果下一步要真正开始收首发 commit，当前最直接的入口已经不是再看原始 `git status`，而是：

- 先看本文件决定当前文件该进哪一批
- 再按 `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md` 的命令顺序实际 stage

## 8. 仍未完成的 gate

即使按本文件把 staged slice 收完，也仍然 **不能** 宣称以下事项已经完成：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条真实人工验收已完成
- GitHub Actions 已在真实远端仓库跑绿
- 项目已达到 production-ready distributed platform 水平
