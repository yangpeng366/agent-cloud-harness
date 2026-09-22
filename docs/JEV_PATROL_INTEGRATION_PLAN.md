# Jev Patrol Integration Plan（Jev × 飞书巡检流程接入）

> 本文是 `JEV_OFFICIAL_SKILL_ABSORPTION.md` 的延展，从「Jev 单项目借鉴」升级到「Jev × 飞书巡检流程系统性接入」。
> 时间：2026-09-21。
> 范围：Bitable 「项目方向」表 5 条当前在跑项目 + auto-patrol / lark-bot-listener 双通路巡检架构。
> 不做：不动 Bitable schema（破坏性变更 + 需维护者拍板）；不动 `Start-CodexAutoPatrolLoop.ps1`（磁盘上不存在，pre-existing drift，本地无源）。

## 0. 一句话总结

**Jev 在飞书巡检生态里的价值不是「让一个项目跑得更快」，而是「让整个巡检体系的决策密度提升 10×」** —— 从「每个项目 round 一次，nssm 推结果」升级到「每个项目每天多次 Jev-driven 决策，nssm 只推 Jev 标了 keep 的」。

## 1. 飞书巡检体系现状（2026-09-21）

### 1.1 Bitable 「项目方向」表当前在跑项目

| 记录 | 项目 | 优先级 | 当前阶段 | 巡检形态 |
|---|---|---|---|---|
| recvuNYpwFcoyx | 巴中方正云雀-见报成品接入预研 | P2 | 开发 | 60min 巡检 |
| (networkx) | networkx 开源贡献 | P1 | 开发 | 长 PR 节奏，按需 |
| (ach) | agent-cloud-harness | P1 | M0 happy path done → M1 待放行 | 本地主仓 |
| (fastjson2) | alibaba/fastjson2 | P1 | 上游 PR hold | 按 PR 推进 |
| (auto-deploy) | 代码自动巡检 / 监听器 | P1 | 巡检自维护 | 进程健康 |
| (ccx) | CCX 环境 | P0 | 60min 巡检 | 渠道健康监控 |

### 1.2 巡检双通路（来自 MEMORY.md 区分）

| 通路 | 形态 | 关键文件 |
|---|---|---|
| **codex-auto-patrol** | nssm 注册的循环调度，按 Bitable 字段派工 | `Start-CodexAutoPatrolLoop.ps1`（**磁盘上不存在**，pre-existing drift） |
| **lark-bot-listener** | nssm 注册的 Windows 服务，IM 事件 + ack | `lark-bot\listener.ps1` + `run-codex.ps1` |

### 1.3 现有巡检的痛点（从现状推断）

- **每次 round 都推飞书通知**：无差别推送，UI 噪音大
- **项目进度靠人手 review**：`.tmp\patrol-last-*.md` 摘要后由人判断
- **P0/P1 抢占 round 资源**：worker-pool refactor 已修，但**项目间相互优先级判断**仍是手工
- **Jev 当前只在 ACH 自己借鉴**：未外溢到「巡检流程本身」

## 2. Jev 接入 5 层路径

### L1. Bitable 条目字段级接入（已做）

**已落地**：

- `recvvNTY6alo4f` (Jev 上下文评分门控) — `recvvNWh9u3lQZ` (OpenEyes UI 自动化接入) 软互引
- 字段：项目名 / 当前阶段 / 优先级 / 贡献类型 / 难度 + 用户批注软引用

**可加（不破坏 schema）**：

- 在「用户批注」字段追加 Jev-derived 笔记（如本轮 JEV_OFFICIAL_SKILL_ABSORPTION.md 完成后，给两条记录追加「已吸收官方材料」段落）
- 在「简要描述」字段追加 confidence 评分（如 `Jev confidence = 0.95`）

### L2. 巡检派工决策级接入（**最有价值**）

**当前形态**：`Start-CodexAutoPatrolLoop.ps1` 按 `nextRun / dueList` 拉起 Codex sub-process 跑项目 round。

**Jev 接入**：

