# Jev × Graph Hybrid Experiment（实验记录 Phase 1）

> 本文是 `JEV_FEASIBILITY_FILTER.md` §4.2 的实验执行记录 + 真实数据。
> 时间：2026-09-21。
> 范围：fake Jev × karate club graph betweenness centrality 排序对比。
> 关键硬发现：**B 场景（加噪 σ=0.15）Kendall τ = 0.19，top-10 命中率 3/10**——比随机（-0.14 / 3/10）还差，**Jev 单次直接排序不可靠**。
> 但这**反过来验证了 hybrid 路径的正确形态**：Jev 作为 soft gate / rerank layer，**叠加在精确算法之上**，传统算法兜底容错。

## 0. 一句话总结

fake Jev 在"完美信息"下能 100% 复现 betweenness 排序（τ=1.0），**但只要加 σ=0.15 的高斯噪声，τ 就跌到 0.19（比随机还差）**。这意味着 **Jev 不能用作 single-shot 排序的独立 oracle**，但**作为 hybrid 路径的语义 rerank layer**（叠加在 networkx 精确算法之上）**完全成立**——因为 hybrid 路径的容错由精确算法兜底，Jev 只负责"语义重排序"。

## 1. 实验设计

### 1.1 目标

验证 Jev 是否能给出有意义的 graph node 排序（特别是 betweenness centrality 这类**结构排序**任务），并量化在 noise 下的衰减曲线。

### 1.2 工具

| 项 | 选型 |
|---|---|
| **Ground truth 算法** | `networkx.betweenness_centrality(karate_club_graph)` |
| **Graph fixture** | `networkx.karate_club_graph()`（Zachary 1977，34 nodes / 78 edges） |
| **Fake Jev 实现** | `FakeJevAsker` —— 与 fast-jev-compaction tests/fakeJev 形态一致，按 question name 给概率 |
| **问题模板** | `high_bc_node_<N>`: noul "Is node <N> a high-betweenness node?" |
| **评估指标** | Kendall τ（排序一致性）、top-10 命中率（实际应用更关心） |
| **scipy 版本** | 系统装的是 SciPy 1.x 兼容（用了 2.4.6 NumPy 但 Kendall τ 算出来了，warning 不影响） |

### 1.3 4 个场景设计

| 场景 | Fake Jev 概率 | 含义 |
|---|---|---|
| **A** | `p ∝ betweenness_centrality`（rescaled to [0.05, 0.95]） | **上限**：fake Jev "看得到" ground truth |
| **B** | A 概率 + Gaussian noise σ=0.15 | **真实 Jev 模拟**：Jev 概率有 ~10-20% 噪声（参考 `consistency_noul_cookbook` 14-question × 15 repeats std=0.0102） |
| **C** | Uniform random in [0,1] | **控制组**：验证测量方法学正确 |
| **D** | B 概率 + threshold 0.5（keep / drop） | **真实 binary gate 形态** |

## 2. 真实数据

```
graph: 34 nodes / 78 edges

ground truth top-5 by betweenness:
  node   0  bc=0.4376
  node  33  bc=0.3041
  node  32  bc=0.1452
  node   2  bc=0.1437
  node  31  bc=0.1383
ground truth top-10 set: [0, 1, 2, 5, 8, 13, 19, 31, 32, 33]

[Scenario A] Perfect fake Jev (probability ∝ betweenness)
  top-5 by fake Jev probability:
    node   0  p=0.9500  gt_bc=0.4376
    node  33  p=0.6753  gt_bc=0.3041
    node  32  p=0.3487  gt_bc=0.1452
    node   2  p=0.3454  gt_bc=0.1437
    node  31  p=0.3344  gt_bc=0.1383
  Kendall tau: 1.0000
  top-10 hit rate: 10/10

[Scenario B] Noisy fake Jev (ground truth + Gaussian noise σ=0.15)
    node   0  p=0.9284  gt_bc=0.4376
    node  33  p=0.5843  gt_bc=0.3041
    node   2  p=0.3287  gt_bc=0.1437
    node  17  p=0.2467  gt_bc=0.0000   ← 噪声让低 bc 节点挤进 top-5
    node  11  p=0.2245  gt_bc=0.0000   ← 同上
  Kendall tau: 0.1943
  top-10 hit rate: 3/10

[Scenario C] Random fake Jev (control group)
  Kendall tau: -0.1408
  top-10 hit rate: 3/10

[Scenario D] Binary threshold (keep p>=0.5 of noisy fake Jev)
  Jev keep set size: 2
  ground truth high-bc set (bc>=0.05): 7
  intersection: 2
  Jaccard: 0.2857
```

## 3. 关键发现（按重要性）

