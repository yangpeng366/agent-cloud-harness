# jev-ultrafast 借鉴调研（browser-use × TypeSafe Jev）

> 借鉴类调研，对齐 `THIRD_HAND_COMPUTER_USE_RESEARCH.md` 与 `JEV_OFFICIAL_SKILL_ABSORPTION.md` 写作形态。

## 0. 摘要

调研 `browser-use/jev-ultrafast`（description：`i. am. speed.`，`A browser agent that chooses instead of generating.`）在 OpenEyes / ACH 借鉴图谱里的位置。

本轮结论：

- **值得强烈关注**：13.6k★ / 838 fork（远超 third-hand 265★），由 `browser-use` 出品，定位是 **Jev 在浏览器侧的生产级实现**——把 TypeSafe Jev 的 fan-out + parallel_questions cookbook 跑通了。
- **核心机制（与 third-hand 异同）**：third-hand 用 Swift 跑 macOS 系统级 UI；jev-ultrafast 用 Python 跑**浏览器 DOM**（snapshot.js + browser-harness）。两者都用 Jev 决策，**互补**。
- **可吸收点**：operation fan-out + 1 round trip + `validate_choice` 严格校验（`set(probabilities) == set(ids)` + `sum ≈ 1` + `top_choice = max`）。这是 ACH `JevDecisionTreeTest` 与 OpenEyes `decision/scorer.py` 都没有的强契约测试形态。
- **不吸收**：browser-harness（专门给 browser-use 体系用）、Chrome harness 协议本身（auto-deploy 已有 puppeteer-core）。
- **下一步**：把 `validate_choice` 形态迁移到 OpenEyes `JevScore` 校验（`probabilities` 字典求和 + top-1 选择校验），让 real Jev 接入时第一道防线就位。

## 1. 项目画像

- 仓库：https://github.com/browser-use/jev-ultrafast
- 作者：`browser-use` Org（与 browser-use 主仓配套），homepage 指向 `https://browser-use.com/ultrafast`
- 创建：2026-09-16；最新 push 2026-09-18；**仅 5 天**，13.6k★ / 838 fork / 91 open issues
- 技术栈：Python 3.12 + `browser-harness==0.1.13` + `httpx[http2]`，MIT
- 本地快照：`F:\github\jev-ultrafast`（main，2.3MB，40 文件，含 docs/ examples/ jev_ultrafast/ scripts/ tests/）
- Demo 数字（README 实测）：
  - Google Flights Zürich→London：7.073s（6 次交替 3/3 PASS；中位时间从 9.450s → 7.092s，降幅 25%；中位 browser protocol 调用 1092 → 101）
  - Wikipedia Gödel 文章打开：2.798s
  - 本地酒店搜索/筛选：1.896s
  - MAX_STEPS = 60

## 2. 架构分解

### 2.1 决策循环（agent.py）

- 单 goal → `Browser(url).observe()` → `page["actions"]` → `action_space()` → `choose()` → `act()` → 循环
- `MAX_STEPS * 2` 是 model-call 预算（决策 + 文本生成各算一次）
- `StalePage` 异常处理：自动重新 observe，不重试 browser mutation
- 失败/成功独立 outcome verification（不靠 DONE 决策）

### 2.2 action_space + operation fan-out（model.py）

关键 `action_space()` 把每个 observed element 索引化（1-based `index`），按 operation 分组：
```python
operations = {"click": "CLICK", "fill": "TYPE_TEXT", "select": "SELECT"}
# 每个 element 携带 operations 列表，targets[operation][target_index] = action
```

好处：**一次 TypeSafe 请求同时问 (operation) + (click_target) + (type_text_target) + (select_target)**，只消费被选 operation 对应的 target head。`questions.py` 三个 prompt：

- `NEXT_ACTION`：决定 operation
- `TARGET`：决定 selected operation 对应的 target
- `TEXT_VALUE`：仅在 `TYPE_TEXT` 时由小 LLM 生成（`inception/mercury-2.5` via OpenRouter）

