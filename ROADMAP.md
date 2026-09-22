# Roadmap

本路线图描述 Agent Cloud Harness 的演进方向与优先级，便于外部读者了解「项目走到哪、接下来做什么」。内容提炼自内部工程计划 `docs/NEXT_EVOLUTION_PLAN.md` 的 E1-E5，并标注每条方向对应的可认领贡献条目（见 [`CONTRIBUTING.md`](CONTRIBUTING.md) 的 Good First Issues / Help Wanted 候选）。

> 状态基准：2026-07。版本变更记录见 [`CHANGELOG.md`](CHANGELOG.md)；当前已有能力见 README「项目状况与已知限制」。

## 当前基线（已就绪）

| 方向 | 状态 |
|------|------|
| Loop 主闭环：goal -> plan -> execute -> judge -> decide | done |
| Goal 合同：subgoal_status + goal progress 优先判断 | done |
| 交接 packet：Resume/Handoff 最小字段集 + cross-worker 稳定性 | done |
| UI 状态展示：active/running/waiting_human/failed/partial/done 一致口径 | done |
| 配置驱动 Worker Lane：harness-config.yml | done |
| CCX codex-free 模型映射 | done |
| harness-state.json 自动发现 | done |
| LLM-assisted Subgoal Update | done |
| Handoff Recovery（handoff_depth） | done |
| Pi/Trae Protocol 注册 + Advisory Handoff | done |

各方向的验证入口见 `docs/NEXT_EVOLUTION_PLAN.md` 第 1 节。

## 下一步方向

### E1 - Loop Decide 深度消费 Goal Progress（优先级 4）

decide 已消费 `subgoal_status` 做 HALT/CONTINUE/ESCALATE 判断，但 goal progress 的消费仍偏浅。

计划：
- decide 输出显式关联 goal progress 的决策理由（不只是 action，还有 why）
- `progress_summary` 从计数升级为语义摘要（如「3/5 subgoals done, 2 blocked on API dependency」）
- LLM-assisted subgoal judgment 在 decide 节点被正式消费

验收：decide 输出含 `decision_rationale` 并引用 goal progress；`progress_summary` 含语义描述；至少一条端到端任务链可见 LLM subgoal judgment 影响 decide。

贡献入口：`FEAT-01`（见 `CONTRIBUTING.md`，需先开 issue 对齐字段设计）。

可选依赖路径（借鉴类调研，非必选）：若维护者决定引入 System One Model 做上下文评分门控，可在 E1 验收项之上叠加一条 `[ ]`「decide / context / judgment 路径中至少有一处使用 Jev-style 评分门控（详见 `docs/JEV_CONTEXT_SCORING_RESEARCH.md` 与 `CONTRIBUTING.md` FEAT-03 / HW-09 / HW-10）」。立项前不开工，凭据由维护者自管，仓库内不出现。

### E2 - 端到端验证闭环（优先级 1，最高）

已有 P2 e2e smoke 证据，但覆盖面仍窄。

计划：
- 扩展 baseline matrix 到 `medium-001` / `long-001` 场景
- 补充 codex-free 路由的端到端验证（免费模型执行 + advisory handoff 升级）
- 修复 pre-existing 测试失败（`WorkerExecutorRouterProviderNativeTest` + `WorkerPromptHeaderBuilderTest`）

验收：short/medium/long 三模式在 codex-main + codex-free 两条 lane 上都有 smoke 证据；advisory handoff（codex-free -> codex-main -> codex-free）有端到端证据；pre-existing 测试失败已修复或有 documented workaround。

贡献入口：`FEAT-02`（扩展 matrix）、`GFI-01` / `GFI-02`（修复 pre-existing 测试失败，good first issue）。
可选依赖路径（仅当 FEAT-05 立项）：`medium-001 / long-001` 端到端 smoke 验证时同步跑 OpenEyes 驱动的 structured UI assertion 模式（`scripts/Run-OpenEyesUiAssertion.ps1`），与现有 screenshot diff 模式对比 flaky 率与回归覆盖率；不绑死，E2 主线仍以 `short-001` baseline 为准。详见 `docs/OPENEYES_UI_AUTOMATION_RESEARCH.md`、`CONTRIBUTING.md` FEAT-04 / FEAT-05 / HW-11 / HW-12 / HW-13。

可选依赖路径（仅当 FEAT-03 立项）：若 FEAT-03 落地，`medium-001 / long-001` smoke 验收时同步验证 Jev 评分门控在长任务下的 token 节省与 `key_decisions` 稳定性，输出对比数据；不强行绑死，E2 主线仍以 `short-001` 基线为准。

### E3 - UI Loop Activity 集成（优先级 2）

`loop-activity-detector-plan.js` 与 `recovery-action-hint-plan.js` 已作为独立模块落地，但尚未集成到 `app.js`，是产品闭环「最后一公里」。

计划：
- 将 loop activity detector 接入 `/dialogue/` 实时状态展示
- 将 recovery action hint 接入 `/dialogue/` 的 `waiting_human` 状态卡
- `/console/` operator 读面展示 loop 活跃度趋势

验收：`/dialogue/` 看到 loop active/stall/stale 指示；`waiting_human` 卡显示可执行人工动作建议；`/console/` 看到 loop 活跃度。

贡献入口：`HW-01`、`HW-02`（可合并一个 PR）。

### E4 - CCX 启动服务集成（优先级 3）

当前 CCX 需用户手动启动 Desktop 应用。

计划：
- harness 启动时自动检测 CCX 可达性，不可达时提示用户启动
- `harness-config.yml` 的 `ccx.health_check_on_startup: true` 触发 precheck
- CCX 渠道状态同步到 `harness-state.json` 的 `ccxChannels`

