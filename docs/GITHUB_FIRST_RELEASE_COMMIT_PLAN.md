# GitHub First Release Commit Plan

> 基于 `docs/GITHUB_FIRST_RELEASE_FILESET.md`、`docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md` 和当前 dry-run 快照，建议把首发范围拆成更清晰的提交批次。

## Commit 1: Repository Baseline

建议内容：

- `.gitignore`
- `README.md`
- `LICENSE`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `.github/workflows/ci.yml`
- `.github/ISSUE_TEMPLATE/*`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`

目的：

- 先把公开仓库的最小基线独立出来
- 这类提交更容易单独 review

当前已有一轮真实 baseline dry-run：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit baseline -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`

当前也已有一轮真实 baseline stage preview：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit baseline -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`

当前命中项主要包括：

- `.gitignore`
- `README.md`
- `.github/`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`

当前还有一轮 index audit 证据：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- baseline 当前为 `staged_only`

## Commit 2: chat-first / facade product line

建议内容：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/*`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

目的：

- 把真正的产品主线与验证放到一起
- 对外能一眼看出首发功能焦点

当前已有一轮真实 product dry-run：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit product -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`

当前也已有一轮真实 product stage preview：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit product -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`

当前还有一轮 index audit 证据：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- product 当前为 `staged_only`

## Commit 3: acceptance harness and operator docs

建议内容：

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
- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`

目的：

- 把 acceptance harness 与操作文档独立出来
- 避免它们和产品逻辑完全混在一个大提交里

当前已有一轮真实 harness dry-run：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit harness -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`

当前也已有一轮真实 harness stage preview：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit harness -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`

当前还有一轮 index audit 证据：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- harness 当前为 `staged_only`

## Working logs

这些文件可以后续单独决定是否跟随首发进入公开历史：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

建议：

- 如果首发追求更干净，可以暂时不放进首批提交
- 如果希望保留开发证据，可以作为单独附加提交

## Still not done

即使按以上批次提交完成，也仍然不代表以下事项已经完成：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条人工验收已完成
- 可以直接公网部署

## Dry-run

建议在真正开始收 commit 前，先跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown
```

它会基于当前 `git status --short` 生成：

- baseline 命中项
- product 命中项
- harness 命中项
- unmatched 项

当前已有一轮真实 `all` dry-run 证据：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`

当前也已有一轮真实 `all` stage preview 证据：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`

现在三个建议提交批次都已经有各自的真实 dry-run：

- `baseline`
- `product`
- `harness`

现在三个建议提交批次也都已经有各自的真实 stage preview：

- `baseline`
- `product`
- `harness`

并且按最新一轮 `all` 结果：

- `GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md` 已进入 `evidence_only`
- `GITHUB_FIRST_RELEASE_STAGE_PREVIEW_*_2026-05-11.md` 已进入 `evidence_only`
- 当前 `unmatched = none`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md` 已纳入 baseline 文档组，不再悬挂在未分类区

## Execution Guide

如果从“commit 方案”切到“实际开始收首发提交”，建议直接按：

- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`

执行。它把三批提交都展开成了：

- 建议 `git add` 命令块
- `git diff --cached` 核对方式
- 当前应仍留在未暂存区的文件类型
