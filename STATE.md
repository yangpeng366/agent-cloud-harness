
> 本文件是跨主题短摘要。详细状态、卡点、未结清项按主题回到对应 docs/<topic>/PROGRESS.md。

## 服务运行态

- 进程 PID 40468 在 9091 上 LISTENING（自 2026/7/31 13:51:15 起）。
- 运行的 JAR：D:\gitAll\agent-cloud-harness\.tmp\runtime-jars\agent-cloud-harness-0.1.0-SNAPSHOT-shaded-port9091-20260731-135115.jar（构建时间 13:50:52）。
- 数据库：C:\Users\47037\.agentcloud\agent_cloud.db，最后写入 15:41:58（timeout 后 continue 决策）。
- CCX gateway 此前在 13:52:30 出现 BindException: Address already in use，是端口已被同 JAR 占用，预期行为。

## 主题状态

| 主题 | 状态 | 入口 | 摘要 |
| --- | --- | --- | --- |
| valuation | 活跃推进中 | docs/evaluation/PROGRESS.md | §4.1 #5 control flow 修复已验证；§4.1 #6 codex-free 模型质量问题暴露；§4.1 #7 docs 缺失已修复 |
| continuity | 维持 | docs/continuity/README.md | pause/resume 链路在探针任务里跑通（resume 接口返回 200；deferred 时 enterLock busy 可恢复） |
| provider | 观察中 | docs/provider/README.md | codex provider 接入正常；ccx-free model 输出质量问题独立跟进 |
| dialogue | 维持 | docs/dialogue/README.md | 本轮未触及 |
| 
elease | 维持 | docs/release/README.md | 本轮未触及 |
| meta | 触发修复 | docs/meta/README.md | 本次按 AGENTS.md 补回 docs 索引面，待后续结构审计 |

## 探针任务总览

- 主探针：	ask_6886b7bacc1c4ace (session session_a2689a752459449a)
  - title：P1 post-fix real handoff trace probe
  - experiment：p1-postfix-real-handoff-20260731-1355
  - 当前状态：waiting_human，control_node=human_gate，ssigned_worker=codex，summary="worker codex failed: timeout"
  - 关键时间窗：13:55 创建 → 13:59 codex planner 完成 → 13:59 handoff 到 codex-free → 14:00 codex-free 完成（乱码）→ 14:01 escalation 到 codex → 14:03/14:04/14:06 codex 三轮 → 15:06 pause/resume → 15:11/15:16 codex-free 重试 → 15:21 codex 再执行 → 15:31 再次 resume → 15:46 codex timeout → 15:46 human_gate

## 本轮已做

1. 通过 API + 数据库回溯，定位 §4.1 #5 修复确实生效（13:59→14:00 真实 cf dispatch 已发生）。
2. 定位 §4.1 #6：ccx-free 输出质量差是独立 blocker，与 control flow 修复无关。
3. 定位 §4.1 #7：judgment 报 docs/README.md 缺失，是仓库 docs 面被误清的副作用。
4. 补回 docs/README.md（首标题 # Docs README）、docs/evaluation/README.md、docs/evaluation/PROGRESS.md、STATE.md、DECISIONS.md，让 task fixture 重新就位。
5. 证明 control flow 修复后，planner→executor 真的会发生（codex-free 在 14:00:50 被真实 dispatch），§4.1 #5 闭环。
6. 证明当前未能跑通 cf→codex→cf 完整收敛的剩余原因不是 control flow，而是 provider 侧 codex hang + timeout + retry budget 耗尽。

## 本轮未做（留给下一步）

1. 恢复源码：src/main/java/com/agentcloud/engine/ControlNodeGraph.java 等 .java 文件目前在仓库中缺失，仅 JAR 内有 bytecode。补源码后才能继续在 control flow 上做 handoff-loop circuit breaker 修复。
2. 评估 ccx-free 模型是否替换 / 是否需要 reading 类型 fallback 规则。
3. 设计 deterministic experiment control（task 创建时锁定 ssigned_worker 与 fixture 路径），避免再次因 fixture 丢失把探针任务拖入循环。
4. 从 human_gate 重新触发探针（或新建任务），验证 docs 补齐后 control flow 能完整收敛。
## 2026-08-02 巡检写回

