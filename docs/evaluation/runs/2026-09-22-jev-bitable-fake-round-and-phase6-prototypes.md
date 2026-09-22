# Jev Bitable Fake Round & Phase 6 Prototypes — 2026-09-22

## Scope

- Read the real Feishu Bitable task table, then execute one fake/dry-run patrol round with Jev shadow scoring. No Bitable write-back and no active routing change.
- Add two read-only Phase 6 prototypes: cross-window cascade divergence and per-project heatmap scoring.

## Inputs

- Table rows: 43
- Sidecars: 43
- Sidecar errors: 0
- Shadow source: `D:\gitAll\patrol-scaffold\downloads\jev-shadow-bitable-fake-20260922-201125` (runtime evidence, outside the repository)
- Machine evaluation: `jev-shadow-eval-20260922-201559.json`

## Evaluation

- Thresholds: low `0.30`, high `0.70`
- TP `39`, FP `0`, FN `4`, TN `0`
- Hit rate: `0.9070`
- Uncertainty band: `23 / 43` (`20 kept`, `3 truncated`)

## Interpretation

The aggregate score exceeds the `0.75` design gate, but this round has no negative ground-truth rows, so TN is zero and the score is positive-label-biased. It must not be used to enable active routing. The large uncertainty band also supports keeping `decision.enabled=false` until a labeled balanced set is available.

## Phase 6 Prototype Results

- Cascade: current mean `0.5050`; divergence from previous window `0.0041`; threshold `0.15`; signal `0`.
- Heatmap: 43 projects; grades `B=21`, `C=21`, `F=1`. The F row is the untitled Bitable record `recvvabY8OpI6V`, score `52`, probability `0.24`.
- Prototype outputs are temporary at `.tmp/phase6-prototype/`; the scripts are deterministic and can regenerate them.

## Commands

```powershell
pwsh -NoProfile -File scripts/Build-JevShadowCascadePrototype.ps1 -SummaryDirectory D:\gitAll\patrol-scaffold\downloads\reports\window -OutputFile .tmp/phase6-prototype/cascade-real.json
pwsh -NoProfile -File scripts/Build-JevShadowHeatmapPrototype.ps1 -ShadowDirectory D:\gitAll\patrol-scaffold\downloads\jev-shadow-bitable-fake-20260922-201125 -OutputFile .tmp/phase6-prototype/heatmap-real.json
```

## Next Gate

Collect balanced, human-labeled Bitable rounds, including negative/completed-outcome rows for TN, then rerun the same evaluator. Do not enable active routing from this positive-only observation.
