# Jev Context Scoring Plan（FEAT-03 · 借鉴类落地设计）

> 立项依据：`docs/JEV_CONTEXT_SCORING_RESEARCH.md`（借鉴类调研）。
> 维护约定：本文档是 `FEAT-03` 的"开 issue 对齐字段设计后"草稿；未签字不开工；签字后由本文档接管主线，调研文档转为"立项依据"。
> 凭据纪律：`TYPESAFE_API_KEY` 任何时候不进仓库；接入前由维护者决定存放位置（推荐 `~/.openclaw/.../secrets/`，沿用 AGENTS.md "credentials 不进仓库"口径）。

## 0. 摘要

在 ACH 控制面主链引入 TypeSafe Jev 作为**可选 System One Model 门控**，挂在 `TaskRuntimeContextBuilder` 之前、`HandoffPacketBuilder` / `RefinedPacketBuilder` 出口、`PromptBasedJudgmentService` 之前三个明确切入点。**不替换**任何现有 LLM judgment 服务，仅在"机械 / 高频 / 显然 yes-no"判决那一层提供 System One = 高频 / 低成本 / 确定性判决的快速通道，把 LLM 从这些机械判断里释放出来。

## 1. 字段设计（待 issue 对齐签字）

### 1.1 `JevContextScorer` 内部状态

```text
JevContextScorerConfig {
  boolean enabled                       # default false；feature flag，由 harness-config.yml 控制
  String apiKeyEnvVar                   # default "TYPESAFE_API_KEY"；运行时从环境变量读
  String baseUrl                        # default "https://api.typesafe.ai"（2026-11-04 订正：原缺一点，与代码 JevContextScorerConfig.DEFAULT_BASE_URL 不一致）
  int    requestTimeoutMs               # default 5000；>5000ms 直接 fallback
  double keepThreshold                  # default 0.5 (probability threshold; 官方更推荐 confidence 3 段阈值,见 §1.1a)
  int    preserveRecentMessages         # default 6
  int    maxStateTokens                 # default 25000
  int    maxRequestTokens               # default 30000
  int    truncateHeadChars              # default 300
  double circuitBreakerFailureRate      # default 0.5（连续 N 次失败中超过此比例触发熔断）
  int    circuitBreakerWindow           # default 20
  int    circuitBreakerCooldownSec      # default 60

### 1.1a Confidence 3 段阈值配置（官方吸收 2026-09-21）

基于 https://docs.typesafe.ai/confidence.md 与 https://docs.typesafe.ai/patterns/confidence-routing，ACH 借鉴 Jev 应同时支持 probability + confidence 双轴：

```text
JevContextScorerConfig {
  ...(原字段保持)...
  double confidenceFloor               # default 0.50  (官方 voice banking 3 段 threshold floor)
  double lowStakesThreshold            # default 0.60  (confidence-routing low-stakes 行为线)
  double highStakesThreshold           # default 0.85  (confidence-routing high-stakes 行为线)
  int    scoreRankingTopK              # default 3     (skill_suggestion progressive disclosure 形态)
  int    scoreNarrowVerifyTopN         # default 3     (skill_suggestion narrow verify)
  ThresholdDict handoffKeepProbThresholds  # 4 个 THRESHOLDS (classifying_rag THRESHOLDS dict 模式)
  ThresholdDict guardrailThresholds       # 4 个 guardrail thresholds (llm_guardrails cookbook)
}
```

**ThresholdDict 模式**（来自 classifying_rag_passages cookbook 官方原话）：所有 thresholds 集中在一个 dict，不散落；改 policy 时是 constant edit under code review，不是 reworded question。

```text
ThresholdDict {
  // HandoffPacket keep-probability（来自 classifying_rag_passages）
  double injectionMax          # default 0.70   # 高于此 -> exclude (prompt injection 类)
  double contradictsMin        # default 0.70   # 高于此 -> conflicting_evidence
  double relevantMin           # default 0.45   # 低于此 -> exclude (不相关)
  double evidenceMin           # default 0.55   # 高于此 -> include
  // Guardrails（来自 llm_guardrails cookbook）
  double jailbreakThreshold    # default 0.70
  double injectionThreshold    # default 0.70
  double hiddenInstructionMax  # default 0.50
  double harmScoreThreshold    # default 0.60
}
```

}
```

### 1.2 Runtime 观测字段（写进 `TaskRuntimeContext.mounted_context_view` 与 `judgment_trace`）

