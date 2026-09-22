# Jev Tool Recall Filter Demo（真 API 实测数据 · 2026-09-21 限时免费 promo 验证）

> 本文是 `Jev-ToolRecall-Filter` 立项门槛的真 Jev 验证。
> 时间：2026-09-21（Toutiao 全网解禁 + 1.2亿 Token 限时免费 promo 当日）。
> 凭据：用户提供的 `apikey_257c5199...`（已存 `~/.openclaw/secrets/typesafe.key`，不入 git，跑完即焚）。
> 跑测脚本：`D:\gitAll\agent-cloud-harness\.tmp\jev-promo-2026-09-21\tool_recall_filter.py`。
> 落点：本文 + `INITIATIVES/Jev-ToolRecall-Filter.md`（17 条候选卡）+ `JevToolRecallFilterTest.java`（合同 test，planned）。

## 0. 一句话总结

**Jev 在 tool recall → context 的初筛门控场景下，precision@5 = 100%（5/5 relevant），noise 完全 0 误入**，clean separation 0.68-0.91 vs 0.01-0.02，单次 1050ms / 2035 input + 404 output tokens / ~$0.00009 成本。**立项门槛达成**。

## 1. 背景：为什么这个场景

来源：2026-09-21 Toutiao 文章《刚刚，Jev全网解禁！1.2亿Token限时免费用》提到 Malde 用 Jev + GPT-6 Astra 屠《我的世界》末影龙：

```
Astra (LLM) → 高层规划 (更新目标/物资/路点)
Jev → 动作选择 (从候选动作中判哪一项最有利于推进目标)
Code → 执行 (寻路/转向/放置方块/操作协议)
```

**这正是「并发工具召回初筛门控」的工程实例** —— 多候选 → 选 top-K 进 context → 代码执行剩余工作。在 ACH 上下文：

- **Astra ↔ ACH 的 LLM judge / planner**
- **Jev ↔ 我们在做的 Jev-ToolRecall-Filter（tool/agent 召回结果→context 初筛）**
- **Code ↔ ACH 的 worker / task 执行**

**和现有候选的关系**：

- `HW-10` Judgment 前置过滤（已签字）→ LLM judgment 调用前过滤「显然 yes/no」
- `Jev-Graph-A` mounted_context_view hybrid（已签字）→ 已 mount 进 context 的 panel 评分
- **`Jev-ToolRecall-Filter`（本条新增）→ recall-to-mount 入口的初筛门控**

三者构成 recall → mount → judge 的完整 Jev 门控链，**这是 gap 而不是重复**。

## 2. 实验设计

### 2.1 场景

Codex 在跑「修 parser 让 nested brackets 解析正确」的 sub-step，**同时收到 10 个并发 tool recall 结果**。每个 recall 候选是一条 tool output / 文件内容 / LLM reply / 第三方 API 返回。Codex 需要在它们进 context 前**选 top-K**（典型 K=5）。

### 2.2 候选构造

10 个候选 = 5 relevant + 5 noise：

**Relevant (5)**：
- `r1` bash 错误日志（FAIL: test_parser_legacy）
- `r2` git diff（Edit parser.py:45）
- `r3` LLM 上轮对话（user 让改 parser + Edit 已落）
- `r4` 文件内容（parser.py 中有 BUG）
- `r5` worker 执行 trace（CodeBuddy stderr IndexError）

**Noise (5)**：
- `n1` 上海天气 API
- `n2` StackOverflow「Python decorators 怎么工作」
- `n3` 飞书群聊「中午吃啥」
- `n4` todo.md（买菜/打电话/报销）
- `n5` AI Safety Principles 博客

### 2.3 State 设计（喂给 Jev 的 goal context）

```python
state = {
    "task": "fix parser so nested brackets parse correctly (test_parser_legacy failing)",
    "current_step": "Edit parser.py to add bounds check before tokens[i+1] access",
    "stage": "implementation",
    "recent_outcome": "Edit applied, need to verify with bash test",
    "goal_progress": "60%",
    "recall_candidates": [{"id":..., "source":..., "content_preview":...} for c in 10]
}
```

### 2.4 2 个 noul questions per candidate

- `relevant_<id>`: 「Is this recall result relevant to the current task 'fix parser nested brackets test'?」
- `executable_<id>`: 「Is this recall result concrete and directly actionable (specific code/log/error, not abstract advice or unrelated content)?」

**打分公式**：`combined = (relevant + executable) / 2`，按 combined 降序排序取 top-5。

## 3. 实测 metrics

```
calling Jev with 20 questions / 10 candidates...
latency_ms=1050 model=jev-1.13.0
usage={'input_tokens': 2035, 'output_tokens': 404}
```

### 3.1 TOP-5 by combined score

