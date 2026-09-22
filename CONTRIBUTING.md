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

## PowerShell 脚本 + lib + test 模式

面向用户的 PowerShell 脚本（`scripts/Run-*.ps1`）推荐拆为三层，便于测试：

1. **`scripts/lib/<Topic>.ps1`** — 一个或多个 pure function，参数化、可空字符串/数字边界清晰，不直接做 IO。
2. **`scripts/Run-<Topic>.ps1`** — IO 包装：解析参数 → 读文件 → dot-source lib → 调函数 → 写输出。脚本薄，逻辑集中在 lib。
3. **`tests/Test-<Topic>.ps1`** — vanilla pwsh 断言（无 Pester 依赖），覆盖 happy path / 边界 / schema 守卫 / schema_version 拒收。
4. **`tests/Run-<Topic>Tests.ps1`**（可选）— 测试 runner，串多个套件并出 aggregate 报告。

参考实现：`scripts/lib/JevPatrolL3Extract.ps1` + `scripts/lib/JevPatrolL3Markdown.ps1` + `scripts/Run-JevPatrolL3Extract.ps1` + `scripts/Run-JevPatrolL3Render.ps1` + `tests/Test-JevPatrolL3Extract.ps1` + `tests/Test-JevPatrolL3Markdown.ps1` + `tests/Run-JevPatrolL3Tests.ps1`（合计 51 contract tests）。

踩坑参考：
- `pwsh -File script.ps1` 不暴露 script scope 函数 → 纯函数必须放独立 .ps1 lib + dot-source。
- 函数返回 `List[object]` 时，PowerShell 会自动 unroll 单元素 → 用 `,$result` 保留 List 类型。
- `[Parameter(Mandatory)]` 默认拒绝空字符串 → 加 `[AllowEmptyString()]`。
- PowerShell 自动变量（`$Latest` 等）不能作为参数名 → rename。
- `pwsh -NoProfile -File` 是跑测试的稳定调用方式，避免 profile 污染。

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

**GFI-01 · 修复 `WorkerPromptHeaderBuilderTest` 失败 · 已完成 ✅**
- 背景：pre-existing 失败（非当前活跃开发引入），长期挂在回归基线上。
- 范围：`src/test/java/com/agentcloud/worker/WorkerPromptHeaderBuilderTest.java` 及被测类。
- 验收：单独运行通过；不引入新回归；若改生产行为，PR 写清根因。
- 上下文入口：`docs/SPEC.md`（worker prompt header 合同段）。
- 技能：Java、JUnit。预估 ≤ 2h。

**GFI-02 · 修复 `WorkerExecutorRouterProviderNativeTest` 失败 · 已完成 ✅**
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
**GFI-06 · 公开发布前清理 tracked 文档/记录中的本地 dev token 与本机路径 · 已完成 ✅**
- 背景：harness-config.example.yml（对外示例配置）已脱敏为占位符（本轮巡检完成）。但同源的本地 CCX dev token（ccx-YOUR_BEARER_TOKEN_HERE，仅 localhost 127.0.0.1:3688 生效，无外网可利用性）与本机路径仍残留在若干 tracked 内部文档/执行记录/测试 fixture 中，随仓发布会暴露 maintainer 本机布局并让示例显得绑定特定 CCX 实例，影响公开卫生。
- 范围：docs/FREE_MODEL_WORKER_LANE_PLAN.md（token ×4、admin_key ×1）、docs/P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md（token ×1）、STATE.md（token ×1）、src/test/java/com/agentcloud/agent/providers/HarnessConfigLoaderTest.java（admin_key fixture + 断言）。逐处替换为占位符（如 ccx-YOUR_TOKEN_HERE）并同步更新断言。
- 验收：git grep -n 'ccx-YOUR_BEARER_TOKEN_HERE\|ccx-YOUR_ADMIN_KEY_HERE' 仅剩显式占位符或为空；mvn -q test -Dtest=HarnessConfigLoaderTest 仍通过；docs audit 仍 passed=true。
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

