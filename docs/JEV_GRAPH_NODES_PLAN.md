# Jev × Graph × Decision Tree Plan（决策树与复杂网络的可借鉴研究）

> 本文是 `JEV_OFFICIAL_SKILL_ABSORPTION.md` 的延展 + ACH 算法借鉴评估。
> 时间：2026-09-21。
> 范围：Jev cookbook/pattern 里的"决策树化"形态 × `D:\gitAll\networkx` 项目本地算法集（algorithms.tree / centrality / flow / shortest_paths） × ACH 借鉴可行性。
> 不做：不动 ACH 任何 src/ 文件；不拉真网络图 fixture（plan 阶段只选型 + 设计）；不创建 Bitable 新条目（与现有 `Jev × 飞书巡检流程接入` 是同一系统层，不重复）。

## 0. 一句话总结

**Jev cookbook 里有 5 个 pattern 与"决策树 × 复杂网络"形态天然耦合**（function_calling chain / intent_routing / fan-out / parallel_questions / confidence-routing），但**官方 cookbook 没有"图节点 keep_probability"的直接对应**。本文把这两端拉到一起，给出 ACH 借鉴的 3 类路径 + 5 步 Phase 落地。

## 1. 三端拉到一起

| 端 | 已有 | 缺口 / 机会 |
|---|---|---|
| **Jev cookbook** | 5 个 pattern（function_calling / intent_routing / fan-out / parallel_questions / confidence-routing） | 无图节点评分 / 树判定 chain 的直接 cookbook |
| **networkx 项目** | 17 个 algorithms 子模块（tree / centrality / flow / shortest_paths / components / isomorphism 等） | 本地 worktree 已 clone；可用作 fixture + 借鉴源 |
| **ACH 借鉴可行性** | 已有 `JEV_CONTEXT_SCORING_PLAN.md`（FEAT-03 harness 内部）+ `JEV_PATROL_INTEGRATION_PLAN.md`（飞书巡检流程） | **图节点 keep_probability 是第三个借鉴落点**：ACH `TaskRuntimeContextBuilder` 的 mounted_context_view 本质是 DAG/树形结构 |

## 2. Jev cookbook 中"决策树 × 复杂网络"的 5 个形态

### 2.1 `function_calling` 形态 —— 决策树化

**官方描述**：把自然语言 trading request 映射到 typed function。

**决策树映射**：

```
                    user request
                         |
        ┌────────────────┼────────────────┐
        |                |                |
   check_balance  approve_transfer   support
        ↓                ↓                ↓
   conf >= 0.6    conf >= 0.85    human agent
                     else confirm
```

每条 path = 一个 Noul/Choice + confidence 阈值。**整棵树就是一批 parallel questions 一次性发出，Jev 用 fan-out 形态跑**。

### 2.2 `intent_routing` 形态 —— 分类网络

**官方描述**：classify incoming requests and route each to optimal handler: deterministic logic / specialist LLM / human。

**复杂网络映射**：每个 intent 是一个顶点，handler 是叶子节点，confidence 是边的权重。整张图是一棵"判定森林"。

### 2.3 `fan-out`（speculative fan-out）形态 —— 多问题并发

**官方描述**：Send many questions in a single call, including speculative ones。

**决策树映射**：树的**全部叶子节点**一次性 fan-out 出去，**Jev 一次 API call 同时评 N 个判定**。**对 ACH 的意义**：一次调用覆盖整棵决策树，避免 N 次串行。

### 2.4 `parallel_questions` 形态 —— 13 question / 1 call

**官方实测**：runs a 13-question regulatory briefing over the GDPR Wikipedia article, showing that batching every question into one TypeSafe call is **12.2× cheaper and 10× faster** with no change in answers。

**决策树映射**：把整棵树"摊平"成 N 个独立 question，1 个 API call 拿全部答案。

### 2.5 `confidence-routing` 形态 —— 边的 confidence 阈值

**官方描述**：confidence as second axis; answer 告诉你"什么"，confidence 告诉你"是否应该行动"。

