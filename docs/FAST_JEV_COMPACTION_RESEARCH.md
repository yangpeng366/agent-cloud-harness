# fast-jev-compaction 借鉴调研（tamaratran × TypeSafe Jev）

> 借鉴类调研，对齐 `THIRD_HAND_COMPUTER_USE_RESEARCH.md` / `JEV_ULTRAFAST_RESEARCH.md` / `JEV_OFFICIAL_SKILL_ABSORPTION.md` 写作形态。

## 0. 摘要

调研 `tamaratran/fast-jev-compaction`（Claude Code plugin + npm library：用 Jev 决策替换默认 compaction summary）在 ACH / dsh / Codex 借鉴图谱里的位置。

本轮结论：

- **值得立刻吸收**：5.6k★ / 305 fork，TypeScript 实现，是 TypeSafe 协议下 **context compaction** 路径的 **canonical 模板**——比 ACH 现有的 `mounted_context_budget_truncated`（字符级硬截）强一档。
- **核心创新（与 ACH 现有方案对比）**：
  - 双 `noul` 问题（call 是否保留 / result 是否 verbatim）—— 比 ACH single-question 更精细，结果可分"记 call 留 trace / 删 result 留 call"两档。
  - 状态拟合 5 阶段（tool input 1000→200→60 → text head+tail → 整条折叠 → 单行塌陷 → call-only 折叠）—— 可直接移植为 ACH `JevContextScorer.fitState`。
  - token 估算 `6 chars/word + 0.5 token/digit + 0.9 token/symbol` —— 比字符数 `/4` 准，calibrated 误差 2–18%。
  - `buildJevRequest` + `parseJevResponse` 纯函数 —— 完美契入 `.tmp/Jev-OpenEyes-experience.md` §4.E 推荐的「库内部只做协议、caller 决定 fallback」模式。
- **可面向 dsh / Codex 用**（用户原话）：codex 的 turn context / dsh 的 subagent context 都是 `messages[]` 形态，几乎零迁移成本。

## 1. 项目画像

- 仓库：https://github.com/tamaratran/fast-jev-compaction
- 作者：tamaratran（个人，非 org）
- 创建：2026-09-17；最新 push 2026-09-18；**仅 4 天**，5.6k★ / 305 fork / 59 open issues
- 技术栈：TypeScript（Node 22+）+ Claude Code function-hook plugin（2.1.274+）；MIT
- 本地快照：`F:\github\fast-jev-compaction`（main，173KB，22 文件：`.claude-plugin/` + `hooks/` + `src/` + `tests/` + `demo/JevDemo/` SwiftUI demo）
- Token 估算 calibrated 误差：2–18%（对比 chars/4 误差可达 40%）

## 2. 架构分解

### 2.1 双 noul 决策（src/compact.ts `questionsFor`）

每个非 pinned tool call 问两个 `noul`：

```typescript
questionsFor(call):
  call_<id>   : keepCall?    # 这条 call 是否还需要记（input 仍相关）
  result_<id> : keepResult?  # call 的完整 output 是否还需 verbatim
```

决策逻辑（`decideCall`）：
- `keepResult ≥ threshold (0.5)` → `keep`（call + result 全留）
- 否则 `keepCall ≥ threshold` → `drop_result`（留 call input，result 截到 truncateHeadChars 300 + note）
- 否则 `drop_call`（call + result 都删）

**关键观察**：`pinned` 永远 keep（第一消息 + 最后 preserveRecentMessages=6）。这是 ACH 当前缺失的"无 LLM 介入强制保留"形态。

### 2.2 状态拟合 5 阶段（src/state.ts `fitState`）

按"激进程度"递增、上一阶段不够才进下一阶段：

1. tool input 截到 1000 字符
2. tool input 截到 200 字符
3. tool input 截到 60 字符
4. 长文本 abridged（head 400 + tail 150）
5. 整条消息折叠为 `[… N chars omitted …]`
6. tool call 塌成单行（`t12 Read file_path=src/a.ts → ok 480ch`）
7. call-only 消息折叠

如果仍超 `maxStateTokens=25k` → 抛错（caller fallback，不静默截）。

### 2.3 token 估算（`estimateTokens`）

```typescript
TOKEN_PIECES = /[A-Za-z]+|\d+|[^\sA-Za-z\d]/g
for each piece:
  digit?        → piece.length / 2
  letter word?  → 1 + Math.floor((piece.length - 1) / 6)
  other symbol? → 0.9
```

`STATE_CONTEXT` 文本作为 state 头，告诉 Jev "这是 compaction 上下文、每个 question 是关于某条 tool call/result 是否要保留、删除的不能恢复但 assistant 可重跑 tool"。

### 2.4 HTTP 协议（src/request.ts + client.ts）

```
POST https://api.typesafe.ai/v1/systemone
Authorization: Bearer <TYPESAFE_API_KEY>
{ model: "jev-latest", state: <any>, questions: { call_1: {type:"noul", instructions:...}, ... } }
```

