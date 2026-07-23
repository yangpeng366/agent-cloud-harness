# Meta Progress

## 当前状态

- `meta/` 仍是文档治理专题工作区，用来承接持续发生的结构审计与入口收口；当前业务主题里，`dialogue/`、`provider/`、`continuity/` 与 `evaluation/` 都已升级到 `README.md + PROGRESS.md`。
- 本轮目标是把“文档治理”从 `docs/README.md` 的长说明里拆出来，形成 `docs/README.md -> docs/meta/README.md -> docs/DOCS_GOVERNANCE.md` 的清晰入口链。
- 当前真实结构状态已经固定为 `6` 个专题入口，其中 `meta/`、`dialogue/`、`provider/`、`continuity/` 与 `evaluation/` 已启用 `PROGRESS.md`；最近一次稳定审计结果为 `root_markdown_count=101`、`topic_linked_root_markdown_count=101`、`violation_count=0`。
- 本轮又补了一层根入口收口：`AGENTS.md` 现在回到真正的 Agent 开工入口角色，不再长期承载项目概述、技术栈、代码树和 API 长表；这些稳定事实改为继续由 `docs/ARCHITECTURE.md`、`docs/API_CONTRACTS.md`、`docs/SPEC.md`、`docs/TROUBLESHOOT.md`、`docs/WEB_CONSOLE.md` 承接。

## 已完成