**HW-09 · Jev × HandoffPacket keep-probability 过滤 · `help wanted`**
- 背景：`docs/JEV_CONTEXT_SCORING_RESEARCH.md` §5.2 已识别该切入点；`HandoffPacketBuilder` / `RefinedPacketBuilder` 当前由 LLM 生成完整摘要，跨 worker handoff 后 `key_decisions / key_artifacts / open_questions` 数量易漂移；FEAT-03 把整套 Jev 上下文评分门控做完后，可以先把这一小段拆出来单独立项。
- 范围：在 packet 输出前对 `key_decisions / key_artifacts / open_questions` 各字段打 Jev keep probability；保留概率 ≥ 阈值的字段照常写入 packet，低概率字段降级为 `## footnotes` 而非删除；保留最新 N 条永不降级。
- 验收：跨 worker handoff 后 `key_decisions` 数量稳定不漂移（同一 baseline task 跑 N 次，方差 ≤ 1）；`## footnotes` 段长度低于 packet 总长 30%。
- 上下文入口：`docs/JEV_CONTEXT_SCORING_RESEARCH.md`、`docs/API_CONTRACTS.md`（`ResumePacket` / `HandoffPacket` 段）、FEAT-03（待立项）。预估 1–2 天。`n- 路径 B 子路径已签字(2026-09-21): HandoffPacket keep-probability contract test 已落 `src/test/java/com/agentcloud/scorer/HandoffPacketKeepProbTest.java` (8478 B, 4/4 tests passed, 0.477s)。详见 `docs/INITIATIVES/HW-09.md` §14。
- 注意：与 FEAT-03 互斥；如 FEAT-03 已立项，本条并入 FEAT-03 范围，不再单独保留。

**HW-10 · Jev × Judgment 前置过滤 · `help wanted`**
- 背景：`docs/JEV_CONTEXT_SCORING_RESEARCH.md` §5.2 已识别该切入点；`PromptBasedJudgmentService` 在长任务里 judgment 频次高、token 占比大，缺显然 yes/no 场景的前置过滤。
- 范围：在 `PromptBasedJudgmentService` 之前跑 Jev 做"显然 yes/no"判定；命中则直接调用 LLM，命中失败则跳过 LLM、保留当前 judgment 结论；命中信息写入 `runtime_facts.judgment_pre_filter` 与 `/judgment_trace`。
- 验收：judgment API 调用频次 -50%、平均 latency -40%；LLM 调用 trace 仍可在 `/judgment_trace` 重放，命中 Jev 跳过的步骤显式标注 `source=jev_skip`。
- 上下文入口：`docs/JEV_CONTEXT_SCORING_RESEARCH.md`、`docs/API_CONTRACTS.md`（`judgment_trace` 段）、FEAT-03（待立项）。预估 1 天。`n- 路径 B 子路径已签字(2026-09-21): Jev Judgment 前置过滤 contract test 已落 `src/test/java/com/agentcloud/scorer/JevJudgmentPrefilterTest.java` (5588 B, 4/4 tests passed)。详见 `docs/INITIATIVES/HW-10.md` §14。
- 注意：与 FEAT-03 互斥；如 FEAT-03 已立项，本条并入 FEAT-03 范围，不再单独保留。
**HW-11 · OpenEyes CDP profile 持久化接入 acceptance 套件 · implemented**
- 背景: docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.1 已识别该切入点;Run-DialogueBrowserAcceptanceProbe.ps1 当前每次重启 Edge 都 fresh 登录,影响 /dialogue/ 中需要登录态的验收。OpenEyes cdp.launch_edge(seed=False) + seed_user_data profile copy 已能跨脚本保留 cookies。
- 范围:在 scripts/Run-DialogueBrowserAcceptanceProbe.ps1 探测 ~/.openeyes/profiles/<name>/;首次跑用 OpenEyes seed 写 profile,后续跑 --no-seed 复用;不替换 puppeteer-core 路径,只在其上叠加一层 profile 持久化。
- 验收:同一 profile 跨 5 次 acceptance 跑,user_session.cookies_loaded=true 全部命中;登录耗时从 ~12s 降到 ~2s;现有 puppeteer-core 路径不受影响。
- 上下文入口:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md、scripts/Run-DialogueBrowserAcceptanceProbe.ps1、E:\AI-Portable\codex-home\skills\openeyes\SKILL.md。预估 1 天。
**进展**: 2026-09-21 `Run-OpenEyesProfileSmoke.ps1` 与 Dialogue probe `UseOpenEyesProfile` 已落地；集成 acceptance no-seed 5/5，fresh direct 对照通过。
- 注意:与 FEAT-05 协同;本条只做 profile 持久化,FEAT-05 才做 assertion 替换。

