# GitHub First Release Fileset

> 本文档把当前 worktree 中的变更分成三类：
>
> 1. 建议直接纳入首发
> 2. 可以保留在仓库中，但不建议当首发核心背书
> 3. 当前建议暂缓或后续再整理

## A. 建议直接纳入首发

### 1. 仓库基线

- `.gitignore`
- `README.md`
- `LICENSE`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `.github/workflows/ci.yml`
- `.github/ISSUE_TEMPLATE/bug_report.yml`
- `.github/ISSUE_TEMPLATE/feature_request.yml`
- `.github/ISSUE_TEMPLATE/config.yml`
- `.github/PULL_REQUEST_TEMPLATE.md`

### 2. chat-first / façade 主线

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/index.html`
- `src/main/resources/web/dialogue/app.css`
- `src/main/resources/web/dialogue/app.js`
- `src/main/resources/web/dialogue/composer-plan.js`
- `src/main/resources/web/dialogue/composer-request-plan.js`
- `src/main/resources/web/dialogue/execution-boundary-plan.js`
- `src/main/resources/web/dialogue/facade-pending-plan.js`
- `src/main/resources/web/dialogue/mounted-object-plan.js`
- `src/main/resources/web/dialogue/pending-auto-task-plan.js`
- `src/main/resources/web/dialogue/task-selection-plan.js`

### 3. 自动化与测试

- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

### 4. 运行与验收工具链

- `scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
- `scripts/Run-ChatFacadePathMatrixProbe.ps1`
- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`
- `scripts/Render-DialogueAcceptanceRecordSeed.ps1`
- `scripts/Run-DialogueRecordSeedProbe.ps1`
- `scripts/Run-GitHubFirstReleaseDryRun.ps1`
- `scripts/Run-GitHubFirstReleaseCommitDryRun.ps1`
- `scripts/Run-GitHubFirstReleaseStagePreview.ps1`
- `scripts/Run-GitHubFirstReleaseIndexAudit.ps1`
- `scripts/Run-GitHubFirstReleasePrecheck.ps1`

### 5. 对外需要的说明文档

- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`

## B. 可以保留，但不建议当首发完成度背书

这些文件可以继续留在公开仓库里，但不要在首发说明中把它们当成“产品已完成”的核心证据：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

原因：

- 带有本机路径
- 带有当天端口和截图目录
- 本质是 working logs，不是稳定规格文档

## C. 当前建议暂缓或后续整理

以下内容不应进入首发提交，或者至少不应作为首发范围的一部分：

- `.tmp/`
- `test-results/`
- `hs_err_pid*.log`
- `replay_pid*.log`
- `*.db`
- `*.db-journal`
- 任何本机临时截图、临时 JSON、临时 markdown 草稿

## D. 当前 worktree 对照结论

按 `git status --short` 与 `git diff --stat` 的当前状态来看：

- 当前已修改 / 新增的大部分文件都属于 A 或 B
- `test-results/` 已被 `.gitignore` 排除
- 本机 crash logs 与 `.tmp/` 已被忽略，不应纳入公开提交
- 最新一轮 `Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown` 结果里，当前 `unmatched = none`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md` 现已纳入 A 组首发文档链，而不是单独悬挂
- stage preview 四份 working logs 现已生成，并在最新 dry-run 中稳定归类到 B 组 `evidence_only`
- 如果要直接开始真实 `git add`，现在除了 staged slice 之外，还已有一份按三段提交拆开的 `stage file list`：
  - `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`

## E. 首发前还缺的动作

- 把 `README.md` 里的 GitHub 仓库 URL 替换成真实地址
- 至少做一轮真实 `/dialogue/` A-H 手工验收
- 用本文件 A/B/C 分类把首发 commit 范围真正收干净
- 真正开始收首发 commit 时，按 `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md` 的顺序执行