- `jev_call_count`：本轮 Jev 调用次数
- `jev_decision_keep`：被保留条目数
- `jev_decision_truncate`：被 truncateHeadChars 降级条目数
- `jev_decision_drop`：被丢弃条目数（按 `fast-jev-compaction` 设计默认不 drop，仅 truncate）
- `jev_latency_ms_p50 / p95`：本轮延迟分布
- `jev_fallback_reason`：当轮 fallback 触发原因枚举（`none / timeout / 5xx / circuit_open / bad_request / empty_response / unknown`）
- `jev_circuit_state`：`closed / open / half_open`
- `source` 标注：被 Jev 跳过的 LLM judgment 步骤写 `source=jev_skip`

### 1.3 HandoffPacket / ResumePacket 增量字段（向后兼容）

- `key_decisions` / `key_artifacts` / `open_questions` 每项追加 `keep_probability: double`（0.0–1.0）
- 新增顶层 `## footnotes` 段：被降级的 low-probability 字段集中放在这里，**不删除**

### 1.4a Jev 三种 answer 形态（hands-on 修正 2026-09-21）
基于 fast-jev-compaction v0.2.0 src/types.ts:JevAnswer = NoulAnswer | ChoiceAnswer | ScoreAnswer，ACH 接入时三种都要支持：
- `noul: number (0-1)` — yes/no + 概率，用于 compaction keep/drop 二元判定
- `choice: string + confidence + probabilities` — 多选一 + 概率分布，可用于 worker routing candidate selection / fallback 策略选择
- `score: number + confidence + probabilities` — 评分 + 概率分布，**HandoffPacketBuilder keep_probability 字段可直接用 score 字段，无需归一**
- `packet_meta.jev_scoring_applied: boolean`：当轮是否走 Jev 评分

### 1.4 LLM Judgment 前置过滤观测

- `runtime_facts.judgment_pre_filter`：结构 `{ jev_called, jev_decision, llm_called, source }`
- `/judgment_trace` 视图加 `pre_filter_decision` 节点，跳过 LLM 时显式标注 `source=jev_skip`

## 2. 默认值来源

全部默认值与 `fast-jev-compaction` 一致，便于跨语言对照与社区一致性：

| 参数 | 默认值 | 来源 |
|---|---|---|
| `keepThreshold` | `0.5` | `fast-jev-compaction` `src/scoring.ts` |
| `preserveRecentMessages` | `6` | 同上 |
| `maxStateTokens` | `25000` | 同上 |
| `maxRequestTokens` | `30000` | 同上 |
| `truncateHeadChars` | `300` | 同上 |
| `requestTimeoutMs` | `5000` | Jev API 声称 70–500ms，留 10× 安全余量 |
| `circuitBreakerFailureRate` | `0.5` | 工程保守值，避免抖动时反复 fallback |
| `circuitBreakerWindow` | `20` | 短窗口，留意瞬时抖动 |
| `circuitBreakerCooldownSec` | `60` | 与 API 限流一般 reset 时间对齐 |

## 3. Fallback 合同（强制条款）
**hands-on 修正（2026-09-21）**：fast-jev-compaction v0.2.0 的 compact() 函数签名只接受 `asker: JevAsker`，**任何 throw 都由 caller 决定 fallback**（库内部不分 6 类）。下表 §3.1–§3.6 是 caller 侧的等效拆解，**ACH 接入时统一 catch → 按 JevAsker 抛出点判类型 → 调用对应 fallback**，不要在库侧做 6 路分支。

**官方 confidence 3 段修订（2026-09-21）**：基于 https://docs.typesafe.ai/confidence.md，**主路径走 confidence 3 段行为**：
```text
confidence >= highStakesThreshold (0.85)  -> act automatically (verbatim keep)
lowStakesThreshold <= confidence < highStakesThreshold (0.60~0.85) -> flag for human review / 收集更多信息 (keep with footnote)
confidence < lowStakesThreshold (0.60)  -> fallback 路径 (字符级硬截 / LLM summary / 直接调 LLM,按 §3.1-§3.6)
```
uncertain band 0.30~0.70 区间（来自 consistency_noul_cookbook）默认送人审，**跟 3 段 threshold 行为叠加**：prob 在 [0.30, 0.70] 且 confidence < 0.85 时进入"高风险 + 中等概率"分支，必须二次确认。


任何 Jev 调用失败都必须有兜底路径，**不允许**因为 Jev 不可用导致主链路停滞：

