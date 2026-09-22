# Jev / OpenEyes 立项表 Schema 设计（飞书 Bitable）

> 本文是 `JEV_OFFICIAL_SKILL_ABSORPTION.md` 之后的"立项工作流"层设计。
> 时间：2026-09-21。
> 范围：盘点 11 个 Jev 文档 + 3 条已写 Bitable 记录 + CONTRIBUTING.md 候选 → 设计独立 Bitable 立项表 schema + 候选映射。
> 不做：不实际创建 Bitable 表（用户 OAuth 操作留待维护者签字）；不动项目方向表（已存在 41 条记录，新表正交补充）。

## 0. 一句话总结

11 个 Jev 文档 + CONTRIBUTING.md + 3 条已写 Bitable 记录里**抽出 16 条独立可立项的候选**。每条候选需要"立项表"追踪：状态 / 负责人 / 签字 / blocker / due date —— 这是项目方向表 schema 缺的能力。**新建一张独立的 Bitable 立项表**，与项目方向表正交（方向表问"做什么"，立项表问"怎么推进"）。

## 1. 为什么需要新表（不是复用项目方向表）

### 1.1 项目方向表 schema 缺口

**当前 18 字段**：
- 项目名 / 仓库路径 / 相关文档 / GitHub / 当前阶段 / 目标 / 下一步 / 贡献类型 / 难度 / 优先级 / 简要描述
- 巡检间隔(分钟) / 巡检占用 / 假数据模式 / codex_thread_id / 父记录

**立项工作流需要的字段**（项目方向表**全部缺失**）：
- `status`（候选 → 立项 → 实施 → 验收 → 关闭）
- `owner`（谁负责）
- `signoff_date`（签字时间）
- `blocker`（卡点）
- `due_date`（截止日期）
- `linked_doc`（关联到 JEV_*.md 哪一份）
- `pr_url`（实施 PR 链接）
- `estimated_effort_days`
- `related_bitable_record_id`（关联项目方向表 entry）
- `phase`（Phase 1 / 2 / 3 / 4 / 5）
- `rollout_decision`（accept / defer / reject）

### 1.2 为什么不污染项目方向表

- 项目方向表是**"方向快照"**：当前焦点 + 巡检节奏，schema 稳 6 个月
- 立项表是**"执行工作流"**：candidate → signoff → done，状态切换频繁
- **正交关系**：一个项目方向条目（如 "agent-cloud-harness / Jev 上下文评分门控"）可以**挂多条立项记录**（FEAT-03 / HW-09 / HW-10 三条对应同一项目方向）

## 2. 立项表 Schema 设计

### 2.1 字段清单（16 字段）

| # | 字段名 | 类型 | 选项 | 必填 | 含义 |
|---|---|---|---|---|---|
| 1 | 立项编号 | text | — | ✓ | 跟 CONTRIBUTING.md 一致（FEAT-03 / HW-09 / HW-10 / FEAT-04 / ...） |
| 2 | 标题 | text | — | ✓ | 一行话（例："Jev × HandoffPacket keep-probability 过滤"） |
| 3 | 状态 | select | 候选/立项中/实施中/验收中/已关闭/已撤销 | ✓ | 立项工作流状态机 |
| 4 | 优先级 | select | P0/P1/P2/P3 | ✓ | 跟项目方向表对齐 |
| 5 | 类别 | select | harness 借鉴/巡检流程/图算法/OpenEyes 借鉴/脑洞 | ✓ | 横向分组 |
| 6 | 关联项目方向 | text | — | — | 关联的项目方向表 record_id 或名称（text 而非 link 是为避免 schema 复杂） |
| 7 | 关联文档 | text | — | — | 关联 docs/JEV_*.md / docs/OPENEYES_*.md 路径 |
| 8 | Owner | text | — | — | 负责维护者（默认 `yangpeng@sobey.com`） |
| 9 | 立项门槛 | text | — | ✓ | 1-N 条 checklist（参考 plan §11） |
| 10 | blocker | text | — | — | 当前卡点（pre-existing drift / key 待定 / 等维护者拍板） |
| 11 | signoff_date | date | — | — | 签字时间 |
| 12 | due_date | date | — | — | 期望交付日期 |
| 13 | estimated_effort_days | number | — | — | 预估工时（天） |
| 14 | phase | select | Phase 1/2/3/4/5/已完成 | — | 跟 JEV_PATROL_INTEGRATION_PLAN.md §4 Phase 1-5 对齐 |
| 15 | rollout_decision | select | accept/defer/reject | — | 最终结论（accept=实施 / defer=暂缓 / reject=不实施） |
| 16 | 备注 | text | — | — | 自由文本（关联 Bitable record_id / 飞书消息 id / 关键 notes） |

