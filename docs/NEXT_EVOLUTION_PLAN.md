# 下一阶段演进计划

> 本文档承接 LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md 的 Phase 1/2 完成状态，规划后续演进方向。
> Phase 1（Loop/Goal/交接/UI 闭环）和 Phase 2（配置驱动 + 免费模型编排 + LLM 辅助 + Handoff Recovery）已全部落地。

## 1. 当前完成基线

| 方向 | 状态 | 验证入口 |
|------|------|----------|
| Loop 主闭环：goal -> plan -> execute -> judge -> decide | done | ControlNodeGraphOrchestrationFlowTest |
| Goal 合同：subgoal_status + goal progress 优先判断 | done | RuntimeJudgmentServiceTest + TaskServiceGoalContractTest |
| 交接 packet：Resume/Handoff 最小字段集 + cross-worker stability | done | TaskServicePacketContractTest |
| UI 状态展示：active/running/waiting_human/failed/partial/done 一致口径 | done | task-status-tone-plan.js + console-status-tone-plan.js |
| 配置驱动 Worker Lane：harness-config.yml | done | HarnessConfigLoaderTest 7 + WorkerRegistryConfigRegistrationTest 6 |
| CCX codex-free 模型映射 | done | CCX chat completions + responses API 实测 |
| harness-state.json 自动发现 | done | HarnessStateWriterTest 6 |
| LLM-assisted Subgoal Update | done | LlmSubgoalJudgmentServiceTest 11 |
| Handoff Recovery (handoff_depth) | done | HandoffDepthLimitTest 5 |
| Pi/Trae Protocol 注册 + Advisory Handoff | done | PiProtocolTest + TraeProtocolTest + AdvisoryHandoffTest |

## 2. 下一阶段方向

### E1: Loop Decide 深度消费 Goal Progress

当前 decide 已消费 subgoal_status 做 HALT/CONTINUE/ESCALATE 判断，但 goal progress 的消费仍偏浅。下一阶段：

- decide 输出显式关联 goal progress 的决策理由（不只是 action，还有 why）
- progress_summary 从计数升级为语义摘要（3/5 subgoals done, 2 blocked on API dependency）
- LLM-assisted subgoal judgment 在 decide 节点被正式消费，而不是只在 subgoal update 时触发

进度（2026-07-29）：E1.1 已落地--decide 经 buildDecisionRationale 输出显式 decision_rationale metadata（goal progress done/blocked/open + execution action + completion status/alignment -> resolved action），withMetadataEntries + sameState 持久化。验收 #1 done；#2（progress_summary 语义化）/ #3（LLM subgoal judgment 进 decide）deferred。

验收标准：
1. decide 的输出包含 decision_rationale 字段，引用 goal progress [done 2026-07-29]
2. progress_summary 包含语义描述而非纯计数
3. 至少一条端到端任务链中能看到 LLM subgoal judgment 影响 decide 输出

### E2: 端到端验证闭环

当前已有 P2 e2e smoke 证据（P2_E2E_SMOKE_EXECUTION_RECORD_2026-07-22.md），但覆盖面仍窄。下一阶段：

- 扩展 baseline matrix 到 medium-001 / long-001 场景
- 补充 codex-free 路由的端到端验证（免费模型执行 + advisory handoff 升级）
- 修复 pre-existing 测试失败（WorkerExecutorRouterProviderNativeTest + WorkerPromptHeaderBuilderTest）

验收标准：
1. short-001 / medium-001 / long-001 三模式在 codex-main + codex-free 两条 lane 上都有 smoke 证据
2. advisory handoff（codex-free -> codex-main -> codex-free）有端到端证据
3. pre-existing 测试失败已修复或有 documented workaround

### E3: UI Loop Activity 集成

当前 loop-activity-detector-plan.js 和 recovery-action-hint-plan.js 已作为独立模块落地，但尚未集成到实际 app.js。下一阶段：

- 将 loop activity detector 接入 /dialogue/ 的实时状态展示
- 将 recovery action hint 接入 /dialogue/ 的 waiting_human 状态卡
- /console/ 的 operator 读面展示 loop 活跃度趋势

验收标准：
1. /dialogue/ 页面能看到 loop active/stall/stale 状态指示
2. waiting_human 状态卡显示可执行的人工动作建议
3. /console/ operator 读面能看到 loop 活跃度

### E4: CCX 启动服务集成

当前 CCX 需要用户手动启动 Desktop 应用。下一阶段：

- harness 启动时自动检测 CCX 可达性，不可达时提示用户启动
- harness-config.yml 的 ccx.health_check_on_startup: true 触发 precheck
- CCX 渠道状态同步到 harness-state.json 的 ccxChannels

验收标准：
1. harness 启动时 CCX 不可达会输出明确提示
2. harness-state.json 反映 CCX 渠道状态
3. 用户可通过 /console/ 查看 CCX 渠道健康

### E5: 配置覆盖闭环

当前 harness-config.yml 和 harness-state.json 已落地，但合并逻辑尚未完全闭环。下一阶段：

- harness-state.json 的 providers.userEnabled 与 harness-config.yml 的 worker 声明合并
- 用户在 harness-config.yml 中禁用的 provider 不进入路由候选
- /console/ 展示当前生效的配置合并结果

验收标准：
1. harness-config.yml 中未声明的 provider 使用 harness-state.json 的自动发现结果
2. 用户显式禁用的 provider 不出现在路由候选中
3. /console/ 能看到自动发现 vs 用户配置的合并结果

## 3. 优先级排序

| 优先级 | 方向 | 理由 |
|--------|------|------|
| 1 | E2 端到端验证 | 已有功能需要真实运行证据，否则后续演进缺乏基线 |
| 2 | E3 UI Loop Activity 集成 | 已有模块未集成，是产品闭环的最后一公里 |
| 3 | E4 CCX 启动服务集成 | 降低用户启动门槛，提升日常使用体验 |
| 4 | E1 Loop Decide 深度消费 | 当前 decide 已可用，深度消费是增量优化 |
| 5 | E5 配置覆盖闭环 | 当前配置已可用，闭环是增量完善 |

## 4. 不做的事

- 不做 harness-config.yml 热重载（第一版重启生效）
- 不做 CCX 渠道状态自动同步到 worker lane（第一版只做启动时 precheck）
- 不引入新 provider 或新 IPC 协议
- 不在 harness 内复制 CCX 的路由逻辑
- 不为每个免费模型加独立 CCX 渠道（用 modelMapping 即可）

## 5. 写回顺序

- 本计划为主入口
- loop / goal 变化写 continuity/PROGRESS.md
- UI 集成变化写 dialogue/PROGRESS.md 与 WEB_CONSOLE.md
- 配置变化写 provider/PROGRESS.md 与 API_CONTRACTS.md
- 稳定取舍写 DECISIONS.md
- 跨主题摘要写 STATE.md