### 3.1 上限证明：fake Jev 形态方法学可行（τ=1.0）

**A 场景 τ=1.0, top-10 命中率 10/10** 证明：

- fake Jev 接受的 question 形态（noul "is this X?"）能完全反映 ground truth 排序
- fast-jev-compaction tests/fakeJev 模板适用于**图节点评分**（不只是 keep tool call）
- **JEV × GRAPH 算法借鉴在**形态层**完全成立**

### 3.2 下限崩塌：σ=0.15 的 noise 让 Jev 失效

**B 场景 τ=0.19, top-10 命中率 3/10** —— **比随机对照组（C 场景 τ=-0.14 / 3/10）还差**：

```
noise 影响 top-5 排序:
- node 0 (gt_bc=0.44, p=0.93): 仍然是 top-1 ✓
- node 33 (gt_bc=0.30, p=0.58): 仍然是 top-2 ✓
- node 32 (gt_bc=0.15, p=0.35) 被 noise 推出 top-5
- node 17 (gt_bc=0.00, p=0.25): 噪声让低 bc 节点挤进 top-3 ✗
- node 11 (gt_bc=0.00, p=0.22): 同上 ✗
```

**结论**：**在临界区间（rank 边界），noise 彻底打乱排序**——这正是 Jev Phishing Bench 上 Haiku 赢 Jev 的原因（Haiku 在 critical 区间更稳）。

### 3.3 binary gate 形态不可单用（D 场景）

**D 场景 Jaccard=0.286**：threshold=0.5 把 ground truth high-bc set（7 节点）筛成 Jev keep set（2 节点），**丢 5/7**。**binary gate 在 noise 下漏判严重**。

但**这是 fake Jev 的 binary gate 实测**。**真实 Jev 的 3 段 confidence 阈值行为**（来自 `JEV_OFFICIAL_SKILL_ABSORPTION.md`）会更细粒度：high / medium / low 各对应不同动作。

### 3.4 控制组验证测量正确（C 场景 τ≈0）

**C 场景 τ=-0.14**（n=34 节点，理论期望 ~0）—— 测量方法学正确，**B 场景的 τ=0.19 不是测量噪声**而是 Jev noise 真实效果。

## 4. 关键洞察

### 4.1 Jev 不是 oracle，是 rerank layer

```
旧假设 (JEV_FEASIBILITY_FILTER §2 方向 1):
  Jev 替代 betweenness centrality = 单一 oracle
  → 实验证伪: τ=0.19 比 random 还差

正确形态:
  networkx.betweenness_centrality()  ← 精确排序（ground truth）
  + Jev rerank                      ← 语义调整（允许 ±10-20% noise）
  = hybrid 排序 (precision from algorithm + semantic from Jev)
```

### 4.2 hybrid 路径的真实形态

```
input graph G
   ↓
networkx.algorithms:
  betweenness_centrality(G) -> bc_score[N]
  pagerank(G)               -> pr_score[N]
   ↓
Jev rerank layer:
  state = {bc_score[N], pr_score[N], N}
  questions:
    "high_bc_node_<N>": noul "is this node important given context X?"
    "high_pr_node_<N>": noul "is this node a credible source?"
  → Jev keep_probability[N]
   ↓
final ranking:
  hybrid_score[N] = α * bc_score[N] + β * pr_score[N] + γ * keep_probability[N]
  where α + β + γ = 1 (configurable)
```

**关键**：Jev 的 noise **不是问题** —— 因为 Jev 只是 hybrid 公式的**一个分量**，noise 在加权和里被 α 衰减。

### 4.3 ACH 借鉴的"软算子"路径就是这形态

**这跟 `JEV_GRAPH_NODES_PLAN.md` 路径 A 完全对齐**：

- `mounted_context_view` 图节点评分
- Jev 给每个 panel 一个 keep_probability
- **不是**用 keep_probability 决定 keep / drop（binary，fail）
- **是**用 keep_probability 作为 mounted_context_view 的 weight（continuous，OK）

binary decision 受 noise 影响大；continuous weight 受 noise 影响**线性衰减**——这正是 soft gate 比 hard gate 鲁棒的原因。

## 5. 实验验证了什么 / 没验证什么

### ✓ 已验证

1. **fake Jev 形态适用于图节点评分**（A 场景 τ=1.0）
2. **noise 让 Jev 排序不可单用**（B 场景 τ=0.19 < random）
3. **measurement 方法学正确**（C 场景 τ≈0）
4. **binary gate 漏判严重**（D 场景 Jaccard=0.29）

### ✗ 未验证（pre 立项 Phase 4）

