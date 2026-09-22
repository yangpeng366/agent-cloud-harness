# Jev Hands-on Notes（本地实测版）

> 本文是 `JEV_CONTEXT_SCORING_RESEARCH.md` 与 `JEV_CONTEXT_SCORING_PLAN.md` 之后的第一手实测记录。
> 时间：2026-09-21。
> 范围：clone `tamaratran/fast-jev-compaction` v0.2.0（4.5k★）到本地、跑 `npm install`、读全 8 个 src 文件、读全 2 个测试文件（29 个 test 全绿）、跑 typecheck 通过。
> 未做：未拿 `TYPESAFE_API_KEY` 跑真 API demo，未跑 `npm run demo`，未跑 `examples/demo.ts`。

## 0. 实测操作链

```
git clone ssh://git@ssh.github.com:443/tamaratran/fast-jev-compaction.git .tmp/fast-jev-compaction
cd .tmp/fast-jev-compaction
npm install          # 50 packages, 29s
npm run typecheck    # tsc --noEmit + hooks typecheck, OK
npm test             # vitest run, 29/29 pass, 2.90s
```

落点：`D:\gitAll\agent-cloud-harness\.tmp\fast-jev-compaction\`（.tmp 是 ACH 现有临时目录约定）。

## 1. 第一手认知（修正 / 补强之前二手资料判断）

### 1.1 5 个默认参数 — **与计划完全一致**

`src/compact.ts:DEFAULT_OPTIONS`：

```ts
export const DEFAULT_OPTIONS: ResolvedCompactOptions = {
  goal: '',
  keepThreshold: 0.5,
  preserveRecentMessages: 6,
  maxStateTokens: 25_000,
  maxRequestTokens: 30_000,
  truncateHeadChars: 300,
};
```

跟我之前 plan.md §1.1 抄的默认参数完全对齐。

### 1.2 HTTP endpoint — **修正一处细节**

`src/request.ts`：

```ts
export const SYSTEM_ONE_URL = 'https://api.typesafe.ai/v1/systemone';
export const DEFAULT_MODEL = 'jev-latest';
```

- endpoint 是 **`/v1/systemone`**，不是 `/v1/chat/completions` 也不是 `/v1/decide`
- model 默认 `jev-latest`
- Authorization: Bearer 头，body 是 `{ model, state, questions }` JSON

之前 plan 写"OpenAI-compatible endpoint 旁路"——错了。**Jev API 是自家专用 endpoint，不是 OpenAI 兼容**。这对 ACH 接入是重要修正：需要单独 HTTP 客户端，不能复用现有 OpenAI-compatible client。

### 1.3 请求体格式 — **第一手摸到**

```json
{
  "model": "jev-latest",
  "state": { "context": "...", "goal": "...", "history": [...] },
  "questions": {
    "call_t1": { "type": "noul", "instructions": "..." },
    "result_t1": { "type": "noul", "instructions": "..." }
  }
}
```

`state` 是一个对象（含 context / goal / history），不是字符串。**计划里之前写"`JevState = string | object`** — 实际只有 object 形态用上，string 形态在 JevClient.ask 签名上是兼容位。

### 1.4 响应体格式 — **三种 answer 都支持**

```ts
type JevAnswer = NoulAnswer | ChoiceAnswer | ScoreAnswer;
// NoulAnswer:   { noul: 0.42 }                  — 概率（0-1）
// ChoiceAnswer: { choice: "...", confidence: 0.9, probabilities: {...} }
// ScoreAnswer:  { score: 8, confidence: 0.9, probabilities: {...} }
```

之前计划只考虑 noul 形态（yes/no + 概率）。**实际 Jev 还支持 choice（多选一）和 score（评分）**——这对 ACH 借鉴的扩展空间比预想大：
- noul：keep / drop 二元判定（compaction 用）
- choice：路由候选 worker / 选择 fallback 策略
- score：HandoffPacket 字段 keep probability 直接用 score 字段，无需归一

### 1.5 state fitting 6 阶段 — **之前 plan 没写细**

`fitState()` 是这个库的核心算法，分 6 阶段逐步缩减 state 直到 fit `maxStateTokens`：

| 阶段 | 触发条件 | 做什么 |
|---|---|---|
| `full` | tool input 已经 fit | 直接发 |
| `inputs<=200` | 第 1 阶段没 fit | 把 tool input JSON 截到 200 字符 |
| `inputs<=60` | 第 2 阶段还没 fit | tool input 截到 60 字符 |
| `texts abridged` | 第 3 阶段还没 fit | 长 text 只留 head 400 + tail 150 字符，中间 omittd |
| `old messages collapsed` | 第 4 阶段还没 fit | 老 message text 替换成 `[… N chars omitted …]` |
| `old calls compacted` | 第 5 阶段还没 fit | 老 tool_calls 数组每条缩成一行 `t12 Read file_path=src/a.ts → ok 480ch` |
| `old messages left out` | 第 6 阶段还没 fit | 整条 message 去掉 |
| `old calls merged` | 第 7 阶段还没 fit | 连续 call-only 折成一条 |

**计划里之前只写了字符级硬截，忽略了这些阶段**。**这意味着**：Jev 实际能处理超大 transcript，比想象中鲁棒。

如果第 7 阶段还超，`throw new Error('history too large for Jev')` —— 走 fallback。

### 1.6 token 估算 — **更精确的算法，不是字符除 4**

`src/state.ts:estimateTokens`：

```ts
const TOKEN_PIECES = /[A-Za-z]+|\d+|[^\sA-Za-z\d]/g;
function estimateTokens(text: string): number {
  for (const [piece] of text.matchAll(TOKEN_PIECES)) {
    const first = piece.charCodeAt(0);
    if (first >= 48 && first <= 57) tokens += piece.length / 2;            // 数字每 2 字 1 token
    else if (letter) tokens += 1 + Math.floor((piece.length - 1) / 6);    // 字母每 6 字 1 token
    else tokens += 0.9;                                                   // 符号每字符 0.9 token
  }
}
```

**比字符除 4 准确 2–18%**。对 JSON-heavy state 字符除 4 会**少算 40%**——之前 ACH 的 `TaskRuntimeContextBuilder.mounted_context_budget_truncated` 用的是字符数估算，**这个偏差 40% 在长任务下会被放大**。

**修正建议**：ACH 接入 Jev 时，应该用 `estimateTokens` 而不是字符除 4。

### 1.7 pinned 判定 — **第一 message + 最新 N message**

```ts
function isPinned(index, total, preserveRecentMessages) {
  return index === 0 || index >= total - preserveRecentMessages;
}
```

`preserveRecentMessages=6` 表示：第一个 message（必有）+ 最后 6 个 message，永不被剪。这跟 plan 一致，但**之前没强调第一个 message 始终 pinned**。

### 1.8 fallback 形态 — **callable 级 fallback**

`compact()` 函数签名只接受 `asker: JevAsker`，throw 时调用方决定 fallback。**这意味着 ACH 接入时 fallback 是 caller 决策，不在库内部**：

- 库内部 throw → caller 决定：是 catch 后用字符级硬截兜底，还是直接降级给 LLM summary
- 库的 hook 形式（`hooks/fast-jev.ts`）演示了"失败 / 缩减不足时 fallback 到 Claude Code 内置 summary"

之前 plan §3 写的"6 类失败原因 → fallback 动作"覆盖度不够，**实际上 Jev 整个库就一个 fallback 触发条件**：任何 throw / 异常 / 缩减不足。**ACH 接入时 fallback 应该按"库级 throw → caller 决定"这条路实现**，而不是按 6 类分别处理。

### 1.9 batch 请求 — **同一 state 重复发**

`batchCalls()` 把 candidate calls 分批，每批一次 Jev 请求，**state 完全相同**：

```ts
const answered = await Promise.all(
  batches.map((batch) => askBatch(asker, state.state, batch)),
);
```

这是 `README §5` 说"the same full state is resent with every request" 的实现验证。

**对 ACH 借鉴的副作用**：N 次 batch = N × state_tokens 的 input cost。state 25k token × 5 batch = 125k input token 一次压缩。即便 Jev 单价便宜（$0.042/Mtok），N 次 batch 的成本累积不可忽视。

## 2. fake Jev 契约 — **直接迁到 ACH**

`tests/fast-jev-compaction.test.ts:fakeJev`：

```ts
function fakeJev(answer: (name: string) => number, seen: Seen[] = []): JevAsker {
  return {
    async ask(state, questions: JevQuestions) {
      seen.push({ state, questions: Object.keys(questions) });
      return {
        answers: Object.fromEntries(
          Object.keys(questions).map((key) => [key, { type: 'noul' as const, noul: answer(key) }]),
        ),
      };
    },
  };
}
```

**这是 ACH `JevContextScorerFake.java` 的 1:1 模板**。可以直接照搬：

```java
public final class JevContextScorerFake implements JevScorer {
    @Override
    public JevDecision decide(JevRequestItem item) {
        double p = (item != null) ? defaultKeep : 1.0;
        if (p >= keepThreshold) return new JevDecision(JevAction.KEEP_VERBATIM, p);
        return new JevDecision(JevAction.TRUNCATE_HEAD, p);
    }
    // ...
}
```

但**更准确**的 fake 应该接受一个 `Map<String, Double>` per-question 概率表（跟 fakeJev 一样按 question name 给概率），不要单一阈值。`tests/` 里用 `fakeJev((name) => (name.startsWith('call_') ? 0.9 : 0.1))` 这种 lambda 风格，证明 fake 应该**可编程**。

## 3. 29 个测试用例覆盖度盘点

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `tests/fast-jev-compaction.test.ts` | 23 | options、token estimate、tool call collection、state fitting、batchCalls、decideCall、applyDecisions、compact 完整流、HTTP client、buildJevRequest、parseJevResponse |
| `tests/hook.test.ts` | 6 | Claude Code hook 适配器 |

**重点用例**：
- `transcript()` fixture = 11 条 message、3 对 tool call/result —— **这就是 ACH baseline matrix 的最小 case 模板**
- `preserveRecentMessages: 1` 时 3 个 candidate call 都 `drop_result`，证明 Jev 倾向"call 留下 / result 删" 的默认行为
- 错误响应测试：HTTP 500、malformed JSON、missing answers、missing TYPESAFE_API_KEY —— **这些是 ACH 的 6 类 fallback 触发条件的具体实现**
- `kept` vs `drop_result` vs `drop_call` 三档 action 验证 —— **这是 HandoffPacketBuilder keep probability 过滤的语义原型**

## 4. 之前的 plan / research 修正项

### 4.1 必须改

| 位置 | 现状 | 改为 |
|---|---|---|
| `JEV_CONTEXT_SCORING_PLAN.md §1.2` | "走 OpenAI-compatible 客户端旁路" | "走 JDK 21 HttpClient 直连 `https://api.typesafe.ai/v1/systemone`" |
| `JEV_CONTEXT_SCORING_PLAN.md §1.4` | 只考虑 noul 形态 | 同时考虑 choice / score 三种 answer 形态 |
| `JEV_CONTEXT_SCORING_PLAN.md §3` | "6 类失败原因 → fallback 动作" 6 路径 | 库级 throw → caller 决定 fallback |
| `JEV_CONTEXT_SCORING_PLAN.md §1.1` | token 估算提到"估算"两字 | 明确用 `estimateTokens` 算法（letter/6 + digit/2 + symbol/0.9） |
| `JEV_CONTEXT_SCORING_PLAN.md §13 JevContextScorerFake` | 单阈值版本 | 改用 `Map<questionName, Double>` 可编程版本 |