```
每次 round 启动前（nssm 触发前）：
1. 拉 Bitable dueList 的所有项目
2. 对每个项目，构造 Jev request：
   - state: 项目的最近 3 轮 patrol output + 当前 stage + next_step
   - questions:
     * progress: noul "此项目最近一轮有进展吗？"
     * blocker: noul "此项目当前是否被外部条件卡住？"
     * urgency: noul "此项目是否需要用户决策？"
     * confidence: noul "next_step 是否具体可执行？"
3. 按 classifying_rag_passages THRESHOLDS dict 路由：
   - progress >= 0.7 AND urgency >= 0.7 → "act" (推 Codex round)
   - progress < 0.3 AND confidence < 0.3 → "human_review" (不推 Codex, 推飞书长卡片给用户)
   - blocker >= 0.7 → "blocked" (不推, 写入 Bitable blocker 字段)
   - 其他 → "skip" (下一轮再说)
4. 只对 "act" 状态项目启动 worker pool
```

**预期收益**：

- **每轮 round 实际启动 worker 数减少 50%+**（很多项目"没进展"被 skip）
- **飞书通知减少 70%+**（"human_review" 与 "blocked" 不发短消息，改成长卡片）
- **每次 round 节省 30–60 分钟 Codex sub-process 时间**

**风险**：

- Jev API 调用增加（每轮 N 项目 × 4 question = 4N 次 API call），但单价 $0.042/Mtok 几乎可忽略
- Jev 网络抖动会导致整个 round 不启动 → **必须 fallback**：任何 throw 退回到「全部 dueList 启动」现状
- 评估缓存：同一项目的 4 个 question 重复时复用 state（fast-jev-compaction 7 阶段 fit 算法可借鉴）

### L3. 巡检输出后处理级接入（中等价值）

**当前形态**：每次 round 落地 `.tmp\patrol-last-*.md`（11–17K 字节），由人或 auto-deploy UI 消费。

**Jev 接入**：

```
patrol-last-*.md 落地后, 启动后处理:
1. Jev 拿全文 + 当轮 round context
2. 用 `key_decisions` cookbook 模式抽 5–8 个 key points
3. 把结构化 JSON 写回 .tmp\patrol-decisions-<project>-<stamp>.json
4. Bitable 的「变更摘要」字段（如果存在）消费该 JSON
```

**预期收益**：

- UI 消费侧看到结构化 `key_decisions`，不需再读 11K raw md
- `patrol-decisions-*.json` 形成跨 round 趋势数据

**风险**：低；patrol-last-*.md 本身已有，是**纯增量**接入。

### L4. 跨项目 keep_probability 联动（脑洞）

**形态**：

- 每个项目除了自己的 `keep_probability`，还有 `sibling_keep_probability`（跟相关项目的关联强度评分）
- Jev 用 `intent_routing` cookbook 评估"项目 X 跟项目 Y 在技术上是否共享 learnings"
- 形成项目间 reference 网（不是强引用，是软引用）

**预期收益**：

- 当 ACH `agent-cloud-harness` 项目有进展时，**自动评估**它对 `Jev × ACH` 借鉴类工作的 keep_probability
- 当 `fastjson2` 上游有 PR 推进时，**自动评估**它对 `codex-auto-patrol` 借鉴的 keep_probability

**风险**：高；schema 改造大，**中长期**。

### L5. Bitable schema 升级（极长期）

**形态**：Bitable 加 5 个新字段：
- `jov_keep_probability`（number 0–1）
- `jov_confidence`（number 0–1）
- `jov_uncertain_band`（boolean / text "true if 0.30 ≤ p ≤ 0.70"）
- `jov_routing`（select "act / human_review / blocked / skip"）
- `jov_last_evaluated`（datetime）

**触发条件**：L2 / L3 跑稳后再做 schema 升级。

## 3. 中长期脑洞（high imagination）

### 脑洞 A：飞书巡检的「假阳性减少」革命