| 失败原因 | 兜底动作 | 观测字段 |
|---|---|---|
| 401 / 403（key 错误） | 立即熔断 5 分钟，写 `runtime_facts.jev_config_error` | `jev_fallback_reason=auth_error` |
| 429 / 5xx（限流 / 服务端） | 重试 1 次（指数退避 200ms），失败后 fallback | `jev_fallback_reason=5xx` |
| timeout（>5s） | 直接 fallback，不重试 | `jev_fallback_reason=timeout` |
| 业务错误（bad_request） | fallback，**不**触发熔断（认为是 caller bug） | `jev_fallback_reason=bad_request` |
| 空响应 / 格式错乱 | fallback | `jev_fallback_reason=empty_response` |
| 熔断开启 | 完全跳过 Jev，fallback 走当前字符级硬截 / LLM 摘要 | `jev_fallback_reason=circuit_open` |

Fallback 时**严格保留**当前行为：
- `TaskRuntimeContextBuilder` fallback → 当前 `mounted_context_budget_truncated` 字符级硬截（不改）
- `HandoffPacketBuilder` / `RefinedPacketBuilder` fallback → 当前 LLM 摘要（不改）
- `PromptBasedJudgmentService` fallback → 当前直接调 LLM（不改）

## 4. 测试合同

### 4.1 单元测试

- `JevContextScorerConfigTest`：默认值、环境变量优先级、`enabled=false` 短路
- `JevCircuitBreakerTest`：5 失败 / 20 调用触发熔断；60s 后进入 half-open；单次成功恢复 closed
- `JevRequestBuilderTest`：payload 序列化、`maxRequestTokens` 截断、`preserveRecentMessages` 边界
- `JevResponseParserTest`：typed decision + probability 解析；空响应 / 非法 JSON / probability 越界 全部 fallback

### 4.2 Fake Jev（不联外网）

`JevContextScorerFake`：实现与 `fast-jev-compaction` `tests/` 一致的伪实现，用于 CI 跑通。

### 4.3 集成 / focused 回归

- `TaskRuntimeContextBuilderJevScoringTest`：对比 `enabled=true / false` 两条路径的 `mounted_context_*` 字段
- `HandoffPacketJevKeepProbabilityTest`：跨 worker handoff 后 `key_decisions` 数量稳定性（同一 baseline task 跑 N 次，方差 ≤ 1）
- `PromptBasedJudgmentJevPreFilterTest`：`judgment_pre_filter` 命中时 LLM 调用跳过，且 `/judgment_trace` 标注 `source=jev_skip`

### 4.4 端到端（baseline matrix 扩展）

- `medium-001` / `long-001` 在 `jev.context_scoring=true` 与 `false` 两条路径各跑 1 次，对比 `mounted_context_panel_count` / `runtime_cognition_surface.prompt_budget` / `key_decisions` 数量稳定性 / judgment API 调用频次 / 平均 latency。
- 输出物：1 份 dated `*_EXECUTION_RECORD_YYYY-MM-DD.md` + 1 份 `*_ACCEPTANCE_RECORD_YYYY-MM-DD.md`。

## 5. 可观察性 / 排障口径

- `docs/TROUBLESHOOT.md` 新增"jev 上下文评分门控"小节：覆盖 key 缺失、限流、5xx、timeout、circuit_open 五类常见排障场景。
- `docs/API_CONTRACTS.md` runtime_context / judgment_trace 段增量字段。
- `runtime_facts.jev_*` 与 `/judgment_trace` 双通道可观测；与现有 `mounted_context_*` 同层级观测面。
- 排障自验命令：`powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1`（仍需绿）；新增 `scripts\Test-WithJava21.ps1 -Dtest=JevContextScorer*` focused 套件入口。

## 6. 凭据与依赖纪律（强制）

- `TYPESAFE_API_KEY` 仅通过环境变量读，**绝不**写入 `harness-config.yml`、`.env`、任何 docs、任何代码注释、任何 git 历史。
- key 存放位置由维护者决定；推荐 `~/.openclaw/.../secrets/`（沿用 AGENTS.md），与本机现成的 OpenClaw / CCX 凭据惯例一致。
- 不在 CI / precheck / dry-run / smoke 里硬编码 key 或触发真实 Jev 调用；CI 一律走 `JevContextScorerFake`。
- `enabled=false` 时完全不联网、不读 key、不发任何请求，**保证零外部依赖**。