- 本轮时间：2026-08-02 10:02
- 观察：D:\gitAll\agent-cloud-harness 已从 2026-07-31 的目录异常中恢复，当前工作树可继续做 GitHub-ready 文档修补。
- 未结清项：工作区仍有未提交改动（DECISIONS.md、STATE.md、docs/docs/、CodexAppServerWorkerExecutor.java、新测试文件），主会话确认前不代做 commit；探针仍停留在 waiting_human。
- 建议：优先整理当前未提交文档/测试的公开边界，再回填 release gate 或首发 README。

## 本轮已做

1. 建立 25200s long stability smoke 的代码回归保护：`WorkerExecutionTimeoutConfigTest.longStabilitySmoke25200sOverrideIsAcceptedAcrossTiers`。
2. 建立可重复 runner：`scripts/Run-LongStabilitySmoke.ps1`，默认对 `long-001` 单 case 单 mode 投 25200s smoke。
3. 沉淀执行证据与文档入口：`docs/LONG_STABILITY_SMOKE_25200S_EXECUTION_RECORD_2026-08-02.md`、`docs/evaluation/runs/README.md`、`docs/evaluation/PROGRESS.md`。

## 本轮未做（留给下一步）

1. 完成一轮真实 25200s 运行并回收 terminal/evaluated report。
2. 恢复源码：src/main/java/com/agentcloud/engine/ControlNodeGraph.java 等 .java 文件目前在仓库中缺失，仅 JAR 内有 bytecode。补源码后才能继续在 control flow 上做 handoff-loop circuit breaker 修复。
3. 评估 ccx-free 模型是否替换 / 是否需要 reading 类型 fallback 规则。
4. 设计 deterministic experiment control（task 创建时锁定 assigned_worker 与 fixture 路径），避免再次因 fixture 丢失把探针任务拖入循环。
5. 从 human_gate 重新触发探针（或新建任务），验证 docs 补齐后 control flow 能完整收敛。
## 2026-08-02 巡检写回

- 本轮时间：2026-08-02 10:02
- 观察：D:\gitAll\agent-cloud-harness 已从 2026-07-31 的目录异常中恢复，当前工作树可继续做 GitHub-ready 文档修补。
- 未结清项：工作区仍有未提交改动（DECISIONS.md、STATE.md、docs/docs/、CodexAppServerWorkerExecutor.java、新测试文件），主会话确认前不代做 commit；探针仍停留在 waiting_human。
- 建议：优先整理当前未提交文档/测试的公开边界，再回填 release gate 或首发 README。
## 2026-08-02 预算超时恢复回归

- 本轮时间：2026-08-02
- 根因已收口：仓库 HEAD 的 prepareFreshSessionRecovery 已会在 fresh session retry 前把 subgoal_status 里 blocked 子目标重置为 pending，并强制回写 status=active / control_node=scheduler / waiting_reason=null；当前 live task 	ask_21f7c333c57e4514 的 SQLite 副本也显示其 subgoal 已是 pending。
- 新增回归覆盖：WorkerBudgetExhaustedRecoveryTest.prepareFreshSessionRecoveryClearsBlockedSubgoalsAndResetsWaitingState，直接从 waiting_human + blocked subgoal 出发验证 recovery 后状态可被清回 ctive。
- 冲突说明：工作区无 merge conflict；未提交项仅为 docs/test/doc 写回，不阻塞源码修复。
- 未结清项：运行中 JAR D:\gitAll\agent-cloud-harness\.tmp\runtime-jars\agent-cloud-harness-0.1.0-SNAPSHOT-shaded-port9091-20260731-135115.jar 仍为旧构建，所以 live API 仍显示 manual_recover_scheduled；需要重建 JAR 并热替换后，自动 retry 才会真的跑起来。

