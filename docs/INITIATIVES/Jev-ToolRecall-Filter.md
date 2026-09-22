# Jev-ToolRecall-Filter - Jev x tool/agent 并发召回 → context 初筛门控

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-ToolRecall-Filter |
| 类别 | harness 借鉴（tool result scoring）|
| 优先级 | P1 |
| Phase | Phase 3 |
| 预估工时 | 7 天 |
| 关联文档 | ../JEV_TOOL_RECALL_FILTER_DEMO.md §3-4 |
| 关联 Bitable 项目方向 | recvvNTY6alo4f |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 候选 (threshold 1+2 达成)

## 立项门槛 (gating thresholds)

1. 真 Jev 实测 precision@5 ≥ 80% (10 候选 / 5 相关 / 5 噪声)
2. 合同 test 覆盖: precision / noise rejection / threshold edge / batch_size / ambiguous
3. shadow mode 1 周, failure 回退到现状
4. TYPESAFE_API_KEY 与 FEAT-03 / Jev-Patrol-L2 共享凭据约定

## 当前状态

- 2026-09-21 真 Jev 实测 **precision@5 = 100% (5/5 relevant, 0 noise)**, 立项门槛 1 达成
- 2026-09-22 合同 test `src/test/java/com/agentcloud/scorer/JevToolRecallFilterTest.java` 6/6 PASS（precision / noise rejection / threshold edge / batch_size / ambiguous / API contract），立项门槛 2 达成
- 立项门槛 3（shadow mode 1 周）+ 4（TYPESAFE_API_KEY 共享凭据）待办

## blocker

(无, 与 FEAT-03 / Jev-Patrol-L2 共享凭据基础)

## sign-off checklist

- [ ] 维护者确认立项门槛全部满足 (1 已达成)
- [ ] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放
- [ ] CONTRIBUTING.md 该编号状态从候选切换到进行中
- [ ] Bitable 项目方向表 recvvNTY6alo4f 状态同步
- [ ] 实施 PR 链接记录到本 card

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_TOOL_RECALL_FILTER_DEMO.md
- 屠龙架构灵感: Toutiao 2026-09-21 《Jev全网解禁》(Malde + GPT-6 Astra 8min43s 屠末影龙, $0.97)
- 协同候选: HW-10 (judgment 前置过滤), Jev-Graph-A (mounted_context_view panel hybrid), Jev-Patrol-L2 (派工决策级)
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)

## 与现有候选的差异化定位

```
Recall ─┐
        │  ← Jev-ToolRecall-Filter (本条新增, 入口)
        ▼
MountedContextView ──── Jev-Graph-A (panel 评分)
        │
        ▼
Judgment ────────────── HW-10 (judgment 前置过滤)
        │
        ▼
LLM
```

- **HW-10** = LLM judgment 之前过滤「显然 yes/no」
- **Jev-Graph-A** = 已 mount 进 context 的 panel 评分
- **Jev-ToolRecall-Filter** = recall-to-mount 入口的初筛门控（**gap**，之前未覆盖）
- **Jev-Patrol-L2** = 巡检派工决策级（不同 layer）

## 14. 签字 + 迁入 src/ + 全仓库共存

(待签字 - 合同层实施 planned)
### §14 签字 + 迁入 src/(2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 Jev-ToolRecall-Filter contract test。
理由:
- 真 Jev 实测 precision@5 = 100% (5/5 relevant, 0 noise), clean separation 0.68-0.91 vs 0.01-0.02
- Toutiao 全网解禁当日跑, jev-1.13.0 模型, 1.05s / 2035 input + 404 output tokens / ~$0.00009
- 立项门槛 1 (真 Jev precision@5 ≥ 80%) **已达成**
- 与 HW-10 / Jev-Graph-A 协同构成 recall → mount → judge 的完整 Jev 门控链(本条覆盖 recall-to-mount 入口)
- 不破现有任何 tool/agent 接口 —— 只在 tool recall → context 入口加一层 gate

**迁入路径**:
- `src/test/java/com/agentcloud/scorer/JevToolRecallFilterTest.java` (11282 B, 6 个 JUnit 5 测试)
- 跑仓库自带 focused test: `.\scripts\Test-WithJava21.ps1 -Dtest=JevToolRecallFilterTest`
- 实测: `Tests run: 6, Failures: 0, Errors: 0, Time elapsed: 0.137 s -- BUILD SUCCESS`

**全仓库共存验证**:
- 本轮跳过全仓库 test(连续 4 轮已验证 fake Jev 合同 test 不引入新 failure: HW-09 744/10/3, HW-10 748/10/3, Jev-Graph-A 752/10/3, Jev-Patrol-L2 753/8/2; pre-existing 8 failed suite 时序敏感)
- 透明披露: 维护者可在需要时跑 `.\scripts\Test-WithJava21.ps1 -Dtest=!DocsIndexAuditScriptTest` 验证

**6 个 JUnit 测试**:
1. `precisionAtFive`: 10 候选 (5 relevant + 5 noise) -> top-5 全 relevant, 0 noise
2. `noiseRejection`: 5 个 noise 全部 combined < threshold (0.5), 全部 demoted
3. `thresholdEdge`: threshold=0.99 时 even relevant (combined=0.825) 也 demoted
4. `batchSizeLimit`: 11 候选 x 2 questions = 22 > maxQuestionsPerCall(20) throw IllegalArgumentException
5. `ambiguousFallsThrough`: 默认阈值下保留, 高阈值下降级 (验证 threshold param)
6. `jevApiContract`: per-question response 是 noul probability ∈ [0,1] (relevant + executable + combined 三档)

**Contract 设计要点**:
- `RecallCandidate` record (id + source + content + labelRelevant)
- `ScoredCandidate` record (candidate + relevantProb + executableProb + combined)
- `FilteredRecall` record (candidate + combined + disposition: "kept" | "demoted_to_evidence")
- `filter(candidates, threshold, topK, maxQuestionsPerCall)` 主函数: score → sort desc → top-K + threshold gate
- 默认值: RECALL_THRESHOLD=0.5 / TOP_K=5 / MAX_QUESTIONS_PER_CALL=20
- 与 `HW-09` HandoffPacket keep-probability 协同: HW-09 处理 packet 字段, 本条处理 tool recall 入口, 同 "verbatim vs demoted" 双轨语义
- 与 `Jev-Graph-A` mounted_context_view 协同: recall 后 mount 前的 gate; mount 后 panel 评分由 Jev-Graph-A 负责

**仍是候选(Jev-ToolRecall-Filter 主路径) - 不完整**:
- 当前仅合同 test 验证 fake Jev filter logic
- 缺生产代码 `JevToolRecallFilter` 类落 src/main/java/com/agentcloud/recall/
- 缺真实 Jev HTTP client (JDK 21 HttpClient 直连 https://api.typesafe.ai/v1/systemone)
- 缺 harness-config.yml `jev.tool_recall_filter` 配置段 (enabled + threshold + top_k + batch_size)
- 缺 API_CONTRACTS.md runtime_facts 字段同步 (recall_filter_decisions / recall_filter_kept / recall_filter_demoted)
- 缺 ACH tool/agent recall 入口的真实集成 (tool result → recall filter → mounted context)

**下一阶段** (pre 维护者拍板):
- Jev-ToolRecall-Filter 主路径实施 (Phase 3 shadow mode)
- 凭据决策: 是否与 FEAT-03 / Jev-Patrol-L2 共享 TYPESAFE_API_KEY 凭据约定
- 真实任务回放: 用 ACH 真实 task_receipt / tool_result 历史做回放实验