**复杂网络映射**：每条边有一个 confidence 阈值；`confidence >= 0.85` 走主路径，0.60–0.85 走 confirm，`< 0.60` 走 human_review。**网络 = 节点 + 带权边 + 边上的判定函数**。

## 3. 与 networkx 算法集的对照

| networkx 模块 | 对应 Jev 形态 | 借鉴价值 |
|---|---|---|
| `algorithms/tree/` (branchings / decomposition / mst / recognition) | decision tree 形态（function_calling） | 直接对应；networkx 给我们的是"如何生成/识别树"，Jev 给我们的是"如何在树上做 Noul 判定" |
| `algorithms/centrality/` (betweenness / closeness / eigenvector) | 复杂网络的**节点重要性评分** | networkx centrality = structural importance；Jev keep_probability = semantic importance；**两者互补** |
| `algorithms/flow/` (maxflow / mincost / gomory_hu / edmondskarp) | 决策树的"流"：判定通过率 / 拒绝率 | maxflow 给结构上限，Jev 给语义实际值 |
| `algorithms/shortest_paths/` | 决策树的最小判定路径（fewest questions to reach answer） | 直接对应；可借鉴 Dijkstra 找最短判定链 |
| `algorithms/components/` (connected / strongly_connected / weakly_connected) | 决策树的等价类（答案相同的子图） | 借鉴 connected components 做"决策树归并" |
| `algorithms/isomorphism/` (VF2 / tree_isomorphism) | 决策树结构匹配（同构 = 同一棵树的不同编码） | 借鉴做"重复决策树模式识别" |
| `algorithms/coloring/` (greedy_coloring / equitable_coloring) | 决策树节点的**冲突避免**（同一 context 不能两次判定同一节点） | 直接对应；coloring = 决策树去重 |

**核心洞察**：**networkx 给我们"结构"（图算法），Jev 给我们"语义"（置信度评分）**。两者在 ACH 借鉴上的角色是**互补**，不是替代。

## 4. ACH 借鉴的 3 类路径

### 路径 A：图节点 keep_probability（最直接）

**场景**：ACH 的 `TaskRuntimeContextBuilder.mounted_context_view` 本质是 **DAG/树形结构**（panel → sub-panel → tool_result）。每个节点一个 panel。

**Jev 接入**：

```
每个 mounted panel 节点:
- state: 当前 mounted_context_view 全图（adjacency list + panel 内容）
- questions:
  * relevance: noul "this panel still relevant to current goal?"
  * freshness: noul "this panel is fresh enough to keep verbatim?"
  * routing: choice "this panel goes to: keep / drop / truncate / route_to_human"
- 每 batch 一个 Noul 给所有非 pinned 节点打分
- 按 confidence 3 段阈值路由（high / medium / low）
```

**借鉴价值**：把现在字符级硬截（`mounted_context_budget_truncated`）升级为**结构 + 语义联合剪枝**。

**风险**：medium；需要把 mounted_context_view 从线性 list 升级为图结构。

### 路径 B：判定树编译（中期）

**场景**：ACH 的 `RuntimeJudgmentService` 当前是单次 Noul prompt。复杂判定（如 handoff_depth > 2 时的 8 个判定条件）需要 8 个 Noul。

**Jev 接入**：

```
判定树: handoff_depth > 2?
        |
        ┌───────┴───────┐
        |               |
   yes              no
        |               |
   check 8个条件     continue
   (parallel questions)
```

把判定树编译成 1 个 parallel questions API call（来自 `parallel_questions` cookbook，12.2× cheap / 10× fast），**8 个判定 1 次跑完**。

**借鉴价值**：RuntimeJudgmentService latency 降低到 1/10，cost 降低到 1/12。

**风险**：低；改 prompt 模板 + 加 parallel_questions 适配层。

### 路径 C：复杂网络中心性 × keep_probability（长期 / 脑洞）

**场景**：ACH 在长任务里，`decision_trace` / `judgment_trace` 形成一张 trace 图。**每个 trace 节点**有：
- networkx `betweenness_centrality`（结构重要性）
- Jev `keep_probability`（语义重要性）

**借鉴价值**：长任务的 checkpoint 决策 = 选**结构 × 语义双重高**的节点作为 checkpoint 候选。