- 新建 `docs/meta/README.md`，让文档治理任务有正式专题入口。
- 新建 `docs/DOCS_GOVERNANCE.md`，收口结构合同、工作区升级规则、命名合同与审计入口。
- 把 `docs/README.md` 收回为更纯粹的总索引，不再同时承担大段治理细则。
- 把根目录 `README.md` 也接入同一条导航链，显式暴露 `docs/meta/README.md` 与 `docs/DOCS_GOVERNANCE.md`，避免公开入口只能看到业务主题、看不到文档治理入口。
- 用 `Run-DocsIndexAudit.ps1` 复核当前结构，确认 `docs/` 根目录正式 Markdown 已全部被专题入口覆盖，没有 `docs/README.md` 独占引用或 orphan docs。
- `Run-DocsIndexAudit.ps1` 与 `DocsStructureContractTest` 现已把 `WAKE.md` / `AGENTS.md` 也纳入结构回归，要求根入口显式同步每个主题的 `README.md + PROGRESS.md` / `README-only` 状态，并固定已升级主题的默认阅读链为 `README.md -> PROGRESS.md -> 当前主线文档`。
- `DocsIndexAuditScriptTest` 现已新增到 `src/test/java/com/agentcloud/docs/`，用于直接执行 `Run-DocsIndexAudit.ps1` 并校验 `summary.passed / violation_count / wake|agents workspace row coverage / 阅读链布尔值`，避免审计脚本只能靠人工手跑发现语法或输出回归。
- `release/` 这条唯一剩余的 `README-only` 主题也已补上显式工作区判断：当前维持轻量入口不是漏做，而是因为 release 的当前真相已经集中在少量稳定基线文档里，剩余多数材料属于 dated 历史证据；相应升级门槛也已写回专题入口与总索引。
- `README.md` 与 `STARTUP_GUIDE.md` 这两个根入口的导航/边界合同也已进入治理主线：前者必须把公开读者送到总索引、文档治理入口和 Agent/连续性入口，后者必须明确“只负责启动”，并把开发、UI、provider 与连续性类任务导回对应入口；这层合同将由脚本和 JUnit 双重回归守住。
- 五个已启用工作区的 `PROGRESS.md` 现已明确收成同一最小模板：`当前状态 / 已完成 / 活跃子线 / 下一步 / 风险`。这意味着后续主题级连续性写回不再只是“有个进度文件”，而是有固定结构、可被脚本和 JUnit 复查。
- 五个已启用工作区的专题 `README.md` 也已确认都显式保留了 `README.md -> PROGRESS.md -> 当前主线文档` 这条阅读链；接下来这层入口合同也会像 `PROGRESS.md` 五段式结构一样进入自动回归，避免后续只剩文件存在、入口却不再告诉读者先读什么。
- `AGENTS.md` 这次也同步收回成“先读什么 / 哪些红线不能破 / 项目事实去哪里查 / 做完写回哪里”的入口形态，避免它继续和 `README.md`、`docs/README.md`、`ARCHITECTURE.md`、`API_CONTRACTS.md` 形成重复百科。
- `AGENTS.md` 的这层角色边界现在也已进入 `Run-DocsIndexAudit.ps1` 与 `DocsIndexAuditScriptTest`：脚本会直接检查它仍保留 `开工红线 / 项目事实入口`，并确认 `项目概述 / 技术栈 / 代码组织 / API 端点速查` 没有长回根入口。
- `docs/README.md` 的治理入口也已继续补进回归：脚本和 JUnit 现在都会检查总索引仍显式保留 `meta/README.md`、`DOCS_GOVERNANCE.md`、`Run-DocsIndexAudit.ps1` 与 focused docs regression 命令，避免以后只剩主题入口、却把治理入口和审计入口从总索引里悄悄删掉。
- `docs/README.md` 的 `按角色找入口` 这一层默认导航也已进入回归：脚本和 JUnit 现在都会检查 startup/verify、文档治理、Agent 接手、连续性读取这四条入口仍然存在，避免总索引只剩主题列表、却失去“我现在该从哪儿开始”的第一层分流。
- `release/README.md` 这条唯一剩余的 `README-only` 主题入口合同现在也已进入回归：脚本和 JUnit 会检查它继续显式解释“为什么仍保持轻量入口”“何时才升级”，并保留 `README.md -> docs/` 根目录主线文档 这条默认阅读链，避免以后只剩一个文件名在那儿、但读者不知道为什么它还没升级。
- `docs/meta/README.md` 的文档治理写回链现在也已进入回归：脚本和 JUnit 会检查它继续显式保留 `docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md` 这条默认写回顺序，避免后续结构任务又跳过总索引或治理合同，直接散落到历史文件、`STATE.md` 或 `DECISIONS.md`。
- 所有专题入口的最小结构合同现在也进一步统一了：脚本和 JUnit 不再只把 `稳定基线` 当成业务主题附加约束，而是会统一检查每个 `docs/<topic>/README.md` 都继续保留 `## 稳定基线`，并显式说明哪些正式文档“今天仍然为真”。
- 专题入口和 `PROGRESS.md` 的段落顺序现在也已进入回归：脚本和 JUnit 会检查每个专题入口继续保持 `命中信号 -> 最小阅读顺序 -> 稳定基线 -> 当前主线文档 -> 写回顺序` 这条核心顺序，而所有已启用的 `PROGRESS.md` 继续保持 `当前状态 -> 已完成 -> 活跃子线 -> 下一步 -> 风险`。
- 五个业务主题入口的子主题分流层现在也已进入回归：脚本和 JUnit 会检查 `continuity / provider / dialogue / evaluation / release` 继续保留 `先做子主题判断`、`当前入口建议` 以及 `| 当前问题 | 先看哪里 | 再下钻 |` 分流表，避免主题入口重新退回成“只有文档长名单，没有第一层判断”的状态。
- 五个业务主题入口的 `当前主线文档` 分组现在也已进入回归：脚本和 JUnit 会检查 `continuity / provider / dialogue / evaluation / release` 在 `## 当前主线文档` 下继续保留 `###` 分组子标题，而四个已启用 `PROGRESS.md` 的业务主题还必须继续保留 `### 主题进度`，避免当前主线重新退回成平铺列表。
- `evaluation/` 这条业务主题现已继续升级了一层：新增 `runs/README.md` 作为 dated execution evidence 聚合入口，但不物理搬动 root-level record；相应的主题入口、总索引、治理合同、审计脚本与 JUnit 也会继续要求“只要启用了 `runs/`，就必须有 `runs/README.md` 做正式入口”，避免再退回成“目录存在，但没有主题级入口”的半成品状态。
- `dialogue/` 这条业务主题也已按同一口径继续升级：新增 `runs/README.md` 聚合 acceptance / execution / precheck 证据，但不物理搬动 root-level dated 文档；后续这类主题内 evidence 聚合将继续沿用“先建 runs/README.md，再决定是否细分或迁移文档本体”的顺序。
- `continuity/` 这条业务主题现也已按同一口径继续升级：新增 `runs/README.md` 聚合 packet、legacy control route、multi-round 与 closure 相关 execution evidence，但仍保持“入口聚合，不搬动文档本体”的轻量策略；后续控制面主题的 dated 证据治理默认也应优先先补主题内聚合入口。
- `provider/` 这条业务主题现也已按同一口径继续升级：新增 `runs/README.md` 聚合 codex profile、route/recovery、CLI protocol 与 focused execution evidence，但仍保持“入口聚合，不搬动文档本体”的轻量策略；后续 provider 主题的 dated 证据治理默认也应优先先补主题内聚合入口。
- 四个已启用的 `runs/README.md` 现在也都把“主题级 evidence 聚合入口”这层职责写成了显式字面，不再只是靠上下文暗示；这意味着 `Run-DocsIndexAudit.ps1` 与 `DocsStructureContractTest` 现在不仅能检查 `runs/README.md` 存在、结构完整，也能继续防止它退回成“只有 dated 文档分组、没有入口角色说明”的半成品页面。

