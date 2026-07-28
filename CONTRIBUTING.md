# Contributing

感谢对 Agent Cloud Harness 的关注。

## 开发前提

- Java 21
- Maven 3.9+
- Windows 开发机建议直接使用仓库内脚本：
  - `.\scripts\Use-Java21.ps1`
  - `.\scripts\Build-WithJava21.ps1`
  - `.\scripts\Test-WithJava21.ps1`

## 基本流程

1. Fork 仓库并创建分支。
2. 修改代码或文档。
3. 本地运行测试。
4. 确认没有把 `.tmp/`、`test-results/`、本机日志、数据库文件提交进来。
5. 提交 PR，并说明：
   - 变更目的
   - 影响范围
   - 测试方式
   - 是否涉及 API / 文档更新

## 代码约定

- 业务代码使用英文标识符。
- 注释以中文为主。
- 领域对象优先保持不可变，使用 `withXxx()` 风格更新。
- 新增 HTTP 接口时，沿用现有 `HttpHandler` 模式，不引入新的 Web 框架。
- 修改接口行为时，同步更新：
  - `docs/API_CONTRACTS.md`
  - `docs/TROUBLESHOOT.md`
  - 相关测试

## 提交前检查

最少完成以下检查：

```powershell
.\scripts\Test-WithJava21.ps1
```

如果只改了文档或前端静态逻辑，也请至少说明你做了哪些局部验证。

## 大改建议

如果变更涉及以下方向，建议先在 issue 或 PR 描述中写清楚方案：

- 控制图节点语义变更
- Packet / Checkpoint 合同变更
- `/dialogue/` 或 `/console/` 交互重构
- `/v1/chat/completions` / `/v1/responses` façade 兼容层调整


## Good First Issues / Help Wanted 候选

下列条目面向外部贡献者，把仓库当前真实 backlog 拆成可独立认领、可独立验证的小颗粒工作，并标注难度、范围与验收标准。条目均来源于 `docs/NEXT_EVOLUTION_PLAN.md` 与 `docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md`，不臆造。

### 如何认领

1. 选一条候选，按「上下文入口」读完对应文档，确认范围理解一致。
2. 在 issue 区留言认领（或 fork 后直接开 PR，PR 描述里注明候选编号）。
3. 完成后必须满足该条的「验收标准」，并跑通 `.\scripts\Test-WithJava21.ps1`。
4. 涉及接口行为变更的，同步更新 `docs/API_CONTRACTS.md` 与相关测试。

### 难度分档

| 标签 | 含义 | 适合人群 |
|------|------|----------|
| `good first issue` | 小、独立、可自验、无需深领域知识 | 首次贡献者 |
| `help wanted` | 中等、有明确验收标准、可能跨 1–2 个模块 | 熟悉 Java/前端的贡献者 |
| `feature` | 较大、需要设计对齐、可能影响 runtime 合同 | 深度参与者 |

### good first issue

**GFI-01 · 修复 `WorkerPromptHeaderBuilderTest` 失败**
- 背景：pre-existing 失败（非当前活跃开发引入），长期挂在回归基线上。
- 范围：`src/test/java/com/agentcloud/worker/WorkerPromptHeaderBuilderTest.java` 及被测类。
- 验收：单独运行通过；不引入新回归；若改生产行为，PR 写清根因。
- 上下文入口：`docs/SPEC.md`（worker prompt header 合同段）。
- 技能：Java、JUnit。预估 ≤ 2h。

**GFI-02 · 修复 `WorkerExecutorRouterProviderNativeTest` 失败**
- 背景：同为 pre-existing 失败，涉及 worker executor 路由 native 路径。
- 范围：`src/test/java/com/agentcloud/worker/WorkerExecutorRouterProviderNativeTest.java` 及被测类。
- 验收：单独运行通过；不引入新回归；根因是 fixture 漂移时优先修 fixture。
- 上下文入口：`docs/provider/README.md`、`docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md`。
- 技能：Java、JUnit。预估 ≤ 2h。

