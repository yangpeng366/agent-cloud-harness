# Jev Feasibility Filter（逻辑可行性过滤）

> 本文是 `JEV_OFFICIAL_SKILL_ABSORPTION.md` 与 `JEV_GRAPH_NODES_PLAN.md` 的前置过滤器。
> 时间：2026-09-21。
> 范围：用户提出的三个前沿方向（Jev 替代图算法 / Jev 作为 NN layer / Jev 抽象为算子）的逻辑可行性 + 工程化验证路径。
> 关键硬数据：Jev 官方 4-workflow benchmark 准确率 **~68%**，0% 类型错误，70–500ms，$0.042/Mtok（来自 https://www.datacamp.com/blog/system-one-models-jev + https://typesafe.ai/blog/introducing-system-one-models-jev + https://github.com/kraayenjon/awesome-jev 多个社区 benchmark）。

## 0. 一句话总结

**Jev 是"语义系统"不是"数值求解器"也不是"可微函数"**。用 Jev 替代需要 100% 精度的算法层（精确图算法、NN 反向传播）**逻辑上不成立**；把 Jev 作为"语义层"嵌入到传统算法的决策点（gate / weight / score）**逻辑上成立**且工程可验证。

## 1. 关键硬数据（决定可行性边界的硬数字）

| 维度 | Jev 实测 | 含义 |
|---|---|---|
| **官方 4-workflow 准确率** | ~68% | 接近 mid-tier LLM（GPT-5.6 Terra），**远低于 frontier LLM** |
| **类型错误率** | 0%（结构化输出） | 与精度无关，是 schema 保证 |
| **Jev Phishing Bench**（社区） | Haiku 在 accuracy 上赢 Jev | **Jev 不是 universal better** —— 高精度任务 LLM 仍胜出 |
| **延迟** | 70–500ms | 比 LLM 快 40–200× |
| **价格** | $0.042/Mtok input | 比 LLM 便宜 40–400× |
| **架构** | 非自回归 + parallel sampler | **不是 transformer，不可微** |
| **训练** | RLCD（Reinforcement Learning for Calibrated Decisions） | 校准概率是 first-class property，不是 prompt 调出来的 |
| **Jev Rerank Bench / Spam Eval** | 多个社区 benchmark | 在 narrow 任务上接近或超越 LLM |
| **Jev sec-bench / agent-failure-bench** | 不同任务结果差异大 | 任务特定，不是 universal |

**核心结论**：
- Jev 的**校准概率**是 first-class —— 用作 soft gate / score / routing 天然合适
- Jev 的 **68% 准确率**对**精确求解任务致命**，对**容错决策任务足够**
- Jev 的**非 transformer 架构**意味着**不可微**，不能作为 NN layer
- Jev 的**结构化输出**（schema-guaranteed）意味着**不会语法错误**，但**会语义错误**

## 2. 三个方向的逻辑可行性过滤

### 方向 1：Jev 替代图算法函数（求数值解）

**用户假设**：Jev 替代 `networkx.shortest_path` / `centrality.betweenness` / `flow.maxflow` 等函数，给"近似最优解"。

**逻辑分析**：

| 场景 | 精度需求 | Jev 68% 是否够？ | 速度收益是否值得 | 结论 |
|---|---|---|---|---|
| **最短路径（Dijkstra）** | **100% 精确**（错误路径 = 错答） | ❌ 致命 | N/A | **否** — 错误 32% 不可接受 |
| **最大流（Edmonds-Karp / Dinic）** | **100% 精确**（流量守恒） | ❌ 致命 | N/A | **否** |
| **最小生成树** | **100% 精确** | ❌ 致命 | N/A | **否** |
| **Betweenness centrality** | 排序正确（不需数值精确） | ✅ 部分够 | 替代 O(V·E) 算法可能成立 | **待验证** |
| **PageRank / eigenvector centrality** | 排序 + 数值（幂迭代） | ✅ 容错（迭代本身就是近似） | ✅ 成立 | **待验证** |
| **社交网络影响力预测**（粗排） | top-K 准确 | ✅ 部分够 | ✅ 成立 | **待验证** |
| **推荐系统候选生成**（召回层） | top-K recall | ✅ 部分够 | ✅ 成立（cooke 类似 cookbook） | **待验证** |

**最终结论**：

- **纯精确求解场景**：**不可替代**，用 Jev 是 anti-pattern
- **排序 / top-K / 召回场景**：**部分可替代**，跟 heuristic 算法 hybrid 是合理路径
- **协同路径（强烈推荐）**：Jev 作为 "semantic gate" 加在精确算法之前或之后 —— 例如 `semantic_rerank(jev_rerank(candidates))`

