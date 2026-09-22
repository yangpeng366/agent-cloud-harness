# Jev Context Scoring Research

> 借鉴类调研，对齐 `WBBENCH_BENCH_SKILLS_RESEARCH.md` 的写作形态。

## 0. 摘要

调研 TypeSafe AI 推出的「System One Model」**Jev** 及其在 Agent Harness 上下文工程中的最新应用形态，评估其能否进入 Agent Cloud Harness（ACH）的「上下文预算 / Handoff Packet / Judgment 前置过滤」三条链路。

本轮结论：

- **值得立项**，但不落代码、不动凭据、不替换任何现有 judgment 服务。
- 入口建议作为 `evaluation/` 主题下的借鉴类调研，与 `WBBENCH_BENCH_SKILLS_RESEARCH.md` 同层。
- 实施侧的首个落点应该在 ROADMAP §E1「Loop Decide 深度消费 Goal Progress」下面，作为可选依赖路径。
- 凭据 `TYPESAFE_API_KEY` 不在任何仓库文档中出现；接入前由维护者决定 key 存放位置（推荐沿用 `~/.openclaw/.../secrets/`，沿用 AGENTS.md "credentials 不进仓库"口径）。

## 1. 背景与时间线

| 日期 | 事件 | 来源 |
|---|---|---|
| 2026-02-05 | Mitchell Hashimoto 提出 "Engineer the Harness" / Engineering Ratchet 命题 | https://mitchellh.com/writing/my-ai-adoption-journey |
| 2026-02-11 | OpenAI 发布 Harness Engineering 报告，主张工程化约束优于 prompt 微调 | OpenAI technical memo |
| 2026-03-10 | LangChain 形式化：Agent = Model + Harness | LangChain blog |
| 2026-08-13 | DeepSeek agent harness 上线开发者预览，48 小时 95k stars | TheNewStack |
| 2026-09 中旬 | TypeSafe AI 发布 Jev（System One Model）；同期 `fast-jev-compaction`（Claude Code 插件）登 GitHub Trending Today #50 | GitHub / DataCamp |
| 2026-09-19 | `tamaratran/fast-jev-compaction` 突破 4.5k stars / 245 forks；Latent Space 称 2 天冒出 6 个 Jev clone | Latent Space |

## 2. Jev 是什么、它解决什么

- **定位**：TypeSafe AI 的「System One Model」，不生成自由文本，只返回类型化决策（Yes/No、enum、score、probability）。
- **接口**：给定文本 + typed question，70–500 ms 返回 typed choice + probability；REST API，需 `TYPESAFE_API_KEY`。
- **速度 / 成本**：相对前沿 LLM 快 40–200×、便宜 40–400×。
- **适用**：在循环里每轮都跑一次的轻判断——保留 / 删除 / 丢弃 / 评分 / 分类 / 路由 / 验证。
- **不适用**：长篇写作、复杂多步推理、开放对话。
- **生态**：`fast-jev-compaction`、`jev-trader`、`jev-search`、`jev-ultrafast`、`pg-jev`、`jev-review` 等同期冒出。

## 3. 参照实现：`tamaratran/fast-jev-compaction`

来源：https://github.com/tamaratran/fast-jev-compaction

设计参数（直接可借鉴到 ACH 的设计默认值）：

| 参数 | 默认值 | 含义 |
|---|---|---|
| `keepThreshold` | `0.5` | 单条 tool call / result 的保留概率下限 |
| `preserveRecentMessages` | `6` | 最新 N 条消息永不剪枝 |
| `maxStateTokens` | `25000` | 状态 token 上限 |
| `maxRequestTokens` | `30000` | 单次请求 token 上限 |
| `truncateHeadChars` | `300` | 被剪枝的 tool result 保留多少 head 字符 |

设计取舍：

- 用 Jev decide 替代 LLM summarize，**保证保留项逐字 verbatim**，不引入"摘要式压缩"的不可控丢字。
- 只对 tool call / result 评分；纯文本 message 不进入评分管线。
- 失败 / 缩减不足时 fallback 到 Claude Code 内置 summary（不是死路）。

## 4. Harness Engineering 在 2026 的最新坐标

来源汇总：`ai-boost/awesome-harness-engineering`（4.4k★）、`mahonzhan/awesome-agent-harness`（282★）、`andyrewlee/awesome-agent-orchestrators`（2k★）、`bradagi/awesome-cli-coding-agents`（1.2k★）、winder.ai 2026 harness comparison、OpenAI Harness Engineering 报告。