验收：启动时 CCX 不可达输出明确提示；`harness-state.json` 反映渠道状态；`/console/` 可查看 CCX 渠道健康。

贡献入口：`HW-04`。

### E5 - 配置覆盖闭环（优先级 5）

`harness-config.yml` 与 `harness-state.json` 已落地，但合并逻辑尚未完全闭环。

计划：
- `harness-state.json` 的 `providers.userEnabled` 与 `harness-config.yml` 的 worker 声明合并
- 用户在 `harness-config.yml` 中禁用的 provider 不进入路由候选
- `/console/` 展示当前生效的配置合并结果

验收：未声明 provider 使用自动发现结果；显式禁用 provider 不进路由候选；`/console/` 看到自动发现 vs 用户配置的合并结果。

贡献入口：`HW-03`。

## 优先级总览

| 优先级 | 方向 | 理由 |
|--------|------|------|
| 1 | E2 端到端验证 | 已有功能需真实运行证据，否则后续演进缺基线 |
| 2 | E3 UI Loop Activity 集成 | 已有模块未集成，是产品闭环最后一公里 |
| 3 | E4 CCX 启动服务集成 | 降低启动门槛，提升日常体验 |
| 4 | E1 Loop Decide 深度消费 | decide 已可用，深度消费是增量优化 |
| 5 | E5 配置覆盖闭环 | 配置已可用，闭环是增量完善 |

## 明确不做（第一版范围外）

为保持范围可控，第一版明确不做以下事项：

- 不做 `harness-config.yml` 热重载（第一版重启生效）
- 不做 CCX 渠道状态自动同步到 worker lane（第一版只做启动时 precheck）
- 不引入新 provider 或新 IPC 协议
- 不在 harness 内复制 CCX 的路由逻辑
- 不为每个免费模型加独立 CCX 渠道（用 `modelMapping` 即可）

## 如何参与

每条方向都对应 [`CONTRIBUTING.md`](CONTRIBUTING.md) 中可认领的贡献条目（good first issue / help wanted / feature）。认领流程与验收标准见其「Good First Issues / Help Wanted 候选」节。

路线图随演进更新；某条方向完成后会在对应章节标注并保留入口（与 GFI 维护约定一致）。

## 公开协作与发布准备候选清单

下列条目面向外部贡献者，聚焦“更容易被理解、更容易被复用、更容易对外协作”的低风险推进点；不臆造能力，只把现有仓库材料整理成可独立认领、可独立验证的小颗粒工作。

### 如何认领

1. 选一条候选，先读完本条目给出的上下文入口，确认范围理解一致。
2. 在 issue 或 PR 描述里注明候选编号与验收结果。
3. 只做本条范围，不改动首发边界、不新增对外发布渠道、不引入敏感凭据。
4. 若只改文档，请至少说明你验证过的阅读路径与命令示例；若改代码，请跑通 `.\scripts\Test-WithJava21.ps1`。

### 文档 / 治理

**HW-05 · 补齐对外协作快速索引 · `help wanted`**
- 背景：仓库已有 README、CHANGELOG、CONTRIBUTING、SECURITY、ROADMAP、docs index audit，但对外新人仍容易漏读 `docs/README.md` 与 `docs/release/README.md`。
- 范围：只整理索引与阅读入口，不重写架构文档。
- 验收：从 `README.md`、`CHANGELOG.md`、`CONTRIBUTING.md`、`ROADMAP.md`、`docs/README.md`、`docs/release/README.md` 都能快速定位到对方的入口；不改动首发范围与对外叙事。
- 上下文入口：`docs/README.md`、`docs/release/README.md`、`CONTRIBUTING.md`、`ROADMAP.md`。
- 技能：Markdown / 信息架构。预估 1–2h。

### 发布准备

**HW-06 · 更新公开就绪 backlog 清单 · `help wanted`**
- 背景：`docs/GITHUB_RELEASE_CHECKLIST.md` 已有本地 precheck、dry-run、release gate 证据，但缺少面向后续对外协作的“接下来还要补什么”的集中清单。
- 范围：在 `docs/GITHUB_RELEASE_CHECKLIST.md` 只追加一节可公开协作的 backlog；不改动首发阶段历史证据。
- 验收：新增节只引用现有文件，不新增对外发布渠道；条目可逐条核对是否 still true；不引入敏感信息。
- 上下文入口：`docs/GITHUB_RELEASE_CHECKLIST.md`、`docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-06-02.md`、`docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md`。
- 技能：Markdown / 发布准备。预估 1–2h。

### 代码 / 测试

**HW-07 · 收敛 docs 审计命令的文档口径 · `help wanted`**
- 背景：`CHANGELOG.md` 提到 docs index audit 持续全绿，但 README 只展示构建与运行，没有给贡献者一条“怎么自验文档结构”的最短命令。
- 范围：只补充贡献者可自验的文档结构命令与预期结果，不改动审计脚本本身。
- 验收：新命令能在当前仓库一次跑通；预期输出与当前 `passed=true` 结论一致；不新增依赖。
- 上下文入口：`docs/README.md`、`CHANGELOG.md`、`scripts/Run-DocsIndexAudit.ps1`。
- 技能：PowerShell / Markdown。预估 1–2h。

### 维护约定

- 条目来源稳定计划文档，不臆造；某条完成后标 ✅ 并保留入口，不直接删除。
- 新增候选须同时给出「验收标准」与「上下文入口」，否则不录入。
- 如新增根级 markdown，须同步跑 `scripts/Run-DocsIndexAudit.ps1` 防止 orphan。
- 建议自验命令：`powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1`；期望关键输出包含 `passed=true`，并说明 `orphan` / `orphan-comment` 数量为 `0` 或仅剩允许项。
