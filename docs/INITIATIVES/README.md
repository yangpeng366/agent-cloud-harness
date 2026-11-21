# INITIATIVES

本专题承接 `HW-*` / `FEAT-*` / `Jev-*` 立项卡与能力条目；入口清单是 `INDEX.md`。

## 命中信号

- 任务提到 `FEAT-03`、`FEAT-04`、`FEAT-05`、`HW-09` ~ `HW-13`
- 任务提到 `Jev-A/B/C`、`Jev-Graph-A/B`、`Jev-Patrol-L2/L3/L5`、`Jev-ToolRecall-Filter`
- 任务要给某个方向新建立项卡、回填结论，或把「提案」推进成「可执行事项」

## 最小阅读顺序

1. `INDEX.md` — 当前条目清单与状态
2. 最贴近当前任务的单卡（`FEAT-*` / `HW-*` / `Jev-*`）
3. 需要跨主题结论时回 `../README.md` 与 `../DOCS_GOVERNANCE.md`

## 稳定基线

- 条目命名分三类前缀：`FEAT-*`（功能）、`HW-*`（硬件 / 现场）、`Jev-*`（Jev 线）
- 每张卡只写：目标、范围、判据、现状、遗留；不写流水账
- `INDEX.md` 是唯一入口清单；新增卡必须同时补 `INDEX.md`

## 当前主线文档

- `INDEX.md`
- `FEAT-03.md`、`FEAT-04.md`、`FEAT-04-pathB.md`、`FEAT-05.md`
- `HW-09.md`、`HW-10.md`、`HW-11.md`、`HW-12.md`、`HW-13.md`
- `Jev-A.md`、`Jev-B.md`、`Jev-C.md`、`Jev-Graph-A.md`、`Jev-Graph-B.md`
- `Jev-Patrol-L2.md`、`Jev-Patrol-L3.md`、`Jev-Patrol-L5.md`、`Jev-ToolRecall-Filter.md`

## 写回顺序

1. 先更单卡：结论、判据、遗留
2. 再更 `INDEX.md`：状态与新增条目
3. 跨主题结论回写 `../README.md`、`../STATE.md` 或 `../DECISIONS.md`

## 历史材料

- 已完成立项卡保留原文，只追写结论，不改写历史判断
- 已被后续方向取代的卡（如早期 `FEAT-04` 与 `FEAT-04-pathB` 并存）按原文件名保留，在 `INDEX.md` 标状态

## 历史材料使用规则

- 历史卡只作背景与判据来源，不作为「今天仍然为真」的结论
- 需要当前结论时，以 `../ARCHITECTURE.md`、`../SPEC.md`、`../DECISIONS.md` 为准
- 引用历史卡时写明文件名与结论日期，避免把旧提案当现行口径

## 稳定基线仍然为真的部分

- 立项卡的「目标 / 范围 / 判据 / 遗留」四段结构今天仍然成立
- `INDEX.md` 作为唯一入口清单的约束今天仍然成立

## 当前工作区判断

- 本专题保持 README-only：只保留 `README.md` 与立项卡（`INDEX.md`、`FEAT-*`、`HW-*`、`Jev-*`），不建 `PROGRESS.md`、`tasks/`、`runs/`、`archive/`
- 默认阅读顺序：`README.md -> docs/` 根目录主线文档；专题内先 `README.md -> INDEX.md -> 单卡`
- 立项卡即最小工作单元，条目状态集中在 `INDEX.md`，不另开进度文件

## 何时升级

- 单卡继续增多、需要按状态或负责人追踪时，升级为 `PROGRESS.md` + `tasks/`
- 出现需要留存 dated 证据的立项（复现日志、对比实验）时，再补 `runs/`
- 被取代的历史卡达到需要归档的规模时，补 `archive/`
