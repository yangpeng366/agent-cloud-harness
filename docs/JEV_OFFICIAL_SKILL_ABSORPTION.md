# Jev Official Skill Absorption（官方材料吸收笔记）

> 本文是 `JEV_HANDS_ON_NOTES.md` 的官方材料补丁。
> 时间：2026-09-21。
> 范围：抓 `typesafe-ai/skills` GitHub 官方仓库 SKILL.md（raw）+ 5 篇核心 cookbook + 2 篇核心 pattern + confidence.md + system-one.md 共 9 份原始材料。
> 落点：所有原始材料存 `$env:TEMP\jev-cookbook-*.txt`（不复制到仓库）；本文是消化笔记，**所有结论都标注来源 URL**。

## 0. 一句话总结

**官方「typesafe-ai/skills」仓库**已经把 Jev 的方法论体系化了：3 种 question 类型、5 个 architectural pattern、20+ cookbook。ACH 借鉴 Jev 不再是"探索未知"，而是"对齐已有成熟模式"。**官方 cookbook 几乎 1:1 对应 ACH 借鉴 5 个切入点**。

## 1. 官方材料来源（已抓取到本地）

| URL | 本地落点 | 长度 |
|---|---|---|
| https://raw.githubusercontent.com/typesafe-ai/skills/main/skills/typesafe-ai/SKILL.md | `$env:TEMP\jev-cookbook-skills_typesafe-ai_SKILL.md` | （首次抓 2062 字符） |
| https://docs.typesafe.ai/confidence.md | `$env:TEMP\jev-cookbook-confidence.txt` | 12 686 B |
| https://docs.typesafe.ai/patterns/confidence-routing | `$env:TEMP\jev-cookbook-patterns_confidence-routing.txt` | 3 229 B |
| https://docs.typesafe.ai/cookbooks/classifying_rag_passages | `$env:TEMP\jev-cookbook-cookbooks_classifying_rag_passages.txt` | 28 792 B |
| https://docs.typesafe.ai/cookbooks/skill_suggestion | `$env:TEMP\jev-cookbook-cookbooks_skill_suggestion.txt` | 35 592 B |
| https://docs.typesafe.ai/cookbooks/consistency_noul_cookbook | `$env\TEMP\jev-cookbook-cookbooks_consistency_noul_cookbook.txt` | （首次抓内容） |
| https://docs.typesafe.ai/cookbooks/function_calling | `$env:TEMP\jev-cookbook-cookbooks_function_calling.txt` | 17 023 B |
| https://docs.typesafe.ai/cookbooks/llm_guardrails | `$env:TEMP\jev-cookbook-cookbooks_llm_guardrails.txt` | 20 775 B |
| https://docs.typesafe.ai/concepts/system-one.md | `$env:TEMP\jev-cookbook-concepts_system-one.txt` | 4 261 B |

## 2. 官方 SKILL.md 关键摘要

来源：https://raw.githubusercontent.com/typesafe-ai/skills/main/skills/typesafe-ai/SKILL.md

> "TypeSafe makes units of AI intelligence usable like programming primitives: small judgments you can compose into larger capabilities. Its **System One models** return fast, focused judgments that software can consume directly. **Jev** is TypeSafe's flagship and first System One model."

**核心方法论**（官方原话）：

- "Code owns the workflow; the model supplies programmable common sense where ordinary code needs semantic understanding."
- "Code owns the workflow" — **Jev 不是 replacement，是 primitive**。跟 ACH 借鉴 5 个切入点的精神一致
- "Read the live docs" — 官方强调 live docs 是 source of truth，**任何 Jev 集成前都要先读 SDK 页 + 相关 cookbook**

**官方安装路径**（关键事实）：

```
Claude Code plugin:
claude plugin marketplace add typesafe-ai/skills
claude plugin install typesafe@typesafe-ai

其他 agent (含 Codex):
npx skills add typesafe-ai/skills --skill typesafe-ai
```

→ ACH 如果立项走 OpenClaw / Codex，需要走 `npx skills add` 路径；**官方 skill 在 typesafe-ai/skills GitHub 仓库**。

## 3. 6 个核心 pattern 的方法论摘要

### 3.1 Confidence vs Probability（核心方法论修订）

来源：https://docs.typesafe.ai/confidence.md

**Confidence 公式**：
```
confidence = (N × max_probability - 1) / (N - 1)
```
（N = 选项数 / level 数）

