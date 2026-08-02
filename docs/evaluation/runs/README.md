# Evaluation Runs

本目录是 `evaluation/` 主题下需要持续回看的 dated 执行证据主题级聚合入口，重点承接多轮任务、matrix、focused regression 与阶段性 execution record。

当前仍保持轻量聚合：只建一个 `README.md` 做入口，不搬动 root-level dated 文档本体。规则是先用这里分流和归组，再决定哪些结论需要回收到 `evaluation/README.md`、`PROGRESS.md` 或稳定基线文档。

## 命中信号

- 任务要回看某一轮真实 execution evidence，而不是重读评估主叙事
- 任务要按日期或专项批次比较多轮任务、matrix、focused verification
- 任务要确认某条 execution record 现在归哪个 evaluation 子线管理

## 最小阅读顺序

1. 先回到 `../README.md` 判断当前问题属于哪个 evaluation 子线。
2. 如果确认是在查 dated 执行证据，再看下面的分组入口。
3. 若某条结论已经稳定，不要停留在 record；回写 `../PROGRESS.md` 或对应基线文档。

## 当前分组

### Multi-round / Task-pack / Execution Closure

- `../../M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`
- `../../O03_ACCEPTANCE_GATE_HTTP_EXECUTION_RECORD_2026-07-21.md`
- `../../P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md`
- `../../P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_FOLLOWUP_EXECUTION_RECORD_2026-07-22.md`
- `../../CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md`
- `../../P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md`
- `../../CCX_RND_CASE_DEBUG_EXECUTION_RECORD_2026-07-25.md`
- `../../O04_LONG_TASK_CLOSURE_EXECUTION_RECORD_2026-06-15.md`
- `../../E2_CODEX_FREE_E2E_SMOKE_EXECUTION_RECORD_2026-07-29.md`

### Worker / Facade / Packet / Control-route Evidence

- `../../D01_WORKER_PRIORITY_OVERRIDE_EXECUTION_RECORD_2026-06-15.md`
- `../../D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`
- `../../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`
- `../../M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`

### Current Codex Profile / Routing Evidence

- `../../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md`

## 使用规则

- 本目录只做主题级 evidence 聚合入口；这里的“聚合入口”职责是不替代 root-level dated 文档本体。
- 如果某条 record 已经只剩历史参考价值，再由 `evaluation/README.md` 降级分流，不在这里继续扩写解释。
- 如果后续 execution evidence 继续密集增长，可在本目录下再按批次补更细的二级索引，但先保留轻量入口。

### Long Stability / 7h+ Smoke

- `../../LONG_STABILITY_SMOKE_25200S_EXECUTION_RECORD_2026-08-02.md`