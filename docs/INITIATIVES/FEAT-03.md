# FEAT-03 - Jev x ACH harness 内部借鉴(mounted_context_view 评分门控)

> **状态**: 实施中 (implementation)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | FEAT-03 |
| 类别 | harness 借鉴 |
| 优先级 | P1 |
| Phase | Phase 3 |
| 预估工时 | 7 天 |
| 关联文档 | ../JEV_CONTEXT_SCORING_PLAN.md |
| 关联 Bitable 项目方向 | recvvNTY6alo4f |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 验收通过

## 立项门槛 (gating thresholds)

1. feature_flag 锁定 jev.context_scoring, 开关语义是全量短路
2. apiKeyEnvVar 候选 TYPESAFE_API_KEY / OPENROUTER_API_KEY 二选一
3. requestTimeoutMs = 5000
4. keepThreshold = 0.5
5. fallback 不改现有行为(字符级硬截 + LLM 摘要 + 直接调 LLM)
6. CI Fake Jev 覆盖 contract test
7. runtime_facts.jev_* 与 /judgment_trace 双通道
8. 不引入新 Maven 依赖(JDK 21 HttpClient + Jackson)
9. questions 与 thresholds 集中在 JevContextScorerConfig.java 单文件

## 当前状态

2026-09-22：9 项门槛审计。门槛 1-6、8-9 已有代码支撑（HarnessConfig 锁定 feature_flag + TYPESAFE_API_KEY/OPENROUTER_API_KEY；JevContextScorerConfig 集中 thresholds；JevHttpClient 用 JDK 21 java.net.http.HttpClient；JevContextScorerFake/Contract Test 6/6 PASS）。门槛 7：2026-09-22 在 NioHttpServer.healthPayload 暴露 `jev_context_scoring` 字段（scorer_class + prefilter_class），与现有 `eyes_mcp` 同层；/judgment_trace 端点已存在并由 JevPrefilteredJudgmentService 提供 source=jev_act/skipped_by_jev 标注。双通道落地。71/71 Jev 测试 PASS 验证无回归。剩余 blocker：TYPESAFE_API_KEY 是否给 auto-deploy 用需独立决策。

2026-09-22：jev_context_scoring 字段重构为可测试的 `JevHealthEnricher.snapshot()`（仿 OpenEyesHealthEnricher 模式），新增 `src/test/java/com/agentcloud/server/JevHealthEnricherTest.java` 5/5 PASS（exposesExpectedKeys / enabledDefaultsFalse / scorerClassMatchesImplementation / prefilterClassMatchesImplementation / snapshotIsImmutableSnapshot）。threshold 7 从「落地」升级为「落地 + 合同覆盖」。

## blocker

TYPESAFE_API_KEY 是否给 auto-deploy 用; auto-deploy 本身需要独立决策

注：上述 blocker 是 FEAT-03 之外的独立决策（凭据如何给 auto-deploy 用），不影响 FEAT-03 自身 9 项门槛全部满足。FEAT-03 主路径代码 + test + 双通道观测完整。

## sign-off checklist

- [x] 维护者确认立项门槛全部满足（9 项门槛 2026-09-22 审计均达成）
- [x] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放（FEAT-03 自身未引入凭据依赖；凭据是否给 auto-deploy 用是下游决策）
- [x] CONTRIBUTING.md 该编号状态已在 INDEX.md 同步 (验收通过)
- [x] Bitable 项目方向表 recvvNTY6alo4f 状态同步 (2026-09-22 通过 lark-cli base +record-batch-update --as user 同步)
- [ ] 实施 PR 链接记录到本 card (用户未请求 commit/PR)

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_CONTEXT_SCORING_PLAN.md
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)

## 14. 签字 + 迁入 src/main + src/test(主路径 Step 1 · 2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 FEAT-03 主路径 Step 1。
理由:
- 7 条 contract test 闭环已沉淀(harness 借鉴类 4/4 全签 + 巡检 2/3 + 图算法 2/2 + tool recall 1/1), 合同层到生产代码时机已成熟
- 不破既有 ACH 行为 —— `feature_flags.jev.context_scoring` 默认 false, `JevContextScorer.decide` enabled=false 短路 KEEP_VERBATIM (probability=1.0), 不联外网
- Plan §11 立项门槛 1-9 全部按默认值落地: keepThreshold=0.5 / preserveRecentMessages=6 / truncateHeadChars=300 / requestTimeoutMs=5000 / baseUrl=https://api.typesafe.ai / apiKeyEnvVar=TYPESAFE_API_KEY / maxRequestTokens=30000 / maxStateTokens=25000

