# Dialogue Runs

本目录是 `dialogue/` 主题下需要持续回看的 dated acceptance / execution / precheck 证据主题级聚合入口，重点承接 chat facade、browser acceptance、release gate 与页面行为回归样本。

当前仍保持轻量聚合：只建一个 `README.md` 做入口，不搬动 root-level dated 文档本体。规则是先用这里按证据类型分流，再决定哪些结论需要回收到 `../README.md`、`../PROGRESS.md`、`../WEB_CONSOLE.md` 或对应 runbook。

## 命中信号

- 任务要回看某一轮真实 acceptance / precheck / execution evidence，而不是重读产品/UI 主叙事
- 任务要按日期比较 `/dialogue/`、chat facade、release gate 或浏览器验证的回归轨迹
- 任务要确认某条 dialogue 证据今天归哪个子线维护

## 最小阅读顺序

1. 先回到 `../README.md` 判断当前问题属于哪条 dialogue 子线。
2. 如果确认是在查 dated 证据，再看下面的分组入口。
3. 若某条行为已经稳定，不要停留在 record；回写 `../PROGRESS.md`、`../WEB_CONSOLE.md` 或对应 runbook。

## 当前分组

### Chat Facade / Acceptance Records

- `../../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `../../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `../../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-14.md`
- `../../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-06-02.md`

### Release Gate / Precheck

- `../../DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`

### Focused Execution Evidence

- `../../D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`

## 使用规则

- 本目录只做 dialogue 主题的 dated evidence 聚合入口；这里的“聚合入口”职责是不替代 root-level 文档本体。
- 如果某条 record 已经只剩历史参考价值，再由 `dialogue/README.md` 降级分流，不在这里继续扩写解释。
- 如果后续 browser acceptance、release precheck 或 façade execution evidence 继续密集增长，可在本目录下再按批次补更细的二级索引，但先保留轻量入口。
