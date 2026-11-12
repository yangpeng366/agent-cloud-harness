# Jev 决策模式吸收矩阵

> 方向表条目：`recvw6rvQKOTOF`（项目方向表「Jev 决策模式吸收清单」，父记录 `recvvQoJfFH1dY`「Jev 开源项目跟踪」）。
> 该行的「下一步」原文：
> 1) ACH FEAT-03：评估 tool/permission gate 与 pre-judgment 快通道；
> 2) OpenEyes/third-hand：评估 browser/action gate 与上下文裁剪；
> 3) Jev×飞书巡检：对照 skill/model routing 与 uncertainty band；
> 4) 每项先给最小验证，再决定是否拆开发任务。
>
> 本文件把上述四项落成可执行矩阵。**只写文档，不改业务代码**；本文件不新增任何 Jev 决策层，也不重复实现。
> 记录日期：2026-11-13。证据全部来自本机实测；路径口径见 §0（本仓相对、外部仓绝对路径 / 本地仓路径）。

## 0. 读法与口径

- 「Jev 现状与出处」写**已存在的实现与证据位置**，不含推测。凡本机无法验证的，写「待验证」。
- 「最小验证」是**一条命令或一个可观察断言**，能在本机跑出确定结论；本轮已逐条跑过，结论见 §5。
- 「建议」取三档：
  - **吸收** —— 值得落到某个既有方向上拆开发任务（写清落点）。
  - **仅观察** —— 当前不拆任务，只登记形态与观察点。
  - **不吸收** —— 与本轮四项无关或已被别处覆盖，明确不做。
- 落点仓只有三个，别的都不许新建：**ACH**（本仓，即本文件所在仓，路径一律写仓库相对路径）、**OpenEyes**（外部仓，本地仓路径 `D:\gitAll\openeyes`）、**飞书巡检**（外部仓，本地仓路径 `D:\gitAll\patrols\feishu-projects-patrol` + `D:\gitAll\patrol-scaffold` + `D:\gitAll\dsh-patrol`）。
- 上游目录仓 **yibie/awesome-jev**（外部仓，本地仓路径 `D:\gitAll\awesome-jev`，我方无写权）自身只做**目录侧**的事：条目出处、去重、格式、标签。目录里的模式已有条目由 §3 的出处列给出。
- **路径口径**：来源矩阵里的本仓绝对路径一律改成仓库相对路径（如 `docs/INITIATIVES/FEAT-03.md`、`src/main/java/...`），并逐个 `Test-Path` 校验；外部仓路径一律注明「外部仓」并保留绝对路径。本文出现的外部仓：`D:\gitAll\awesome-jev`、`D:\gitAll\openeyes`、`D:\gitAll\patrols\feishu-projects-patrol`、`D:\gitAll\patrol-scaffold`、`D:\gitAll\dsh-patrol`、`F:\github\third-hand`（本机无快照）；其 `categories\*.md`、`openeyes\...` 等均为该仓内写法，按本地仓路径看待。
- **链接口径**：指向外部仓本地路径的写法一律是「本地仓路径」纯文本 / 代码片段，**不是可点击的网页链接**；`http(s)` 链接为真实网页链接，照原样保留。

## 1. 矩阵