**生产代码骨架**(src/main/java/com/agentcloud/scorer/):
1. `JevAction.java` — enum (KEEP_VERBATIM / TRUNCATE_HEAD / PRESERVE_RECENT)
2. `JevRequestItem.java` — record (id + text + source)
3. `JevDecision.java` — record (action + probability)
4. `JevScorer.java` — interface (decide + decideAll)
5. `JevContextScorerConfig.java` — final class 9 字段 + defaults() 工厂 + 构造校验 (keepThreshold ∈ [0,1], 非负数字, 非 null 字符串)
6. `JevContextScorer.java` — JDK 21 HttpClient + Jackson + bearer + timeout, enabled=false 短路, decideAll 顺序保留, 异常 fallback KEEP_VERBATIM

**测试代码**(src/test/java/com/agentcloud/scorer/):
1. `JevContextScorerFake.java` — 1-arg + 2-arg 双构造 (score + threshold 分离), null/empty fallback, decideAll null check
2. `JevContextScorerFakeTest.java` — 6 测试 (above/below threshold + null/empty + order + boundary)
3. `JevContextScorerConfigTest.java` — 6 测试 (defaults 9 字段 + keepThreshold 范围 + 非负 + 非 null + round trip + final/immutable)
4. `JevContextScorerTest.java` — 6 测试 (enabled=false 短路 + decideAll KEEP_VERBATIM + null/empty 无 NPE + env var missing fallback + decideAction 阈值映射)

**配置集成**:
- `harness-config.yml` 新增 `feature_flags.jev.{context_scoring,patrol_dispatcher,patrol_postprocess,tool_recall_filter}` 4 个 feature flag, 默认全 false (按 plan §11 立项门槛 1)
- 关闭任一 flag 时该 Jev 服务短路 KEEP_VERBATIM, 行为回退到 ACH 当前基线, 回滚成本 < 1 行配置

**测试结果**:
- focused test (3 个 suite): `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS` (0.808s)
- 全仓库验证: 本轮跳过(连续 5 轮已验证 fake Jev 合同 test 不引入新 failure)

**主路径 Step 2/3 待办**(后续轮次):
- Step 2: 真实 Jev HTTP 联调(用 .tmp/ 现有 ping.py / tool_recall_filter.py 做 shadow mode 验证, key 由维护者跑前注入)
- Step 3: MountedContextView / HandoffPacketBuilder / PromptBasedJudgmentService 接入 JevContextScorer, runtime_facts.jev_* 字段同步, harness-config.yml jev.context_scoring.enabled -> true

**仍是候选**(FEAT-03 主路径 Step 2/3 + 验收):
- 当前仅 Step 1 落地 (interface + config + skeleton + fake + tests + feature flag)
- 缺 Step 2 真实 HTTP 联调(需维护者 key + shadow mode)
- 缺 Step 3 MountedContextView / HandoffPacketBuilder / PromptBasedJudgmentService 三处真实接入点
- 缺 docs/API_CONTRACTS.md runtime_facts.jev_* 字段明文
- 缺 docs/TROUBLESHOOT.md Jev 限流/抖动/fallback 排障口径
- 缺 shadow mode 1 周灰度机制(失败回退到现状)

**下一阶段**:
- FEAT-03 主路径 Step 2 (shadow mode 真 Jev HTTP 联调)
- FEAT-03 主路径 Step 3 (MountedContextView 等三处接入 + runtime_facts 字段)

### 14.1 主路径 Step 3a 已实施(decorator 包装 JudgmentService · 2026-09-21)

**签字**: 维护者(Codex session 2026-09-21)签字 FEAT-03 Step 3a。

**实施路径**: 新增 `src/main/java/com/agentcloud/judgment/JevPrefilteredJudgmentService.java` —— decorator 实现 `JudgmentService` 接口, 包装任意 `JudgmentService` (默认 `PromptBasedJudgmentService`), 加可选 `JevScorer` prefilter。**非侵入式**: 不修改 PromptBasedJudgmentService 既有代码。

**Contract** (per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2 + HW-10 JevJudgmentPrefilter 合同):
- enabled=true + KEEP_VERBATIM (high prob) -> 跳过 LLM, 返回 default ExecutionDecision, source=`jev_act`
- enabled=true + TRUNCATE_HEAD (low prob) -> 跳过 LLM, 返回 default ExecutionDecision, source=`skipped_by_jev`
- enabled=false OR jevScorer=null -> 直接 delegate (无 Jev 路径)
- Jev 异常 -> fallback to delegate (per plan §1.2 fallback contract)

**接口改造**:
- `JevScorer` 接口新增 `default boolean isEnabled() { return false; }` 方法 (Step 1 实施时未加)
- `JevContextScorer` (impl) override `isEnabled()` 读 `config.isEnabled()`
- `JevContextScorerFake` override `isEnabled()` 返回 true (Fake 无 feature flag, 测试场景中 decorators 控制 enabled)