**GFI-03 · 新增 `CHANGELOG.md` 并回填首版变更记录 · 已完成 ✅**
- 背景：已完成并提交。仓库根 `CHANGELOG.md` 已纳入版本控制并随仓发布（Keep a Changelog 风格，含 `[Unreleased]` + `[0.1.0]` 待发布基线），缺口已闭合；后续按 Conventional Commits 持续回填。
- 范围：新建仓库根 `CHANGELOG.md`（根目录 markdown 会被 `scripts/Run-DocsIndexAudit.ps1` 纳入索引，新增后须在 `docs/README.md` 或对应主题 README 引用，避免 orphan）。
- 验收：Keep a Changelog 风格，含 Unreleased + `0.1.0-SNAPSHOT`；内容从 `STATE.md`、`docs/continuity/PROGRESS.md`、`docs/provider/PROGRESS.md` 提炼；docs audit 仍 `passed=true`。
- 上下文入口：`STATE.md`、`docs/release/GITHUB_RELEASE_CHECKLIST.md`。
- 技能：Markdown。预估 ≤ 2h。

**GFI-04 · 根目录编译产物收口：`com/` 与 `META-INF/` 纳入 `.gitignore` · 已完成 ✅**
- 背景：已完成并提交。`.gitignore` 已含根级 `com/`、`META-INF/`，根级编译产物泄漏已清理（`git status` 不再出现根级 `com/`、`META-INF/`）。
- 范围：`.gitignore`（追加根级 `com/`、`META-INF/`）；清理已泄漏的根级 `com/`、`META-INF/`（勿动 `src/`）。
- 验收：`git status` 不再出现根级 `com/`、`META-INF/`；`mvn package` 仍正常。
- 上下文入口：`.gitignore`、本文件「基本流程」第 4 步。
- 技能：Git、Maven。预估 ≤ 1h。
- 注意：`hs_err_pid*.log`、`replay_pid*.log`、`*.stackdump`、`nul` 已在 `.gitignore`，本条不重复处理。

**GFI-05 · 修复 `.gitignore` 误忽略所有 `README.md`（发布阻塞） · 已完成 ✅**
- 背景：已完成并提交。裸 `README.md` 规则已从 `.gitignore` 删除，`docs/README.md`、`docs/release/README.md`、`docs/provider/README.md` 等已 tracked 并随仓发布，docs 治理索引层不再被忽略。
- 范围：`.gitignore`（删除裸 `README.md` 行；如确需忽略某个 README，改用精确路径）。
- 验收：`git check-ignore docs/README.md docs/release/README.md` 均不再被忽略；`git status` 出现对应 `??` 后由 maintainer 决定 `git add` 范围；`scripts/Run-DocsIndexAudit.ps1` 仍 `passed=true`。
- 上下文入口：`.gitignore`、`docs/README.md`「根目录文档职责」表。
- 技能：Git。预估 ≤ 30min。
- 注意：本条影响可发布范围，建议 maintainer 确认后再 `git add` 各 README，不要一次性盲加。
**GFI-06 · 公开发布前清理 tracked 文档/记录中的本地 dev token 与本机路径**
- 背景：harness-config.example.yml（对外示例配置）已脱敏为占位符（本轮巡检完成）。但同源的本地 CCX dev token（ccx-081f9efd9e7203d4，仅 localhost 127.0.0.1:3688 生效，无外网可利用性）与本机路径仍残留在若干 tracked 内部文档/执行记录/测试 fixture 中，随仓发布会暴露 maintainer 本机布局并让示例显得绑定特定 CCX 实例，影响公开卫生。
- 范围：docs/FREE_MODEL_WORKER_LANE_PLAN.md（token ×4、admin_key ×1）、docs/P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md（token ×1）、STATE.md（token ×1）、src/test/java/com/agentcloud/agent/providers/HarnessConfigLoaderTest.java（admin_key fixture + 断言）。逐处替换为占位符（如 ccx-YOUR_TOKEN_HERE）并同步更新断言。
- 验收：git grep -n 'ccx-081f9efd9e7203d4\|ccx-admin-2026' 仅剩显式占位符或为空；mvn -q test -Dtest=HarnessConfigLoaderTest 仍通过；docs audit 仍 passed=true。
- 上下文入口：harness-config.example.yml（已脱敏参照）、docs/release/GITHUB_RELEASE_CHECKLIST.md。
- 技能：Markdown、Java/JUnit、Git。预估 ≤ 2h。
- 注意：token 仅 localhost 生效，本条属发布前卫生清理而非密钥轮换；如确需轮换 CCX 凭证，由 maintainer 在本机另行处理，不在本条范围。

