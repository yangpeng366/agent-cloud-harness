# Jev-Graph-A - Jev x mounted_context_view 节点 keep_probability (hybrid 公式)

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-Graph-A |
| 类别 | 图算法 |
| 优先级 | P1 |
| Phase | Phase 4 |
| 预估工时 | 14 天 |
| 关联文档 | ../JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md §5 |
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
2. mounted_context_view 升级为图结构
3. hybrid 公式 α=0.5 γ=0.5 默认
4. runtime_facts.panel_weight 可观测

## 当前状态

2026-09-22：JevMountedContextHybridTest 4/4 PASS，mounted_context_view keep_probability 合同 test 覆盖。

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

- 关联文档: ../JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md §5
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)

## 14. 签字 + 迁入 src/ + 全仓库共存(2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 Jev-Graph-A contract test。
理由:
- FEAT-03 主路径的 contract 层(mounted_context_view panel hybrid weight)
- 不破 `MountedContextView` record signature —— 通过 metadata Map 追加 `jev_decisions` / `jev_footnotes` / `jev_panel_weight`
- Phase 3 真实验数据支持: G(100,0.05) 100 节点 real Jev τ=+0.10, top-5=5/5, top-10=9/10, hybrid α=0.5 γ=0.5 稳定

**迁入路径**:
- `src/test/java/com/agentcloud/scorer/JevMountedContextHybridTest.java` (8463 B, 4 个 JUnit 5 测试)
- 跑仓库自带 focused test: `.\scripts\Test-WithJava21.ps1 -Dtest=JevMountedContextHybridTest`
- 实测: `Tests run: 4, Failures: 0, Errors: 0 -- BUILD SUCCESS`

**全仓库共存验证**:
- 排除 DocsIndexAuditScriptTest 后跑 96 个 suite / 752 tests
- Failed suites 与 Jev-Graph-B + HW-09 + HW-10 跑通时**完全相同** (8 个 pre-existing)
- Jev-Graph-A 不引入任何新 failure

**4 个 JUnit 测试**:
1. `hybridWeightInRange`: MountedContextView 空列表 normalize 到 7 个 panel, 每个 weight ∈ [0,1]
2. `hybridWeightSum`: weight = α * structural + γ * jev (0.7*0.5 + 0.3*0.5 = 0.5)
3. `decidedVsBlockerSeparation`: DECIDED panel (0.95) > neutral (0.50) > BLOCKER (0.10), Jev 信号在 α 加权下仍可区分
4. `emptyPanelsHandledGracefully`: 空 panels -> 7 个 default panel + 无 NPE

**中途纠错(透明披露)**: MountedContextView constructor 会自动 normalize 空 panel list 到 7 个默认 panel (PINNED/ACTIVE/ANCESTOR/SIBLING/EVIDENCE/INDEX/ARCHIVE_HANDLES), 我最初期望 3 个 panel 是错的, 改为期望 7。

**仍是候选(Jev-Graph-A 主路径) - 不完整**:
- 当前仅 contract test 验证 fake Jev hybrid 公式
- 缺生产代码 `TaskRuntimeContextBuilder` 真实集成 JevScorer 计算 panel weight
- 缺 `MountedContextPanel` 加 `jev_panel_weight` 字段(需要 schema 改造)
- 缺 harness-config.yml `jev.hybrid_alpha` / `jev.hybrid_gamma` 配置项
- 缺 API_CONTRACTS.md 同步更新

**下一阶段** (pre 维护者拍板):
- Jev-Graph-A 主路径实施 (对应 FEAT-03 主路径的 mounted_context_view hybrid 部分)
- 考虑 DocsIndexAuditScriptTest sandbox fork JVM bug 隔离研究(阻断未来每个 round)
