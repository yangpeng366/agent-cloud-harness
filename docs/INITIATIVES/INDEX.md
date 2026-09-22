# Initiatives Index（Jev / OpenEyes 立项跟踪总览）

> 本目录是 `docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md` 的"本地落仓"实现。
> 时间：2026-09-21。
> 落仓决策：本地落仓 + 引用到项目方向（不新建 Bitable 表），schema 已稳定后再迁飞书。
> 范围：17 条独立可立项的候选，每条一个 candidate card（2026-09-21 Jev-ToolRecall-Filter 真 Jev 实测 precision@5=100% 立项门槛达成，新增第 17 条）。

## 0. 一句话总结

17 条 Jev / OpenEyes 立项候选从「文档散落」升级到「本地立项跟踪目录」，每条都有独立 card（含状态机 / 立项门槛 / 关联文档 / Bitable record_id / sign-off checklist）。3 条项目方向表 Jev 关联记录已反向引用本目录（recvvNTY6alo4f / recvvNWh9u3lQZ / recvvP9I0XrgTJ）。

## 1. 状态机

每个候选 card 都有 `status` 字段，状态切换：

```
候选 (candidate) ── 维护者签字 ──→ 立项中 (signed off) ── 开始实施 ──→ 实施中 (in progress) ── 实施 PR ──→ 验收中 (review) ── 验收通过 ──→ 已关闭 (closed)
                            │                                        │                                 │
                            └─ 撤回 ──→ 已撤销 (cancelled)              └─ 阻塞 ──→ 阻塞中 (blocked)         └─ 验收未过 ──→ 候选
```

## 2. 17 条候选总览

### 2.1 harness 借鉴类（4 条）

| 编号 | 状态 | 标题 | 关联项目方向 | Phase |
|---|---|---|---|---|
| `FEAT-03` | 验收通过 | Jev × ACH harness 内部借鉴 | recvvNTY6alo4f | Phase 3 |
| `HW-09` | 候选 (test 通过) | Jev × HandoffPacket keep-probability 过滤 | recvvNTY6alo4f | Phase 3 |
| `HW-10` | 候选 (test 通过) | Jev × Judgment 前置过滤 | recvvNTY6alo4f | Phase 3 |
| `Jev-ToolRecall-Filter` | 候选 (threshold 1+2 达成) | Jev × tool/agent 并发召回 → context 初筛门控 | recvvNTY6alo4f | Phase 3 |

### 2.2 OpenEyes 借鉴类（5 条）

| 编号 | 状态 | 标题 | 关联项目方向 | Phase |
|---|---|---|---|---|
| `HW-11` | 验收通过 | OpenEyes CDP profile 持久化接入 acceptance 套件 | recvvNWh9u3lQZ | Phase 1 |
| `HW-12` | 验收通过 | OpenEyes UIA native app acceptance probe | recvvNWh9u3lQZ | Phase 1 |
| `HW-13` | 验收通过 | OpenEyes MCP 启动可达性 precheck | recvvNWh9u3lQZ | Phase 2 |
| FEAT-04 | 验收通过 | OpenEyes MCP 13 tool 注册为可选 worker tool | recvvNWh9u3lQZ | Phase 3 |
| FEAT-04 path B | 验收通过 | OpenEyes MCP 13 tool e2e + runtime_facts.openeyes_tool_calls | recvvNWh9u3lQZ | Phase 3 |
| `FEAT-05` | 实施中 (baseline run 已通过) | OpenEyes 驱动的 structured UI assertion | recvvNWh9u3lQZ | Phase 4 |

### 2.3 巡检流程类（3 条）

| 编号 | 状态 | 标题 | 关联项目方向 | Phase |
|---|---|---|---|---|
| `Jev-Patrol-L2` | 候选 (test 通过) | Jev 派工决策级 | recvvP9I0XrgTJ | Phase 3 |
| `Jev-Patrol-L3` | 验收通过 | Jev 巡检输出后处理级 | recvvP9I0XrgTJ | Phase 1 |
| `Jev-Patrol-L5` | 候选 | Jev × Bitable schema 升级（5 个新字段） | recvvP9I0XrgTJ | Phase 5 |

### 2.4 图算法类（2 条）

| 编号 | 状态 | 标题 | 关联项目方向 | Phase |
|---|---|---|---|---|
| `Jev-Graph-A` | 候选 (test 通过) | Jev × mounted_context_view 节点 keep_probability | recvvNTY6alo4f | Phase 4 |
| `Jev-Graph-B` | 候选 (test 通过) | Jev × RuntimeJudgmentService parallel questions 决策树化 | recvvNTY6alo4f | Phase 3 |

### 2.5 横切脑洞类（3 条）

| 编号 | 状态 | 标题 | 关联项目方向 | Phase |
|---|---|---|---|---|
| `Jev-A` | 候选 | Jev × 飞书巡检假阳性减少 | recvvP9I0XrgTJ | 长期 |
| `Jev-B` | 候选 | Jev × 项目健康度评分热力图 | recvvP9I0XrgTJ | 长期 |
| `Jev-C` | 候选 | Jev × 项目间 keep_probability 联动网 | recvvP9I0XrgTJ | 长期 |

## 3. 维护约定

- 每条候选对应一个 `<编号>.md` 文件（例：`FEAT-03.md`）
- 修改 card 时**只改对应文件**，INDEX.md 通过链接聚合
- 状态切换在 card 的 YAML frontmatter + 文末"当前状态"小节同时更新
- 关联 Bitable 项目方向表 record_id 通过软引用（避免双向 schema 依赖）
- 凭据纪律延续 AGENTS.md §凭据纪律：key 不进任何文件

## 4. 与 12 层 Jev 文档的关系

```
JEV_OPENEYES_INITIATIVE_TABLE_DESIGN  ← Schema 设计（飞书表方案）
INITIATIVES/  ← 本地落仓（每条 card）  ← 本轮新增
  INDEX.md       16 条总览
  FEAT-03.md     单条 card
  HW-09.md ...
  ...
```

## 5. 下一步

- 维护者拍板某条候选 → 改对应 card 的 `status` 字段
- 16 条全部 `accept` 后，可以一次性迁 Bitable
- 本地目录保持是 git 跟踪状态，**不会**因飞书表 schema 调整而漂移
