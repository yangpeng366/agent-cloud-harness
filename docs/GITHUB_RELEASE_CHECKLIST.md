# GitHub Release Checklist

> 本清单用于把当前仓库从“本地研发态”收成“可公开首发态”。

## 1. 仓库基线

- [ ] `README.md` 已替换成真实公开 GitHub 仓库地址
- [x] `LICENSE` 存在
- [x] `CONTRIBUTING.md` 存在
- [x] `SECURITY.md` 存在
- [x] `CODE_OF_CONDUCT.md` 存在
- [x] `.github/workflows/ci.yml` 存在
- [x] issue / PR templates 存在

## 2. 构建与自动化

- [x] Maven Java 测试可在 CI 中运行
- [x] `/dialogue/` 前端 smoke 已进入 CI
- [x] 已有一轮本地公开前预检证据
- [ ] 在干净环境里完整跑过一次公开前验证

## 3. 发布边界

- [x] worktree 已整理出首发范围提案与 dry-run
- [x] 首发提交批次已有建议方案
- [x] 首发提交批次已有可执行的 staging / execution guide
- [x] 三批首发主提交范围已真实进入本地 Git 历史，且 evidence-only 文件未混入这三批主提交
- [x] `.tmp/`、`test-results/`、本机 crash log、数据库文件不会进入公开提交
- [x] 首发范围与非首发证据文件已明确区分

## 4. 文档口径

- [x] `README.md` 已说明项目定位是本地/单机 harness
- [x] `SECURITY.md` 已说明当前不适合直接公网部署
- [x] `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md` 已说明哪些文件建议纳入首发
- [x] 对外发布说明中不再使用伪造的 GitHub 占位 URL
- [ ] 对外发布说明中的 `<your-published-repo-url>` 已替换成真实地址

## 5. 产品级 gate

- [x] `/dialogue/` 页面发布前测试矩阵至少完整串行跑过一轮
  - latest unified fresh shell + light-smoke sample:
    - `18386`
    - `.tmp/dialogue-shell-report-18386.json`
    - `.tmp/dialogue-business-smoke-18386.json`
- [x] Windows 宿主下的外部进程输出编码兼容已收口为“UTF-8 优先 + 本地编码兜底”
- [x] `/dialogue/` richer browser acceptance 现已具备 fresh 真实证据
  - 2026-06-02 isolated real both-surface lifecycle:
    - `18457` `Surface both` / `LifecycleMode real`
    - report: `.tmp/dialogue-browser-real-both-18457/probe-output.json`
    - screenshots: `.tmp/dialogue-browser-screens-18457-real-both`
    - chat and responses `pause` / `resume` all used real `POST /api/v1/tasks/{id}/...`
    - `hashTaskId == selectedTaskId == action target task id` for both surfaces
  - isolated fresh samples:
    - `18338` `chat`
    - `18340` `responses`
  - real long-lived instance recheck:
    - `8080` `chat` fresh-restart rerun: `.tmp/dialogue-browser-screens-8080-chat-rerun7`
    - `8080` `responses` fresh-restart rerun: `.tmp/dialogue-browser-screens-8080-responses-rerun4`
  - manual-acceptance starter prep bundle:
    - `.tmp/dialogue-manual-18276.json`
    - `Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json`
- [x] `/dialogue/` richer browser acceptance 已覆盖当前可达 A-H seam
  - current coverage:
    - A `default task_auto`
    - B `message_only + task_id` -> current `task_note_attach` seam
    - C `task_required`
    - D `follow-up + manual-start`
    - E `manual-start continuity`
    - F `stream fallback`
    - G `#facade=responses + message_only` -> current `responses_surface.task_note_attach` seam
    - H `#facade=responses + task_required`
  - current rationale:
    - B / G no longer rely on nonexistent `*message-only.png` artifacts
    - both now use real browser-probe evidence from `task_note_attach` on chat / responses surfaces
  - boundary:
    - 这里的 A-H 指 scripted browser 对“当前可达 seam”的覆盖；2026-06-02 已有 `Surface both` + `LifecycleMode real` 复核，但仍不等于严格人工逐条手点 gate 已关闭