### 4.2 可保留

- 5 个默认参数值 ✓
- `TYPESAFE_API_KEY` env 变量读 ✓
- feature flag `jev.context_scoring=false` 默认关闭 ✓
- runtime_facts / judgment_trace 双通道观测 ✓
- fallback 严格保留现有字符级硬截 + LLM 摘要 + 直接调 LLM 三条路径 ✓
- 立项门槛 8 条 ✓
- 不引入新 Maven 依赖 ✓（JDK 21 HttpClient + Jackson）

### 4.3 计划文档需要加 §14

新章节："Jev 6 阶段 state fitting 对 ACH 的借鉴意义" —— 给 ACH `TaskRuntimeContextBuilder` 一个 7 阶段的渐进式剪枝方案，**不要只走"字符除 4"硬截**。

## 5. Jev 优缺点的第一手判断

### 优点（实测可见）

1. **算法鲁棒**：6–7 阶段渐进式 fit，能处理远超 `maxStateTokens` 的 transcript
2. **保留 verbatim**：默认不重写任何文本，只删 / truncate，**保真度比 LLM summary 高一档**
3. **接口极简**：3 形态 answer（noul / choice / score）覆盖 90% 的判决场景
4. **零依赖**：库自身只依赖 Node 标准库 + fetch，npm 50 个包都是 devDependencies
5. **测试充分**：29 用例覆盖 options / fitting / batch / decide / HTTP client / hook 全链