| 该项 | Jev 现状与出处 | 最小验证 | 建议 |
| --- | --- | --- | --- |
| **1. ACH FEAT-03 — tool / permission gate** | ACH 侧有两条线，都**不是**「Jev 驱动的 tool gate」：(a) `src/main/java/com/agentcloud/judgment/model/shadow/ToolGateShadowService.java` 只做**事后观察**，产出 `allow / block / approval_required` 三值 + 一条 `record_only` 的 DecisionEvent；其 provider `HeuristicToolGateProvider.java` 是**关键词规则**（destructive / network_egress / credential / out_of_workspace），不含 Jev。(b) `JevToolRecallFilter` **只有合同测试**（`src/test/java/com/agentcloud/scorer/JevToolRecallFilterTest.java`），`src/main` 下无该类——`JevToolRecall-Filter.md` 自述「缺生产代码 `JevToolRecallFilter` 类落 `src/main/java/com/agentcloud/recall/`（本地不存在）」。目录侧同类（外部仓 awesome-jev 本地仓路径）模式已有成熟条目：`categories\verification-guardrails.md:31`（jev-axi，44/44 标注调用）、`:32`（pi-verdict，allow/ask/deny 三态）、`:64`（local-decider，先规则后 Jev，112 例）、`:51`（dsh-jev-interceptor，64 tests）、`:18`（pi-jev）、`:38`（jev-engineering，300 调用注入测试）、`:47`（approval-judge-bridge，fail-closed）、`categories\agent-decisions.md:68`（jev-auto-approve，PreToolUse 0.95）、`categories\infra-sdks-integrations.md:47`（jev-use，fail-open PreToolUse）。 | `mvn -o test "-Dtest=ToolGateShadowServiceTest,JevToolRecallFilterTest" "-DfailIfNoTests=false"`（在本仓根目录，JDK21）→ 实测 `Tests run: 12, Failures: 0, Errors: 0` / `BUILD SUCCESS`。配套断言：`rg -n "JevToolRecallFilter" src/main` **0 命中**，`rg -n "implements ToolGateProvider" src/main` 只命中 `HeuristicToolGateProvider`。 | **吸收**。落点 ACH：给 `ToolGateProvider` 补一个 Jev 实现（复用既有 `JevScorer` 接口，不新开决策层），先落 shadow 模式。目录侧已覆盖得够，不新增条目。 |
| **1b. ACH FEAT-03 — pre-judgment 快通道** | `src/main/java/com/agentcloud/judgment/JevPrefilteredJudgmentService.java` 是 decorator：`KEEP_VERBATIM` → `source=jev_act`、**跳过 LLM**；`TRUNCATE_HEAD` → `source=skipped_by_jev`；异常 → 回退 delegate（`tryPrefilter` catch）。**但生产装配没做**：`src/main/java/com/agentcloud/cli/Main.java:204` 仍是 `new PromptBasedJudgmentService(llmClient)`，全仓 `rg -i "jev" src/main/java/com/agentcloud/cli/Main.java` **0 命中**；`new JevPrefilteredJudgmentService` 只出现在 `src/test`。`feature_flags.jev.context_scoring` 在 `harness-config.yml:51` 为 `false`，且 `FeatureFlags.jevContextScoring` 在 `src/main` 无消费方（只有 `HarnessConfigLoader` 读它）。`docs/INITIATIVES/FEAT-03.md` §16 声称已装配，与代码不符——**以代码为准**，差异写「待验证」（是否有意回退，未查到 commit 记录）。目录侧同类（外部仓 awesome-jev 本地仓路径）：`categories\classification-routing.md:24`（Harness Router，快路径 + 模糊才走 Jev）、`:73`（JevRouter，低于阈值返回 `no_decision`）、`categories\agent-decisions.md:24`（yoshi）。 | `mvn -o test "-Dtest=JevPrefilteredJudgmentServiceTest,JevJudgmentPrefilterTest" "-DfailIfNoTests=false"`（在本仓根目录，JDK21）→ 实测 `Tests run: 11, Failures: 0, Errors: 0` / `BUILD SUCCESS`。配套断言：`rg -n "new JevPrefilteredJudgmentService" src/main` **0 命中**。 | **吸收**。落点 ACH：`src/main/java/com/agentcloud/cli/Main.java:204` 处按 `feature_flags.jev.context_scoring` 装配 decorator（flag 已解析、已默认 false，回滚成本一行）。这是本轮**唯一「代码在、线没接」**的项，改造面最小。 |
| **2. OpenEyes / third-hand — browser / action gate** | OpenEyes（外部仓，本地仓路径 `D:\gitAll\openeyes`）已有完整决策层：`openeyes\decision\threshold.py`（4 带 ACT/FLAG_FOR_REVIEW/FALLBACK/UNCERTAIN + uncertain 带 `[0.30,0.70]`）、`router.py`（`decide()`，唯一入口，`enabled=False` 返回 `None` 走原脚本路径；只有 FLAG_FOR_REVIEW 置 `needs_attention=True`）、`scorer.py`（`FakeJevScorer` + `TypesafeJevScorer` + `validate_probabilities` 契约）、`scale.py`（阈值随 scale 走：hosted probability vs 本地 support_share）。但**没有接进动作路径**：`rg -n "decide\(" openeyes` 只命中 `decision\router.py` 自身；`openeyes\mcp\server.py` 与 `openeyes\cli\main.py` 里 `jev` / `decision` **0 命中**；生产上唯一消费者是 `openeyes\record\generator.py` 的 `backend="jev"`（`record` 生成器，非 MCP/CLI 动作）。third-hand 侧本机**无快照**（`F:\github` 为空目录，`F:\github\third-hand` 不存在，均为外部仓本地仓路径），只有 ACH 侧的转述：`docs/THIRD_HAND_COMPUTER_USE_RESEARCH.md`（§2.4 Jev 决策协议 `done≥0.70` / `absent≥0.50` / `pickedNone→BLOCKED`、11 个操作；§9 十条不变量）。third-hand 的两条安全护栏**已落 OpenEyes**：`openeyes\core\safety.py`（`validate_text_entry` 要求目标可编辑 + 文本必须出自 `source_text`；`is_foreground_window`）已被 `openeyes\mcp\server.py:65,767,1200` 与 `openeyes\cli\main.py:936` 引用。**未落**：third-hand 的 `checkFocus`（每步前置校验）本机 `rg -n "checkFocus" openeyes` **0 命中**；N-gram 候选抽取（引号短语 + 1..12 词滑窗）**0 命中**。目录侧同类（外部仓 awesome-jev 本地仓路径）：`categories\agent-decisions.md:18`（jev-agent-browser）、`:49`（GUI JEV Harness）、`:76`（jeff-act）、`categories\verification-guardrails.md:64`（local-decider）、`categories\content-moderation.md:20`（jev-screen-mcp）。 | `python -m pytest tests/decision/ -q`（外部仓 `D:\gitAll\openeyes`）→ 实测 `62 passed`。配套断言：`rg -n "decide\(" openeyes` 只命中 `openeyes\decision\router.py`（即无生产调用点）。 | **吸收**（但只吸收两件小事）：落点 OpenEyes —— (a) 把 `decide()` 接进 MCP/CLI 的动作路径（`enabled` 默认 false，保留 scripted 路径）；(b) 补 third-hand 的 `checkFocus` 前置校验。third-hand 快照本机缺失，其细节一律**待验证**，不得据此写实现。 |
| **2b. OpenEyes / third-hand — 上下文裁剪** | **OpenEyes 侧无上下文裁剪**：`rg -n -i "prune\|trim\|compact" openeyes` 只命中 `agent\attention.py` 与 `agent\profile.py` 的 `_prune()`（按时间窗清计数戳，与文本无关）和 `backends\dom_probe.py` 的 `.slice(0,200)`（单元素文本截断）——**没有会话历史裁剪层**。裁剪能力在 ACH：`src/main/java/com/agentcloud/scorer/compactor/JevContextCompactor.java`（5 段 state fitting `1000→200→60→text_abridge→collapse`、双 noul `keepCall/keepResult`、pinned 消息免评、纯函数 token 估算）。**但 `compact()` 不落地重建**：`applyDecisions` 在 `src/main` 内**只被定义、无调用者**（`compact()` 第 420 行注释「Rebuild not done here」，`charsAfter = charsBefore`），且 `src/main` 下**没有** `import com.agentcloud.scorer.compactor` 的生产类——只有 `JevContextCompactorDemo` 与测试在用。目录侧同类（外部仓 awesome-jev 本地仓路径）：`categories\agent-decisions.md` 10 条 compaction 族（fast-jev-compaction、pi-fast-jev-compaction、fast-dev-compaction、jev-compaction、jev-pruner、lossless-compaction、opencode-jev-compaction、opencode-jev-safe-compact、yoshi、hermes-jev-skills）+ `categories\related-practices-discussions.md:29,47`（含一条公开反对意见「compaction 是重建不是过滤」）。 | `mvn -o test "-Dtest=JevContextCompactorTest" "-DfailIfNoTests=false"`（在本仓根目录，JDK21）→ 实测 `Tests run: 24, Failures: 0, Errors: 0` / `BUILD SUCCESS`。配套断言：`rg -n "applyDecisions" src/main` → 只有定义行，无调用行。 | **吸收**（落 ACH，不落 OpenEyes）：把 `compact()` 接上 `applyDecisions` 并让 `charsAfter` 真实变小，再接进 `TaskRuntimeContextBuilder` 出口。OpenEyes 侧**不吸收**——它没有会话历史可裁。 |
| **3. Jev × 飞书巡检 — skill / model routing** | 巡检侧**没有 Jev 驱动的 skill 或 model 路由**：`D:\gitAll\dsh-patrol\src\`（外部仓本地仓路径）内 `rg -n -i "jev" src/` **0 命中**；dispatcher 是三选一固定枚举（`src\dispatch.ts:53,67,103` → `log / codex / dsh`），由 `src\index.ts:70` 的 `dispatcher` 配置项决定，不是决策。skill 侧只有该仓自带的 `skills\dsh-patrol\SKILL.md` 一个技能。model 侧在 `D:\gitAll\patrols\feishu-projects-patrol\feishu-projects-patrol-config.json:30-41`（外部仓本地仓路径）的 `model_promotion`（固定 `glm-5.3-flash` + `reasoning_effort: medium` + `auto_compact_token_limit`），由 `Start-FeishuProjectsWorkLoop.ps1:77-79,423-425` 透传成 `-c model_context_window=…`——**时间窗切换，不是 Jev 选型**。目录侧同类（外部仓 awesome-jev 本地仓路径）：`categories\classification-routing.md:40`（jev-skill-router，0.30 双门 + shadow mode）、`:45`（pi-jev-skill-picker）、`:54`（langchain-skill-router，两步 progressive disclosure）、`:73`（JevRouter）、`:18`（jev-router）、`:20`（pi-jev-router）、`:76`（decision-router，73%→93%）、`:81`（pi-gear-router，五档）、`categories\infra-sdks-integrations.md:56`（jev-skill-suggester）、`categories\agent-decisions.md:59`（SkillRanker）、`:55`（hermes-jev-skills）、`categories\evaluation-benchmarking.md:59`（s1kit，逐问题 cutoff）。官方方法论：`docs/JEV_OFFICIAL_SKILL_ABSORPTION.md` §3.5（两步渐进披露，Hermes 182 skill：错装 16.8%→7.3%）、§3.2（confidence-routing 0.6/0.85）。 | `pwsh -NoProfile -File tests\verify-jev-decision.ps1`（外部仓 `D:\gitAll\patrols\feishu-projects-patrol`）→ 实测 `summary pass=12 fail=0`（含「mid band → HUMAN_REVIEW」）；同脚本在外部仓 `D:\gitAll\patrol-scaffold` → `summary pass=9 fail=0`。配套断言：`rg -n -i "jev" src/`（在 dsh-patrol）**0 命中**。 | **仅观察**。巡检当前是「固定 executor + 固定 model + 单 skill」，没有可选候选集，Jev 路由**没有可路由的对象**；真要做，候选集得先存在。另注：`s1kit`（逐问题 cutoff）证明全局阈值不足以表达逐问题精度，官方 `parallel_questions` 的整批 OR 聚合会把 80/80 问题全推强档（`docs/evaluation/runs/2026-11-04-jev-feat03-item4-candidate-draft.md` §5）——将来若做批量，必须逐问判定、取最严。 |
| **3b. Jev × 飞书巡检 — uncertainty band** | 巡检侧**已落地**这条：`D:\gitAll\patrols\feishu-projects-patrol\scripts\JevDecision.ps1`（外部仓本地仓路径；第 10 行 `$t = @{ low = 0.30; high = 0.70 }`；第 35/38/40 行 `KEEP_VERBATIM(low_band)` / `TRUNCATE_HEAD(high_band)` / `HUMAN_REVIEW(uncertainty_band)`；第 25/28/31 行 `FALLBACK` 覆盖 disabled / `jev_error` / `no_probability`），`D:\gitAll\patrol-scaffold\scripts\JevDecision.ps1` 同构（第 40 行同一 `uncertainty_band`）。配置默认关：`feishu-projects-patrol-config.json:100-106` 的 `decision.enabled=false` + `thresholds {low:0.3, high:0.7}`。设计依据：`docs/JEV_PATROL_PHASE4_DESIGN.md` §2 决策准入表（0.30/0.70 三带，异常一律 `fallback to current behavior`）。目录侧同类（外部仓 awesome-jev 本地仓路径）：`categories\classification-routing.md:51`（jev-oncall，0.80/0.20 双阈 + 中带给人 15 分钟 ack）、`categories\verification-guardrails.md:56`（jev-secret-guard，0.80 拦 / 0.30 问）、`:50`（JevGate，未决即 `uncertain`）、`categories\infra-sdks-integrations.md:74`（Jev AI Tools，不确定路线暂停复核）。OpenEyes 侧同名机制：`openeyes\decision\threshold.py:57` 的 `[0.30,0.70]`，但 `scale.py:42-48` 在本地 support_share scale 上**故意留空**该带（share 没有「危险中段」）——两边同名不同义，**待验证**是否需对齐。 | `pwsh -NoProfile -File tests\verify-jev-decision.ps1`（外部仓 `D:\gitAll\patrols\feishu-projects-patrol`）→ 实测 `summary pass=12 fail=0`，其中包含 `PASS mid band → HUMAN_REVIEW`；同脚本在外部仓 `D:\gitAll\patrol-scaffold` → `summary pass=9 fail=0`。 | **吸收**（落点：巡检侧，非本仓）。但**只到「shadow 证据齐备」为止**：`docs/JEV_PATROL_PHASE4_DESIGN.md` §4 的开闸门槛是 `TP+TN` 显著大于 `FP+FN`（参考 75%）且 uncertainty 带复核率 ≥60%，未达标前 `decision.enabled` 保持 false。目录侧不新增条目（已有 4 条同类）。 |

## 2. 四项的落点汇总

| 项 | 落点仓 | 落点文件/位置 | 是否拆任务 | 前置 |
| --- | --- | --- | --- | --- |
| 1 tool / permission gate | ACH | `src/main/java/com/agentcloud/judgment/model/shadow/ToolGateProvider.java` 的 Jev 实现（**不新开 provider 体系**） | 是 | 先 shadow 模式，无阻断力 |
| 1b pre-judgment 快通道 | ACH | `src/main/java/com/agentcloud/cli/Main.java:204` 装配 `JevPrefilteredJudgmentService` | 是 | flag 已解析、默认 false |
| 2 browser / action gate | OpenEyes | `decision\router.py` 的 `decide()` 接进 MCP/CLI 动作路径 | 是 | `enabled` 默认 false，保留 scripted 路径 |
| 2 browser / action gate（护栏） | OpenEyes | 补 `checkFocus` 前置校验（`core\safety.py` 已有 `is_foreground_window` 可复用） | 是 | third-hand 细节**待验证**（本机无快照） |
| 2b 上下文裁剪 | ACH | `src/main/java/com/agentcloud/scorer/compactor/JevContextCompactor.java` 的 `compact()` 接 `applyDecisions` → `TaskRuntimeContextBuilder` 出口 | 是 | `charsAfter` 必须真实变小 |
| 2b 上下文裁剪 | OpenEyes | — | 否 | OpenEyes 无会话历史可裁 |
| 3 skill / model routing | 飞书巡检 | — | 否 | 无候选集可路由 |
| 3b uncertainty band | 飞书巡检 | 已落地，`decision.enabled` 保持 false | 否（待开闸证据） | TP+TN 占比 ≥75% + 不确定带复核率 ≥60% |

**与方向表口径漂移（本轮唯一一处）**：ACH 的 `docs/INITIATIVES/FEAT-03.md` §16 声称 `Main` 已按 flag 装配 `JevPrefilteredJudgmentService`，但 `rg -i "jev" src/main/java/com/agentcloud/cli/Main.java` 是 0 命中、该 decorator 在 `src/main` 无实例化点。**以代码为准**，差异写「待验证」，别照抄文档。

## 3. 目录侧（awesome-jev）现状

> 本节描述上游目录仓（外部仓 `D:\gitAll\awesome-jev`，我方无写权）的现状，非本仓。

- 条目总数 **679**，分类 15 个 + 1 个待播种（`categories/scientific-pipelines.md`）。核对命令见 §5。
- 三类模式在目录里都**已有覆盖**，故本轮**不新增条目**：
  - tool / permission gate：`verification-guardrails.md` 8 条 + `agent-decisions.md` 1 条 + `infra-sdks-integrations.md` 1 条。
  - 上下文裁剪：`agent-decisions.md` 10 条 + `related-practices-discussions.md` 2 条（含一条公开反对意见，条目已写「compaction 是重建不是过滤」）。
  - skill / model routing：`classification-routing.md` 4+4 条 + `infra-sdks-integrations.md` 1 条 + `agent-decisions.md` 1 条。
  - uncertainty band：`classification-routing.md` 6 条 + `verification-guardrails.md` 3 条 + `scoring-ranking.md` 3 条等。
- 同源 URL 重复检查：679 条目 / 679 个不同 URL / **0 重复**（§5 命令 10）。

### 3.1 目录侧被引条目的公开来源

下列条目即本矩阵各行「目录侧同类」所指，全部已在外部仓 `D:\gitAll\awesome-jev` 的 `categories/*.md` 目录内（分类文件 + 行号见 §1）；此处给出公开链接，便于复核。链接取自该仓 `categories/*.md` 条目本身，未另行拼写；均为真实网页链接。

| 模式 | 条目 → 公开链接 |
| --- | --- |
| tool / permission gate | [jev-axi](https://github.com/shiftynick/jev-axi) · [pi-verdict](https://github.com/jesset/pi-verdict) · [local-decider](https://github.com/pusucip25/local-decider) · [dsh-jev-interceptor](https://github.com/AskTheWay/dsh-jev-interceptor) · [pi-jev](https://github.com/y0usaf/pi-jev) · [jev-engineering](https://github.com/eugeniughelbur/jev-engineering) · [approval-judge-bridge](https://github.com/oppih/approval-judge-bridge) · [jev-auto-approve](https://github.com/BasmaAbouzied0/jev-auto-approve) · [jev-use](https://github.com/shitianfang/jev-use) |
| pre-judgment 快通道 | [Harness Router](https://github.com/Protocol-Lattice/harness-router) · [JevRouter](https://github.com/BillionsBobby/JevRouter) · [yoshi](https://github.com/compozy/yoshi) |
| browser / action gate | [jev-agent-browser](https://github.com/forvela/jev-agent-browser) · [GUI JEV Harness](https://github.com/ZihuaEvan/GUI_JEV) · [jeff-act](https://github.com/saembit/jeff-act) · [jev-screen-mcp](https://github.com/jiawei686/jev-screen-mcp) · [local-decider](https://github.com/pusucip25/local-decider) |
| 上下文裁剪 | [fast-jev-compaction](https://github.com/tamaratran/fast-jev-compaction) |
| skill routing | [jev-skill-router](https://github.com/shimo4228/jev-skill-router) · [pi-jev-skill-picker](https://github.com/safzanpirani/pi-jev-skill-picker) · [langchain-skill-router](https://github.com/deyna256/langchain-skill-router) · [jev-skill-suggester](https://github.com/win4r/jev-skill-suggester) · [SkillRanker](https://github.com/Dicklesworthstone/skillranker) · [hermes-jev-skills](https://github.com/kerpopule/hermes-jev-skills) |
| model routing | [jev-router](https://github.com/gargpratyush/jev-router) · [pi-jev-router](https://github.com/mejiasd3v/pi-jev-router) · [decision-router](https://github.com/Ruivalim/decision-router) · [pi-gear-router](https://github.com/joslynSmall/pi-gear-router) · [s1kit](https://github.com/Eliot5566/s1kit) |
| uncertainty band | [jev-oncall](https://github.com/mingleiw/jev-oncall) · [jev-secret-guard](https://github.com/BasmaAbouzied0/jev-secret-guard) · [JevGate](https://github.com/Tech-Byte-Frontier/jevgate) · [Jev AI Tools](https://jevai.tools/) |

官方方法论原文（转述的落地依据）：`https://docs.typesafe.ai/cookbooks/skill_suggestion`（两步渐进披露）、`https://docs.typesafe.ai/patterns/confidence-routing`（0.6 / 0.85 双阈）、`https://docs.typesafe.ai/cookbooks/consistency_noul_cookbook`（uncertain band 0.30–0.70）。本机快照：`docs/JEV_OFFICIAL_SKILL_ABSORPTION.md` §3.2 / §3.3 / §3.5。

## 4. 明确不做的事

- 不新增 Jev 决策层：四处落点全部复用既有接口（ACH `JevScorer` / `ToolGateProvider`、OpenEyes `JevScorer` + `decide()`、巡检 `JevShadow.ps1` + `JevDecision.ps1`）。
- 不重复实现：目录侧已有条目描述的形态（SkillRouter、compaction 族、permission gate 族）不再另写条目。
- 不动业务代码：本文件与 `AGENTS.md` 只是文档。
- third-hand 无本机快照，其实现细节一律待验证，不得照转述直接写码。

## 5. 本轮实跑的验证命令与结果

| # | 命令（工作目录） | 结果 |
| --- | --- | --- |
| 1 | `mvn -o test "-Dtest=ToolGateShadowServiceTest,JevToolRecallFilterTest" "-DfailIfNoTests=false"`（本仓根目录，`JAVA_HOME=C:\Program Files\Java\jdk-21.0.9+10`） | `Tests run: 12, Failures: 0, Errors: 0` / `BUILD SUCCESS` |
| 2 | `mvn -o test "-Dtest=JevPrefilteredJudgmentServiceTest,JevJudgmentPrefilterTest" "-DfailIfNoTests=false"`（本仓根目录） | `Tests run: 11, Failures: 0, Errors: 0` / `BUILD SUCCESS` |
| 3 | `mvn -o test "-Dtest=JevContextCompactorTest" "-DfailIfNoTests=false"`（本仓根目录） | `Tests run: 24, Failures: 0, Errors: 0` / `BUILD SUCCESS` |
| 4 | `python -m pytest tests/decision/ -q`（外部仓 `D:\gitAll\openeyes`） | `62 passed` |
| 5 | `pwsh -NoProfile -File tests\verify-jev-decision.ps1`（外部仓 `D:\gitAll\patrols\feishu-projects-patrol`） | `summary pass=12 fail=0` |
| 6 | `pwsh -NoProfile -File tests\verify-jev-decision.ps1`（外部仓 `D:\gitAll\patrol-scaffold`） | `summary pass=9 fail=0` |
| 7 | `python scripts/build-readme.py`（外部仓 `D:\gitAll\awesome-jev`） | 无输出（生成器静默），`git diff` 空 |
| 8 | `python scripts/audit-tags.py categories`（外部仓 `D:\gitAll\awesome-jev`） | `tags check out (0 notice(s)).`，退出码 0 |
| 9 | `python -m unittest discover -s scripts/tests`（外部仓 `D:\gitAll\awesome-jev`） | `Ran 34 tests in 0.842s` / `OK` |
| 10 | URL 去重脚本（扫外部仓 `categories/*.md` 的 `- [..](url)` 行） | `total entries with url: 679` / `distinct urls: 679` / `duplicate urls: 0` |