### help wanted

**HW-01 · E3：将 `loop-activity-detector-plan.js` 集成进 `/dialogue/`**
- 背景：模块已落地但未接入 `dialogue/app.js`，是产品闭环「最后一公里」。
- 范围：`src/main/resources/web/dialogue/app.js`、`loop-activity-detector-plan.js`。
- 验收：`/dialogue/` 显示 loop active/stall/stale 指示；JS 套件无新失败；`docs/dialogue/README.md` 追加一行集成说明。
- 上下文入口：`docs/dialogue/README.md`、`docs/LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` E3 段。
- 技能：原生 JS、DOM。预估半天。

**HW-02 · E3：将 `recovery-action-hint-plan.js` 接入 `waiting_human` 状态卡**
- 背景：同 HW-01，recovery hint 模块未集成。
- 范围：`dialogue/app.js`、`recovery-action-hint-plan.js`、`waiting_human` 状态卡渲染处。
- 验收：`waiting_human` 卡显示可执行人工动作建议；JS 套件无新失败。
- 上下文入口：同 HW-01。可与 HW-01 合并一个 PR。预估半天。

**HW-03 · E5：`/console/` 展示配置合并结果**
- 背景：`harness-config.yml` 与 `harness-state.json` 合并逻辑未完全闭环。
- 范围：`console/app.js`、`HarnessConfigLoader`、`HarnessState` 读面。
- 验收：`/console/` 看到自动发现 vs 用户配置合并结果；显式禁用的 provider 不进路由候选（含测试）。
- 上下文入口：`docs/FREE_MODEL_WORKER_LANE_PLAN.md`、`docs/NEXT_EVOLUTION_PLAN.md` E5 段。
- 技能：原生 JS、Java。预估 1–2 天。

**HW-04 · E4：harness 启动时 CCX 可达性 precheck**
- 背景：当前 CCX 需手动启动 Desktop 应用；启动期缺可达性提示。
- 范围：`cli/Main.java` 或启动脚本、`harness-config.yml` 的 `ccx.health_check_on_startup`。
- 验收：`health_check_on_startup: true` 时启动做 precheck，不可达给提示而非崩溃；渠道状态同步到 `harness-state.json`。
- 上下文入口：`docs/FREE_MODEL_WORKER_LANE_PLAN.md`、`scripts/Run-HarnessWithCcx.ps1`。
- 技能：Java、HTTP 健康检查。预估 1 天。

### feature（需设计对齐）

**FEAT-01 · E1：decide 输出 `decision_rationale` 引用 goal progress**
- 背景：decide 已消费 `subgoal_status`，但 goal progress 消费偏浅，缺「为什么」。
- 范围：`ControlNodeGraph` decide 阶段、`RuntimeJudgmentService`、`docs/API_CONTRACTS.md`。
- 验收：见 `docs/NEXT_EVOLUTION_PLAN.md` E1 验收标准 1–3。
- 上下文入口：`docs/NEXT_EVOLUTION_PLAN.md` E1、`docs/continuity/README.md`。预估 2–3 天，建议先开 issue 对齐字段设计。

**FEAT-02 · E2：扩展 baseline matrix 到 `medium-001` / `long-001`**
- 背景：现有 e2e smoke 覆盖面窄，缺多模式 × 多 lane 横向证据。
- 范围：`docs/evaluation/` 任务包、smoke 执行记录。
- 验收：见 `docs/NEXT_EVOLUTION_PLAN.md` E2 验收标准 1–3（含 codex-free 路由 + advisory handoff 端到端证据）。
- 上下文入口：`docs/evaluation/README.md`、`P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md`。预估 2–3 天。

### 候选维护约定

- 条目来源稳定计划文档，不臆造；某条完成后标 ✅ 并保留入口，不直接删除。
- 新增候选须同时给出「验收标准」与「上下文入口」，否则不录入。
- 如新增根级 markdown，须同步跑 `scripts/Run-DocsIndexAudit.ps1` 防止 orphan。