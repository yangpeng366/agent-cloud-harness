# GitHub First Release Commit Commands

> 本文档只回答一个问题：如果现在开始真正收首发提交，三批建议提交各自对应什么 `git add` 命令块，以及当前 worktree 下这些命令块已经被哪一轮真实 `git add -n` / simulated staged diff 证明可用。

## 1. Repository Baseline

### 建议命令

```powershell
git add -n .gitignore README.md LICENSE CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md .github docs/GITHUB_RELEASE_CHECKLIST.md docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md docs/GITHUB_FIRST_RELEASE_FILESET.md docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md
```

### 当前已验证命中

- `.gitignore`
- `README.md`
- `.github/ISSUE_TEMPLATE/*`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `.github/workflows/ci.yml`
- `CODE_OF_CONDUCT.md`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `docs/GITHUB_FIRST_RELEASE_FILESET.md`
- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`

## 2. chat-first / facade product line

### 建议命令

```powershell
git add -n src/main/java/com/agentcloud/engine/ChatFacadeService.java src/main/java/com/agentcloud/server/WebConsoleHandler.java src/main/resources/web/console/app.js src/main/resources/web/dialogue src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java src/test/js
```

### 当前已验证命中

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/*`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

## 3. acceptance harness and operator docs

### 建议命令

```powershell
git add -n scripts/Start-DialogueChatFacadeManualAcceptance.ps1 scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1 scripts/Run-ChatFacadePathMatrixProbe.ps1 scripts/Run-DialogueBrowserAcceptanceProbe.ps1 scripts/dialogue-browser-acceptance-probe-runner.cjs scripts/Render-DialogueAcceptanceRecordSeed.ps1 scripts/Run-DialogueRecordSeedProbe.ps1 scripts/Run-GitHubFirstReleaseDryRun.ps1 scripts/Run-GitHubFirstReleaseCommitDryRun.ps1 scripts/Run-GitHubFirstReleaseStagePreview.ps1 scripts/Run-GitHubFirstReleasePrecheck.ps1 docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
```

### 当前已验证命中

- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1`
- `scripts/Run-ChatFacadePathMatrixProbe.ps1`
- `scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `scripts/Render-DialogueAcceptanceRecordSeed.ps1`
- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/Run-DialogueRecordSeedProbe.ps1`
- `scripts/Run-GitHubFirstReleaseCommitDryRun.ps1`
- `scripts/Run-GitHubFirstReleaseDryRun.ps1`
- `scripts/Run-GitHubFirstReleaseStagePreview.ps1`
- `scripts/Run-GitHubFirstReleasePrecheck.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`

## 4. 当前结论

这三组命令块都已经在当前 worktree 上做过真实 `git add -n` 预演，不再只是纸面 staging 提案。

另外，现在还有一层更强的证据：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit baseline -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit product -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit harness -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown`

对应产物：

- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`

这层 stage preview 使用临时 `GIT_INDEX_FILE` 做 simulated staged diff，并且不会污染真实 index。

而当前更强的真实状态已经是：

- `8350a8c chore: prepare repository baseline for public first release`
- `d7fefea feat: ship chat-first dialogue facade and related UI flows`
- `4f559c2 chore: add acceptance harness and operator release tooling`

也就是说，这三组命令块不只是可预演，它们对应的三段主 slice 也已经真实进入本地 Git 历史。

但这仍然不代表：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条人工验收已完成
- GitHub Actions 已在真实远端仓库跑绿

## 5. 相关当前态参考

如果你想 replay / 复核这三段主 slice，而不是假设它们当前仍在 index 里，先看这两份：

- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