## 2026-08-02 巡检写回

## 2026-09-22 Jev Bitable fake round

- Read 43 real Bitable rows, ran fake/dry-round with Jev shadow: 43 sidecars, 0 errors, TP=39 / FP=0 / FN=4 / TN=0, hit_rate=0.9070.
- Result is positive-label-biased (no TN); active routing remains disabled. Evidence: `docs/evaluation/runs/2026-09-22-jev-bitable-fake-round-and-phase6-prototypes.md`.
- Phase 6 cascade and heatmap prototypes are read-only and validated: cascade divergence=0.0041 / signal=0; heatmap B=21 / C=21 / F=1.
## 2026-09-28T12:07:57+08:00 RESUME 巡检写回（saved-output recheck #46 — M0 稳态 + docs audit 回归）
- 复核 m0 worktree saved-output review 与 M0 产物：review=1911B/SHA 26B5DB70...；hello-m0.txt=16B/无 BOM/无尾换行；port9090 shaded JAR=20453006B/SHA 7DA1D644...。M0 不重跑，M1 继续待放行。
- 实测当前 master=2cf90c2，仅用户自管 `_tmp_task.json` dirty；patrol/release worktree clean。
- 新发现 `Run-DocsIndexAudit.ps1` passed=false，violation_count=33，含 10 个 root docs orphan、dated 命名违约、release topic 结构与 docs 总入口 contract 不同步；违反清单落 `.tmp/audit-violations-20260928.txt`。
- 本轮仅写回 `.tmp/patrol-last-20260928-120757.md`、`.tmp/project-results/agent-cloud-harness-20260928-120757.json`、本备份与追加段；未改正式文档/源码，未 commit/push/发布/启动 harness。
- blocker：high=docs audit 33 项回归；low=M1 未放行与历史 drift/orphan 处置待审定。
- 下一步：从 `docs/meta/README.md` 进入修复审计回归，重跑至 passed=true、violation_count=0；M1 放行后再在 m0 worktree 单独执行并验 resume_packet / pause_checkpoint / key_artifacts / 终态字节。
## 2026-09-29 docs index audit 收口

- 本轮按 `Run-DocsIndexAudit.ps1` 修复 33 项违规，至 `violation_count=0` / `passed=true`。
- 操作要点：
  - 删除 legacy `docs/docs/`（重复 `docs/README.md` 与 `docs/evaluation/*`）。
  - `docs/BUDGET_TIMEOUT_RECOVERY_REGRESSION_2026-08-02.md` 改名为 `BUDGET_TIMEOUT_RECOVERY_EXECUTION_RECORD_2026-08-02.md`（对齐 dated doc 核心命名合同）。
  - `docs/release/GITHUB_READINESS_SNAPSHOT_2026-08-02.md` 移出 `release/` 根并改名为 `GITHUB_READINESS_SNAPSHOT_EXECUTION_RECORD_2026-08-02.md`，由 `release/README.md` “巡检补登”节承接。
  - `docs/README.md` 重构为带 命中信号 / 最小阅读顺序 / 稳定基线 / 当前主线文档 / 写回顺序 / 历史材料使用规则 顺序的结构，并在工作区现状表里新增 `docs/` 自身（仅 `README.md`）。
  - `docs/release/README.md` 去掉误导的 `+` 前缀、保留 subtopic-routing 表头（`当前问题 / 先看哪里 / 再下钻`），并新增 “巡检补登：一次性历史快照”节。
  - `docs/evaluation/README.md` 新增 “巡检补登：一次性历史证据”节，承接 9 份 JEV patrol / FEAT-05 / BUDGET / LONG_STABILITY dated 文档。
  - `docs/meta/README.md` 新增 “巡检补登：跨主题共享参考”节，承接 `GLOSSARY.md`。
- 复跑 `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/Run-DocsIndexAudit.ps1` 验证：`violation_count=0`, `passed=True`。
- 全部修改文件 BOM 校验通过（首三字节均非 `EF BB BF`）。