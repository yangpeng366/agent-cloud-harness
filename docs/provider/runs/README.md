# Provider Runs

本目录是 `provider/` 主题下需要持续回看的 dated execution evidence 主题级聚合入口，重点承接 codex profile lane、provider route / recovery、CLI protocol 接入与 provider selection 读面相关的 focused 收口证据。

当前仍保持轻量聚合：只建一个 `README.md` 做入口，不搬动 root-level dated 文档本体。规则是先用这里按 provider 子线分流，再决定哪些结论需要回收到 `../README.md`、`../PROGRESS.md`、`../AGENT_PROVIDER_TECHNICAL_DESIGN.md` 或稳定契约文档。

## 命中信号

- 任务要回看某一轮 provider route / profile / protocol 的真实 execution evidence
- 任务要按日期比较 codex profile、worker priority / facade 接缝或 packet / control-route 证据
- 任务要确认某条 provider 侧 dated 证据今天归哪个子线维护

## 最小阅读顺序

1. 先回到 `../README.md` 判断当前问题属于哪条 provider 子线。
2. 如果确认是在查 dated execution evidence，再看下面的分组入口。
3. 若某条行为已经稳定，不要停留在 record；回写 `../PROGRESS.md`、`../AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`../AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md` 或相关基线文档。

## 当前分组

### Codex Profile / Routing Evidence

- `../../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md`

### Worker / Facade / Protocol Boundary Evidence

- `../../D01_WORKER_PRIORITY_OVERRIDE_EXECUTION_RECORD_2026-06-15.md`
- `../../D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`

### Shared Control-plane / Packet Evidence Relevant To Provider Routing

- `../../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`
- `../../M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`

## 使用规则

- 本目录只做 provider 主题的 dated execution evidence 聚合入口；这里的“聚合入口”职责是不替代 root-level 文档本体。
- 如果某条 record 已经只剩历史参考价值，再由 `provider/README.md` 降级分流，不在这里继续扩写解释。
- 如果后续 provider route、profile、CLI protocol 或 precheck 证据继续密集增长，可在本目录下再按批次补更细的二级索引，但先保留轻量入口。