**对应 `JEV_GRAPH_NODES_PLAN.md` 路径 C**：已经覆盖（decision_trace × centrality × keep_probability 三维交叉），本文不重复。

### 方向 2：Jev 作为神经网络层

**用户假设**：Jev 的 decision tree / fan-out / confidence-routing 当作 NN 的 layer。

**逻辑分析**：

| 维度 | NN layer 需求 | Jev 性质 | 是否兼容 |
|---|---|---|---|
| **可微** | 必需（梯度反传） | **Jev 不是 transformer，不可微** | ❌ |
| **可嵌入大型网络一起训练** | 必需（end-to-end） | Jev 是独立 HTTP API | ❌ |
| **批量化 GPU 并行** | 必需（速度） | Jev 是 sequential API call | ❌ |
| **输出可微信号** | 必需（logit / softmax） | Jev 输出概率，已经是 softmax 形态 | ✅ 但**这一步可微但跨网络不可微** |
| **可解释性** | 不必需（黑盒常用） | Jev 概率有 calibration 优势 | ✅ |

**最终结论**：

- ❌ **Jev 不能作为可训练的 NN layer** —— 不可微、不可批量梯度反传
- ⚠️ **Jev 可以作为 "frozen feature extractor" + 后续可微分类器** —— 这是 hybrid 路径，但本质是把 Jev 当作"昂贵但精确的特征工程"，不是真正的 NN layer
- ⚠️ **Jev 可以作为 "inference-time ensemble member"** —— 训练好的 NN 做预测时，并行调 Jev 做概率融合，但这是 ensemble，不是 layer

**对应 ACH 借鉴**：**否**方向。可借鉴性低。

### 方向 3：Jev 抽象为"软算子" / Fuzzy Operator

**用户假设**：Jev 像 `+ - × /` 一样作为代数算子（或 fuzzy operator）。

**逻辑分析**：

| 维度 | 算子 / Operator 需求 | Jev 性质 | 是否兼容 |
|---|---|---|---|
| **确定性 / 可重现** | 必需（同一输入 → 同一输出） | Jev 自报 "calibrated probabilities"，但**实测 14-question × 15 repeats 标准差 0.0102**（参考 `consistency_noul_cookbook`） | ✅ 准可重现 |
| **组合性 / 可复合** | 必需（f(g(x))） | Jev 可 pipeline，但**每次 call 都有 latency 70–500ms** | ⚠️ 复合 N 次 = 100× latency |
| **数学严格性** | 必需（证明 / 推理） | Jev 是概率输出，**不是数学证明** | ❌ 不能用作定理证明 |
| **域内可表达** | 必需（任何输入都可表达） | Jev 输入**必须 state + questions**，**不能纯数值输入** | ⚠️ 需 wrapper |
| **可作为 fuzzy operator** | Zadeh fuzzy set 成员度 | Jev 输出 `noul: 0.42` = "this is a member with degree 0.42" | ✅ 数学对应天然成立 |
| **可作为 soft gate** | neural net activation (sigmoid) | Jev 概率 + confidence | ✅ 形态对应天然成立 |

**最终结论**：

- ✅ **Jev 可以作为 "soft operator" / "fuzzy operator"**：数学上对应 Zadeh fuzzy membership
- ✅ **Jev 可以作为 "soft gate"**：形态对应 sigmoid activation
- ⚠️ **但 latency 是硬约束**：每次 call 70–500ms，**不能放在 hot path**（训练 / 推理主链路）；只能放在 cold path（启动 / 决策点 / cache refresh）
- ⚠️ **不是代数算子的可替换物**：Jev 没有反演律 / 结合律 / 分配律，无法证明数学性质

**对应 ACH 借鉴**：**作为 soft gate 嵌在 control flow 关键决策点**，对应 `JEV_GRAPH_NODES_PLAN.md` 路径 A（mounted_context_view 图节点评分）—— Jev gate 在每个 panel 节点上。

## 3. 三个方向综合判断

| 方向 | 逻辑可行性 | 工程可验证性 | 推荐度 |
|---|---|---|---|
| **图算法替代（Dijkstra / maxflow 等）** | ❌ 精确求解场景不可替代 | ✅ 排序场景可实验 | ⭐⭐（hybrid 路径推荐） |
| **NN layer** | ❌ 不可微不可训练 | ❌ hybrid 可但本质是 feature engineering | ⭐（不推荐） |
| **软算子 / Fuzzy Operator / Soft Gate** | ✅ 数学对应天然成立 | ✅ ACH 可直接接入（路径 A 已是这个形态） | ⭐⭐⭐（强烈推荐） |

## 4. 工程化验证路径（pre 立项）