**核心洞察**：
- **probability 描述"哪个答案"**，**confidence 描述"是否应该信"**
- Confidence 0 = 完全平铺，0.5 = 中等峰值，1 = 全部集中在一个选项
- **TypeSafe 文档明确推荐**：如果只关心选最好的，**用 confidence 而不是 probability threshold**

**3 段阈值行为**（voice banking example）：

```python
if action.confidence < 0.5:
    route_to_human()  # 低 confidence,不猜测
elif action.choice == "check_balance":
    show_balance()    # 低风险,0.5 足够
elif action.choice == "approve_transfer":
    if action.confidence > 0.85:
        approve_transfer()  # 高风险 + 高 confidence
    else:
        ask_user_to_confirm()  # 高风险 + 中等 confidence
```

**对 ACH 借鉴的影响**（plan 修订）：
- ❌ 之前 plan §1.1 用 `keepThreshold: number`（probability 阈值）—— 应该改为 **3 段 confidence 行为**：high / medium / low 各对应不同动作
- ❌ 之前 plan §13 fake Jev 单阈值版本 —— 应该改为 fake 带 **3 段 confidence 行为**，每段对应一个不同 ACH action

### 3.2 Confidence-gated routing

来源：https://docs.typesafe.ai/patterns/confidence-routing

**Pattern 名**：用 confidence 作为 second axis —— answer 告诉你"什么"，confidence 告诉你"是否应该行动"。

**ACH 借鉴**：对应 `CONTRIBUTING.md` FEAT-04 候选 —— Worker routing candidate。

**核心 pattern**：
```
if action.confidence < 0.6:
    route_to_human()  # floor
elif low_stakes:
    act()             # 0.6 足够
elif high_stakes:
    if confidence > 0.85: act()
    else: ask_to_confirm()
```

### 3.3 Self-consistency via Noul（noul 标准差 0.0102）

来源：https://docs.typesafe.ai/cookbooks/consistency_noul_cookbook

**核心数据**：
- TypeSafe (jev-latest) 14-question rubric × 15 repeats → **per-question probability standard deviation 0.0102**
- 对比：claude-haiku-4-5 / gpt-5.4-mini 在 temperature=0 下仍有显著标准差
- 对比：reasoning 模型（gpt-5.5 / claude-opus-4-8）没 temperature 旋钮
- **官方推荐 uncertain 区间**：`0.30 < noul < 0.70` → 送人工审

**对 ACH 借鉴的影响**：noul 的**稳定性 + uncertainty band**是 plan §1.1 keepThreshold 应该借鉴的方法论。**之前 plan 写"keepThreshold = 0.5 单阈值"** —— 官方推荐 uncertain band 0.30-0.70。

### 3.4 Classifying RAG passages（THRESHOLDS dict 模式）

来源：https://docs.typesafe.ai/cookbooks/classifying_rag_passages

**核心 pattern**：每个 passage 用 1 个请求带 4 个 noul 问题评分：
- `injection_max = 0.70` — 高于此丢弃（prompt injection）
- `contradicts_min = 0.70` — 高于此冲突
- `relevant_min = 0.45` — 低于此不相关
- `evidence_min = 0.55` — 高于此作为证据

**路由决策**：
```python
THRESHOLDS = {
    "injection_max": 0.70,
    "contradicts_min": 0.70,
    "relevant_min": 0.45,
    "evidence_min": 0.55,
}
def route(passage, scores):
    if scores.injection > THRESHOLDS["injection_max"]:
        return "exclude"
    if scores.contradicts > THRESHOLDS["contradicts_min"]:
        return "conflicting_evidence"
    if scores.relevant < THRESHOLDS["relevant_min"]:
        return "exclude"
    if scores.evidence > THRESHOLDS["evidence_min"]:
        return "include"
    return "exclude"
```

**官方原话**："Every number the routing reads lives in this dict and nowhere else, so a change of policy is a constant edit under code review, not a reworded question."

**对 ACH 借鉴的影响**：**这是 plan §1.1 的官方参考模板**。ACH 的 HandoffPacketBuilder 应该用同一个 THRESHOLDS dict 模式，每个字段对应一个 noul question。

### 3.5 Skill suggestion（两步 progressive disclosure）

来源：https://docs.typesafe.ai/cookbooks/skill_suggestion