## 7. 回滚纪律

- `feature_flag: jev.context_scoring = false` 即可全量关闭，恢复到当前基线。
- 关闭时所有观测字段保留（仅 `jev_call_count = 0`、`jev_fallback_reason = disabled`），保证历史 trace 不漂移。
- 配置回滚成本 < 1 行（harness-config.yml 单字段）。

## 8. 与现有契约的对齐

- `docs/HARNESS_CHANGE_CONTRACT.md`：本变更属于 `Contract-Additive`（仅增字段、不删字段、不改状态机），按既有流程走 contract test + focused 回归。
- `docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md`：Jev 接入走 JDK 21 内置 java.net.http.HttpClient 直连 https://api.typesafe.ai/v1/systemone（**Jev API 是自家专用 endpoint，不是 OpenAI-compatible**；基于 2026-09-21 hands-on 验证 fast-jev-compaction v0.2.0 src/request.ts: buildJevRequest）。独立 client，不复用现有 OpenAI-compatible endpoint，避免 key 与 channel 耦合。
- `docs/PERSISTENCE_ARTIFACT_INVENTORY.md`：`runtime_facts.jev_*` 与 `packet_meta.jev_scoring_applied` 都属于 derived 层，不进 DB schema。

## 9. 验收（与 `CONTRIBUTING.md` FEAT-03 验收项一致）

- `active_context_only` 与 `mounted_context_primary` 两条模式下，单轮 task 平均 token 消耗下降 30%+（baseline matrix 端到端证据）。
- `mounted_context_budget_truncated` 命中率在 `enabled=true` 时显著低于基线（默认配置下接近 0%）。
- 跨 worker handoff 后 `key_decisions` 数量稳定不漂移（同一 baseline task N 次，方差 ≤ 1）。
- judgment API 调用频次 -50%、平均 latency -40%（`enabled=true` vs `false` baseline matrix 对照）。
- 关闭 `jev.context_scoring` 时所有指标回到当前基线，回滚成本 < 1 行配置。
- docs audit（`Run-DocsIndexAudit.ps1`）仍 `passed=true`；focused 回归全绿。

## 10. 风险

| 风险 | 缓解 |
|---|---|
| TypeSafe API 商业化、限流、SLA 变化 | fallback + circuit breaker；接入前后必须保留 `enabled=false` 默认 |
| TypeSafe 政策变化导致 key 形态、URL 变化 | `apiKeyEnvVar` + `baseUrl` 都可配置，不硬编码 |
| LLM 团队误以为 Jev 替换了 judgment 服务 | docs/API_CONTRACTS.md 显式写"System One 门控层，不替换 LLM judgment" |
| `key_decisions` keep probability 被滥用为排序依据 | 写明 keep_probability 是 0–1 实数，**不可**作为排序权重；只在 handoff 时做保留/降级决策 |
| Jev 输出概率被误读为"安全删除证据" | fallback 路径明确写"fallback 是安全选择，Jev 只是加速"；保留逐字 verbatim 才能保证 LLM 后续能精确看到 |

## 11. 立项门槛（issue 对齐 checklist）

维护者签字前必须明确以下 8 条；任一条悬而未决都不签字：

1. `feature_flag` 命名锁定为 `jev.context_scoring`，开关语义是"全量短路"而非"per-task"。
2. `apiKeyEnvVar` 候选 `TYPESAFE_API_KEY` 或 `OPENROUTER_API_KEY` 二选一；**OpenRouter 已代理 typesafe/jev-latest（已实测 2026-09-21）**，key 实际存放位置由维护者决定，推荐 `~/.openclaw/secrets/<name>.key`。
3. `requestTimeoutMs = 5000` 是否接受；不接受则改 `3000` 或 `8000`，并改 fallback 合同。
4. `keepThreshold = 0.5` 是否接受；不接受则改 `0.6`（更保守）或 `0.4`（更激进）。
5. fallback 路径严格保持现有行为（不改字符级硬截、不改 LLM 摘要、不改直接调 LLM judgment）。
6. CI 不联外网，Fake Jev 必须实现与 `fast-jev-compaction` 测试套件同等的最低覆盖。
7. `runtime_facts.jev_*` 与 `/judgment_trace` 观测字段同时上；只上一条会被 issue 打回。
8. 不引入新 Maven 依赖；Jev HTTP 客户端走 JDK 21 内置 HttpClient + Jackson（仓库已有）。
9. questions 和 thresholds 必须集中定义在 `JevContextScorerConfig.java` 单文件（来自官方 typesafe-ai/skills SKILL.md "common issues" 第 4 条）；改 policy 时是 constant edit under code review，不是 reworded question。


