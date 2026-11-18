# Jev × 飞书巡检 Phase 6 脑洞 B：项目健康度评分热力图

> 目的：用 sidecar 中已有的 probability / latency / error 三个信号，按时间窗口聚合成 per-project 评分（B、C、F 三个等级），并产出可被 Bitable view / 飞书机器人消费的 markdown 热力图。
> 本文是设计稿，仅 Phase 6 候选；不写 Bitable、不改派工。

## 1. 评分公式（per project, per window）—— 口径 v1（2026-11-18 定稿）

```
score = clamp(0, 100,
              100
            - 60 * (1 - prob_avg)              # 概率越低扣分越多（权重 60）
            - 25 * (lat_p95 / latency_budget)  # 延迟归一化，权重 25
            - 15 * error_rate)                 # error 率每 +1 减 15
```

字段定义（与 `scripts/Build-JevShadowHeatmapPrototype.ps1` 一一对应，任何一侧修改必须同步另一侧并跑 `tests/Test-JevShadowHeatmapPrototype.ps1`）：

| 字段 | 定义 |
|---|---|
| `prob_avg` | 该 project 内**有数值 `jev.probability` 且非 error** 的样本算术平均；无此类样本时记 `0` |
| `lat_p95` | 每条 `jev.duration_ms` 先硬截断到 `[0, latency_budget]`，再取 **nearest-rank** p95（`rank = ceil(0.95*n)`，结果即第 rank 小值） |
| `latency_budget` | 默认 `5000` ms（`-LatencyBudgetMs`） |
| `error_rate` | 该 project 内 `jev.error` 为真的样本数 / 样本总数 |
| `samples` | 该 project 在窗口内的样本数（输出里同时给出，便于人工判断样本稀薄） |

等级（含端点，对 round 后的 score 判定）：

- `score ≥ 80` → B（健康）
- `60 ≤ score < 80` → C（关注）
- `score < 60` → F（告警）

窗口：默认 `-WindowDays 7`，按 sidecar 的 `generated_at` 过滤（缺该字段时回退文件 mtime）；`-WindowDays 0` = 全量不限窗口。

### 1.1 口径变更历史（为什么和早期草稿不一致）

| 项 | 早期草稿 | v1 定稿 | 理由 |
|---|---|---|---|
| 延迟处理 | 单条按 `(latency/5000)²` clip | 单条硬截断到 budget，再由 p95 归一化 | 平方项叠在 p95 之上会让单条极值主导等级；硬截断 + p95 使扣分严格落在 `[0,25]`，与 25 分权重对齐、可解释 |
| `+10 * window_count_in_due` | 计入 | **v1 未启用**（待 v2） | sidecar 无 due 字段，硬加等于凭空造分；需先接 cascade window 数据源再作为独立开关（默认 0） |
| p95 取位 | `floor(n*0.95)`（n=5 时取到第 4 小值） | `ceil(0.95n)` nearest-rank（n=5 → 第 5 小值） | 与统计学 nearest-rank p95 一致，小样本时不会低估尾部 |
| 概率均值样本域 | 未写明 | 仅非 error 且 probability 有数值的样本 | error 样本通常无 probability，混入会污染均值 |
| score 上下界 | 未写明 | clamp 到 `[0,100]`，保留 1 位小数 | 早期样例出现负数分未定义行为；保留 1 位避免 80/60 端点歧义 |

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

- 5-10 行级 prototype 已落 `scripts/Build-JevShadowHeatmapPrototype.ps1`；真实 43 样本分布 B=21 / C=21 / F=1（旧口径，常数 60/25/15 相同但延迟项与 p95 取位不同，**不可与 v1 数字直接对比**）。
- 2026-11-18：口径统一为 v1，脚本 + 本文 §1 + 金标准用例三处对齐；验证 `pwsh -NoProfile -File tests/Test-JevShadowHeatmapPrototype.ps1` → pass=31 fail=0。
- 若维护者要开启，再起单独 initiative（Jev-Heatmap-L1）落实施计划；v2 的 due-count 加分需先接 cascade window 输出。