### 2.2 与现有 schema 的复用

- **复用项目方向表的 select 选项**：优先级 / 类别（部分）
- **不复用**：项目方向表没有 status / owner / signoff 字段
- **schema 演进路径**：项目方向表 schema 后续可加 `linked_initiative_record_id` 反向引用立项表（避免现在加破坏 schema）

### 2.3 与 ACH CONTRIBUTING.md 编号体系对接

**立项编号直接用 CONTRIBUTING.md 现有编号**：
- FEAT-01..05（feature 候选）
- HW-01..13（help wanted 候选）
- 未来新增 GFI-XX / 脑洞类候选

这样**候选来源唯一**（立项表不引入新编号），**状态机工作流在 Bitable**（CONTRIBUTING.md 维持纯文本）。

## 3. 候选映射（16 条 → 16 条立项记录）

### 3.1 harness 借鉴类（3 条）

| record_id（待） | 立项编号 | 标题 | 关联文档 | 关联项目方向 | Phase |
|---|---|---|---|---|---|
| TBD-001 | FEAT-03 | Jev × ACH harness 内部借鉴（mounted_context_view 评分门控） | JEV_CONTEXT_SCORING_PLAN.md | recvvNTY6alo4f | Phase 3 |
| TBD-002 | HW-09 | Jev × HandoffPacket keep-probability 过滤 | JEV_CONTEXT_SCORING_PLAN.md §1.1a | recvvNTY6alo4f | Phase 3 |
| TBD-003 | HW-10 | Jev × Judgment 前置过滤 | JEV_CONTEXT_SCORING_PLAN.md §1.1a | recvvNTY6alo4f | Phase 3 |

### 3.2 OpenEyes 借鉴类（5 条）

| record_id（待） | 立项编号 | 标题 | 关联文档 | 关联项目方向 | Phase |
|---|---|---|---|---|---|
| TBD-004 | HW-11 | OpenEyes CDP profile 持久化接入 acceptance 套件 | OPENEYES_ACCEPTANCE_DEMO_PLAN.md §1 | recvvNWh9u3lQZ | Phase 1 |
| TBD-005 | HW-12 | OpenEyes UIA native app acceptance probe | OPENEYES_ACCEPTANCE_DEMO_PLAN.md §2 | recvvNWh9u3lQZ | Phase 1 |
| TBD-006 | HW-13 | OpenEyes MCP 启动可达性 precheck | OPENEYES_ACCEPTANCE_DEMO_PLAN.md + JEV_PATROL_INTEGRATION_PLAN.md §L3 | recvvNWh9u3lQZ | Phase 2 |
| TBD-007 | FEAT-04 | OpenEyes MCP 13 tool 注册为可选 worker tool | OPENEYES_UI_AUTOMATION_RESEARCH.md | recvvNWh9u3lQZ | Phase 3 |
| TBD-008 | FEAT-05 | OpenEyes 驱动的 structured UI assertion | OPENEYES_ACCEPTANCE_DEMO_PLAN.md | recvvNWh9u3lQZ | Phase 4 |

### 3.3 巡检流程类（3 条）

| record_id（待） | 立项编号 | 标题 | 关联文档 | 关联项目方向 | Phase |
|---|---|---|---|---|---|
| TBD-009 | 脑洞 Jev-Patrol-L2 | Jev 派工决策级（每个 due 项目 4 noul 评分） | JEV_PATROL_INTEGRATION_PLAN.md §L2 | recvvP9I0XrgTJ | Phase 3 (shadow 1 周) |
| TBD-010 | 脑洞 Jev-Patrol-L3 | Jev 巡检输出后处理级（key_decisions JSON） | JEV_PATROL_INTEGRATION_PLAN.md §L3 | recvvP9I0XrgTJ | Phase 1 |
| TBD-011 | 脑洞 Jev-Patrol-L5 | Jev × Bitable schema 升级（5 个新字段） | JEV_PATROL_INTEGRATION_PLAN.md §L5 | recvvP9I0XrgTJ | Phase 5 |

