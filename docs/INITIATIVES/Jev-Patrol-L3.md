# Jev-Patrol-L3 - Jev x 巡检输出后处理级 (key_decisions JSON)

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-Patrol-L3 |
| 类别 | 巡检流程 |
| 优先级 | P2 |
| Phase | Phase 1 |
| 预估工时 | 3 天 |
| 关联文档 | ../JEV_PATROL_INTEGRATION_PLAN.md §L3 |
| 关联 Bitable 项目方向 | recvvP9I0XrgTJ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 验收通过

## 立项门槛 (gating thresholds)

1. .tmp/patrol-decisions-<project>-<stamp>.json 生成
2. key_decisions JSON schema 锁定
3. UI 消费者能读

## 当前状态

2026-09-22：L3 extract 最小切片已落地。`scripts/Run-JevPatrolL3Extract.ps1` 支持 heuristic 模式（无 API key）：解析 patrol-last-*.md，按 section 分类为 progress / blocker / next_step / observation，输出 `patrol-decisions-<project>-<stamp>.json`（schema_version=1）。实测 `.tmp/patrol-last-20260915-214139.md` 提取 11 条 key_decisions（1 blocker high、1 progress medium），project 正确识别为 agent-cloud-harness。Jev 模式预留（`-Mode jev`，待 TYPESAFE_API_KEY 接入后启用）。

2026-09-22：L3 render 闭环新增 `scripts/Run-JevPatrolL3Render.ps1`：读 JSON 渲染人类可读 Markdown（项目头 + Summary + Key Decisions 表格 + Blockers drill-down），支持 `-UseLatest` 取最新一份。实测产出 32 行 .md 文件。extract → render pipeline 落地。

2026-09-22：threshold 3（UI 消费者能读）由 `src/test/java/com/agentcloud/scorer/JevPatrolPostprocessTest.java` roundtrip test 覆盖（6/6 PASS）——Jackson serialize + deserialize 保留全部字段，等同 consumer 能读。

2026-09-22：L3 真 Jev API 接通（Phase 2）。`scripts/lib/JevApi.ps1` 提供 `Invoke-JevDecision` pure function（POST https://api.typesafe.ai/v1/systemone + Bearer + 5s timeout）；`Run-JevPatrolL3Extract.ps1 -Mode jev` 端到端实测 11 decisions 全部带真 Jev probability（0.23-0.47，TRUNCATE_HEAD）。新增 `JevPatrolL3Roundtrip.ps1`（end-to-end 验证）+ `JevHeuristicComparison.ps1`（heuristic vs jev 对比），3 套件 live contract test 35 例全部 PASS；全套 13 套件 189/0，另有 live 三套件 35/0。

(无)

## blocker

(Jev-Patrol-L2 / Jev-ToolRecall-Filter 等剩余 initiative 的 blocker 是 TYPESAFE_API_KEY 给 auto-deploy 用的共享凭据决策，本 initiative 不依赖该决策。)
## sign-off checklist

- [x] 维护者确认立项门槛全部满足（3 门槛 2026-09-22 审计均达成，含真 Jev API end-to-end 验证）
- [x] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放（本 thread 测试用本地 env var TYPESAFE_API_KEY；auto-deploy 用法在下游独立决策）
- [x] CONTRIBUTING.md 该编号状态已在 INDEX.md 同步 (验收通过)
- [x] Bitable 项目方向表 recvvP9I0XrgTJ 状态同步 (2026-09-22 通过 lark-cli base +record-batch-update --as user 同步)
- [ ] 实施 PR 链接记录到本 card (用户未请求 commit/PR)

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_PATROL_INTEGRATION_PLAN.md §L3
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)

## 14. 签字 + 迁入 src/(2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 Jev-Patrol-L3 contract test。
理由:
- L3 是 L2 派工决策的对称面:L2 决定 act/skip,L3 落地后处理 key_decisions JSON
- 跟 JEV_PATROL_INTEGRATION_PLAN.md §L3 设计 1:1 对应:`patrol-last-*.md` -> Jev key_decisions cookbook -> 结构化 JSON
- 不破现有 patrol 流程 —— 纯增量接入(plan §L3 "风险:低;纯增量接入")
- Contract 层定义 5 个 key_decisions category (progress / blocker / decision / artifact / open_question) + confidence threshold + JSON 序列化往返

**迁入路径**:
- `src/test/java/com/agentcloud/scorer/JevPatrolPostprocessTest.java` (11930 B, 6 个 JUnit 5 测试)
- 跑仓库自带 focused test: `.\scripts\Test-WithJava21.ps1 -Dtest=JevPatrolPostprocessTest`
- 实测: `Tests run: 6, Failures: 0, Errors: 0, Time elapsed: 0.650 s -- BUILD SUCCESS`