| # | id | label | relevant | executable | combined | verdict |
|---|---|---|---|---|---|---|
| 1 | r5 | relevant | 0.95 | 0.87 | 0.91 | OK |
| 2 | r4 | relevant | 0.90 | 0.82 | 0.86 | OK |
| 3 | r1 | relevant | 0.89 | 0.75 | 0.82 | OK |
| 4 | r2 | relevant | 0.88 | 0.75 | 0.81 | OK |
| 5 | r3 | relevant | 0.92 | 0.43 | 0.68 | OK |

### 3.2 TOP-10 完整排名（clean split）

| # | id | label | combined |
|---|---|---|---|
| 1-5 | r5/r4/r1/r2/r3 | relevant | 0.91 / 0.86 / 0.82 / 0.81 / 0.68 |
| 6 | n2 | noise | 0.02 |
| 7 | n4 | noise | 0.02 |
| 8 | n5 | noise | 0.01 |
| 9 | n1 | noise | 0.01 |
| 10 | n3 | noise | 0.01 |

### 3.3 关键指标

```
precision@5 = 100% (5/5 relevant in top-5)
noise in top-5 = 0/5
gap relevant-vs-noise = 0.68 - 0.02 = 0.66 (huge)
```

### 3.4 成本与延迟

- 单次 1050ms（20 questions / 10 candidates）
- 2035 input + 404 output tokens
- 按 Toutiao 文章定价 **$0.042 / 1M input token**（输出免费）：**$0.0000855** ≈ 0.009 cents
- 折算到 single candidate：~105ms / $0.0000086（**几乎免费**）

## 4. 关键发现

### 4.1 precision@5 = 100% 远超立项门槛（80%）

`Jev-ToolRecall-Filter` 候选卡立项门槛 1 = 「precision@5 ≥ 80%」 → **本次实测 100%，立项门槛达成**。

### 4.2 clean separation 0.68-0.91 vs 0.01-0.02

Jev 不仅能**选出 relevant**，还能**完全压低 noise**（noise 全部 ≤ 0.02）。这意味着：
- threshold 可以放得很高（如 ≥ 0.5）而不漏掉任何 relevant
- 不需要 LLM 二次复核（除非用户明确要求）

### 4.3 屠龙架构的可移植性

Toutiao 文章里 Malde 的 3 层架构（planner / action selector / executor）在 ACH 借鉴里**有完整的对应关系**：

```
屠龙架构                  ACH 借鉴
─────────────────         ─────────────────
Astra (LLM planner)  →   ACH LLM judge / planner (FEAT-03 主路径)
Jev (action selector) →   Jev-ToolRecall-Filter + Jev-Patrol-L2 + Jev-Graph-A
Code (executor)      →   ACH worker / task execution (FEAT-04 OpenEyes MCP / puppeteer-core)
```

**「异步运行规划器（默认 15 秒间隔）」** 模式也直接对应 Jev-Patrol-L2 的 patrol dispatch loop —— shadow mode 灰度可以参考这个间隔。

### 4.4 凭据窗口

Toutiao 文章「限时免费」= 新用户注册送 $5（折算 1.2亿 token）。**现有 key 是否同享此额度未知**（credit 在 console.typesafe.ai 后台，不在 API response）。本次实测用了 ~2000 input token ≈ $0.0001，**1.2亿 token 可跑约 60000 次同等调用**。

**用户决策点**：
- 若现有 key 已自动获得 $5 额度 → 立即大规模跑 recall filter 真实任务实验（建议 50+ 候选场景）
- 若需新注册 → 用户本人去 console.typesafe.ai 注册新账号（apikey 走 `~/.openclaw/secrets/typesafe.key` 凭据约定）

## 5. 下一步

1. **新增 `Jev-ToolRecall-Filter` 候选卡**（17 条候选）→ `INITIATIVES/Jev-ToolRecall-Filter.md` + INDEX.md 更新 16→17
2. **合同层实施**：写 `JevToolRecallFilterTest.java`（fake Jev 5 测试，覆盖 precision@5 / noise rejection / threshold edge / batch_size limit / ambiguous_skip），迁入 src/test/java/com/agentcloud/scorer/，跑全仓库 test
3. **生产层预留**：与 `FEAT-03` 主路径的 `JevContextScorer` 协同（recall → mount 入口），定义 `JevToolRecallFilter` 落 `src/main/java/com/agentcloud/recall/`，受 `harness-config.yml` `jev.tool_recall_filter.enabled` 控制（默认 false，回滚 < 1 行）
4. **真实任务回放**：用 ACH 真实 task_receipt / tool_result 历史（auto-deploy 仓库的 `runs/` 数据）做回放实验，验证 precision@5 ≥ 80% in real traffic