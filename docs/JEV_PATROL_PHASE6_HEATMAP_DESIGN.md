# Jev × 飞书巡检 Phase 6 脑洞 B：项目健康度评分热力图

> 目的：用 sidecar 中已有的 probability / latency / error 三个信号，按时间窗口聚合成 per-project 评分（B、C、F 三个等级），并产出可被 Bitable view / 飞书机器人消费的 markdown 热力图。
> 本文是设计稿，仅 Phase 6 候选；不写 Bitable、不改派工。

## 1. 评分公式（per project, per window）

```
score = 100
       - 60 * avg(1 - probability)                # 概率越低扣分越多
       - 25 * p95(latency_ms) / 5000              # 延迟 5s 为阈值上限
       - 15 * error_rate                          # error 率每 +1 减 15
       + 10 * window_count_in_due                 # 连续 due 计数，多表示"持续被命中"
```

每条 sidecar：
- `error != null` → `error_rate = 1`，否则 `0`。
- `latency_ms > 5000` → 延迟按 (latency/5000)² clip，避免极慢路径让均值失控。

聚合：
- 默认 7 天滑动窗口（per-project）。
- score ≥ 80 → B（健康）/ ≥ 60 → C（关注）/ < 60 → F（告警）。

## 2. 输出

每次 sample window 跑完，在 `downloads/reports/window/window-summary-<stamp>.md` 末尾追加 `heatmap` 段：

```
## 项目健康度（截至 2026-09-22 16:50）
| 项目 | score | 等级 | prob_avg | latency | err | window_count |
|---|---|---|---|---|---|---|
| 示例项目A-紧急 | 87.3 | B | 0.58 | 0.3s | 0 | 4 |
| 受控负样本-0 | 12.1 | F | 0.30 | 0.4s | 0 | 3 |
| OpenEyes | 42.0 | F | 0.64 | 2.9s | 0 | 1 |
```

维护者一眼可见哪些项目"项目级"健康 vs 哪些项目"持续 low prob + 高 latency"。

## 3. 与现有组件关系

| 现有 | 与本设计的关系 |
|---|---|
| `JevShadowDigest` | 输出 per-project count/last_at；本设计叠加 score |
| `JevShadowEval` | 评估 hit_rate；本设计输出 per-project 评分 |
| `JevShadowControlledSample` | 不影响 ground truth；标注样本来源 |
| `JevShadowCascade` | 输出跨窗口信号；本设计是 per-window 评分 |

## 4. 维护者启用流程

1. 跑 ≥2 个 window（每个 ≥10 条）。
2. fetch 输出 heatmap 段 + markdown 自审：等级分布是否合理（一般 B > C > F）；评分公式常数（60/25/15/10）可被维护者接受。
3. 维护者拍板：`heatmap.enabled=true`。
4. 默认 disabled：纯只读 + 现有 sidecar 不动。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 评分公式常数被误设 | 公开在文档 + 用例测试；每条 prompt 修改 maintenance review |
| 7 天窗口随时间漂移 | 提供 `-WindowDays 1/7/30` 选项，默认 7 |
| score 跨项目不可比 | 同时给原始 prob_avg / latency / err，便于人工审查 |
| 与现有 summary 文件冲突 | 仅追加段，不替换任何字段 |

## 6. 不触碰项

- Bitable schema 不动。
- fake / real 派工分流不动。
- 评分结果不写 Bitable（仅 markdown 输出）。
- Key 仍走临时进程环境变量。

## 7. 当前依据

- Phase 3/4 已落地；sample window + digest 已可产出多窗口数据。
- 当前 eval 的"hit_rate"是聚合指标；本设计把 per-project 信号可视化，让维护者一眼能看到 F 等级。
- heatmap 不依赖 Bitable 视图，可直接 paste 到飞书机器人 / Obsidian memory / 月度复盘文档。

## 8. 下一步

- 5-10 行级 prototype 已落 `scripts/Build-JevShadowHeatmapPrototype.ps1`；真实 43 样本分布 B=21 / C=21 / F=1。
- 若维护者要开启，再起单独 initiative（Jev-Heatmap-L1）落实施计划。