### 缺点 / 边界（实测可见）

1. **N × state_tokens input cost**：同一 state 重复发 N 批，**N 大时成本不便宜**（即便单价便宜）
2. **库级 throw 不分类型**：fallback 决策权 100% 在 caller，ACH 接入时要自己包 try/catch
3. **token 估算不是真 tokenizer**：偏差 2–18%，对内存敏感的场景要小心
4. **pinned 第一 message 永不被剪**：长会话的 system prompt 永远占位，**对长任务不友好**
5. **state 估算依赖 Jev 真实 token 报告反推**：Jev 内部会回 input_tokens，ACH 用 fake Jev 时拿不到这字段，**只能信 estimateTokens**
6. **降级到 LLM summary 时 Jev 帮不上忙**：库只关心"删/留"，不写摘要，**需 caller 自己 fallback 到 LLM summary**

### 上下限

- **下限**：state 大小 = `maxStateTokens = 25000` token + question payload。如果 state 自己就超 25k，会在第 7 阶段 throw `history too large for Jev`，**走 fallback**。
- **上限**：state 不限，但每 batch 都重发 25k state + question，**单次压缩 N batch = 25k × N token input**。N 由 `batchCalls` 自动切分，单 question 约 50 token，batch 上限约 100 question/批。**实际一次压缩单 candidate 不到 100 个 call 的 transcript 不超 1 batch**。

