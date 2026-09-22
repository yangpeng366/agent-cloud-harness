# Jev-Patrol-L2 - Jev x 派工决策级(4 noul per due 项目)

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-Patrol-L2 |
| 类别 | 巡检流程 |
| 优先级 | P2 |
| Phase | Phase 3 (shadow 1 周) |
| 预估工时 | 14 天 |
| 关联文档 | ../JEV_PATROL_INTEGRATION_PLAN.md §L2 |
| 关联 Bitable 项目方向 | recvvP9I0XrgTJ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 候选 (合同 test 已通过)

## 立项门槛 (gating thresholds)

1. 巡检启动前对 dueList 每项跳 4 个 noul
2. 按 THRESHOLDS dict 路由 act/human_review/blocked/skip
3. shadow 1 周, failure 回退到现状
4. TYPESAFE_API_KEY 是否给 auto-deploy 用

## 当前状态

2026-09-22：JevPatrolDispatcherTest 5/5 PASS，派工决策级合同 test 覆盖。

## blocker

auto-deploy 本身需要独立决策; Start-CodexAutoPatrolLoop.ps1 磁盘上不存在 (pre-existing drift)

## sign-off checklist

- [ ] 维护者确认立项门槛全部满足
- [ ] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放
- [ ] CONTRIBUTING.md 该编号状态从候选切换到进行中
- [ ] Bitable 项目方向表 recvvP9I0XrgTJ 状态同步
- [ ] 实施 PR 链接记录到本 card

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_PATROL_INTEGRATION_PLAN.md §L2
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)

## 14. 签字 + 迁入 src/ + 全仓库共存(2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 Jev-Patrol-L2 contract test。
理由:
- L2 高价值切入点:巡检派工决策级,worker pool 启动数 -50%+,飞书通知 -70%+
- Contract 层定义 4 noul (progress/blocker/urgency/confidence) + 4 路由 (ACT/HUMAN_REVIEW/BLOCKED/SKIP) + THRESHOLDS dict 暴露给 callers
- BLOCKED 路由优先于 ACT (blocker 短路),符合 docs/JEV_PATROL_INTEGRATION_PLAN.md §L2 step 3 路由语义
- 不破现有 Start-CodexAutoPatrolLoop.ps1 (磁盘上不存在 pre-existing drift) —— contract test 只验证 decision logic,shadow 模式接入由后续 Phase 3 维护者拍板

**迁入路径**:
- `src/test/java/com/agentcloud/scorer/JevPatrolDispatcherTest.java` (8910 B, 5 个 JUnit 5 测试)
- 跑仓库自带 focused test: `.\scripts\Test-WithJava21.ps1 -Dtest=JevPatrolDispatcherTest`
- 实测: `Tests run: 5, Failures: 0, Errors: 0, Time elapsed: 0.253 s -- BUILD SUCCESS`

**全仓库共存验证**:
- 排除 DocsIndexAuditScriptTest 后跑全仓库
- TOTAL: tests=753 failures=8 errors=2 (vs prior baseline 752/10/3: failures 减 2, errors 减 1,**净改善**)
- Failed suites 与 Jev-Graph-A 跑通时一致 (ProviderProtocolDiscoveryTest 1 fail + CodexAppServerWorkerExecutorTest 3 fail + 其他 pre-existing)
- Jev-Patrol-L2 不引入任何新 failure

**5 个 JUnit 测试**:
1. `actRouteHighProgressHighUrgency`: progress=0.9 urgency=0.9 blocker=0.1 -> ACT (推 Codex worker round)
2. `blockedOverridesAct`: progress=0.9 urgency=0.9 blocker=0.9 -> BLOCKED (blocker 短路优先于 ACT)
3. `humanReviewLowProgressLowConfidence`: progress=0.1 confidence=0.1 -> HUMAN_REVIEW (推飞书长卡片, 不推 round)
4. `ambiguousSkips`: progress=0.5 urgency=0.5 confidence=0.5 -> SKIP (下一轮再说)
5. `dispatchDueListIndependentRouting`: 5 个项目 dueList 各走 4 种路由, 验证独立性

**Contract 设计要点**:
- `THRESHOLDS` Map 公开 (act.progress_min=0.7, act.urgency_min=0.7, human_review.progress_max=0.3, human_review.confidence_max=0.3, blocker.min=0.7)
- `Route` enum (ACT / HUMAN_REVIEW / BLOCKED / SKIP)
- `PatrolDecision` record 携带 projectName + route + 4 noul probs + blockerPrecedenceTriggered + reason 字符串 (可写入 Bitable blocker 字段 + 长卡片 reason)
- `route(projectName, noulProbs)` 与 `dispatchDueList(projectNames, perProjectProbs)` 两个 pure functions,便于 shadow mode 接入 auto-deploy 后续 Phase 3

**仍是候选(Jev-Patrol-L2 主路径) - 不完整**:
- 当前仅 contract test 验证 decision logic (5 个测试, fake Jev)
- 缺生产代码 `JevPatrolDispatcher` 类落 src/main/java/com/agentcloud/patrol/
- 缺 `Start-CodexAutoPatrolLoop.ps1` 接入点(磁盘上不存在 pre-existing drift,需先恢复路径)
- 缺 shadow mode 1 周灰度机制(失败回退到现状)
- 缺 Bitable blocker 字段写入 + 飞书长卡片模板
- 缺 harness-config.yml `jev.patrol_dispatcher` 配置段

**下一阶段** (pre 维护者拍板):
- Jev-Patrol-L2 主路径实施 (Phase 3 shadow mode 1 周)
- 配合 auto-deploy 维护者先恢复 `Start-CodexAutoPatrolLoop.ps1` 路径
- 决定 TYPESAFE_API_KEY 是否给 auto-deploy 用 (立项门槛 4)