### 2.3 validate_choice 严格校验（model.py）

> 这才是本轮最值钱的产物。

```python
def validate_choice(answer, ids):
    probabilities = answer["probabilities"]
    numbers = [*probabilities.values(), answer["confidence"]]
    valid = (
        answer["choice"] in ids
        and set(probabilities) == set(ids)                 # 概率字典 key 与候选 ID 完全一致
        and all(0 <= n <= 1 and math.isfinite(n) for n in numbers)
        and abs(sum(probabilities.values()) - 1) < 0.02   # 概率和 ≈ 1
        and probabilities[answer["choice"]] >= max(probabilities.values()) - 1e-6  # top-1 = choice
    )
```

### 2.4 snapshot.js + browser-harness

- `snapshot.js`：浏览器侧原子 DOM snapshot（role / label / value / checked / selected / expanded / node reference）
- `browser-harness`：browser-use 自家的 Chrome harness（**不**吸收，是它自家协议）

### 2.5 安全护栏（与 third-hand 同形态）

- 输入是单一自然语言 goal，不加 site-specific plans
- TypeSafe 只选 operation + target；**模型不输出 selector / 坐标 / shell / JS**
- TEXT_VALUE 小模型必须解析为 JSON object 才打字
- credentials server-side、`.env` ignored
- DONE 选择 ≠ 成功，independent outcome verification

## 3. OpenEyes / ACH 可吸收点（草案）

1. **`validate_choice` 形态迁移到 OpenEyes `JevScore`**（强烈推荐）
   - 当前 `JevScore.__post_init__` 只校验 `confidence` 与 `prob` 单值，未校验"top-1 = choice / sum ≈ 1 / ids 全覆盖"。
   - 建议加 `validate_probabilities(probs, choice)` 静态方法，与 `JevScore` 解耦，便于 fake 与 real 同享。
2. **operation fan-out 模板**
   - OpenEyes 决策层当前输出单一 `Action`；若未来要支持"同一 page 多 operation 选择"，可借鉴 `targets[operation][target]` 字典结构。
3. **questions.py 三 prompt 模板**
   - `NEXT_ACTION` + `TARGET` + `TEXT_VALUE` 三段对齐 TypeSafe 协议，可直接用作 ACH `JevDecisionTreeTest` 的 prompt fixture 灵感。
4. **MAX_STEPS = 60** 作为 OpenEyes task runner 边界默认值（third-hand 用 30，更激进；jev-ultrafast 60 更稳）。
5. **不吸收**：browser-harness / Chrome harness / Chrome Inspector Web UI（与 OpenEyes / auto-deploy 现有栈无交集）。

## 4. 风险 / 注意

- **强绑定 TypeSafe**：与 ACH 一样，离开 TYPESAFE_API_KEY 就跑不起来；OpenEyes 决策层仍按"不重复造 JevClient"原则，real client 走 ACH 或直接对接。
- **browser-harness 是 browser-use 自家协议**：不直接复制，但可观察其与 puppeteer-core 的对比，作为 `electron-cdp-probe.js` 后续对照。
- **5 天 13.6k★ 暴增**：项目极新，README 自述"MVP / not a general reliability benchmark"；absorb 以"协议/契约"为主，不绑死实现。
- **MAX_STEPS × 2 = 120 model calls per task**：注意 model-call 预算——OpenEyes 任务跑动时需类似上限。

## 5. 与既有借鉴条目的关系