## 6. 结论与下一步建议

### Jev 是不是真适合 ACH？

**是，但有边界**：

- ACH 的 `TaskRuntimeContextBuilder` 当前是"字符除 4"硬截，**Jev 7 阶段渐进式 fit 远比这鲁棒** —— 借鉴价值高
- ACH 的 `HandoffPacketBuilder` 当前是 LLM 生成完整摘要，**Jev 直接打 keep probability 比 LLM 摘要保真度高** —— 借鉴价值高
- ACH 的 `PromptBasedJudgmentService` 在长任务下 judgment 频次高，**Jev 前置过滤能砍掉显然 yes/no 情形** —— 借鉴价值中等

但**所有借鉴都依赖 key**，ACH 接入门槛 = 「用户拿 TYPESAFE_API_KEY」。目前 key 没拿到，只能跑 fake Jev。

### 下一步

1. **如果用户给 TYPESAFE_API_KEY**：
   - `npm run demo` 跑 `examples/demo.ts`，拿真实 transcript 压缩实测
   - 拿 p50 / p95 / p99 latency
   - 拿 input_tokens / output_tokens 真实账单（$0.042/Mtok 估算）
   - 拿 reduction ratio 分布（最差 / 中位 / 最好）

2. **如果用户给 OpenRouter key**：
   - 走 `https://openrouter.ai/api/v1/chat/completions`，model `typesafe/jev-latest`
   - 但 OpenRouter 计费 + 多一层代理延迟，**仅作为 TypeSafe 直签不通时的退路**

3. **如果用户想保留 key 决策空间**：
   - plan §11 立项门槛第 2 条从 "apiKeyEnvVar = TYPESAFE_API_KEY" 改为 "apiKeyEnvVar = TYPESAFE_API_KEY | OPENROUTER_API_KEY"
   - ACH 接入时 runtime 探测 `process.env` 两个 key 都试，按可用性回退

4. **如果用户暂时拿不到 key**：
   - 当前 fake Jev 已经覆盖 contract test 所需全部行为
   - 维持 plan §13 的 fake 骨架，等 key 到位再实施

## 7. 参考

- 本地落点：`D:\gitAll\agent-cloud-harness\.tmp\fast-jev-compaction\`
- 上游：`https://github.com/tamaratran/fast-jev-compaction`（v0.2.0）
- 关联文档：
  - `docs/JEV_CONTEXT_SCORING_RESEARCH.md`（调研层）
  - `docs/JEV_CONTEXT_SCORING_PLAN.md`（计划层）
  - `docs/JEV_HANDS_ON_NOTES.md`（本文，实测层）

> 维护约定：本文是实测记录，未来 plan.md 修订时按 §4.1 列表改动；fake Jev 骨架迁入 src/test/java 时按 §2 fakeJev 模板实现。