> 2026-10-24 上游印证（仅记录证据，**不改门槛语义**）：yibie/awesome-jev 本轮并入 `opencode-jev-safe-compact` 条目（PR #384，2026-10-22）。该条目写明：`Noul` 分数会随状态形状漂移，而其 AUC 仍稳定在 0.70-0.83，因此该插件按**排序**而非绝对阈值决定裁掉多少。本计划第 4 条的绝对阈值 `keepThreshold = 0.5`（同 `fast-jev-compaction` 源）正属同一形态 —— 签字前应把「绝对阈值 vs 排序保留 + AUC 锚定」一并比对。证据：`docs/evaluation/runs/2026-10-24-awesome-jev-ecosystem-watch.md`。

> 2026-10-31 上游印证（仅记录证据，**不改门槛语义**）：本轮上游目录并入 `s1kit`（2026-10-28 新仓，MIT，0 star，6 open issues）与 `dsh-jev-goal-judge`（2026-10-12，MIT，1 star），二者 README 均已一手核验。
>
> - `s1kit` 对应第 4 条：`s1kit calibrate results.jsonl --target 0.95` **按问题**各取一个达到目标精度的最低置信度阈值；其自带样例给出 `keyword/mood >= 0.5761 -> 100.0% 精度、覆盖 25%`、`keyword/team >= 0.3333 -> 100.0% 精度、覆盖 100%`、`keyword/urgent >= 0.7143 -> 100.0% 精度、覆盖 40%`——同一模型在不同问题上的可用阈值相差 2 倍以上，故全局阈值（`keepThreshold = 0.5`；§1.1a 的 `lowStakesThreshold 0.60` / `highStakesThreshold 0.85`）要么误放、要么误杀。签字前应把「全局阈值 vs 按问题拟合阈值（附目标精度与覆盖率）」与 2026-10-24 的「绝对阈值 vs 排序保留」并列为同一决策项。证据等级：lead（0 star、三天新仓、README 数字未复现）。
> - `dsh-jev-goal-judge` 对应第 9 条：该插件把**问题文本**与**被观察内容**分开——instruction 字符串为常量，transcript 只进 `state` 数据字段，"tool output can never rewrite what is being asked"；一 call 五问（`achieved` / `evidence` noul + `progress` score + `sameObstacle` noul + `action` choice），阈值 achieved ≥ 0.75 / evidence ≥ 0.6，任何降级一律 fail-open，shadow 默认写 `telemetry.jsonl`；首轮真实 API 冒烟 achieved 0.83 / evidence 0.90 / progress 3.94（confidence 0.95）。第 9 条只要求「question 字符串不许改写」，此处补的是更硬的另一半：**工具输出不得进入 instruction**——本仓 judgment 层的 question 组装正来自 runtime facts 与工具结果，这是需要检查的注入面。证据等级：lead（1 star，但带真实 key 冒烟数字）。
>
> 证据：`docs/evaluation/runs/2026-10-31-awesome-jev-ecosystem-watch.md`。

> 2026-11-01 复核结论（已一手复现，**不改门槛语义**）：s1kit 0.2.0（HEAD `9948b2d`，仅依赖 PyYAML，可离线跑，其自带测试 30 passed）实测：`calibrate` 输出按 **(provider, question)** 粒度给阈值 `threshold / precision / coverage / n`，无阈值可达目标时写 `null`（永远升级）；同一 provider 下 `keyword/team` 在 0.71 处 100% 精度、覆盖 100%，`keyword/urgent` 在 0.70 处同样 100% 精度却只覆盖 5% —— 单一全局阈值无法同时表达两者。另其 Router 语义为「任一问不达标即整批升级，链尾 provider 无条件兜底（fail-open）」，与本仓 Router / Tool Gate / Verifier 多问一次调用的形状不同：只搬阈值表而不改判定粒度，会把一个不可校准的问题变成整批成本放大。故第 4 条的三个候选（绝对阈值 / 排序保留 + AUC / 按问题拟合阈值）应由维护者拍板取其一或并存，试点原文见 `docs/evaluation/runs/2026-11-01-s1kit-per-question-cutoff-spike.md`。