**当前**：60min 巡检 → 不管项目有没有变化都推飞书。
**未来**：每次 round 启动前 Jev 评估 → 只有 `progress ≥ 0.7` 的项目才发短消息；其他项目**静默汇总到每日摘要**。
**收益**：飞书噪音减少 70%+；用户注意力聚焦到真正变化的项目。
**前提**：L2 接入跑通。

### 脑洞 B：项目方向表的「持续健康度评分」

**当前**：每个项目字段靠人手维护。
**未来**：每周一次 Jev 跑 `self-consistency` 14-question rubric → 输出每个项目的 keep_probability 曲线 → 形成"项目健康度热力图"。
**收益**：长期投资地图可视化；项目优先级动态调整（`priority` 字段从 P0/P1/P2 静态 → 动态 + 不确定性带）。
**风险**：高；schema + 算法 + UX 三层都需要重做。

### 脑洞 C：项目间 keep_probability 联动网

**当前**：项目间靠手工 `用户批注` 软引用（我刚做的工作）。
**未来**：Jev 自动评估「项目 X 跟项目 Y 在技术路径上的 keep_probability」，形成图结构。
**收益**：自动发现「AGENTS.md 应该 link 哪些项目」、「哪些项目可以共享 learnings」。
**风险**：高；图计算 + UI 渲染。

## 4. 落地优先级（pre 维护者拍板）

| 阶段 | 任务 | 落地形态 | 依赖 | 预估时间 |
|---|---|---|---|---|
| **Phase 1** | L1 信息层加 Jev 笔记 | 已做（用户批注 + 简要描述） | 无 | 0 |
| **Phase 1.5** | L3 heuristic 后处理骨架 | scripts/lib/JevPatrolL3Extract.ps1 + Run-JevPatrolL3Extract.ps1（heuristic，无 key 也能跑）；51 个合同 test 覆盖 | 无（heuristic fallback） | 已完成 |
| **Phase 2** | L3 Jev 后处理骨架 ✅ 已完成（2026-09-22） | `scripts/lib/JevApi.ps1` + `Run-JevPatrolL3Extract.ps1 -Mode jev` 已真接通；每条 decision 记录 `jev_probability/action/error`。真实巡检样本 5/5 有效返回；live API、roundtrip、heuristic-vs-Jev 三套件 35/35 通过；全套脚本 189/0。 | key = `TYPESAFE_API_KEY`（仅临时进程注入） | 已完成 |

| **Phase 3** | L2 派工决策 dry-run（sidecar 已落地，默认关闭） | 飞书 concrete：`D:\gitAll\patrols\feishu-projects-patrol\scripts\JevShadow.ps1` 在 due 项目进入 Codex 前旁路评分；写 `state/jev-shadow/*.json`，不改变 fake/real 分流、不跳过 worker、不写 Jev 结果回 Bitable。scaffold concrete：`D:\gitAll\patrol-scaffold\scripts\JevShadow.ps1` provider-agnostic；默认在 worker 完成回写后旁路评分，写 `downloads/jev-shadow/*.json`；`tests/verify-jev-shadow.ps1` 离线合同 `9/9`。两条 concrete 主入口 AST 均为 0 errors。 | `jev_shadow.enabled=true` / `patrol-shadow-config.json enabled=true` + 临时进程 `TYPESAFE_API_KEY` | 待真实样本窗口 |
| **Phase 4** | L2 派工决策 active | 启用 Jev 路由，failure fallback 到现状 | Phase 3 | 1 周 |
| **Phase 5** | L5 schema 升级 | Bitable 加 5 个字段 | Phase 4 跑稳 | 1 周 |
| **Phase 6** | 脑洞 A/B/C | 各自独立 | Phase 5 | 长期 |

## 5. 关键风险与缓解