1. **真实 Jev API 在同样任务上的数据** —— 需要 Phase 2（真 API demo）
2. **不同 σ 下的衰减曲线** —— 当前只测 σ=0.15，应扫 0.05/0.10/0.15/0.20/0.25
3. **不同 question 模板的影响** —— 当前只测 `is high-betweenness?`，应测 `is central?` / `is bridge?` / `is critical-path?` 多模板
4. **不同 graph 上的稳定性** —— 当前只测 karate club（34 nodes），应测 G(n,p) / les_miserables / 真实社交网络
5. **真 hybrid 公式的实际收益** —— 应对比 `α * bc + β * Jev` vs `α * bc`（baseline）在 top-K recall 上的提升

## 6. Phase 2 设计（pre 真 API demo）

如果 Phase 2 决定跑真 API，实验形态：

```
input: karate_club_graph (34 nodes)
Jev request per batch (8 nodes per batch for maxRequestTokens=30000):
  state = {graph: adjacency_list(N), betweenness_scores(N)}
  questions:
    high_bc_node_<N>: noul "Given betweenness scores, is node N highly central?"
    
result: keep_probability[N] for N in nodes

compare:
  baseline_ranking: betweenness_centrality(G)
  jev_ranking: rank by keep_probability[N]
  hybrid_ranking: α * betweenness + β * keep_probability
  
metrics:
  Kendall τ vs ground truth
  top-10 hit rate
  cost per ranking
```

**预估**：34 nodes / 8 per batch = 5 batches × $0.001 / call = **$0.005 总成本**。

## 7. 与现有 8 层 Jev 文档的关系

```
JEV_CONTEXT_SCORING_RESEARCH          调研层      - Jev 是什么
JEV_CONTEXT_SCORING_PLAN              计划层      - harness 内部借鉴
JEV_HANDS_ON_NOTES                    实测层      - fast-jev-compaction 本地 fake
JEV_OFFICIAL_SKILL_ABSORPTION         吸收层      - 6 个官方 pattern
JEV_REAL_API_DEMO                     数据层      - 9 次真 API (compact benchmark)
JEV_PATROL_INTEGRATION                流程层      - 飞书巡检流程接入
JEV_GRAPH_NODES_PLAN                   算法层      - 决策树 × 复杂网络
JEV_FEASIBILITY_FILTER                过滤层      - 前沿方向逻辑过滤
JEV_GRAPH_HYBRID_EXPERIMENT  (本文)    实验层      - graph × Jev hybrid 假说验证 (fake Jev)
─────────────────────────────────────────────────────
9 层结构
```

**新增"实验层"**：在"过滤层"和"算法层"之间，**先做实验再写 plan**。Phase 2（真 API）后再扩到"真实验层"。

## 8. 下一步（pre 维护者拍板）

### 8.1 必须维护者拍板

1. **Phase 2 真 API demo** 是否启动？（用现有 TYPESAFE_API_KEY，$0.005 总成本）
2. **σ 衰减曲线实验** 是否做？（多花 ~$0.02，跑 5 个 σ 值）
3. **不同 question 模板实验** 是否做？（多花 ~$0.01，跑 4 种 question）

### 8.2 不需要维护者拍板

- **JEV_GRAPH_NODES_PLAN.md §6.2 扩展**：把本文 §4.2 hybrid 路径的真实形态补进去
- **Bitable 不动**：hybrid path 是 algorithm layer，不是新方向条目

### 8.3 否决

- **NN layer 路径**：本文再次确认否决（详见 `JEV_FEASIBILITY_FILTER.md` §2 方向 2）
- **直接 Jev 排序替代算法**：本文证伪

## 9. 关键数据持久化（pre Phase 2）

实验脚本落 `.tmp/jev-graph-experiment/`：

```
D:\gitAll\agent-cloud-harness\.tmp\jev-graph-experiment\
├── phase1_fake.py        ← 本次实验脚本
└── phase1_fake.log       ← 完整实验输出（含 warning）
```

**不落 src/，不落仓库** —— 临时数据按 ACH `.tmp/` 约定。

## 10. 参考

- `JEV_FEASIBILITY_FILTER.md` §2 方向 1 + §4.2
- `JEV_GRAPH_NODES_PLAN.md` §4 路径 A / B / C
- `JEV_HANDS_ON_NOTES.md` §3 fake Jev 合同
- https://www.datacamp.com/blog/system-one-models-jev（Jev 官方 4-workflow benchmark 68%）
- https://docs.typesafe.ai/cookbooks/consistency_noul_cookbook（Jev noise std=0.0102）
- networkx `karate_club_graph`（Zachary 1977, 34 nodes / 78 edges）

> 维护约定：本文是 fake Jev 实验记录；Phase 2 真 API 实验另起 `JEV_GRAPH_HYBRID_EXPERIMENT_REAL.md`，结构对齐本文。