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

### E2 - 端到端验证闭环（优先级 1，最高）

已有 P2 e2e smoke 证据，但覆盖面仍窄。

计划：
- 扩展 baseline matrix 到 `medium-001` / `long-001` 场景
- 补充 codex-free 路由的端到端验证（免费模型执行 + advisory handoff 升级）
- 修复 pre-existing 测试失败（`WorkerExecutorRouterProviderNativeTest` + `WorkerPromptHeaderBuilderTest`）

验收：short/medium/long 三模式在 codex-main + codex-free 两条 lane 上都有 smoke 证据；advisory handoff（codex-free -> codex-main -> codex-free）有端到端证据；pre-existing 测试失败已修复或有 documented workaround。

贡献入口：`FEAT-02`（扩展 matrix）、`GFI-01` / `GFI-02`（修复 pre-existing 测试失败，good first issue）。

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