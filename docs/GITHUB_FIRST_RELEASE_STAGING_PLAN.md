# GitHub First Release Staging Plan

> 本文档不是要求立刻执行 `git add`，而是给出一套可直接复用的首发收口顺序与 staging 边界。

## 1. 目标

把当前 worktree 收成一个“可公开首发”的提交范围，同时避免把本机临时证据、噪音文件和未完成 gate 误包装成已完成状态。

## 2. 建议收口顺序

### Step A. 先纳入仓库基线

建议先 stage：

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
git add docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md
git add docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md
git add docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md
```

目的：

- 先把公开仓库最基本的壳层固定住
- 让后续功能提交不再和仓库基线混在一起

### Step B. 再纳入 chat-first / façade 主线

```powershell
git add src/main/java/com/agentcloud/engine/ChatFacadeService.java
git add src/main/java/com/agentcloud/server/WebConsoleHandler.java
git add src/main/resources/web/console/app.js
git add src/main/resources/web/dialogue
git add src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java
git add src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java
git add src/test/js/dialogue-*.test.mjs
```

目的：

- 把真正公开要展示的功能主线和自动化验证放在一起

### Step C. 再纳入运行与验收工具链

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
git add scripts/Run-CodexPartialTimeoutSmoke.ps1
git add scripts/provider-discovery-smoke.js
```

目的：

- 明确这些脚本属于公开的 acceptance harness，而不是本机私货
- provider discovery smoke 已接入 precheck，用于验证 `providers.yaml/json` 动态 provider 注册与 readiness 投影
- Codex partial timeout smoke 用于验证长任务有输出但被通信/时间策略截断时，后端、控制图和 Dialogue 操作入口保持一致

### Step D. 最后纳入 acceptance / operator 主文档

```powershell
git add docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md
git add docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
```

目的：

- 最后再纳入文档，可以避免文档先行、代码边界后改

## 3. 不建议进入首发提交的内容

不要 stage：

```powershell
.tmp/
test-results/
hs_err_pid*.log
replay_pid*.log
*.db
*.db-journal
```

以及：

- 本机截图目录
- 本机生成的临时 JSON
- 临时 markdown 草稿

## 4. 可以公开保留，但不建议当首发完成背书

这两份更适合作为 working logs，而不是首发完成声明：

```powershell
docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md
docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md
```

建议：

- 可以留在仓库中
- 但不要把它们作为“产品已通过全部验收”的对外论据

## 5. Staging 前的最小检查

建议在首发前至少确认：

```powershell
node --check src/main/resources/web/dialogue/app.js
node --test src/test/js/*.test.mjs
```

以及：

```powershell
.\scripts\Test-WithJava21.ps1
```

## 6. 当前仍未完成的 gate

即使全部 stage 完成，也不要在首发说明里越界宣称以下事项已经完成：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条严格人工手点全部通过（当前已有 scripted current-reachable seam 证据，但不等价于人工 gate）
- GitHub Actions 已在真实远端仓库跑绿
- 可以直接公网部署
- 已达到 distributed production platform 水平

## 7. 实际执行时的建议

真正开始收首发 commit 时，建议边 stage 边看：

```powershell
git diff --cached --stat
git diff --cached
```

确保：

- 首发功能范围清楚
- 文档口径和代码一致
- 未完成 gate 仍然被诚实保留

> 补充说明：截至当前真实 Git 状态，三段主 slice 已经分别进入本地 Git 历史：
> - `8350a8c chore: prepare repository baseline for public first release`
> - `d7fefea feat: ship chat-first dialogue facade and related UI flows`
> - `4f559c2 chore: add acceptance harness and operator release tooling`
>
> 因此本节现在更适合作为 replay / 复核边界，而不是声称这些 slice 仍然处于“等待首次 stage”的状态。

## 8. Dry-run 建议

在真正 `git add` 前，建议先跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseDryRun.ps1 -WriteMarkdown
```

它会基于当前 `git status --short` 输出：

- 建议纳入首发
- 保留但不建议当首发完成背书
- 建议暂缓 / 排除
- 仍需人工判断

并生成一份 markdown 快照，方便对照 `docs/GITHUB_FIRST_RELEASE_FILESET.md` 做最后确认。

当前约定上：

- `scripts/Run-GitHubFirstReleaseDryRun.ps1` 属于首发可公开工具链
- `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md` 更接近 working snapshot，不建议当首发完成度背书
- 真正开始收 commit 时，批次建议以 `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md` 为准
- 真正开始执行 `git add` 时，命令块和核对方式建议以 `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md` 为准
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_*_2026-05-11.md` 也属于 working logs，应归入 evidence-only，而不是首发核心文件

当前已有一轮真实 dry-run 证据：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseDryRun.ps1 -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`

按最新一轮快照：

- 绝大多数当前 worktree 变更都能自动落到 `include` 或 `evidence_only`
- 当前没有额外 `review` 项残留
- 所以首发边界已经从“纯文档提案”推进成了“有脚本、有快照的 dry-run”

同时已有一轮 commit-level dry-run：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown`
- 产物：`docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`

按最新这轮结果：

- baseline / product / harness 三组都已有真实命中项
- 当前 `unmatched = none`
- 当前 `defer` 明确包含 `.reasonix/`、`tmp/`、`task-ops.js`、`version`，这些属于本机运行残留或临时调试入口，不进入首发主 slice；`docs/GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md` 已脱敏并纳入 baseline 文档
- 这说明首发提交批次已经不仅是纸面方案，而是有真实 worktree 快照支撑

同时现在还已有一轮 stage-level proof：

- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit baseline -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit product -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit harness -WriteMarkdown`
- `powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown`

对应产物：

- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`

这层证据说明当前三批 staged slice 不只是 `git add -n` 命令块，而是已经有 simulated staged diff 可供核对。

## 9. 当前仍未完成的公开 gate

即使以上 staging 方案都已经收稳，当前仍不能宣称以下事项已完成：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条严格人工手点已完成（区别于当前 scripted browser seam coverage）
- GitHub Actions 已在真实远端仓库跑绿