### 4.1 软算子 / Soft Gate 路径（已部分覆盖）

**对应 `JEV_GRAPH_NODES_PLAN.md` 路径 A** —— mounted_context_view 图节点评分。

**验证步骤**：

1. **Phase 1（0.5 天）**：手写 8-node decision tree fixture + fake Jev
2. **Phase 2（1 天）**：JevDecisionTreeTest 验证 `confidence 3 段 → act / confirm / human_review`
3. **Phase 3（1 周）**：RuntimeJudgmentService 升级为 parallel questions（路径 B），实测 `12.2× cheap / 10× fast` 数据

### 4.2 图算法 hybrid 路径（**新增验证方向**）

**形态**：Jev 作为语义 rerank 层，叠加 networkx 算法之上。

**最小实验**：

1. 取 `D:\gitAll\networkx\networkx.algorithms.centrality.betweenness` 跑 karate_club graph（34 nodes）
2. 拿到 betweenness centrality 全表（ground truth）
3. Jev 对每个节点问：`is this node high-betweenness?`（noul）
4. 对比 Jev 的概率排序 vs ground truth 排序的 Kendall tau / Spearman correlation
5. 如果 τ > 0.7 且 Jev 命中率（top-10 节点命中比例）> 80%，**hybrid 路径成立**

**预估**：0.5 天写 fixture + benchmark；半天跑数据；半天分析。

### 4.3 NN layer 路径（**否决**）

**不验证**。理由见 §2 方向 2 分析。

## 5. 与现有 7 层 Jev 文档的关系

```
JEV_CONTEXT_SCORING_RESEARCH          调研层      - Jev 是什么
JEV_CONTEXT_SCORING_PLAN              计划层      - harness 内部借鉴（FEAT-03）
JEV_HANDS_ON_NOTES                    实测层      - fast-jev-compaction 本地 fake
JEV_OFFICIAL_SKILL_ABSORPTION         吸收层      - 6 个官方 pattern + 5 cookbook
JEV_REAL_API_DEMO                     数据层      - 9 次真 API metrics
JEV_PATROL_INTEGRATION                流程层      - 飞书巡检流程接入
JEV_GRAPH_NODES_PLAN                   算法层      - 决策树 × 复杂网络 × networkx
JEV_FEASIBILITY_FILTER  (本文)         过滤层      - 前沿方向逻辑可行性 + 工程化路径
─────────────────────────────────────────────────────
8 层结构
```

**8 层结构清晰**：过滤层（本文）是"前沿方向"的过滤器，把不可行的方向否掉，把可行的方向送回算法层或流程层做立项。

## 6. 三个方向的最终判断（决策树）

```
用户提出方向
    │
    ├─ "Jev 替代图算法求数值解"
    │       ├─ 精确求解（Dijkstra / maxflow / MST）: ❌ 否（精度致命）
    │       ├─ 排序场景（betweenness / PageRank 粗排）: ✅ hybrid 验证（§4.2）
    │       └─ 召回 / top-K 推荐: ✅ cookbook 已支持（re-ranking 类）
    │
    ├─ "Jev 作为 NN layer"
    │       └─ ❌ 否（不可微不可训练；hybrid 路径是 feature engineering 不是 layer）
    │
    └─ "Jev 抽象为软算子 / Fuzzy Operator"
            ├─ 作为 soft gate: ✅ 强推荐（对应 JEV_GRAPH_NODES_PLAN 路径 A）
            ├─ 作为 fuzzy membership operator: ✅ 数学对应天然成立
            └─ 作为 hot-path 算子: ❌ 否（latency 70-500ms 不能放热路径）
```

## 7. 下一步

- **软算子路径**：已被 `JEV_GRAPH_NODES_PLAN.md` 路径 A 覆盖，无需新文件
- **图算法 hybrid 路径**：建议落到 `JEV_GRAPH_NODES_PLAN.md` §6.2 加一段 hybrid 验证（最小实验 1.5 天）
- **NN layer 路径**：否决，不进任何 plan

## 8. 参考

- `https://www.datacamp.com/blog/system-one-models-jev`（68% 准确率，4-workflow benchmark）
- `https://github.com/kraayenjon/awesome-jev`（社区 benchmarks 列表）
- `https://typesafe.ai/blog/introducing-system-one-models-jev`（官方 System One 介绍）
- `JEV_OFFICIAL_SKILL_ABSORPTION.md` §5.2（confidence 3 段阈值）
- `JEV_GRAPH_NODES_PLAN.md` §4 路径 A / B / C

> 维护约定：本文是"前沿方向逻辑可行性过滤"，不是 plan；不要把本文当 plan 推进，只作为后续方向决策的过滤器。