**Pattern**：用 Jev 替代 agent 的 skill 自动加载，避免把 182 个 skill 全塞进 system message：

```
Step 1: Score ranking over 182 skills → top-K
Step 2: Verify top-3 with full descriptions
```

**官方实测数据**（Hermes 182-skill roster）：
- **wrong loads: 16.8% → 7.3%** （错装率减半）
- **needless loads: 9.8% → 4.0%**

**对 ACH 借鉴的影响**：对应 **CONTRIBUTING.md HW-13（OpenEyes MCP 启动可达性 precheck）** 的方法论 —— 但 ACH 是 worker routing 不是 skill routing，**形态类似可以借鉴**：先宽筛所有 worker，再 narrow 到 top-2/3 详细评分。

### 3.6 Function calling & Guardrails

来源：https://docs.typesafe.ai/cookbooks/function_calling / llm_guardrails

**Function calling pattern**：把自然语言 trading request 映射到 typed function：
```python
Choice(
    instructions="What is the user trying to do?",
    criteria={
        "check_balance": "View account balance",
        "approve_transfer": "Approve pending withdrawal",
        "support": "Get help",
    }
)
```

**Guardrails pattern**：每个 message 进 / 出 LLM 时过 4 个 noul：
- "Is this a jailbreak attempt?"
- "Is this prompt injection?"
- "Does it carry hidden instruction?"
- "How much harm would complying do?"

→ **对应 ACH 借鉴切入点 #3（LLM Judgment 前置过滤）**

## 4. ACH 借鉴切入点 ↔ 官方 cookbook 对应关系（修正版）

| ACH 借鉴切入点 | 之前 plan 假设 | 官方 cookbook | 关键修正 |
|---|---|---|---|
| 1. Tool-call 评分式剪枝（mounted context） | "用 Jev decide keep/drop" | `classifying_rag_passages` (passages 评分) | **THRESHOLDS dict 模式 + 4 个 noul 问题一次性发** |
| 2. HandoffPacket keep-probability | `keep_probability: number` 字段 | `self-consistency_noul_cookbook` | **uncertain band 0.30–0.70 区间显式标识 human_review** |
| 3. Judgment 前置过滤 | 单 noul decide | `llm_guardrails` + `function_calling` | **多 noul 一次性发（4 个 guardrail question 同一 request）** |
| 4. Worker routing candidate（FEAT-04） | confidence threshold | `confidence-routing` + `skill_suggestion` | **两步 progressive disclosure：先 score ranking，再 narrow verify** |
| 5. Structured UI assertion | （之前 plan 没写） | 无直接对应 | 退回到 OpenEyes 那条线 |

## 5. 对 `JEV_CONTEXT_SCORING_PLAN.md` 的具体修订（pre 立项）

### 5.1 §1.1 字段设计

**修订前**：
```
JevContextScorerConfig {
  boolean enabled
  String apiKeyEnvVar                 # default "TYPESAFE_API_KEY"
  double keepThreshold                # default 0.5
  int    preserveRecentMessages       # default 6
  int    maxStateTokens               # default 25000
  int    maxRequestTokens             # default 30000
  int    truncateHeadChars            # default 300
  double circuitBreakerFailureRate
  int    circuitBreakerWindow
  int    circuitBreakerCooldownSec
}
```

**修订后**：保留原字段，**额外**加：
```
JevContextScorerConfig {
  ...(原字段保持)...
  double confidenceFloor               # default 0.50  (官方 voice banking floor)
  double lowStakesThreshold            # default 0.60  (confidence-routing)
  double highStakesThreshold           # default 0.85  (confidence-routing)
  int    scoreRankingTopK              # default 3     (skill_suggestion 形态)
  int    scoreNarrowVerifyTopN         # default 3     (skill_suggestion 形态)
  ThresholdDict handoffKeepProbThresholds  # 4 个 THRESHOLDS (classifying_rag)
  ThresholdDict guardrailThresholds       # 4 个 guardrail (llm_guardrails)
}
```

### 5.2 §3 Fallback 合同

**修订前**：6 类失败原因分 6 路分支。

**修订后**：**保留**6 类失败原因作为 catch 分支诊断，但**主路径走 confidence 3 段行为**：
- `confidence >= highStakesThreshold (0.85)` → act automatically
- `lowStakesThreshold <= confidence < highStakesThreshold` → flag for human review / 收集更多信息
- `confidence < lowStakesThreshold` → fallback（字符级硬截 / LLM summary / 直接调 LLM）