> 2026-11-04 候选 C 草案（**待维护者拍板；不改动第 4 条现行条文、门槛值与 checklist 语义**）：
>
> **先说清这份草案的性质（状态校正）**：第 4 条已随 FEAT-03 于 2026-09-21 签字并落地（`JevContextScorerConfig.DEFAULT_KEEP_THRESHOLD = 0.5`，见 `docs/INITIATIVES/FEAT-03.md` §14「签字 + 迁入 src/main + src/test」；该卡「当前状态 = 验收通过」）。故本段**不是补签，而是变更提案**：采纳即一次 policy constant edit（§1.1a 纪律「改 policy 是 constant edit under code review」）。签字前不动 `JevContextScorerConfig`、不建 `src/` 文件。
>
> **第 4 条的三个候选（取其一或并存）**
>
> | 候选 | 形态 | 依据 | 状态 |
> |---|---|---|---|
> | A 绝对阈值 | 单一 `keepThreshold = 0.5`（§1.1a 另有 `lowStakesThreshold 0.60` / `highStakesThreshold 0.85`） | `fast-jev-compaction` 源 | 现行条文，已落地 |
> | B 排序保留 + AUC 锚定 | 按 `Noul` 分数**排序**决定保留量，AUC 0.70-0.83 锚定 | `opencode-jev-safe-compact`（2026-10-24 印证） | 候选，未展开 |
> | C 按问题拟合阈值 | 每个问题一条**校准出来**的阈值，附目标精度 / 覆盖率 / 样本量 | `s1kit calibrate`（2026-11-01 已一手复现） | **本段草案** |
>
> **候选 C 拟定条文（签字时逐字替换或增补第 4 条）**
>
> ```text
> 4. keepThreshold = 0.5 是否接受；不接受则改 0.6（更保守）或 0.4（更激进）。
>     4-C（未签字前不生效）若引入按问题拟合阈值 perQuestionCutoffs：
>     a) 拟合表以「问题指令 + 来源标签」为主键（见 D4），每项含
>        threshold / targetPrecision / coverage / n / source / calibratedAt；
>     b) 命中条目 → 用该项 threshold 判该问题；未命中条目 → 回落全局 keepThreshold；
>        全局阈值亦缺失 → 判「不可回答」，不判「回答」；
>     c) alwaysEscalate = true 的条目 → 该问题直通 confirm 通道，本层不给 keep/drop 判定；
>     d) 一次调用含多问题时按问判定，调用级结论取最严者（任一问题不可回答 → 该门控升级）；
>        不得整批 OR；
>     e) 拟合表是校准产物不是策略：只读加载，随第 9 条落在 JevContextScorerConfig.java 单文件内。
> ```
>
> **表结构**
>
> 人读（评审 / plan 用）：
>
> | question | source | threshold | targetPrecision | coverage | n | alwaysEscalate | sourceOfRecord | calibratedAt |
> |---|---|---|---|---|---|---|---|---|
> | `keep_verbatim_relevant` | `judgment` | 0.71 | 0.95 | 1.00 | 20 | false | `s1kit-calibrate@9948b2d` | 2026-11-01 |
> | `keep_verbatim_relevant` | `tool_result` | 0.70 | 0.95 | 0.05 | 20 | false | `s1kit-calibrate@9948b2d` | 2026-11-01 |
> | `evidence_sufficiency` | `panel` | — | 0.95 | — | 0 | true | `s1kit-calibrate@9948b2d` | 2026-11-01 |
>
> > 表中三行为**示意**：把 s1kit 样例的 (provider, question) 轴映射到我方「问题指令 + 来源标签」轴的形状，`threshold / targetPrecision / coverage / n` 取自 2026-11-01 的 s1kit 实测值，**不是**我方数据上的校准结果——我方首轮校准须用真实 key 跑出（见 §11 blocker）。
>
> 机读（配置形状，**候选，未实现**）：
>
> ```yaml
> perQuestionCutoffs:
>   - question: keep_verbatim_relevant
>     source: judgment
>     threshold: 0.71
>     targetPrecision: 0.95
>     coverage: 1.00
>     n: 20
>     alwaysEscalate: false
>     sourceOfRecord: s1kit-calibrate@9948b2d
>     calibratedAt: 2026-11-01
> ```
>
> 判定表（单问判定 → 调用级结论取最严）：
>
> | 该问题状态 | 该问题去向 | 调用级影响 |
> |---|---|---|
> | 命中条目且 `p >= threshold` | keep | 取最严 |
> | 命中条目且 `p < threshold` | 升级（fail-open 至 confirm / 强档） | 取最严 |
> | 命中条目且 `alwaysEscalate` | 直通 confirm | 取最严 |
> | 未命中（有全局阈值） | 用全局 `keepThreshold` 判 | 取最严 |
> | 未命中且无全局阈值 | 不可回答 → 升级 | 取最严 |
>
> **待拍板点（D1-D5；括号内为推荐值）**
>
> | 编号 | 决策点 | 选项 | 影响 |
> |---|---|---|---|
> | D1 | 未校准问题怎么走 | (A) 回落全局 `keepThreshold`（**推荐**） / (B) 直通 confirm | A 保持 §3 fallback 合同「Jev 只是加速、fallback 是安全选择」；B 更保守，但把不可校准问题变成常态 confirm 流量 |
> | D2 | `alwaysEscalate` 是否直通人工 | (A) 直通 confirm 通道（**推荐**） / (B) 直通人工 | 我方 confirm 通道可自动/半自动；直通人工会让任一不可校准问题变成人工瓶颈 |
> | D3 | 拟合表落位 | (A) 与 `JevContextScorerConfig.java` 同文件常量（**推荐**，遵守第 9 条） / (B) 外置 YAML 资源 | B 需维护者同意偏离第 9 条「thresholds 集中单文件」 |
> | D4 | 拟合表主键 | (A) 「问题指令 + 来源标签」（**推荐**） / (B) 只按问题指令 | 我方当前每次调用只发**一条**问题（`JevContextScorer.decideOneHttp`，question key = `item.id()`，instruction 为固定串），真正有差异的维度是 `JevRequestItem.source`（`judgment` / `panel` / `tool_call` / `tool_result` / `user_msg`）；只按指令则表退化为一行 |
> | D5 | 与 §1.1a 三段阈值的关系 | (A) 命中拟合表按问阈值，全局三段作兜底（**推荐**） / (B) 拟合表完全覆盖三段阈值 | B 会让 `lowStakesThreshold` / `highStakesThreshold` 两档失去用途 |
>
> **若候选 C 被采纳，需同步复核的位置**（供评估工作量；本轮未改）：§1.1 字段、§1.1a 三段阈值表、§3 fallback 合同、§4 测试合同（Fake Jev 需能按问题/来源返回不同置信度）、§5 可观察性（建议加 `perQuestionCutoffUsed` 字段）、§9 验收项。
>
> **本轮新增上游印证（第 2 条，只记录证据，不改门槛）**：`browser-use/jev-ultrafast` open PR #232（2026-10-25，提案人 R3-da，**未合并**）把硬编码端点改为读环境变量 `TYPESAFE_BASE_URL`（含版本段，空值回落托管端点），并给出 OpenRouter 路由写法 `TYPESAFE_BASE_URL=https://openrouter.ai/api/v1` + `TYPESAFE_MODEL=~typesafe/jev-latest`（另有 #175 提同一变量）。我方代码已先行同向：`JevContextScorerConfig.enabledDefaults()` 已读 `TYPESAFE_BASE_URL`，默认 `https://api.typesafe.ai`。证据等级 lead（未合并提案）。
>
> 草案原文、形状实验与周快照：`docs/evaluation/runs/2026-11-04-jev-feat03-item4-candidate-draft.md`。

