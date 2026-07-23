# WAKE

开始任何实质工作前，按下面顺序建立上下文：

1. 先读本文件，确认开工顺序。
2. 读 `AGENTS.md`，确认架构边界、已知陷阱、代码风格和文档约定。
3. 读 `docs/README.md`，先用“按任务找入口”确定当前任务属于哪个主题。
4. 读 `STATE.md`，了解最近进度、卡点和下一步。
5. 读 `DECISIONS.md`，确认已经固定的方案取舍。
6. 按任务主题先读对应专题入口；如果该主题目录已经启用了 `PROGRESS.md`，接着读它，再下钻到具体文档；如果还拿不准主题，回到 `docs/README.md` 的任务分流表重新判断：
   - meta / 文档治理 / 结构审计：`docs/meta/README.md`
   - continuity / 控制面主链：`docs/continuity/README.md`
   - provider / worker / recovery：`docs/provider/README.md`
   - dialogue / console / facade：`docs/dialogue/README.md`
   - evaluation / 多轮任务 / priorities：`docs/evaluation/README.md`
   - release / github：`docs/release/README.md`
7. 如专题入口仍不足，再补基线文档：
   - 文档结构合同：`docs/DOCS_GOVERNANCE.md`
   - 架构/模块边界：`docs/ARCHITECTURE.md`
   - API/存储契约：`docs/API_CONTRACTS.md`
   - 功能规格/状态机：`docs/SPEC.md`
   - 排障/历史回归：`docs/TROUBLESHOOT.md`

当前专题工作区状态：

- `meta/`: `README.md + PROGRESS.md`
- `continuity/`: `README.md + PROGRESS.md`
- `provider/`: `README.md + PROGRESS.md`
- `dialogue/`: `README.md + PROGRESS.md`
- `evaluation/`: `README.md + PROGRESS.md`
- `release/`: `README-only`

当前默认阅读路径：

- 已启用 `PROGRESS.md` 的主题：`README.md -> PROGRESS.md -> 当前主线文档`
- `README-only` 主题：`README.md -> docs/` 根目录主线文档
- `meta/` 承接文档治理，`dialogue/` 承接 UI/acceptance，`provider/` 承接路由与接入，`continuity/` 承接控制面主链，`evaluation/` 承接评估、优先级、任务包与执行证据，`release/` 承接 GitHub 首发、precheck 与 dry-run。

工作原则：

- 先续写已有文档，再考虑新建文档。
- 文档整理类任务先改 `docs/README.md` 与专题入口 `README.md`；如果规则本身变了，再同步 `docs/DOCS_GOVERNANCE.md`，最后才决定是否要动历史文件位置。
- 做完一轮专题入口或总索引调整后，补一次 root-level `docs/*.md` 差集审计，确认没有 orphan docs。
- 如果某个专题目录已经扩成轻量工作区，优先把本轮工作接到该主题的 `PROGRESS.md` / `tasks/` / `runs/`，不要回退到在 `docs/` 根目录平铺近义新文档。
- 不要直接从 dated execution record 或旧 roadmap 开工，先让专题入口帮你定位当前主线。
- 方案、代码、验证三者至少要有一个正式文档入口串起来。
- 如果本轮工作会中断，离开前更新当前主题文档或 `STATE.md`。