### 3.4 图算法类（2 条）

| record_id（待） | 立项编号 | 标题 | 关联文档 | 关联项目方向 | Phase |
|---|---|---|---|---|---|
| TBD-012 | 脑洞 Jev-Graph-A | Jev × mounted_context_view 节点 keep_probability（hybrid 公式） | JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md §5 | recvvNTY6alo4f | Phase 4 |
| TBD-013 | 脑洞 Jev-Graph-B | Jev × RuntimeJudgmentService parallel questions 决策树化 | JEV_GRAPH_NODES_PLAN.md §4 路径 B | recvvNTY6alo4f | Phase 3 |

### 3.5 横切脑洞类（3 条）

| record_id（待） | 立项编号 | 标题 | 关联文档 | 关联项目方向 | Phase |
|---|---|---|---|---|---|
| TBD-014 | 脑洞 Jev-A | Jev × 飞书巡检假阳性减少 | JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 A | recvvP9I0XrgTJ | 长期 |
| TBD-015 | 脑洞 Jev-B | Jev × 项目健康度评分热力图 | JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 B | recvvP9I0XrgTJ | 长期 |
| TBD-016 | 脑洞 Jev-C | Jev × 项目间 keep_probability 联动网 | JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 C | recvvP9I0XrgTJ | 长期 |

## 4. 落地步骤（pre 维护者拍板）

### 4.1 必须维护者拍板

1. **是否新建 Bitable 立项表**（vs 沿用项目方向表 + 加字段）
2. **表名**：建议 "Jev / OpenEyes 立项表"；备选 "Harness 立项跟踪" / "ACH Initiative"
3. **是否一次性批量写入 16 条候选**（vs 先写入 5-8 条主候选，剩 8-11 条等 Phase 启动）

### 4.2 不需要维护者拍板

- Schema 字段（已设计好）
- 候选编号（沿用 CONTRIBUTING.md 既有 FEAT-XX / HW-XX）
- 关联项目方向表 record_id（已盘点完整）

### 4.3 否决

- **不污染项目方向表**：41 条既有记录 schema 不动
- **不重命名既有编号**：FEAT-03 / HW-09 等保持稳定

## 5. 关联到现有 11 层 Jev 文档

```
JEV_CONTEXT_SCORING_RESEARCH
JEV_CONTEXT_SCORING_PLAN              ← TBD-001/002/003
JEV_HANDS_ON_NOTES
JEV_OFFICIAL_SKILL_ABSORPTION
JEV_REAL_API_DEMO
JEV_PATROL_INTEGRATION_PLAN           ← TBD-009/010/011 + 脑洞 A/B/C
JEV_GRAPH_NODES_PLAN                   ← TBD-012/013
JEV_FEASIBILITY_FILTER
JEV_GRAPH_HYBRID_EXPERIMENT
JEV_GRAPH_HYBRID_EXPERIMENT_REAL
JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE   ← TBD-012
JEV_OPENEYES_INITIATIVE_TABLE_DESIGN  (本文) ← 12 层
```

**新增第 12 层**：立项工作流设计，把"立项"从「文档候选」升级到「Bitable 工作流」。

## 6. 下一步

- 等维护者拍板 §4.1
- 拍板后批量写入 16 条立项记录（user OAuth 必走 `lark-cli base +record-batch-create`）
- 项目方向表 41 条既有记录**不动**
- 飞书通知维护者拍板

## 7. 参考

- `CONTRIBUTING.md` "Good First Issues / Help Wanted 候选" 节（现有 FEAT-03 / HW-09-13 / FEAT-04/05 编号）
- 项目方向表 schema（base_id Vb6pb5SJ4aWpEpsqPxvcR5Tqnte / table_id tbl2XGd8bO10NBY5）
- 11 个 Jev 文档（候选来源）
- `JEV_PATROL_INTEGRATION_PLAN.md` §4 Phase 1-6（巡检流程类 Phase 起点）
- `JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md` §5（hybrid 默认配置 α=0.5 γ=0.5）

> 维护约定：本文是"立项工作流 schema 设计"，不创建 Bitable 表；维护者签字后另起 `JEV_OPENEYES_INITIATIVE_TABLE_CREATION.md` 记录创建步骤。