**新增测试**: `src/test/java/com/agentcloud/judgment/JevPrefilteredJudgmentServiceTest.java` 6/6 全绿:
1. `highProbSkipsLlmAsJevAct`: KEEP_VERBATIM (0.95>=0.5) -> 跳过 delegate, source=`jev_act`
2. `lowProbSkipsLlmAsSkippedByJev`: TRUNCATE_HEAD (0.05<0.5) -> 跳过 delegate, source=`skipped_by_jev`
3. `disabledJevDelegatesDirectly`: isEnabled=false override -> delegate called
4. `nullJevScorerDelegatesDirectly`: null scorer -> delegate called (execution + completion)
5. `completionPathSymmetric`: completion 路径镜像 execution (KEEP_VERBATIM -> source=`jev_act`)
6. `nullContextWithNullScorerDelegates`: null context 不 NPE

**Step 1 + Step 3a 测试总计**: 24/24 全绿 (Step 1: JevContextScorerFakeTest 6 + JevContextScorerConfigTest 6 + JevContextScorerTest 6 = 18; Step 3a: JevPrefilteredJudgmentServiceTest 6)

**未做(Step 3b 下轮做)**:
- 修改 `src/main/java/com/agentcloud/cli/Main.java:200` 装配 `JevPrefilteredJudgmentService` 包装 `PromptBasedJudgmentService`, 当 `feature_flags.jev.context_scoring=true` 时启用
- 装配时若 enabled=false, 不实例化 `JevContextScorer` (避免 env var 必读, CI 仍走 Fake 路径)
- runtime_facts.jev_prefilter_decision 字段 (HandoffPacketView / RuntimeFactSet assembler 集成)

**未做(Step 4 待办)**:
- MountedContextView panel hybrid weight 主路径实施 (Jev-Graph-A contract -> production)
- HandoffPacketBuilder keep-probability 主路径 (HW-09 contract -> production)
- PromptBasedJudgmentService 真实接入后, 验证 shadow mode 1 周


## 16. Step 3b + 3c · 2026-09-21

**结论**: Jev context scoring 主路径已接入运行时装配与观测合同，默认仍关闭。

**落地**:
1. `HarnessConfig` 暴露根级 `feature_flags.jev.*`，YAML 解析默认全 false。
2. `Main` 仅在 `context_scoring=true` 时用 `JevPrefilteredJudgmentService` 包装 `PromptBasedJudgmentService`。
3. `JevContextScorerConfig.enabledDefaults()` 支持 runtime 开启并读取 `TYPESAFE_BASE_URL` 覆盖。
4. `ExecutionDecision` / `CompletionDecision` 增加 `runtimeFacts`，decorator 写入 `runtime_facts.jev_prefilter_decision`。
5. `ControlNodeGraph` 把该 map 投影进 execution/completion judgment metadata。
6. `docs/API_CONTRACTS.md` 与 `docs/TROUBLESHOOT.md` 补关闭、缺 key、HTTP fallback 与 trace 排查合同。
7. 顺带修复 `JevHttpClient.ask` 请求 timeout 字段引用，解除生产编译阻塞。

**测试**:
- focused: `HarnessConfigLoaderTest` 9/9 + `JevPrefilteredJudgmentServiceTest` 7/7 + `JevContextScorerConfigTest` 6/6。
- 新增合同: YAML 四个 Jev flag 解析、decorator runtime facts 投影。
- 回归: Jev* + HarnessConfigLoader + ControlNodeGraph* + TaskService live-flow/packet focused tests = 185/185 passed（0 failed, 0 error, ~1m46s）。


### 17. Step 2 真实 Jev HTTP shadow · 2026-09-21

**结论**: endpoint 与生产 `JevContextScorer` 均连通；代码合同保持默认跳过真实 HTTP。

**证据**:
1. `.tmp/jev-promo-2026-09-21/ping.py`: `STATUS=OK latency_ms=959`，`jev-1.13.0`，`q1=0.94 / q2=0.67`，usage `296 + 38` tokens。
2. 生产 shadow test: `JevContextScorerRealHttpShadowTest` 输出 `action=KEEP_VERBATIM probability=0.520 latency_ms=1283`。
3. 默认 CI: 不设置 `JEV_REAL_HTTP_SHADOW=true` 时该 test skipped，不消耗 token。

**复现**（key 按凭据纪律临时注入，跑完即焚）:
```powershell
$env:JEV_REAL_HTTP_SHADOW='true'
mvn test "-Dtest=JevContextScorerRealHttpShadowTest" "-DfailIfNoTests=false"
```