## 13. 测试合同源码骨架（不落 `src/`，仅作 plan 附录供维护者签字时对照）

> 本节是 plan 文档的**附录**——为方便维护者对照测试合同、把 fake Jev 直接迁入 `src/test/java/`，先在 plan 里给完整骨架代码片段。**本轮不创建任何 `src/` 文件**；维护者签字后，由 FEAT-03 实施 PR 一次性迁入。

### 13.1 `JevContextScorerFake.java`（落点 `src/test/java/com/agentcloud/scorer/JevContextScorerFake.java`）

```java
package com.agentcloud.scorer;

import java.util.List;

/**
 * 测试用伪 Jev 实现：不联外网，按内置规则返回 typed decision + probability。
 * 与 fast-jev-compaction 的 tests/fake-jev.ts 等价。
 */
public final class JevContextScorerFake implements JevScorer {
    private final double defaultKeepProbability;

    public JevContextScorerFake(double defaultKeepProbability) {
        this.defaultKeepProbability = defaultKeepProbability;
    }

    @Override
    public JevDecision decide(JevRequestItem item) {
        // 默认策略：keepProbability >= defaultKeepProbability -> keep verbatim
        //                  否则 -> truncate(head=truncateHeadChars)
        if (item == null || item.text() == null) {
            return new JevDecision(JevAction.KEEP_VERBATIM, 1.0);
        }
        double p = scoreByRules(item);
        if (p >= defaultKeepProbability) {
            return new JevDecision(JevAction.KEEP_VERBATIM, p);
        }
        return new JevDecision(JevAction.TRUNCATE_HEAD, p);
    }

    @Override
    public List<JevDecision> decideAll(List<JevRequestItem> items) {
        return items.stream().map(this::decide).toList();
    }

    private double scoreByRules(JevRequestItem item) {
        // 可在此处添加规则：关键词、最近 N 条白名单、字段类型等
        // 当前默认：所有 panel 一视同仁，由 defaultKeepProbability 决定
        return defaultKeepProbability;
    }
}
```

