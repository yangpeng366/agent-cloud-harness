# Jev × 飞书巡检 Phase 6 脑洞 A：keep_probability 联动网

> 目的：把 Jev 的 keep probability 当成跨项目"健康信号"的最低开销连接通道；不依赖 schema、不改派工、不写 Bitable，只在 sidecar + digest 里加一个聚合维度。
> 本文是设计稿，仅 Phase 6 候选；不直接动任何运行时。

## 1. 动机

每次 sample window 跑完 N 条 sidecar，Jev 给的概率彼此独立。如果只看"per-project hit_rate"，看不到项目间的关联 — 一个项目确实在推进 vs 另一个项目同样卡住，可能有 70% 概率相关联（运维侧故障、市场侧变化）。

维护者真正想知道的是『这条 high-uncertainty sidecar 和同窗口其它 sidecar 是否同步』。如果 12 条样本里有 6 条都因 `proxy_unavailable` 失败，hit_rate 会被噪声淹没，但 keep_probability 联动网能在一行日志里指出"6 条 sidecar 同时降到 0.2-0.3 区间"。

## 2. 数学骨架

| 维度 | 含义 | 计算 |
|---|---|---|
| per-window mean | 当前窗口 keep_probability 均值 | sum/N |
| per-window std | 当前窗口 keep_probability 标准差 | sqrt(variance) |
| cross-correlate | 任两条 sidecar 的 Pearson r (按 generated_at 排序) | sum((p-mean)(s-mean))/sqrt(...) |
| cluster cohort | 在 0.30-0.70 uncertainty band 内且 std<0.05 的样本聚类 | `Find-UncertaintyCohort` |
| divergence | 当前窗口均值 vs 上一窗口均值的绝对差 | abs(mean_t - mean_t-1) |
| cascade signal | "≥3 条样本同时落入同一 uncertainty sub-band 且时间间隔 < 5min" | coalesce 规则 |

输出形态：在每个 `downloads/reports/window/window-summary-<stamp>.md` 末尾追加"cascade"段：

```
- mean_keep_probability: 0.5023
- std_keep_probability: 0.073
- divergence_from_previous_window: 0.014 (stable)
- uncertainty_cohort_size: 28/29
- cross_correlation_max: 0.42 (sample pair 项目A:8 ↔ 项目B:11)
- cascade_signal: 0
```

`cascade_signal >= 1` 触发"运维侧的关联告警"，但默认 disabled — 仅在 `cascade.enabled=true` 才向飞书通知。

## 3. 维护者启用流程

1. 跑 ≥3 个连续 sample window（每个 ≥10 条 sidecar）。
2. 检查 `cross_correlation_max < 0.7` 且 `uncertainty_cohort_size < 90%`：表示 Jev 行为基本独立。
3. 维护者拍板：`cascade.enabled=true`。
4. 启动后，`Run-BuildJevShadowCascade.ps1` 与现有 digest 并行；不替换原 digest。

## 4. 与现有组件关系

| 现有 | 与本设计的关系 |
|---|---|
| `JevShadowEval` | 仅在单次 window 内计数 TP/FP/FN/TN，不做跨 window |
| `JevShadowDigest` | 单 window 汇总（per-project / avg）；本设计扩展出 cross-correlate |
| `JevShadowControlledSample` | 给 ground_truth，但不参与 cascade 算分 |

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 跨窗口波动把正常项目误报 | 默认 disabled + divergence > 0.15 才发 |
| 多窗口 cache IO 抖动 | 不读 sidecar 文件，只读 summary json |
| cross-correlation 计算复杂度过高 | 用 N(N-1)/2，N≤200 都可秒级 |
| 维护者把 cascade 误当作派工决策 | 仅"建议观察"，不写回 Bitable、不改 fake/real 分流 |

## 6. 不触碰项

- Bitable schema 不动。
- fake / real 派工分流不动。
- `decision.enabled` 仍 default false。
- Key 仍走临时进程环境变量。

## 7. 当前依据

- Phase 3 / 4 已落地；shadow + digest + eval + decision + schema draft 全部 ready。
- controlled sample window 已证明 Jev 在中段几乎不区分正负 — cascade 主要在 "Jev 持续低分/同步低" 这条路径上提供额外信号。
- 单一 eval 不能跨窗口；cascade 是下游最便宜的扩展点。

## 8. 下一步

- 不在本轮写 `Run-BuildJevShadowCascade.ps1`；先把本文与 plan §六关联。
- 若维护者要开启，再起单独 initiative（Jev-Cascade-L1）落实施计划。