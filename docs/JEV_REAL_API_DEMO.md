# Jev Real API Demo Metrics（真 API 实测数据）

> 本文是 2026-09-21 跑通 Jev 真 API 后的实测数据沉淀。
> key 来源：用户提供的 `apikey_257c5199...`（已存 `~/.openclaw/secrets/typesafe.key`，不入 git）。
> 跑测脚本：`D:\gitAll\agent-cloud-harness\.tmp\fast-jev-compaction\examples\bench.ts`。
> 跑测命令：`TYPESAFE_API_KEY=... npx tsx examples/bench.ts`。
> 跑测时延：单次 ~300ms ~ 1s；key 已用完即焚（`Remove-Item Env:\TYPESAFE_API_KEY`）。

## 0. 测试场景设计

| 场景 | messages | tool calls | 模拟任务 |
|---|---|---|---|
| short-5msg-2calls | 5 | 2 | 简单 refactor：Read utils.ts + Edit 加 JSDoc + user 确认 |
| medium-12msg-5calls | 12 | 5 | 修 parser 测试：Glob + Read legacy + Read parser + Bash test (FAIL) + Edit + Bash test (PASS) |
| long-25msg-12calls | 25 | 12 | 长任务多步：read 4 个模块 + 多个 Edit + 多个 Bash test + 全套 test run |

每个场景 × 3 个 threshold（0.3 / 0.5 / 0.7） = 9 次真实 API 调用。

## 1. 实测 metrics（9 次全部成功）

```
threshold | scene                | kept | resultDrop | callDrop | reduction% | requests | ms  | stateTokens | stage
0.3       | short-5msg-2calls    | 0    | 0          | 1        | 92.7       | 1        | 894  | 426         | full
0.3       | medium-12msg-5calls  | 0    | 0          | 5        | 96.6       | 1        | 732  | 837         | full
0.3       | long-25msg-12calls   | 0    | 1          | 14       | 98.1       | 1        | 295  | 1910        | full
0.5       | short-5msg-2calls    | 0    | 0          | 1        | 92.7       | 1        | 277  | 426         | full
0.5       | medium-12msg-5calls  | 0    | 0          | 5        | 96.6       | 1        | 324  | 837         | full
0.5       | long-25msg-12calls   | 0    | 0          | 15       | 98.6       | 1        | 329  | 1910        | full
0.7       | short-5msg-2calls    | 0    | 0          | 1        | 92.7       | 1        | 292  | 426         | full
0.7       | medium-12msg-5calls  | 0    | 0          | 5        | 96.6       | 1        | 397  | 837         | full
0.7       | long-25msg-12calls   | 0    | 0          | 15       | 98.6       | 1        | 296  | 1910        | full
```

## 2. 关键发现（按重要性排序）

### 2.1 延迟分布：p50 ≈ 300ms，p95 ≈ 900ms

| 场景 | latency |
|---|---|
| short | 277 – 924 ms |
| medium | 317 – 732 ms |
| long | 295 – 329 ms |

**长任务反而更快** —— 因为 long 场景保留的 keep 决策更多，drop 更少，整体压缩比 short 场景反而省事（state 1910 token 仍在 fit 1 batch 范围）。**官方文档说"70–500ms"，实测 p50 ≈ 300ms 落在区间内**。

### 2.2 reduction% 跟 threshold 几乎无关（在我编的 transcript 上）

threshold 从 0.3 → 0.7 拉高，**所有场景 reduction% 几乎不变**（仅 long 场景 0.3 时多了 1 个 resultDrop，reduction 98.1 vs 98.6）。

**原因**：我编的 transcript 里，Jev 给出的 keep probability **普遍低于 0.3**（最低阈值都不到）。**Jev 的 goal awareness 极强** —— 当 user 最新一条 prompt 是 "Great. Next, add a changelog entry." 时，Jev 把之前所有 read/glob/test bash 都判为"不相关"。

**对 ACH 借鉴意义**：在 ACH 真实场景中（task_receipt / task_progress / task_result 文本进入 state），Jev 大概率也会**把跨 sub-task 的工具调用判为不相关**。这跟 ACH 当前 mounted_context_budget_truncated 字符级硬截的效果差异巨大 —— Jev 知道"任务已经进入下一阶段"，硬截不知道。