共识分层：

| 层 | 定义 | 代表 |
|---|---|---|
| Model | 推理 | Claude Code / Codex / OpenCode / Qwen Code / DeepSeek Harness / Goose / Pydantic AI Harness |
| Harness | 单 agent 的 loop + 工具 + 沙盒 + 记忆 + 上下文预算 + 规则 | LangChain 定义 |
| Framework | 多 agent 编排 | LangGraph / CrewAI / Deep Agents |
| Platform | 多 harness 跨团队运行 | Helix / Replit Agent 4 / Antigravity / Copilot Coding Agent |
| Protocol | 跨 harness 互通 | MCP / A2A / ANP / ACP / AG-UI / llms.txt / AGENTS.md / Skills / AP2 / A2UI / DESIGN.md |

核心命题：「agent 出错时不要改 prompt / 改权重，而是工程化出一个环境约束让它下次不可能再出这种错」—— ACH 的 `decide / handoff / judgment` 三件套在做这件事。

## 5. Jev 与 ACH 的耦合点评估

### 5.1 ACH 现有结构（来自 `docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md` / `docs/API_CONTRACTS.md` / `ROADMAP.md` E1–E5）

```
intake -> scheduler -> continueNode -> WorkerExecutor (Tool-aware)
                                        |
                                        +-- PromptBasedJudgmentService        [LLM 语义判断]
                                        +-- LlmSubgoalJudgmentService         [LLM 语义判断]
                                        +-- RuntimeJudgmentService            [LLM 语义判断]
                                        |
                                        +-- TaskRuntimeContextBuilder         [字符预算裁剪]
                                        +-- ActiveContextBuilder              [摘要生成]
                                        +-- mounted_context_view              [预算截断面板]
                                        |
checkpoint / ResumePacket / HandoffPacket [LLM 生成文本摘要+路径]
```

### 5.2 可用 Jev 替换 / 增强的具体位置

1. **Tool-call / message 评分式剪枝**（替换"按 token 数硬截"的现状）
   - 现在：`mounted_context_budget_truncated` 是字符级硬截，按字节丢而不是按重要性丢。
   - 用 Jev 后：每条 mounted panel / tool result 跑一次 Jev decide（`is_still_relevant_to_current_goal?`），保留概率 >= 阈值的，其余降级成 `head(truncateHeadChars)` 提示。
   - 入口：`TaskRuntimeContextBuilder` / `ActiveContextBuilder`。

2. **HandoffPacket / ResumePacket 的"保留哪些决策"过滤**
   - 现在：packet 由 LLM 生成完整摘要。
   - 用 Jev 后：packet 输出前对 `key_decisions / key_artifacts / open_questions` 打 keep probability；handoff 时按阈值过滤。
   - 入口：`HandoffPacketBuilder` / `RefinedPacketBuilder`，配合 `context_retention_hint`。

3. **LLM Judgment 之前的"候选答案排序"前置过滤**
   - 现在：`PromptBasedJudgmentService` 直接调 LLM 做 execution / completion 判断。
   - 用 Jev 后：在调 LLM 前对候选判定先做一次评分（`does this artifact contain evidence the goal succeeded?`），决定要不要调 LLM、调哪个 model、走哪个 prompt mode。
   - 收益：长任务里 judgment 频次高、token 占比大，Jev 一刀砍掉显然 yes/no 的情形，剩下的才走 LLM。

### 5.3 不替代 ACH 现有 LLM Judgment 的部分

- 复杂多步推理（handoff_depth > 2 的链路主决策）：仍走 `PromptBasedJudgmentService`。
- Long-horizon plan / subgoal 语义摘要：仍是 LLM。
- 任何需要"生成文本"的产物（task_result / task_progress）：仍是 LLM。

Jev 的角色是 **System One = 在每轮循环里高频、低成本、确定性判决的那一层**，把 LLM 从这些机械判断里释放出来。

## 6. 风险与依赖

