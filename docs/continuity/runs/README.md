# Continuity Runs

本目录是 `continuity/` 主题下需要持续回看的 dated execution evidence 主题级聚合入口，重点承接 packet schema、legacy control route、multi-round 与 long-task closure 相关的控制面收口证据。

当前仍保持轻量聚合：只建一个 `README.md` 做入口，不搬动 root-level dated 文档本体。规则是先用这里按证据子线分流，再决定哪些结论需要回收到 `../README.md`、`../PROGRESS.md`、`../LIVE_FLOW_RUNBOOK.md` 或稳定基线文档。

## 命中信号

- 任务要回看某一轮 packet / control-route / multi-round / closure 的真实 execution evidence
- 任务要按日期或专项批次比较 continuity 主题下的回归轨迹
- 任务要确认某条 control-plane evidence 现在归哪个 continuity 子线维护

## 最小阅读顺序

1. 先回到 `../README.md` 判断当前问题属于哪条 continuity 子线。
2. 如果确认是在查 dated execution evidence，再看下面的分组入口。
3. 若某条行为已经稳定，不要停留在 record；回写 `../PROGRESS.md`、`../LIVE_FLOW_RUNBOOK.md` 或相关基线文档。

## 当前分组

### Packet / Checkpoint / Resume Schema

- `../../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`

### Control Action / Legacy GET Route

- `../../M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`

### Multi-round / Control Graph

- `../../M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`

### Long-task Closure

- `../../O04_LONG_TASK_CLOSURE_EXECUTION_RECORD_2026-06-15.md`

## 使用规则

- 本目录只做 continuity 主题的 dated execution evidence 聚合入口；这里的“聚合入口”职责是不替代 root-level 文档本体。
- 如果某条 record 已经只剩历史参考价值，再由 `continuity/README.md` 降级分流，不在这里继续扩写解释。
- 如果后续 control-plane execution evidence 继续密集增长，可在本目录下再按批次补更细的二级索引，但先保留轻量入口。
