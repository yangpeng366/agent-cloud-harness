# GitHub First Release Execution Guide

> 本文档把 `docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md` 和 `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md` 收成一套可直接执行的首发收口步骤。当前它更准确的定位是：给出 replay / 复核三段主提交边界的执行顺序、`git add` 边界和核对方法，而不是假设三段主提交还未落地。
>
> 当前入口说明：本文是 release replay / stage 边界的执行入口。若只想看 gate 是否已满足，先看 `docs/GITHUB_RELEASE_CHECKLIST.md`；若只想看最新一轮本地预检证据，优先看 `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`。

## 0. 前提

开始前默认：

- 当前 worktree 与 `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_*_2026-05-11.md` 对齐
- `test-results/`、`.tmp/`、`hs_err_pid*`、`replay_pid*`、`*.db` 已由 `.gitignore` 或首发边界排除
- 不要使用 `git add .`
- 三段主提交已真实存在于本地 Git 历史：
  - `8350a8c`
  - `d7fefea`
  - `4f559c2`

先做最小核对：

```powershell
git status --short
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleasePrecheck.ps1
```

如需再次确认批次范围，先跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown
```

如需直接看三批提交各自的真实 `git add -n` 命令块，见：

- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`

如需直接看三批提交各自的目标 staged file list，见：

- `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`

如需直接看三批提交各自的建议 commit 文案、执行顺序和每步验证点，见：

- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`

如需在不污染真实 index 的前提下，看 simulated staged diff，额外执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown
```

对应产物：

- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`

如需确认当前三段主 slice 是否已经稳定留在 index、没有 `staged_and_unstaged` 漂移，额外执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseIndexAudit.ps1 -WriteMarkdown
```

对应产物：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`

## 1. Commit 1: Repository Baseline

### 1.1 建议 stage 命令

```powershell
git add .gitignore README.md LICENSE CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md
git add .github
git add docs/GITHUB_RELEASE_CHECKLIST.md
git add docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md
git add docs/GITHUB_FIRST_RELEASE_FILESET.md
git add docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md
git add docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md
git add docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md
git add docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md
git add docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md
git add docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md
git add docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md
git add docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md
```

### 1.2 预期命中范围

应该主要命中：

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
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`

### 1.3 核对命令

```powershell
git diff --cached --stat
git diff --cached
```

### 1.4 此时应仍留在未暂存区的内容

以下类型此时仍应看到：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/resources/web/dialogue/*`
- `src/test/js/dialogue-*.test.mjs`
- acceptance harness 脚本
- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_*`

### 1.5 对应 dry-run / replay 参考

当前参考：

- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`

## 2. Commit 2: chat-first / facade product line

### 2.1 建议 stage 命令

```powershell
git add src/main/java/com/agentcloud/engine/ChatFacadeService.java
git add src/main/java/com/agentcloud/server/WebConsoleHandler.java
git add src/main/resources/web/console/app.js
git add src/main/resources/web/dialogue
git add src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java
git add src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java
git add src/test/js
```

### 2.2 预期命中范围

应该主要命中：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/*`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

### 2.3 核对命令

```powershell
git diff --cached --stat
git diff --cached
```

必要时补跑：

```powershell
node --check src/main/resources/web/dialogue/app.js
node --test src/test/js/*.test.mjs
```

### 2.4 此时应仍留在未暂存区的内容

以下类型此时仍应看到：

- acceptance harness 脚本
- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- acceptance record working logs

### 2.5 对应 dry-run / replay 参考

当前参考：

- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`

## 3. Commit 3: acceptance harness and operator docs

### 3.1 建议 stage 命令

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
git add scripts/Run-GitHubFirstReleaseIndexAudit.ps1
git add scripts/Run-GitHubFirstReleasePrecheck.ps1
git add docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
```

### 3.2 预期命中范围

应该主要命中：

- acceptance harness 脚本链
- `scripts/Run-GitHubFirstReleaseStagePreview.ps1`
- `scripts/Run-GitHubFirstReleaseIndexAudit.ps1`
- `scripts/Run-GitHubFirstReleasePrecheck.ps1`
- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`

### 3.3 核对命令

```powershell
git diff --cached --stat
git diff --cached
```

如需核对 record-seed 链，可额外执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18246.json
```

### 3.4 此时应仍留在未暂存区的内容

以下类型可以继续留在未暂存区，后续单独决定：

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

### 3.5 对应 dry-run / replay 参考

当前参考：

- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`

## 4. Working Logs

这些文件可以公开保留，但不建议混进前三个主提交里当完成度背书：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

## 5. 仍未完成的 Gate

即使以上三批都收完，也不要越界宣称：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条真实人工验收已完成
- GitHub Actions 已在真实远端仓库跑绿
- 项目已可直接公网部署
- 项目已达到 production-ready distributed platform 水平

这四项在当前仓库里仍然没有完成。

## 6. Next Actions

如果此时不做 replay，而是先想清楚“当前最值钱的动作”，参考：

- `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