**HW-12 · OpenEyes UIA native app acceptance probe · implemented**
- 背景:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.2 已识别该切入点;ACH acceptance 套件完全 puppeteer-core 化,无法验收 native Windows app(Any VPN / Feishu native / VS Code / Word / Excel / Explorer)。
- 范围:新建 scripts/Run-NativeAppAcceptanceProbe.ps1,调用 eyes windows list --title-contains + eyes detect --window <hwnd> + eyes click --window <hwnd> --name-contains;覆盖 Any VPN 主窗口「连接」按钮验收为最小 case。
- 验收:能在 Windows 11 24H2 + DPI 3840x1200 下成功 detect / click / screenshot;不依赖绝对坐标;本地 smoke 脚本可演示。
- 上下文入口:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md、E:\AI-Portable\codex-home\skills\openeyes\SKILL.md、OpenEyes UIA backend。预估 2 天。
- 进展:2026-09-21 `Run-NativeAppAcceptanceProbe.ps1` 已落地，默认 dry-run 输出结构化 JSON；OpenEyes detect 原生 JSON 解析、selector click、窗口截图均打通。Explorer `此电脑 / 剪切` dry-run PASS；Any VPN 真实 `--go` 因当前无可见窗口待实测。
- 注意:本条只做 native app probe;不注册 worker tool,不替换 browser acceptance。


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

FEAT-04 才把 UIA 接入 worker tool;FEAT-05 才把它做成 structured assertion。

**HW-13 · OpenEyes MCP 启动可达性 precheck · implemented**
- 背景:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.3 已识别该切入点;harness-config.yml 已有 ccx.health_check_on_startup 配置项,本条加对称的 eyes_mcp.health_check_on_startup;ACH 启动时自动探测 eyes-mcp stdio transport 可达性。
- 范围:在 scripts/Use-Java21.ps1 + Run-HarnessWithJava21.ps1 加探测;不可达时提示用户启动(与 CCX precheck 同样模式);检测结果写 harness-state.json.ccxChannels(如需新增 eyesMcpChannels 字段,由本条 PR 同步扩展)。
- 验收:启动日志包含 eyes_mcp: healthy / unhealthy: <reason>;/console/ 看到 OpenEyes 渠道健康卡。
- 进展:2026-09-21 harness-config.yml 新增 eyes_mcp 段(command/health_check_on_startup/startup_timeout_seconds),HarnessConfigLoader 解析到 HarnessEyesMcpConfig;HarnessStateWriter.discover(llmConfig, eyesMcp) 启动时 stdio 探测 initialize + tools/list,timeout 后进程 destroyForcibly;HarnessState.EyesMcpStatus 持久化进 harness-state.json;NioHttpServer.healthPayload 暴露 eyes_mcp 字段;/console/ 新增 OpenEyesMcpHealthPlan 健康卡(healthy/disabled/unhealthy 三态)。真启动实测 eyes_mcp: healthy server=openeyes/1.26.0 tools=13 durationMs=2923;mvn test -Dtest=HarnessConfigLoaderTest,HarnessStateWriterTest,ApiErrorContractHttpTest 47/47 通过;node --test openeyes-mcp-health-plan.test.mjs 2/2 通过;PowerShell parse 0 error,UTF-8 无 BOM,docs audit 维持基线 9。
- 上下文入口:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md、现有 CCX precheck 实现(docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md)。预估 3 天。
- 注意:本条只做可达性 precheck;FEAT-04 才把 MCP 13 tool 实际注册进 worker tool;本条与 FEAT-04 互不依赖。

