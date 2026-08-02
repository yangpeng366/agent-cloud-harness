# Release / GitHub

本专题覆盖 GitHub 首发、release 边界、precheck、dry-run、commit/stage 相关材料。

当前目录仍采用轻量工作区：先以 `README.md` 做入口；只有当 release 主题持续高频续写时，才按 `docs/README.md` 的升级顺序增补 `PROGRESS.md`、`tasks/`、`runs/`、`archive/`。如果这些层级未来出现，阅读顺序仍然是 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/archive`。

当前 release 主题内部也已经不止一条线，不要把 checklist、scope、execution guide、precheck、dry-run、stage preview、commit 计划都当成同层主线。先判断当前任务属于哪一类，再进入对应子主题：

- release gate / 当前是否可公开
- 首发范围边界 / 对外叙事
- replay / stage / commit 执行链
- 最新 dated precheck 证据
- 历史 dry-run / stage-preview / commit-plan 工作日志

## 当前工作区判断

- `release/` 现在仍保持 `README-only`，这是刻意的当前状态，不是漏建 `PROGRESS.md`。
- 当前仍然为真的 release 基线已经集中在 checklist、scope proposal、execution guide 和最新 precheck 这几份稳定入口里。
- 其余多数 release 文档属于 dated 历史证据或一次性 dry-run / stage-preview / commit-plan 日志，而不是正在持续推进的活跃主线。
- 只要 release 任务仍以“复核当前能否公开”“回放既有首发流程”“回看历史证据”为主，就继续维持 `README.md -> docs/` 根目录主线文档 这条轻量阅读链。

## 何时升级

- 新一轮 release 周期正式开始，并且需要连续多轮短进度写回时，再增加 `PROGRESS.md`。
- precheck / dry-run / stage / commit 证据开始在多个新日期持续累积，且需要主题内集中追踪时，再考虑增加 `runs/`。
- release 主题同时出现多条并行子任务，需要显式维护拆分计划或执行线程时，再考虑增加 `tasks/`。
- 历史首发材料已经明显退出当前主线，且需要从当前入口里降级分流时，再考虑增加 `archive/`。

## 命中信号

- 任务提到 GitHub 首发、公开范围、release checklist、stage/commit
- 任务提到 precheck、dry-run、提交切片、公开仓库口径
- 任务是在判断“现在能不能发”或“该怎么 replay 首发流程”

## 先做子主题判断

| 当前问题 | 先看哪里 | 再下钻 |
|------|------|------|
| 现在离公开首发还差什么、release gate 是否已满足 | `../GITHUB_RELEASE_CHECKLIST.md` | `../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`、`../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md` |
| 首发应该公开哪些内容、主叙事讲到哪里、范围边界怎么划 | `../GITHUB_RELEASE_SCOPE_PROPOSAL.md` | `../GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md`、`../GITHUB_FIRST_RELEASE_FILESET.md` |
| 要 replay 一轮首发流程、复核 stage / commit 边界、核对三段主提交 | `../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md` | `../GITHUB_FIRST_RELEASE_STAGING_PLAN.md`、`../GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`、`../GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md` |
| 要看最新一轮本地预检证据 | `../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md` | `../GITHUB_RELEASE_CHECKLIST.md` |
| 要回看早期 dry-run、stage preview、index audit、commit dry-run 的历史工作日志 | 对应 dated release 历史文档 | 若结论仍有效，再回收到 checklist / scope proposal / execution guide |

## 最小阅读顺序

1. `../GITHUB_RELEASE_CHECKLIST.md`
2. `../GITHUB_RELEASE_SCOPE_PROPOSAL.md`
3. `../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
4. `../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`
5. 如果任务已经明确落在某条子线，再按上面的子主题判断进入对应文档，不需要把所有 release 历史日志全文扫一遍。

## 稳定基线

- `../GITHUB_RELEASE_CHECKLIST.md`
- `../GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`
- `../GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md`

这些文档更接近“今天仍然为真”的 release gate、公开范围、execution replay 入口和最新预检证据。若本轮改动改变了首发边界、对外口径或 replay 步骤，优先回写这里。

## 当前主线文档

### Release Gate / Current Public-Readiness

- `../GITHUB_RELEASE_CHECKLIST.md`
- `../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`

### Scope / Positioning / Public Narrative

- `../GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- `../GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md`

### Replay / Stage / Commit Execution Chain

- `../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `../GITHUB_FIRST_RELEASE_FILESET.md`
- `../GITHUB_FIRST_RELEASE_STAGING_PLAN.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md`

## 历史工作记录

- `../GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md`
- `../GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md`
- `../GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`

## 写回顺序

- release gate、公开范围、对外口径变化：
  - 优先续写 `GITHUB_RELEASE_CHECKLIST.md`
  - 或 `GITHUB_RELEASE_SCOPE_PROPOSAL.md`、`GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- 首发 replay / stage / commit 边界变化：
  - 优先续写 `GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
  - 需要时同步 `GITHUB_FIRST_RELEASE_STAGING_PLAN.md`、`GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`
- 预检、stage preview、dry-run 证据：
  - 用 dated `*_PRECHECK_YYYY-MM-DD.md` / `*_DRY_RUN_YYYY-MM-DD.md` 文档续写
- 不要再额外新建一份平行“release progress”文档

## 历史材料使用规则

- 旧 dry-run / stage preview / commit dry-run / index audit 记录只作为对照样本，不应用来替代当前 release gate 或当前 replay 入口。
- `GITHUB_FIRST_RELEASE_FILESET.md`、`GITHUB_FIRST_RELEASE_STAGING_PLAN.md`、`GITHUB_FIRST_RELEASE_COMMIT_PLAN.md`、`GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md` 现在更适合作为 replay / 边界辅助材料，而不是所有 release 任务的第一入口。
- 如果某个首发规则今天仍然有效，应提炼回 checklist、scope proposal 或 execution guide。

## 当前入口建议

- 要判断现在是否可以公开：`../GITHUB_RELEASE_CHECKLIST.md`
- 要判断首发讲到哪里：`../GITHUB_RELEASE_SCOPE_PROPOSAL.md`
- 要实际 replay 一轮首发流程：`../GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- 要看最新一轮本地公开前证据：`../GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`

## 公开边界提示

当前仓库更适合作为**本地/受控环境的可公开实验验证原型**；对外贡献前请先阅读根目录 [SECURITY.md](../../SECURITY.md)，不要把未受管服务直接暴露到公网。

适合参与的范围：本地实验验证、文档补强、控制面原型探索、小颗粒 issue/PR；不适合直接用于生产多租户、公开 SaaS 或需要严格 SLA 的在线服务。

在认领 `GFI-09` / `GFI-10` 前，请先确认该候选是否仍在当前公开边界内，不把内部实验假设直接带到候选描述里。

## 外部协作候选

- 外部贡献者可认领条目见仓库根 `../../CONTRIBUTING.md` 的「Good First Issues / Help Wanted 候选」节（good first issue / help wanted / feature 三档），每条带范围、验收标准与上下文入口，均来源于 `docs/NEXT_EVOLUTION_PLAN.md` 与 `docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 的真实 backlog。