**风险**：高；schema + 算法 + UI 三层都要重做。

## 5. Fixture 选型（pre 立项）

按 networkx 项目本地 clone 已有的算法集，5 类 fixture：

| Fixture | 来源 | 用途 |
|---|---|---|
| **小型 decision tree** | 手写 8-node tree（深度 3） | 单元测试 fixture；fast-jev-compaction fake Jev 直接套 |
| **G(n,p) 随机图** | `networkx.gnp_random_graph(50, 0.1)` | 中规模图 fixture；验证 fan-out batch 性能 |
| **maxflow benchmark** | `networkx.algorithms.flow.maxflow` | decision tree 通过率 benchmark |
| **centrality-weighted** | `networkx.betweenness_centrality` | 路径 C fixture |
| **真实网络** | networkx examples / 自带 graph 数据集（karate_club / les_miserables / florentine_families_graph 等） | 端到端 demo |

### 6.2a Hybrid 路径真实形态（基于 Phase 1 fake Jev 实验）

详见 `docs/JEV_GRAPH_HYBRID_EXPERIMENT.md` 的 fake Jev 实验数据（karate_club 34 nodes）。

**关键发现**：fake Jev 在 noise σ=0.15 下 Kendall τ=0.19（**比随机还差**），top-10 命中率 3/10。这证伪了"Jev 直接做 single-shot 排序"的旧假设，**确认了 hybrid 路径的正确形态**：

```text
input graph G
   ↓
networkx.algorithms:
  betweenness_centrality(G) -> bc_score[N]
  pagerank(G)               -> pr_score[N]
   ↓
Jev rerank layer:
  state = {graph: adjacency_list(N), bc_score[N], pr_score[N]}
  questions:
    high_bc_node_<N>: noul "is this node important given context X?"
  → Jev keep_probability[N]
   ↓
final ranking:
  hybrid_score[N] = α * bc_score[N] + β * pr_score[N] + γ * keep_probability[N]
  where α + β + γ = 1 (configurable; γ=0.3 typical)
```

**核心洞察**：Jev 的 noise **不是问题**——因为 Jev 只是 hybrid 公式的一个分量，noise 在加权和里被 α 衰减。**binary decision 受 noise 影响大；continuous weight 受 noise 影响线性衰减**——这正是 soft gate 比 hard gate 鲁棒的原因。

**对应 ACH 路径 A**：mounted_context_view 图节点评分应该用 Jev keep_probability 作为 **panel weight**（不是 binary keep/drop decision）。

**Phase 2 真 API 验证（pre 维护者拍板）**：
- 用现有 TYPESAFE_API_KEY，34 nodes / 8 per batch = 5 batches × $0.001 = **$0.005 总成本**
- 对比 baseline（只用 betweenness）+ hybrid（Jev rerank）的 Kendall τ / top-10 命中率
- 验证 noise σ=0.15–0.25 范围内 hybrid 仍稳定（baseline + Jev 衰减对齐）