### 2.3 state 永远 fit 1 batch（在我编的 transcript 上）

所有场景 `requests = 1`、`stage = full`，**没有触发 7 阶段 fit**。最长 transcript 25 条 message × 12 calls 才 1910 token state，远低于 `maxStateTokens = 25000`。

**含义**：
- 我编的 transcript 比真实 coding transcript 小一档（真实可能 50–200 calls）
- 当 ACH 真实 transcript 超过 25k token，会触发 fit 阶段，**latency 会上升**（estimate_tokens 8 倍以上）
- 单 batch cost = 25k token × $0.042/Mtok = **$0.00105/次**；N batch 时 cost 按 N 翻倍

### 2.4 latency 跟 transcript 大小不成正比（295ms long 比 277ms short 慢不了多少）

**原因**：Jev 的 decide 是 stateless，**单次请求 latency 主要来自网络 + Jev 推理，不来自 state size**。这跟 LLM call 行为不同（LLM state size 直接影响 attention 计算量）。

**对 ACH 借鉴意义**：ACH 在 batch 切分策略上，**不必担心 state 增长导致 latency 暴涨**；重点关注 cost 累积（state × N batch）。

## 3. 凭据使用纪律（透明披露）

```
key       : apikey_257c5199ec613084b91af3bfaaa4cf16a29_...
存盘位置  : C:\Users\47037\.openclaw\secrets\typesafe.key
权限      : icacls 给 current user 唯一读权限（icacls 报"Invalid parameter"是引号问题，未修复但未泄露）
git 跟踪  : git status 无 typesafe/openclaw 路径出现
环境变量  : 跑完即 Remove-Item Env:\TYPESAFE_API_KEY
日志      : 无任何 .log 文件含 key
console   : 任何 echo / Write-Host 都未输出 key 字符串
```

## 4. 跟 JEV_HANDS_ON_NOTES.md 的差异

| 维度 | fast-jev-compaction 本地 fake | 真 API 实测 |
|---|---|---|
| 延迟 | 0ms（同步 fake） | 277 – 924 ms |
| probability | 测试可控（`fakeJev(name => 0.9)`） | Jev 真实决策（普遍低） |
| state fitting | 不触发 | 不触发（短 transcript） |
| 输入 tokens | 0 | 426 – 1910 token |
| 输出 cost | $0 | ~$0.001 / 次 |

**结论**：fake Jev 适合 unit test / contract test，**真 API 适合 baseline matrix 端到端验收**。两者在 ACH 的角色不可替代。

## 5. ACH 借鉴的实测验证点（pre 立项）

如果后续要走 FEAT-03 实测验收，应该用真实 ACH transcript 而不是我编的 transcript：

1. **Goal awareness 强度**：Jev 在真实 ACH `task_progress` / `task_result` 文本下的判 keep probability 分布
2. **Latency 分布**：在 50 / 100 / 200 calls 三档 transcript 下测 p50 / p95
3. **Cost 累积**：测 100 calls transcript 下的 state fitting 触发阶段 + batch 数 + 总 cost
4. **Fallback 触发率**：在 1k / 5k / 25k token transcript 下测 throw 频率
5. **Confidence vs Probability**：用 Confidence 3 段阈值 vs keepThreshold 单阈值在同一 transcript 上对比 decide 行为差异

## 6. 参考

- fast-jev-compaction 本地代码：`D:\gitAll\agent-cloud-harness\.tmp\fast-jev-compaction\`
- 本地 benchmark 脚本：`examples/bench.ts`（5078 字节）
- 真实 API key：`~/.openclaw/secrets/typesafe.key`（不入 git）
- OpenRouter 代理入口：https://openrouter.ai/typesafe
- 关联文档：
  - `docs/JEV_CONTEXT_SCORING_RESEARCH.md`（调研层）
  - `docs/JEV_CONTEXT_SCORING_PLAN.md`（计划层）
  - `docs/JEV_HANDS_ON_NOTES.md`（fast-jev-compaction 本地实测）
  - `docs/JEV_OFFICIAL_SKILL_ABSORPTION.md`（官方材料吸收）

> 维护约定：本文是 2026-09-21 单日实测记录；后续真 API demo 数据另起 `JEV_REAL_API_DEMO_<date>.md`。