### 13.2 `JevContextScorerFakeTest.java`（落点 `src/test/java/com/agentcloud/scorer/JevContextScorerFakeTest.java`）

```java
package com.agentcloud.scorer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JevContextScorerFakeTest {

    @Test
    void keepVerbatimAboveThreshold() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        JevDecision d = fake.decide(new JevRequestItem("panel-A", "some long text", "tool_call"));
        assertEquals(JevAction.KEEP_VERBATIM, d.action());
        assertTrue(d.probability() >= 0.5);
    }

    @Test
    void truncateBelowThreshold() {
        JevContextScorerFake fake = new JevContextScorerFake(0.9);
        JevDecision d = fake.decide(new JevRequestItem("panel-B", "some long text", "tool_call"));
        assertEquals(JevAction.TRUNCATE_HEAD, d.action());
        assertTrue(d.probability() < 0.9);
    }

    @Test
    void emptyItemReturnsKeepVerbatim() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        assertEquals(JevAction.KEEP_VERBATIM,
            fake.decide(new JevRequestItem("p", null, "tool_call")).action());
    }

    @Test
    void decideAllPreservesOrder() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        List<JevDecision> ds = fake.decideAll(List.of(
            new JevRequestItem("a", "t1", "tool_call"),
            new JevRequestItem("b", "t2", "tool_call")));
        assertEquals(2, ds.size());
    }
}
```

### 13.3 与生产代码的接口合同（`JevScorer.java`，落点 `src/main/java/com/agentcloud/scorer/JevScorer.java`）

```java
package com.agentcloud.scorer;

import java.util.List;

/**
 * Jev 评分接口契约。生产实现 = JevContextScorer（HTTP）；
 * 测试实现 = JevContextScorerFake。
 *
 * Contract note: 接口本身必须在 FEAT-03 立项签字时由维护者确认；
 * 任何字段调整都会触发 HARNESS_CHANGE_CONTRACT.md 的 Contract-Additive 流程。
 */
public interface JevScorer {
    JevDecision decide(JevRequestItem item);
    List<JevDecision> decideAll(List<JevRequestItem> items);
}
```

### 13.4 维护者签字前 src/ 树约定

- **不创建**任何 `src/main/java/com/agentcloud/scorer/` 文件（FEAT-03 立项签字后才迁入）。
- **不创建**任何 `src/test/java/com/agentcloud/scorer/` 文件（同上）。
- **不修改** `pom.xml`（JDK 21 内置 HttpClient + 已有 Jackson，无新依赖）。
- 本附录仅供维护者对照 plan §4 测试合同是否完整；签字后由实施 PR 一次迁入。
## 12. 下一步

- 维护者按 §11 立项门槛开 issue 对齐字段设计；签字后由本 plan.md 接管主线。
- `CONTRIBUTING.md` FEAT-03 由"借鉴类 feature 候选"转为"进行中"；HW-09 / HW-10 自动并入本计划范围。
- 调研文档 `JEV_CONTEXT_SCORING_RESEARCH.md` 转为"立项依据"，仅在 evaluation 主题被引用，不进入产品叙事。
> 2026-09-21 附录同步:本轮新增 13 测试合同源码骨架(plan 附录,不落 src/)。CONTRIBUTING.md FEAT-03 立项目录中明确立项前不开工;本轮以 plan 附录形式提供测试骨架对照,不创建任何 src/ 文件。维护者签字后由实施 PR 一次性迁入。
