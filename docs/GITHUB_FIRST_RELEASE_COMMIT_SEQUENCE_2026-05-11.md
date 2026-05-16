# GitHub First Release Commit Sequence

> 本文档当前更准确的定位是：保留 2026-05-11 这轮首发三段主提交的建议顺序、建议 commit 文案和 replay/复核命令。它不再假设这三段提交当前仍未落地。

## 1. 建议执行顺序

当前建议顺序不要改：

1. `Commit 1: Repository Baseline`
2. `Commit 2: chat-first / facade product line`
3. `Commit 3: acceptance harness and operator docs`

原因：

- 先把仓库公开基线独立出来，review 成本最低
- 再把真正的产品主线单独落成一批，功能边界最清晰
- 最后再补 acceptance harness / operator docs，避免和产品逻辑混成一个大提交

当前更强的真实状态是，这三段建议顺序已经各自对应到真实本地 commit：

1. `8350a8c` `chore: prepare repository baseline for public first release`
2. `d7fefea` `feat: ship chat-first dialogue facade and related UI flows`
3. `4f559c2` `chore: add acceptance harness and operator release tooling`

## 2. Commit 1: Repository Baseline

### 2.1 建议 subject

```text
chore: prepare repository baseline for public first release
```

### 2.2 建议 body

```text
- add public repo baseline files and GitHub templates
- add CI baseline for Maven tests and dialogue JS smoke
- document first-release scope, checklist, staging, and execution guide
- keep project positioning honest as a local/single-node harness
```

### 2.3 replay / 提交前最小验证

先按目标 slice 重新 stage，再看 staged 范围：

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

先看 staged 范围：

```powershell
git diff --cached --stat
git diff --cached --name-only
```

应主要命中：

- `.github/`
- `.gitignore`
- `README.md`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `docs/GITHUB_RELEASE_*`
- `docs/GITHUB_FIRST_RELEASE_*` 中的 baseline 文档

不应混入：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/resources/web/dialogue/*`
- acceptance harness 脚本
- acceptance records / dry-run / stage preview working logs

如果要 replay / 重新收 Commit 1，可再交叉核对：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- baseline 当前应显示为 `staged_only`

### 2.4 提交后最小验证

```powershell
git show --stat --oneline HEAD
```

检查点：

- commit 看起来像“公开仓库壳层和首发文档基线”
- 不带产品逻辑或 acceptance harness 脚本

## 3. Commit 2: chat-first / facade product line

### 3.1 建议 subject

```text
feat: ship chat-first dialogue facade and related UI flows
```

### 3.2 建议 body

```text
- add chat-first dialogue updates and facade-facing composer behavior
- tighten web console/dialogue static serving and task selection flows
- add JS smoke coverage and HTTP contract updates for facade paths
- keep product changes separate from release-only operator tooling
```

### 3.3 replay / 提交前最小验证

先按目标 slice 重新 stage，再看 staged 范围：

```powershell
git add src/main/java/com/agentcloud/engine/ChatFacadeService.java
git add src/main/java/com/agentcloud/server/WebConsoleHandler.java
git add src/main/resources/web/console/app.js
git add src/main/resources/web/dialogue
git add src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java
git add src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java
git add src/test/js
```

先看 staged 范围：

```powershell
git diff --cached --stat
git diff --cached --name-only
```

应主要命中：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/server/WebConsoleHandler.java`
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/dialogue/*`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`
- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
- `src/test/js/dialogue-*.test.mjs`

建议补跑：

```powershell
node --check src/main/resources/web/dialogue/app.js
node --test src/test/js/*.test.mjs
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

不应混入：

- acceptance harness 脚本
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_*`
- `docs/GITHUB_FIRST_RELEASE_*` working logs

如果要 replay / 重新收 Commit 2，可再交叉核对：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- product 当前应显示为 `staged_only`

### 3.4 提交后最小验证

```powershell
git show --stat --oneline HEAD
```

检查点：

- commit 看起来像“产品主线 + 对应测试”
- 不带发布辅助脚本和 operator-only 文档

## 4. Commit 3: acceptance harness and operator docs

### 4.1 建议 subject

```text
chore: add acceptance harness and operator release tooling
```

### 4.2 建议 body

```text
- add local acceptance harness helpers and scripted browser probes
- add record-seed rendering/probing utilities for manual dialogue acceptance
- add release dry-run, stage-preview, and precheck scripts
- document operator runbooks and acceptance preparation chain
```

### 4.3 replay / 提交前最小验证

先按目标 slice 重新 stage，再看 staged 范围：

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

先看 staged 范围：

```powershell
git diff --cached --stat
git diff --cached --name-only
```

应主要命中：

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
- `scripts/Run-GitHubFirstReleasePrecheck.ps1`
- `docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`

建议补跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseDryRun.ps1 -WriteMarkdown
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown
```

目标仍应保持：

- `Needs manual review = none`
- `Unmatched = none`

如果要 replay / 重新收 Commit 3，可再交叉核对：

- `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- harness 当前应显示为 `staged_only`

### 4.4 提交后最小验证

```powershell
git show --stat --oneline HEAD
```

检查点：

- commit 看起来像“operator tooling / acceptance harness”
- 不再混入产品主线文件

## 5. 不建议混入前三段主提交的文件

这些文件当前更适合留在 `working logs / evidence_only`：

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

## 6. 即使三段提交都完成，当前仍未完成的 gate

不要越界宣称以下事项已完成：

- `README.md` 已替换成真实公开 GitHub 仓库地址
- `/dialogue/` A-H 八条真实人工验收已完成并回填
- GitHub Actions 已在真实远端仓库跑绿
- 项目已达到 production-ready distributed platform 水平

## 7. 推荐入口

如果你现在要 replay 这三段主提交、或重新复核它们的边界，建议按这个顺序打开文档：

1. `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
2. `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
3. `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
4. `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`

这样先看真实文件清单，再看执行步骤，再看命令块，最后看提交文案和验证点。