- [ ] `/dialogue/` A-H 严格人工逐条手点已完成
  - current note:
    - 当前已有 screenshot / JSON bundle 可辅助回填，但 release precheck 仍应把严格人工 gate 保持为未完成，除非有人按 runbook 完成并签入对应记录
    - 2026-06-02 后续复核中，`Surface both` + `LifecycleMode real` 已在隔离端口 `18457` 通过，旧的低内存 OOM 只保留为运行风险；该自动化证据仍不能替代“人工逐条手点”签核
- [x] 验收记录已回填
  - current evidence:
    - `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
    - `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-14.md`
    - `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-06-02.md`
  - current status:
    - current formal records cover the current `task_note_attach` seam and the 2026-06-02 automated real both-surface lifecycle evidence
- [x] 公开说明中没有把当前状态夸大成 production-ready distributed platform
  - current public-facing wording audit:
    - `README.md` 将项目定位为“本地原型与单机 harness”
    - `SECURITY.md` 明确写明当前不建议直接暴露到公网
    - `docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md` 现已用“推荐首发叙述 + 避免夸大表述”表达边界，不再保留夸大 slogan 原文作为对外描述样例

## 当前判断

如果只看“能公开 push 到 GitHub”：

- 已接近完成，剩余工作主要是整理与验收

如果看“作为首发项目对外站得住”：

- 仍需完成发布范围收口与更大范围产品线收尾
- 仍需保持未完成 gate 的诚实边界：真实公开 URL、干净环境完整预检、真实远端 GitHub Actions、严格人工 A-H 手点

## 证据说明

- 忽略项隔离：见 `.gitignore`
- 本地首发预检记录：见
  - `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`
    - 当前更新后的本地 precheck 记录，包含 provider discovery smoke、Codex partial timeout smoke、`unmatched_count=0` 的 commit dry-run，以及 precheck 标题日期修正
- 首发范围与非首发证据分层：见
  - `docs/GITHUB_FIRST_RELEASE_FILESET.md`
  - `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- 首发执行指南：见
  - `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- 页面功能发布前测试矩阵：见
  - `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
- 文本编码兼容策略：见
  - `docs/TEXT_ENCODING_COMPATIBILITY_PLAN.md`
- 当前 `/dialogue/` 页面发布前预检记录：见
  - `docs/DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`
- 当前目标 staged file list / replay slice：见
  - `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- 当前 index 稳定性审计：见
  - `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- README 发布占位策略与真实 URL gate：见
  - `README.md`
- 首发提交命令块：见
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`
- 首发提交顺序与建议 commit 文案：见
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- simulated staged diff 预演：见
  - `scripts/Run-GitHubFirstReleaseStagePreview.ps1`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- 当前 worktree 的 staged slice 清单：见
  - `docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- 当前目标暂存区命中清单 / replay 清单：见
  - `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- 当前 staged vs unstaged 漂移审计：见
  - `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
- 当前首发优先动作：见
  - `docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- 本地首发预检脚本：见
  - `scripts/Run-GitHubFirstReleasePrecheck.ps1`
  - 该脚本现包含 provider discovery smoke：先构建 shaded jar，再运行 `scripts/provider-discovery-smoke.js`，验证临时 `providers.yaml` 注册的 provider 会同时出现在 `/api/v1/agents` 与 `/api/v1/workers`，且列表 readiness 与 runtime readiness 一致
  - 该脚本现包含 Codex partial timeout smoke：运行 `scripts/Run-CodexPartialTimeoutSmoke.ps1`，验证有输出的 Codex 通信/时间截断会投影为 `partial_timeout`、进入 human gate，并在 Dialogue `worker_round` 上暴露继续/移交入口
- Commit 1 baseline dry-run：见
  - `scripts/Run-GitHubFirstReleaseCommitDryRun.ps1`
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- 最新 `all` commit dry-run：见
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
  - 当前 `unmatched = none`
  - 当前 defer/exclude 明确包含 `.reasonix/`、`tmp/`、`task-ops.js`、`version`，这些不应被误当作首发遗漏项；`docs/GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md` 已脱敏并纳入 baseline 文档
- 当前真实本地首发主提交：见
  - `8350a8c chore: prepare repository baseline for public first release`
  - `d7fefea feat: ship chat-first dialogue facade and related UI flows`
  - `4f559c2 chore: add acceptance harness and operator release tooling`