| 维度 | 评估 |
|---|---|
| 可落地性 | 高。TypeSafe 提供 REST API + JS SDK；ACH 是 Java 21，调一个 HTTP 客户端即可纳入；`harness-config.yml` 已有 provider 接入点 |
| 依赖风险 | 中。商业 API + `TYPESAFE_API_KEY`；按 AGENTS.md 凭据约定放 `~/.openclaw/.../secrets/`，不进 git |
| 可观察性 | 高。Jev 每次调用都返回 probability，可直接写进 `runtime_facts` / `judgment_trace`，对齐现有 `mounted_context_*` 观测面 |
| 回滚成本 | 低。`feature_flag: jev_context_scoring`，默认关闭；只对短 panel / 小 artifact 启用 |
| SLA 风险 | 中。TypeSafe 是外部 SaaS，限流 / 抖动会卡住高频判断；兜底：仍走 LLM，不引入新硬依赖 |
| 跟现有 OSS 的差异 | 大。`fast-jev-compaction` 是 Claude Code 插件（TypeScript）；`awesome-harness-engineering` 中 `Trellis / TokenSavior / context-mode` 都没用 System One 模型；ACH 拿这一手可形成明显差异化 |

## 7. 建议立项清单（不进 FEAT-XX，等用户拍板）

> 以下三条作为后续 `FEAT-XX` 候选的输入；若维护者决定立项，按 `CONTRIBUTING.md` 流程开 issue 对齐字段设计再写 `plan.md`。

1. **FEAT-03 · Jev 上下文评分门控**
   - 挂在 ROADMAP §E1 下，与 `decide 消费 goal progress` 串联。
   - 在 `TaskRuntimeContextBuilder` 之前增加 `JevContextScorer`，对 mounted panel / tool result 做评分式剪枝。
   - 预期：长任务 token 节省 30%+、关键决策 0 丢失。
   - 验收：`active_context_only` 与 `mounted_context_primary` 两条模式 token 统计同时下降；`mounted_context_budget_truncated` 命中率显著低于 0；`key_decisions` 在 handoff 后能逐字保留。

2. **HW-08 · Jev × HandoffPacket keep-probability 过滤**
   - 在 `HandoffPacketBuilder` / `RefinedPacketBuilder` 出口对 `key_decisions / key_artifacts / open_questions` 打 keep probability。
   - 验收：跨 worker handoff 后 `key_decisions` 数量稳定不漂移；low-probability 字段降级为 `## footnotes` 而非删除。

3. **HW-09 · Jev × Judgment 前置过滤**
   - 在 `PromptBasedJudgmentService` 之前跑 Jev 做"显然 yes/no"判定。
   - 验收：judgment API 调用频次 -50%、平均 latency -40%；LLM 调用 trace 仍可在 `/judgment_trace` 重放。

## 8. 与现有 evaluation 主题的对齐

- 本文档登记到 `docs/evaluation/README.md` 的"对标 / 借鉴 / 产品化"区。
- 与 `WBBENCH_BENCH_SKILLS_RESEARCH.md` 同形态：借鉴类调研，不在本主题落代码。
- 与 `MULTICA_BENCHMARK_AND_BORROWING_PLAN.md` 互补：本调研聚焦"上下文评分门控"这条具体技术借鉴；Multica 关注"整体产品化叙事"。

## 9. 下一步

- 等维护者拍板是否立项；立项则按 `CONTRIBUTING.md` "feature（需设计对齐）" 节开 issue。
- 不在此调研阶段引入任何 `TYPESAFE_API_KEY`、任何新依赖、任何生产路径变更。
- 凭据 / 限流 / fallback 三个工程化约束，必须在 FEAT-XX 的 plan.md 里单独成节，不允许在调研阶段假设。

## 10. 参考链接

- https://github.com/tamaratran/fast-jev-compaction
- https://github.com/ai-boost/awesome-harness-engineering
- https://github.com/mahonzhan/awesome-agent-harness
- https://github.com/andyrewlee/awesome-agent-orchestrators
- https://github.com/bradagi/awesome-cli-coding-agents
- https://winder.ai/ai-agent-harness-comparison/
- https://www.datacamp.com/blog/system-one-models-jev
- https://www.latent.space/p/ainews-here-are-6-clones-of-jev-in
- https://mitchellh.com/writing/my-ai-adoption-journey
- https://openai.com/index/harness-engineering/

> 维护约定：本文是借鉴类调研，立项后由对应 FEAT-XX 的 plan.md 接管主线，本调研转为"立项依据"；未立项前只在 evaluation 主题下被引用，不进入产品叙事。