**FEAT-03 · E1+E2：Jev 上下文评分门控（System One Model · 借鉴类）**
- 背景：`docs/JEV_CONTEXT_SCORING_RESEARCH.md` 已完成借鉴类调研；ACH 现有 `mounted_context_budget_truncated` 是字符级硬截，长任务下关键决策与 artifact 容易被整段吞掉；`HandoffPacketBuilder` / `ResumePacketBuilder` 由 LLM 生成完整摘要，跨 worker handoff 后 `key_decisions` 数量易漂移；`PromptBasedJudgmentService` 在长任务里 judgment 频次高、token 占比大，缺乏显然 yes/no 场景的前置过滤。本条引入 TypeSafe Jev（System One Model）作为可选依赖门控，**不替换任何现有 judgment 服务**，仅在评分门控层提供 System One = 高频 / 低成本 / 确定性判决那一层。
- 范围：
  - 新增 `JevContextScorer`（`continuity/` 主题下），对 mounted panel / tool result 跑 Jev decide；保留概率 ≥ `keepThreshold`（默认 0.5）逐字 verbatim，低概率降级为 `head(truncateHeadChars)`（默认 300）提示，最新 `preserveRecentMessages`（默认 6）永不剪枝。
  - 在 `HandoffPacketBuilder` / `RefinedPacketBuilder` 出口对 `key_decisions / key_artifacts / open_questions` 打 keep probability；低概率字段降级为 `## footnotes` 而非删除。
  - 在 `PromptBasedJudgmentService` 之前跑 Jev 做"显然 yes/no"判定；命中则直接调用 LLM，命中失败则跳过 LLM、保留当前 judgment 结论。
  - 接入走 `harness-config.yml` 新增 `jev.context_scoring` 段，**默认关闭**；`TYPESAFE_API_KEY` 按 AGENTS.md "credentials 不进仓库" 口径由维护者决定存放位置（推荐 `~/.openclaw/.../secrets/`），仓库内绝不出现。
  - 同步更新 `docs/API_CONTRACTS.md`（新增 `jev` 观测字段）、`docs/TROUBLESHOOT.md`（Jev 限流 / 抖动 / fallback 排障口径）、相关单元测试与 focused 集成测试。
- 验收：
  - `active_context_only` 与 `mounted_context_primary` 两条模式下，单轮 task 平均 token 消耗下降 30%+。
  - `mounted_context_budget_truncated` 命中率显著低于 0（默认配置下接近 0%）；`key_decisions` 在跨 worker handoff 后能逐字保留，且 `key_decisions` 数量稳定不漂移。
  - judgment API 调用频次 -50%、平均 latency -40%；LLM 调用 trace 仍可在 `/judgment_trace` 重放，命中 Jev 跳过的步骤需在 `runtime_facts` 显式标注 `source=jev_skip`。
  - 关闭 `jev.context_scoring` 时所有指标回到当前基线，回滚成本 < 1 行配置。
