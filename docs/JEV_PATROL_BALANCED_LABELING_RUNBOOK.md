# Jev × 飞书巡检 — 平衡标注 Runbook（TN / 负标签）

> 目的：在 **不** 打开 `decision.enabled` 的前提下，补齐 fake round 43 样本里缺失的负标签 / TN，让 hit_rate 可被复核。
> 时间：2026-10-10（NEW 巡检恢复后补齐）。
> 约束：遵守 `DECISIONS.md`「巡检 hit_rate 不单独放行 active routing」；Phase 6 仍只读。

## 1. 为什么要做

- 2026-09-22 真实 Bitable fake round：43 sidecar / 0 error / TP=39 FN=4 TN=0 FP=0 / hit_rate=0.9070。
- TN=0 说明观测几乎全是正样本；单独用 hit_rate 放行 active routing 会被 positive bias 骗过。
- Phase 4 启用门槛仍是：平衡标注 + uncertainty band 人工覆核 + 维护者显式切 `decision.enabled=true`。

## 2. 工具

| 文件 | 作用 |
|---|---|
| `scripts/lib/JevBalancedLabel.ps1` | 读 sidecar、算缺口、生成 queue、应用 `observed.human_label` |
| `scripts/Run-JevBalancedLabelQueue.ps1` | 一键扫描默认 fake-round 目录并落 queue/summary |
| `tests/verify-jev-balanced-label.ps1` | 离线合同 |

默认扫描目录：

`D:\gitAll\patrol-scaffold\downloads\jev-shadow-bitable-fake-20260922-201125`

## 3. 操作步骤

```powershell
cd D:\gitAll\agent-cloud-harness
pwsh -NoProfile -File .\tests\verify-jev-balanced-label.ps1
pwsh -NoProfile -File .\scripts\Run-JevBalancedLabelQueue.ps1
```

产出（默认 `.tmp/jev-balanced-label-<stamp>/`）：

- `label-queue.json`：待标注队列（先填 uncertainty / KEEP 样本）
- `label-gap.md` / `label-gap-summary.json`：缺口摘要

维护者在 queue 里填：

```json
{ "path": "...", "human_label": "negative", "note": "no real progress" }
```

再应用（默认写副本，不改原 sidecar）：

```powershell
. .\scripts\lib\JevBalancedLabel.ps1
Apply-JevHumanLabels -LabelMapPath .\.tmp\jev-balanced-label-XXXX\label-queue.json -OutputDir .\.tmp\jev-labeled-copies
```

然后对**标注副本目录**跑 eval：

```powershell
pwsh -NoProfile -File .\scripts\Run-BuildJevShadowEval.ps1 `
  -ShadowDir D:\gitAll\agent-cloud-harness\.tmp\jev-labeled-copies `
  -OutputDirectory D:\gitAll\agent-cloud-harness\.tmp\jev-eval-after-label
```

## 4. 通过标准（仍不自动启用）

- 至少出现 TN > 0，且负标签样本量建议 ≥ 10。
- uncertainty band 样本人工覆核覆盖率 ≥ 60%。
- 重新计算后的 hit_rate 仍 ≥ 0.75 **且** 不再是「TN=0 的假高分」。
- 以上都满足后，**仍需维护者**手动把 feishu/scaffold 的 `decision.enabled` 从 false 改为 true。

## 5. 不触碰

- 不改 Bitable schema。
- 不改 fake/real 分流。
- 不在本 runbook 流程里打开 active routing。
- 不把 `TYPESAFE_API_KEY` 写入仓库或测试夹具。