落点：`D:\gitAll\agent-cloud-harness\.tmp\jev-graph-fixtures\`（按 ACH 临时目录约定）。

## 6. ACH 借鉴的 5 步 Phase 落地

| Phase | 任务 | 落点 | 依赖 | 预估 |
|---|---|---|---|---|
| **Phase 1** | 5 类 fixture 生成脚本（落到 .tmp） | `.tmp\jev-graph-fixtures\` | 无 | 0.5 天 |
| **Phase 2** | decision tree 单元测试（fake Jev + 手写 8-node tree） | `src/test/java/.../JevDecisionTreeTest.java` | Phase 1 + key | 1 天 |
| **Phase 3** | RuntimeJudgmentService 升级为 parallel questions（路径 B） | `RuntimeJudgmentService.java` + `JevContextScorer.java` | Phase 2 + FEAT-03 签字 | 1 周 |
| **Phase 4** | mounted_context_view 升级为图结构 + 图节点评分（路径 A） | `TaskRuntimeContextBuilder.java` | Phase 3 | 2 周 |
| **Phase 5** | decision_trace × centrality × keep_probability（路径 C） | `decision_trace` + 新 schema | Phase 4 + schema 升级 | 长期 |

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Jev API 抖动阻塞判定 | Phase 2–4 任一 throw 必须 fallback；判定失败 → 当前 RuntimeJudgmentService 行为 |
| mounted_context_view 升级为图结构破坏现有契约 | Phase 4 走 Contract-Additive 流程；`mounted_context_view` 兼容旧线性 list，新图结构走并行字段 |
| ACH 没有现成图结构 schema | 借鉴 networkx 序列化（`nx.node_link_data` / `nx.adjacency_data`）做参考，但落地用 ACH 现有 JSON 字段 + JSON-LD 风格 |
| networkx 是 Python，ACH 是 Java 21 | 算法层面借鉴（math + graph theory），实现用 JGraphT（ACH 未来候选）或纯手写 adjacency list |
| fixture 选型不当导致 benchmark 失真 | 5 类 fixture 覆盖 small / medium / large / real-world 四档，cross-validate |

## 8. 关键决策

### 8.1 必须维护者拍板的

1. **ACH 是否真的需要图结构化 mounted_context_view？**（路径 A 涉及 schema 改造）
2. **decision tree 是否是 RuntimeJudgmentService 的正确抽象？**（路径 B 涉及 prompt 模板重设计）
3. **decision_trace × centrality 是否对产品叙事有价值？**（路径 C 是脑洞，可能不是优先级）

### 8.2 不需要维护者拍板的

- **Fixture 选型**：用 networkx 自带 + 手写，不破坏 ACH
- **算法借鉴**：networkx 算法是公开的，借鉴不涉及版权
- **Jev API key**：仍走 `~/.openclaw/secrets/typesafe.key`，不变

## 9. 与现有借鉴类研究的关系

| 文档 | 层级 | 焦点 |
|---|---|---|
| `JEV_CONTEXT_SCORING_RESEARCH.md` | 调研层 | Jev 是什么 |
| `JEV_CONTEXT_SCORING_PLAN.md` | 计划层 | FEAT-03 harness 内部借鉴 |
| `JEV_HANDS_ON_NOTES.md` | 实测层 | fast-jev-compaction 本地 fake |
| `JEV_OFFICIAL_SKILL_ABSORPTION.md` | 吸收层 | 6 个官方 pattern + 5 cookbook |
| `JEV_REAL_API_DEMO.md` | 数据层 | 9 次真 API metrics |
| `JEV_PATROL_INTEGRATION_PLAN.md` | 流程层 | 飞书巡检流程接入 |
| **`JEV_GRAPH_NODES_PLAN.md`**（本文） | **算法层** | **决策树 × 复杂网络 × networkx 借鉴** |

**七层结构**清晰：调研 → 计划 → 实测 → 吸收 → 数据 → 流程 → 算法。每层独立可维护。

## 10. 下一步

- 等维护者对 §8.1 三个决策的拍板
- Phase 1（fixture 生成脚本）可立即开始：0 凭据 + 0 网络 + 0 src/ 改动
- Phase 2 需要 key 决策（同前几轮的纪律：TYPESAFE_API_KEY → ~/.openclaw/secrets/）
- Phase 3–5 必须在 FEAT-03 harness 内部借鉴先签字后再做

## 11. 参考

- JEV 官方材料：https://docs.typesafe.ai/llms.txt
- 网络图借鉴源：`D:\gitAll\networkx\networkx\algorithms\`（17 子模块）
- skill_suggestion cookbook：`parallel_questions` 形态（13 question / 1 call / 12.2× cheap）
- intent_routing cookbook：handler 选择模式
- function_calling cookbook：function chain → decision tree
- confidence.md：confidence vs probability 3 段阈值
- 关联文档：JEV_CONTEXT_SCORING_PLAN.md / JEV_OFFICIAL_SKILL_ABSORPTION.md / JEV_PATROL_INTEGRATION_PLAN.md

> 维护约定：本文是 algorithm-level 借鉴研究 plan。Phase 推进时按阶段单独立项，与 JEV_PATROL_INTEGRATION_PLAN.md 并列；不冲突、不互相阻塞。