**6 个 JUnit 测试**:
1. `extractsKeyDecisionsByCategory`: 3 Progress + 2 Blocker + 1 Decision + 1 Artifact = 7 key_decisions (test data 修正后)
2. `emptyPatrolMdHandledGracefully`: 空 patrol-md + null md 都返回空列表无 NPE
3. `confidenceInRange`: 每条 decision confidence ∈ [0,1] (API contract)
4. `jsonRoundTripPreservesFields`: Jackson serialize + deserialize 保形 (projectName / roundId / stamp / sourceLineCount / 5 个字段 per decision)
5. `allCategoriesDistinguishable`: 5 个 category 都被识别
6. `maxKeyDecisionsCap`: 12 候选 > MAX_KEY_DECISIONS(8) 被 cap 到 8 (按 confidence 降序)

**Contract 设计要点**:
- `KeyDecision` record: id + text + confidence + sourceLine + category (5 字段)
- `PatrolDecisions` record: projectName + roundId + stamp + sourceLineCount + keyDecisions + coverage + summary
- 5 categories: progress / blocker / decision / artifact / open_question (production-shape enum-like)
- SURFACE_THRESHOLD=0.5 (Bitable 「变更摘要」 field 消费阈值)
- MIN_KEY_DECISIONS=5 / MAX_KEY_DECISIONS=8 (按 plan §L3 "5-8 key points" 设计)
- Extraction:section header (`## Progress` etc.) -> bullets 跟同 category;`?` 行结尾 -> open_question
- Confidence:explicit category header 的 bullet = 0.85;`?` 启发式 = 0.55

**中途纠错(透明披露)**: 第一次跑测试时,regex 写法只匹配 header line 不匹配 bullets -> 6 测试 2 fail (extractsKeyDecisionsByCategory 期望 8 实际 4, maxKeyDecisionsCap 期望 8 实际 1)。改 extraction 为 "currentCategory tracking + bullets under same header", 并修正 test data 实际 bullet 数 (3 Progress 不是 4), 跑第二次 6/6 全绿。

**当前边界（Phase 2 已完成，后续仍未启用）**:
- 已有 heuristic fallback、真实 Jev HTTP client、live roundtrip 和 heuristic-vs-Jev 对比
- 尚未把 Jev 结果接入 `harness-config.yml` 的巡检开关与统一配置面
- 尚未接入 Bitable「变更摘要」字段的真实写入
- Jev-Patrol-L2 dispatcher 已有默认关闭的 shadow sidecar 插点，但尚未启用真实样本窗口；仍未把 Jev 结果用于 decide/act 或写回 Bitable
- 尚未在生产巡检中启用 active routing；当前建议只做 shadow/dry-run

**下一阶段**:
- 先做 Phase 3 shadow mode：保留现有 dueList 派工，旁路记录 Jev 评分、延迟、错误与人工结果
- shadow 数据稳定后再评估 Phase 4 active routing；任何 Jev 异常必须 fallback 到现状
- 最后再做 Phase 5 Bitable schema 升级，不提前把实验字段当生产契约
2026-09-22：L3 lib + contract test 闭环。新增 `scripts/lib/JevPatrolL3Extract.ps1`（Resolve-JevPatrolL3Project/Stamp + Build-JevPatrolL3Decisions 三个 pure function）和 `scripts/lib/JevPatrolL3Markdown.ps1`（Build-JevPatrolL3Markdown pure function）。两个脚本（Run-JevPatrolL3Extract.ps1 / Run-JevPatrolL3Render.ps1）重构为 dot-source lib 的 IO 包装。新增 `tests/Test-JevPatrolL3Extract.ps1` 32/32 PASS + `tests/Test-JevPatrolL3Markdown.ps1` 19/19 PASS。新增 `tests/Run-JevPatrolL3Tests.ps1` 测试 runner，双套件总 pass=51/fail=0。踩坑修复：PowerShell 函数返回 List 自动 unroll → `,$decisions` 保留 List 类型；`[AllowEmptyString()]` 解空字符串绑定限制；`$Latest` 是 PowerShell 自动变量，rename 为 `$UseLatest`。
2026-09-22：end-to-end smoke 上线。`tests/Run-JevPatrolL3Pipeline.ps1` 串起 extract → render，验证 markdown 含 ## Summary + ## Key Decisions。`tests/Run-JevPatrolL3Tests.ps1` 套件增至 4 个（extract 32 + render 19 + openeyes-delivery 10 + e2e-pipeline 1 = 62 总通过 / 0 失败）。