| 风险 | 缓解 |
|---|---|
| Jev API 抖动阻塞整轮 round | Phase 3 必须 shadow mode；任何 throw 立即 fallback 到全 dueList 启动现状 |
| Jev 评估增加延迟 | Jev 单次 ~300ms，N 项目 ~3s（p95），远低于 60min 巡检周期，可接受 |
| Jev 评估本身错（false negative skip 真有进展的项目） | uncertainty band 0.30–0.70 显式送人审；用户能 override 决策 |
| 凭据管理混乱 | TYPESAFE_API_KEY 仍存 `~/.openclaw/secrets/typesafe.key`；auto-deploy 路径加一份引用但**绝不复制** |
| auto-deploy 项目本身不支持 L2 | 已落 `D:\gitAll\patrol-scaffold\Start-CodexAutoPatrolLoop.ps1`（provider=bitable / dry-run-first）+ `D:\gitAll\auto-deploy\Start-CodexAutoPatrolLoop.ps1`（wrapper 转发）。`scripts\BitAbleAdapter.ps1` 复用 `lark-base-helpers.ps1` 完成 Bitable ↔ item shape 转换；`-SyncBack` 双闸门（config.sync_back && CLI `-SyncBack`）默认关闭。离线合同 `tests\verify-bitable-adapter.ps1` `16/16`。Phase 3 sidecar 沿用同一 dry-run / shadow 通道。 |

## 6. 与 ACH 自有借鉴的关系

ACH 自有借鉴（FEAT-03 / HW-09 / HW-10）走的是 **harness 内部**判断 Jev 是否接入；本计划是 **auto-deploy 巡检体系**接入 Jev。**两条路互不依赖，但能共享**：
- ACH 借鉴落地后，auto-deploy 的 L2 接入可以直接调 ACH 的 `JevContextScorer`（不需要单独 SDK）
- auto-deploy 的 L4 联动网（脑洞 C）需要 ACH 的 `TaskRuntimeContextBuilder` + Jev 评分共同支撑

## 7. 立项门槛（pre 维护者拍板）

1. 维护者先确认「飞书巡检流程接入 Jev」是否符合预期方向
2. 维护者声明 `TYPESAFE_API_KEY` 是否给 auto-deploy 用（按现有凭据纪律存 `~/.openclaw/secrets/typesafe.key`）
3. Phase 3 先做 shadow/dry-run；Phase 4 active routing 必须等 shadow 证据
4. schema 升级（Phase 5）需要单独决策

## 8. 下一步

- Phase 1 已完成（本轮 + 之前几轮）
- Phase 4 设计稿已存 docs/JEV_PATROL_PHASE4_DESIGN.md：决策准入（uncertainty band 0.30-0.70 必须人审，小于 0.30 直接 KEEP，大于 0.70 直接 TRUNCATE；sidecar 1xx Jev 异常均 fallback） + 控制器（feishu Invoke-JevProjectDecision / scaffold Invoke-JevItemDecision） + 评测（Run-BuildJevShadowEval.ps1 输出 per-project 命中率、TP/FP/FN/TN 与人工对照）。
- uto-deploy ↔ patrol-scaffold ↔ Bitable 的桥已恢复；下一步是真实 shadow 窗口下放 sample run，验证 sidecar 切错码、错位、漏数据等异常被捕获。
- Phase 2 已完成：真 key 仅以临时进程环境变量注入，未写入仓库或文档
- 下一步按 Phase 3 shadow mode 设计旁路记录，不改变现有 worker pool 派工

## 9. 参考

- `docs/JEV_OFFICIAL_SKILL_ABSORPTION.md`（官方材料吸收）
- `docs/JEV_REAL_API_DEMO.md`（真 API metrics）
- `docs/JEV_CONTEXT_SCORING_PLAN.md`（FEAT-03 落地设计）
- Bitable `recvuNYpwFcoyx` (巴中方正云雀) / `recvvNTY6alo4f` (Jev 上下文评分门控) / `recvvNWh9u3lQZ` (OpenEyes UI 自动化接入)
- MEMORY.md `Codex Auto-Patrol worker pool` Task Group（`Start-CodexAutoPatrolLoop.ps1` 实际形态）

> 维护约定：本计划是「Jev × 飞书巡检」方向的 system map；Phase 推进时按阶段单独立项，不要一次吃 6 个 Phase。
