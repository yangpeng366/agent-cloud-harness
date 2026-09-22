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
  String baseUrl                        # default "https://api.typesafeai.com"
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