## 活跃子线

- 总索引与专题入口边界
- 工作区升级规则与当前现状同步
- dated 文档命名合同与历史例外口径
- PowerShell 审计脚本与 JUnit 结构回归
- 审计脚本可执行合同

## 下一步

- 如果文档治理继续产生密集 dated 证据，再考虑给 `meta/` 增加 `runs/`。
- 如果其他业务主题也开始持续高频续写，再按同样节奏只升级那个主题自己的 `PROGRESS.md`。
- 如果 `release/` 后续真的进入新一轮连续 precheck / dry-run / stage / commit 推进，再把它从 `README-only` 升级成主题工作区，而不是提前空建目录层。
- 如果后续再重写根目录公开入口或启动入口，先守住 `README.md` / `STARTUP_GUIDE.md` 的最小导航合同，再考虑文案压缩或版式调整。
- 如果后续某个主题新增 `PROGRESS.md`，默认也必须沿用这五段式结构，否则会被文档审计打红。
- 如果后续某个主题新增 `PROGRESS.md`，对应专题 `README.md` 也必须同步写出 `README.md -> PROGRESS.md -> 当前主线文档` 的默认阅读链。
- 如果后续某个主题新增 `runs/`，对应专题 `README.md` 与 `docs/README.md` 的默认阅读路径都必须显式暴露 `runs/README.md`，否则应视为“目录存在但入口缺失”的结构回归。
- 对已经启用了 `runs/` 的主题，这层合同现在还继续收紧到了默认阅读顺序本身：主题 README 不能再写成 `... -> runs/` 这种目录级指向，而要明确写成 `... -> runs/README.md`，避免入口语义比总索引更弱。
- 这轮又把 `runs/README.md` 本身收进了最小结构合同：四个已启用的 runs 入口虽然已经自然演化出一致写法，但之前还没有自动回归保护；现在固定要求它们继续保留 `命中信号 / 最小阅读顺序 / 当前分组 / 使用规则`，避免 evidence 入口退回成只剩一串 dated 文档名。
- 如果后续再往 `AGENTS.md` 回填大段项目百科内容，应该先判断这些事实是否应写回 `docs/ARCHITECTURE.md`、`docs/API_CONTRACTS.md`、`docs/SPEC.md`、`docs/TROUBLESHOOT.md` 或 `docs/WEB_CONSOLE.md`。
- 若后续再新增专题入口、升级某个主题工作区，或调整 root-level 正式 Markdown 归属，先跑 `Run-DocsIndexAudit.ps1`，再跑 `DocsStructureContractTest` 与 `DocsIndexAuditScriptTest`，避免根入口、总索引、专题入口和审计脚本本身之间出现漂移。

## 风险

- 未来若再新增专题或把 `release/` 也升级为工作区，需要同步 `WAKE.md`、`AGENTS.md`、`docs/README.md` 与 `DOCS_GOVERNANCE.md` 的状态块，否则会被结构回归直接打红。
- 如果后续直接改写 `README.md` / `STARTUP_GUIDE.md` 的导航文案，但忘了同步保留总索引、治理入口和专题回流路径，也会被这轮新增的根入口合同回归打红。
- 如果后续有人保留了 `PROGRESS.md` 文件名，却删掉最小结构段落，主题级连续性写回会退化成不可扫读的自由文本；这类漂移现在也应该被回归阻止。
- 如果后续有人保留了 `PROGRESS.md` 文件，但把专题 `README.md` 里的默认阅读链删掉，读者会重新退回“有进度文件却不知道该不该先读”的状态；这类入口漂移也应被回归阻止。
- 如果后续又把 `AGENTS.md` 扩回项目概述、技术栈、HTTP 端点长表和源码树摘要，而不把这些事实维护在正式基线文档里，根入口之间会重新出现职责漂移。
- 每次结构调整后，仍应跑 `Run-DocsIndexAudit.ps1`、`DocsStructureContractTest` 与 `DocsIndexAuditScriptTest` 做收口。