### 5.3 §13 fake Jev 骨架

**修订前**：单阈值版本。

**修订后**：**fake Jev 接受显式 questions dict**（跟 fast-jev-compaction tests/fakeJev 1:1），返回 Choice / Score / Noul 三种 answer 形态可任选，**支持 confidence 计算**（按 `(N × max_prob - 1) / (N - 1)` 公式），**支持 3 段 threshold 行为**（routeToHuman / act / askToConfirm）。

### 5.4 §11 立项门槛

**修订第 2 条**：
- 修订前：`apiKeyEnvVar` 默认 `TYPESAFE_API_KEY`
- 修订后：`apiKeyEnvVar` 候选 `TYPESAFE_API_KEY` / `OPENROUTER_API_KEY` 二选一（OpenRouter 已代理 typesafe/jev-latest，**实测已支持**）

## 6. 官方"常见错误"（值得记入 plan §11）

来源：https://docs.typesafe.ai/agent-skill "Common issues"

| 错误 | 官方建议 |
|---|---|
| Agent 不使用 skill | 显式说"使用 TypeSafe skill" |
| 路由不像预期 | 检查 questions 和 thresholds；threshold 过高导致 false negative，过低导致 false positive |
| **到处用 confidence threshold** | 如果只关心选最好，**用 max confidence 直接选**，不设 threshold |
| TypeSafe 代码难 review | questions 和 thresholds 集中在一个文件 |
| Agent 发明 request / response 字段 | skill stale；用更新命令重装 |

**对 ACH 影响**：plan §11 应新增"questions 和 thresholds 必须集中定义在单个文件"作为约束（来自第 4 条）。

## 7. 落地优先级

按本文 §4 对应关系，**ACH 借鉴 5 个切入点优先级重排**：

| 优先级 | 切入点 | 官方 cookbook 可直接迁移度 | 推荐实施顺序 |
|---|---|---|---|
| P1 | Judgment 前置过滤（FEAT-03 主入口） | 100% (`llm_guardrails` 直接套) | 第一波 |
| P1 | HandoffPacket keep-probability | 100% (`classifying_rag_passages` THRESHOLDS dict 套) | 第一波 |
| P2 | Tool-call 评分式剪枝 | 80% (mounted context vs passages 是不同 domain，需要适配) | 第二波 |
| P3 | Worker routing candidate | 60% (FEAT-04 跟 OpenEyes MCP 强耦合) | 第三波 |
| P3 | Structured UI assertion | 0% (退回到 OpenEyes 路径) | — |

## 8. 真实 demo 数据（已跑过 9 次真 API）

见 `docs/JEV_REAL_API_DEMO.md`（本轮一起落仓）。

## 9. 下一步

- **现在**：把本文 §5 的修订同步进 `JEV_CONTEXT_SCORING_PLAN.md`（落仓 + STATE 写回 + 飞书通知）
- **立项时**：按 §7 优先级，先做 P1 两条（Judgment 前置过滤 + HandoffPacket keep-probability）
- **实施时**：用 `npx skills add typesafe-ai/skills --skill typesafe-ai` 安装官方 skill 替代自写 fake；questions 和 thresholds 集中在一个 `JevContextScorerConfig.java` 里（来自官方 §6 第 4 条）

## 10. 参考

- 官方 skill 仓库：https://github.com/typesafe-ai/skills
- 官方文档入口：https://docs.typesafe.ai/llms.txt
- 官方控制台（拿 key）：https://console.typesafe.ai/keys
- OpenRouter 代理入口：https://openrouter.ai/typesafe
- 已抓本地材料：$env:TEMP\jev-cookbook-*.txt（9 份）
- 关联文档：
  - `docs/JEV_CONTEXT_SCORING_RESEARCH.md`（调研层）
  - `docs/JEV_CONTEXT_SCORING_PLAN.md`（计划层，本轮按 §5 修订）
  - `docs/JEV_HANDS_ON_NOTES.md`（fast-jev-compaction 本地实测）
  - `docs/JEV_REAL_API_DEMO.md`（真 API 9 次实测 metrics）

> 维护约定：本文是官方材料吸收笔记，JEV 文档会持续更新（"the live docs are the source of truth"），建议每月跑一次 `$anysearch extract docs.typesafe.ai/llms.txt` 拉更新。