- 上下文入口：`docs/JEV_CONTEXT_SCORING_RESEARCH.md`、`ROADMAP.md` §E1 / §E2、`docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`docs/API_CONTRACTS.md`（runtime_context / judgment_trace 段）。预估 1 周，建议先开 issue 对齐 `feature_flag` / `keepThreshold` / `truncateHeadChars` 默认值与 fallback 策略，再写 `plan.md`。
- **路径 B 子路径已实施（2026-09-21 签字）**：RuntimeJudgmentService parallel questions decision tree 的 contract test 已落 `src/test/java/com/agentcloud/scorer/JevDecisionTreeTest.java` (12310 B, 4/4 tests passed, 0.136s)。详见 `docs/INITIATIVES/Jev-Graph-B.md` §14 + `docs/JEV_DECISION_TREE_REAL_EXPERIMENT.md`。
- **mounted_context_view hybrid 子路径已实施（2026-09-21 签字）**：MountedContextView panel hybrid weight 的 contract test 已落 `src/test/java/com/agentcloud/scorer/JevMountedContextHybridTest.java` (8463 B, 4/4 tests passed, 41s)。详见 `docs/INITIATIVES/Jev-Graph-A.md` §14 + `docs/JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md` §5。
- **tool recall filter 子路径已实施（2026-09-21 签字 · 真 Jev 实测验证）**：tool/agent 并发召回 → context 初筛门控的 contract test 已落 `src/test/java/com/agentcloud/scorer/JevToolRecallFilterTest.java` (11282 B, 6/6 tests passed, 0.137s)。真 Jev (jev-1.13.0) 实测 precision@5=100% (5/5 relevant, 0 noise), clean separation 0.68-0.91 vs 0.01-0.02, 1.05s / ~$0.00009。详见 `docs/INITIATIVES/Jev-ToolRecall-Filter.md` §14 + `docs/JEV_TOOL_RECALL_FILTER_DEMO.md`。
- **主路径 Step 1 已实施（2026-09-21 签字）**：JevContextScorer 生产代码骨架落 `src/main/java/com/agentcloud/scorer/` (JevScorer 接口 + JevRequestItem/JevDecision/JevAction records + JevContextScorerConfig 9 字段 config + JevContextScorer HTTP impl skeleton with JDK 21 HttpClient + Jackson + bearer + timeout)。Fake + Config + HTTP 三个 test 共 18/18 全绿 (JevContextScorerConfigTest 6 + JevContextScorerFakeTest 6 + JevContextScorerTest 6, 0.808s)。harness-config.yml 新增 `feature_flags.jev.{context_scoring,patrol_dispatcher,patrol_postprocess,tool_recall_filter}` 4 个 feature flag, 默认全 false。详见 `docs/INITIATIVES/FEAT-03.md` §14 + `docs/JEV_CONTEXT_SCORING_PLAN.md` §13.1-13.3 + §11 立项门槛 1-9。
- **主路径 Step 3a 已实施（2026-09-21 签字 · decorator 包装 JudgmentService）**：JevPrefilteredJudgmentService decorator 落 src/main/java/com/agentcloud/judgment/ (实现 JudgmentService + 包装任意 JudgmentService + 可选 JevScorer prefilter + KEEP_VERBATIM/TRUNCATE_HEAD 路由 source 标签 jev_act/skipped_by_jev + null/异常 fallback)。JevScorer 接口加 default boolean isEnabled() (Fake override true, impl override config)。JevPrefilteredJudgmentServiceTest 6/6全绿 (high/low prob skip LLM + disabled + null scorer + completion 对称 + null context 无 NPE)。Step 1 + Step 3a 测试合计 24/24全绿。详见 docs/INITIATIVES/FEAT-03.md §14.1。
- 维护约定：此条候选为借鉴类调研驱动，立项前不开工；调研本身不引入任何凭据、不动生产路径。

**FEAT-04 · OpenEyes MCP tool 注册为可选 worker tool · help wanted → in progress**
- 背景:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.4 已识别该切入点;ACH WorkerExecutor 当前 Tool-aware 执行面只有本地文件 / shell / REST,无 UI 自动化能力;OpenEyes MCP 13 tool 是稳定本地服务,可通过 stdio 注册。
- 范围(path A 已实施):`src/main/java/com/agentcloud/tool/OpenEyesTool.java` 透传本地 `eyes` CLI 到 ToolRegistry;`HarnessConfig.HarnessEyesMcpConfig` 扩 `enabled/registerAsTool` 字段;`HostToolAvailability` + `WorkerHandler.KNOWN_TOOL_CAPABILITIES` 加 `openeyes`;`harness-config.yml` 加 `eyes_mcp.{enabled,register_as_tool}`(默认 false)。path B 待做:13 tool MCP stdio 协议客户端;e2e 任务链验证;runtime_facts.openeyes_tool_calls。
- 验收:ACH 通过 MCP stdio 启动并发现 13 个 tool;至少一条 end-to-end 任务链可验证「open Feishu → click 消息框 → type hello → screenshot」;runtime_facts.openeyes_tool_calls 记录调用次数、延迟、成功率;关闭 eyes_mcp.enabled 时所有指标回到当前基线,回滚成本 < 1 行配置。
- 上下文入口:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md、docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md、docs/API_CONTRACTS.md、docs/HARNESS_CHANGE_CONTRACT.md(Contract-Additive 流程)。预估 1-2 周,建议先开 issue 对齐 mcp_client 选型与 stdio transport 协议。

**FEAT-05 · OpenEyes 驱动的 structured UI assertion · in progress**
- 背景:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md §3.5 已识别该切入点;baseline matrix smoke 当前用截图 diff 做 UI 断言,CI flaky 率偏高;OpenEyes eyes detect --window <hwnd> 返回结构化 Element[],可对比 contract 而非像素。
- 范围:`scripts/Run-OpenEyesUiAssertion.ps1` 与 baseline `UiAssertionMode=openeyes_structured` 已落地；assertion report 按 task 写入 baseline JSON 并参与 `ui_assertion_passed` gate。下一档把 assertion JSON/历史透传 `/console/`。
- 验收:baseline matrix 加 1 条 ui_assertion_mode=openeyes_structured 模式;替代 screenshot diff 后,CI flaky 率下降;/console/ 看到 UI assertion 历史(pass / fail / reason)。
- 上下文入口:docs/OPENEYES_UI_AUTOMATION_RESEARCH.md、scripts/Run-BaselineMatrixRealWorkerSmoke.ps1、scripts/Run-BaselineMatrixGateProbe.ps1。预估 1 周,建议先开 issue 对齐 assertion contract schema 与失败可重试策略。

**Jev-Patrol-L2 · Jev × 飞书巡检派工决策级 · `help wanted`**
- 背景:`docs/JEV_PATROL_INTEGRATION_PLAN.md` §L2 已识别该切入点为 L1-L5 中**最有价值**;当前 `Start-CodexAutoPatrolLoop.ps1` 按 `nextRun / dueList` 拉起 Codex sub-process,worker 启动数与飞书通知密度全靠人 review;`auto-deploy` 项目间相互优先级判断仍是手工。
- 范围:每次 round 启动前对 dueList 每项跳 4 个 noul (progress / blocker / urgency / confidence);按 THRESHOLDS dict 路由 act / human_review / blocked / skip 四桶;只对 `act` 状态项目启动 worker pool;`human_review` 推飞书长卡片给用户,`blocked` 写入 Bitable blocker 字段。
- 验收:每轮 round 实际启动 worker 数减少 50%+;飞书通知减少 70%+;Bitable blocker 字段可被查询;`runtime_facts.jev_patrol_decisions` 记录每项目 4 noul probs + 路由 + reason;关闭 `jev.patrol_dispatcher` 时所有指标回到当前基线,回滚成本 < 1 行配置。
- 上下文入口:`docs/JEV_PATROL_INTEGRATION_PLAN.md` §L2、`docs/JEV_PATROL_INTEGRATION_PLAN.md` Phase 2-4、`docs/INITIATIVES/Jev-Patrol-L2.md`、`auto-deploy` 项目 `Start-CodexAutoPatrolLoop.ps1`(磁盘上不存在,pre-existing drift,需先恢复路径)。预估 14 天,建议先开 issue 对齐 shadow mode 灰度策略与 `TYPESAFE_API_KEY` 在 auto-deploy 侧的凭据约定。
- **决策层 contract 子路径已实施（2026-09-21 签字）**：JevPatrolDispatcher 4 noul + 4 路由的 contract test 已落 `src/test/java/com/agentcloud/scorer/JevPatrolDispatcherTest.java` (8910 B, 5/5 tests passed, 0.253s)。BLOCKED 路由短路优先于 ACT,THRESHOLDS dict 公开给 callers。详见 `docs/INITIATIVES/Jev-Patrol-L2.md` §14 + `docs/JEV_PATROL_INTEGRATION_PLAN.md` §L2。
- **输出后处理 contract 子路径已实施（2026-09-21 签字）**：JevPatrolPostprocess key_decisions extraction (5 categories: progress/blocker/decision/artifact/open_question) 的 contract test 已落 `src/test/java/com/agentcloud/scorer/JevPatrolPostprocessTest.java` (11930 B, 6/6 tests passed, 0.650s)。5-8 key_decisions 提取 + JSON 序列化往返 + confidence threshold + MAX cap。详见 `docs/INITIATIVES/Jev-Patrol-L3.md` §14 + `docs/JEV_PATROL_INTEGRATION_PLAN.md` §L3。

### 候选维护约定

- 条目来源稳定计划文档，不臆造；某条完成后标 ✅ 并保留入口，不直接删除。
- 新增候选须同时给出「验收标准」与「上下文入口」，否则不录入。
- 如新增根级 markdown，须同步跑 `scripts/Run-DocsIndexAudit.ps1` 防止 orphan。