| 条目 | 焦点 | 本条目补充 |
|---|---|---|
| `recvvPSdSO6yTa` (OpenEyes/third-hand 借鉴) | OpenEyes 决策层 + third-hand 三后端模式落地 | 浏览器侧 operation fan-out / validate_choice |
| `recvvNTY6alo4f` (ACH Jev 上下文评分门控) | mounted_context_view 评分 | questions.py 三 prompt / MAX_STEPS 边界 |
| `recvvNWh9u3lQZ` (ACH OpenEyes UI 自动化) | UIA + CDP + MCP 13 tool | browser-harness 对照观察 |
| `recvvOoAx7RP8D` (Jev × 飞书巡检) | 5 层接入路径 | fan-out 接入巡检选择层 |
| **本条目 recvvQoJfFH1dY** | **全局 Jev 生态跟踪** | **聚合上述 4 条目外部源头** |


---

## 6. validate_probabilities 落地（2026-09-21 17:30）

> `validate_choice`（jev-ultrafast model.py L18-32）的契约形态被吸收进 OpenEyes `decision/scorer.py`。

### 6.1 落地物

- `E:\gitAll\openeyes\openeyes\decision\scorer.py`（9.3KB，新增 `validate_probabilities` 静态方法 + `JevScore.probabilities` 字段 + `FakeJevScorer._build` 自动归一化）
- `E:\gitAll\openeyes\tests\decision\test_decision.py`（13 个新测试：8 个 `validate_probabilities` + 2 个 `inject_probabilities` + 2 个 `JevScore.probabilities` + 1 个 default-fallback 契约）
- 测试总数：24 → 37；全仓库 93 → 106，零回归

### 6.2 契约 5 条

```python
def validate_probabilities(probabilities, choice, candidate_ids) -> None:
    # 1. choice ∈ candidate_ids
    # 2. set(probabilities.keys()) == set(candidate_ids)  ← set equality
    # 3. every value finite ∈ [0, 1]
    # 4. sum(probabilities.values()) ≈ 1.0  (tolerance 0.02)
    # 5. probabilities[choice] is top-1  (tolerance 1e-6)
    # Raises ValueError on any violation.
```

### 6.3 关键工程教训

- **`FakeJevScorer` default fallback 必须把 `prob` 抬到 `1/n + 0.01`**——否则 `(1-prob)/(n-1) > prob` 时 top-1 不在 choice，违反契约 5。这暴露了 jev-ultrafast 的 `validate_choice` 一旦反向用 fake（mock 弱真实分布）会立刻报错——必须保证 fake 构造的概率分布也满足 contract。
- **`JevScore.probabilities` 用 `frozenset` 存储**——`dict` 不是 hashable，但 fake/real 共享概率 dict 是常见 debug 需求。`__post_init__` 自动归一化为 frozenset，hashable 但语义保留。
- **`inject_probabilities` 测试注入点**：允许测试用例传入完整 probabilities dict（任意形状）来验证契约违反场景（如 `sum≠1` 抛错、`choice` 不在 ids 抛错）。这给后续接 real JevScorer 时做"协议回归测试"留好了入口。
- **n=1 边界**：`prob=0.90` + `n=1` 时 `_build` 必须把单候选 prob 设为 1.0（否则 sum=0.9 ≠ 1）。这是 fake 与 real 通用的边界，单独处理。

### 6.4 与 ACH FEAT-03 / OpenEyes 决策层关系

- **`validate_probabilities` 是 Contract-Additive 的边界**：fake 与 real 共享同一函数，契约统一在模块顶层；`JevScore.__post_init__` 只校验单值（已有），不重复校验概率字典（避免双重 raise）。
- **未引入 jev-ultrafast 的其它借鉴**：browser-harness、questions.py 三 prompt、MAX_STEPS 边界——这些是浏览器域专用，与 OpenEyes 跨后端定位不同。
- **下一步候选**：把 `validate_probabilities` 也加到 ACH `JevDecisionTreeTest` 的 fake Jev Asker（path B prototype 已签 src/test/java/com/agentcloud/scorer/JevDecisionTreeTest.java），让 Java 侧也能享受同一道闸——但需先看 Java 侧 JevAsker 是否有完整 probabilities 字段；若没有则属另一轮扩展。