`buildJevRequest` + `parseJevResponse` 是纯函数，便于测试与 caller 自带 transport。**这正是 ACH `JevDecisionTreeTest` 缺的形态**——目前 ACH 把 HTTP 客户端与 JevAsker 混在一起。

### 2.5 Claude Code plugin 形态（hooks/fast-jev.ts）

- `.claude-plugin/plugin.json` + `.claude-plugin/marketplace.json` 两件即可发布。
- `hooks/hooks.json` 注册 function-hook 到 `session.compact` 事件。
- hook 收到 transcript → 调 `compactMessages` → 失败 fallback 到 Claude Code built-in summary。
- 安装靠 `CLAUDE_CODE_ENABLE_FUNCTION_HOOKS=1` opt-in flag（2.1.274+ 实验性）。

## 3. ACH / dsh / Codex 可吸收点（草案）

### 3.1 立刻可吸（路径 A：协议 + 状态拟合移植）— **已 prototype 落地 2026-09-21**

- **path A prototype 已落 src/test/java/com/agentcloud/scorer/compactor/JevContextCompactor.java**（20069 B），24/24 fake Jev 测试通过（0.231s）。
  - itState(messages, calls, opts) 5 阶段：full → input_cap_1000 → input_cap_200 → input_cap_60 → text_abridge；超 maxStateTokens 直接抛错（caller fallback，不静默截）。
  - decideCall(call, answer, opts) 双 noul：keepResult ≥ threshold → KEEP；keepCall ≥ threshold → DROP_RESULT（truncate 到 headChars + note）；否则 DROP_CALL。
  - pinned 短路：第一消息 + 最后 preserveRecentMessages 永远 KEEP，无需 LLM。
  - token 估算：stimateTokens(text)，calibrated 误差 2–18% 形态（letter 1+floor((len-1)/6)、digit len/2、symbol 0.9）。
  - null-safe：.tmp/Jev-OpenEyes-experience.md §4.E 形态，Jev 返回 null 时默认 keepCall=keepResult=1.0（safe default）。
- **main 路径迁入条件**：按 §11 立项门槛 8+1 条，需维护者开 issue 对齐字段设计后再迁入 src/main/java/com/agentcloud/scorer/compactor/。当前 prototype 形态与 JevDecisionTreeTest path B prototype 平行。
- **uildJevRequest + parseJevResponse 协议分离**：本轮未做（保留为下次，参考 §4.B 不绑死 transport 的指引）。
- **pinned 形态对接 MountedContextView 7 panel PINNED**：下轮在 main 路径迁入时再做。

### 3.2 中期可吸（路径 B：dsh subagent context）

- dsh 的 subagent messages 同样 `role + content` 形态，零迁移成本。
- 落地为 dsh plugin / dsh-context-fragments（注：MEMORY 已记 `dsh-context-fragments` 是 next-cycle candidate）。

### 3.3 长期可吸（路径 C：Codex turn context）

- Codex turn context 是 `messages[]` 但字段稍不同（`role + content[]` 嵌套），需要适配层。
- codex 的 compact 与 resume 流程目前是 LLM-driven summary，可改为 Jev 驱动。

### 3.4 不吸

- Claude Code function-hook 形态（Codex / dsh 各自 hook 模型不同，不通用）。
- SwiftUI demo（`demo/JevDemo/`）—— 仅作 screencast 用。

## 4. 风险 / 注意

- **4 天 5.6k★**：与 jev-ultrafast 同量级新项目，README 自述 "calibration at request level; probability is not proof"——**absorb 以协议/算法为主，不绑死实现**。
- **Jev failures throw 静默 fallback**：README 明示 caller 决定 fallback。这是与 ACH 当前"silent fallback"决策不同的强契约，需用户确认是否接受 throw 形态。
- **保持 verbatim**：chat 文本永不删/改；只删 tool call/result。这条强约束若不写入 ACH 现有 `JevContextScorer`，可能破坏 agentchain 的 traceability。

## 5. 与既有借鉴条目的关系

| 条目 | 焦点 | 本条目补充 |
|---|---|---|
| `recvvPSdSO6yTa` (OpenEyes/third-hand 借鉴) | OpenEyes 决策层 + third-hand 三后端模式 | 无 |
| `recvvNTY6alo4f` (ACH Jev 上下文评分门控) | mounted_context_view 评分门控 | **本条目直接补 compaction 路径（替换 mounted_context_budget_truncated）** |
| `recvvOoAx7RP8D` (Jev × 飞书巡检) | 5 层接入路径 | fast-jev-compaction 形态可接入 Bitable 条目级 compaction |
| `recvvQoJfFH1dY` (Jev 开源项目跟踪) | 全局 Jev 生态源头跟踪 | **本条目为其下子条目之一** |