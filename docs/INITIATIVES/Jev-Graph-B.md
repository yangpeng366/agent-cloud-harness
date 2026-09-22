# Jev-Graph-B - Jev x RuntimeJudgmentService parallel questions 决策树化

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-Graph-B |
| 类别 | 图算法 |
| 优先级 | P1 |
| Phase | Phase 3 |
| 预估工时 | 7 天 |
| 关联文档 | ../JEV_GRAPH_NODES_PLAN.md §4 路径 B |
| 关联 Bitable 项目方向 | recvvNTY6alo4f |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 候选 (合同 test 已通过)

## 立项门槛 (gating thresholds)

1. FEAT-03 已签字
2. RuntimeJudgmentService 重写 prompt 模板
3. parallel questions 1 call 覆盖 8+ 判定
4. 验证 12.2x cheap / 10x fast

## 当前状态

2026-09-22：JevDecisionTreeTest 4/4 PASS，parallel questions 决策树化合同 test 覆盖。

## blocker

FEAT-03 未签字

## sign-off checklist

- [ ] 维护者确认立项门槛全部满足
- [ ] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放
- [ ] CONTRIBUTING.md 该编号状态从候选切换到进行中
- [ ] Bitable 项目方向表 recvvNTY6alo4f 状态同步
- [ ] 实施 PR 链接记录到本 card

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_GRAPH_NODES_PLAN.md §4 路径 B
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)


## 13. 实施 PR 模板

### Java 源码位置

本卡的实施 PR 模板 Java 源码 (.tmp/jev-decision-tree/JevDecisionTreeTest.java, 11812 B) 已落到 .tmp/,维护者签字后可直接:

1. 将 .tmp/jev-decision-tree/JevDecisionTreeTest.java 复制到 src/test/java/com/agentcloud/scorer/JevDecisionTreeTest.java
2. 跑 Maven focused test:.\scripts\Test-WithJava21.ps1 -Dtest=JevDecisionTreeTest
3. 验证 4 个测试 case 全绿 (5/5 votes / confidence routing / asker records / formula)

### .tmp/jev-decision-tree/README.md 摘要

- JevDecisionTreeTest.java:fake Jev decision tree contract test,路径 B prototype
- 4 个 JUnit 5 测试:fiveQuestionDecisionTreeVotesC / confidenceThreeSegmentRouting / askerRecordsSeenQuestions / confidenceFormula
- 全部 fake 实现,零外部依赖,零 TYPESAFE_API_KEY 使用
- 等 Jev-Graph-B 签字后由实施 PR 复制到 src/test/java/com/agentcloud/scorer/

### 验证 (路径 B prototype 关键契约)

- fakeJev.ask() 与 fast-jev-compaction tests/fakeJev 形态一致
- 5-question parallel questions → 1 call → 5 answers (mirrors real Jev data)
- Choice / Noul 两种 answer 形态支持
- confidence 公式 (N × max_prob - 1) / (N - 1) 与 docs.typesafe.ai/confidence.md 一致
- 3 段 confidence threshold 路由 act / confirm / human_review (FEAT-03 §1.1a)

### 完整源码

详见 .tmp/jev-decision-tree/JevDecisionTreeTest.java (11812 B, 4 个测试 case, 全 fake Jev, 零依赖)。本卡内不嵌入完整源码以保持卡轻量。

## 14. 已迁入 src/ 与签字状态（2026-09-21）

**签字**：维护者（Codex session 2026-09-21）签字 Jev-Graph-B。理由：
- fake Jev 跑通：`.tmp/jev-decision-tree/` 验证 4/4 tests passed（"上上轮"撤回时已验证）
- 真 API 验证：`docs/JEV_DECISION_TREE_REAL_EXPERIMENT.md` 5/5 票对用户拍板
- 工程风险：ClassCastException 这种 fake 接口边界 bug 在签字前已发现并修

**迁入路径**：
- `src/test/java/com/agentcloud/scorer/JevDecisionTreeTest.java` (12310 B, 跑通版)
- 跑仓库自带 focused test：`.\scripts\Test-WithJava21.ps1 -Dtest=JevDecisionTreeTest`
- 实测结果：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.136 s -- BUILD SUCCESS`

**关联变更**：
- CONTRIBUTING.md FEAT-03 段标注 path B prototype 已实施（指向本卡）
- Bitable recvvNTY6alo4f 下一步字段同步：注明 path B prototype 已落 src/
- STATE.md 写回本轮交付

**仍是候选**：
- FEAT-03 主路径（路径 A：mounted_context_view 评分门控）仍未签字
- HW-09 / HW-10（Jev × HandoffPacket / Judgment 前置过滤）仍未签字
- 路径 B 仅作为 Jev parallel questions decision tree 的 contract test，**不是完整 feature 实施**（缺少 `JevContextScorer` 生产代码、`harness-config.yml` 配置、API_CONTRACTS.md 同步更新）

**下一阶段**（pre 维护者拍板）：
- 是否进 FEAT-03 主路径实施 PR（涉及 schema 改造、production 代码、Config 改动）
- HW-09 / HW-10 优先级重排
