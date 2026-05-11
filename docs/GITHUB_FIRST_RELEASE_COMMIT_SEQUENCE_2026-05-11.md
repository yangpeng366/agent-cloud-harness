# GitHub First Release Commit Sequence

> 本文档把当前首发三段提交从“文件分层”推进到“真正开始提交时怎么做”：包括建议提交顺序、建议 commit subject/body、每一步提交前后最小验证点，以及仍未完成的最终发布 gate。

## 1. 建议执行顺序

当前建议顺序不要改：

1. `Commit 1: Repository Baseline`
2. `Commit 2: chat-first / facade product line`
3. `Commit 3: acceptance harness and operator docs`

原因：

- 先把仓库公开基线独立出来，review 成本最低
- 再把真正的产品主线单独落成一批，功能边界最清晰
- 最后再补 acceptance harness / operator docs，避免和产品逻辑混成一个大提交

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

### 2.3 提交前最小验证

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

当前可再交叉核对：

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

### 3.3 提交前最小验证

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

当前可再交叉核对：

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

### 4.3 提交前最小验证

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

当前可再交叉核对：

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

如果你现在真的要开始收首发提交，建议按这个顺序打开文档：

1. `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
2. `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
3. `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
4. `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`

这样先看真实文件清单，再看执行步骤，再看命令块，最后看提交文案和验证点。
