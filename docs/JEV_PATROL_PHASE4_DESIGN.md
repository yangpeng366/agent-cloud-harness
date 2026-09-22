# Jev × 飞书巡检 Phase 4 active routing 设计稿

> 目的：在 shadow sidecar 已经积累 ≥30 条真样本之后，把 Jev 从旁路评估升级为可影响 worker pool 派工的 active 控制器。
> 本文只读设计 + 默认关闭，不修改生产派工；要启用必须按 §4 维护者流程批准。

## 1. 目标与边界

- 把 Jev 输出（probability + action + error）转成三类 active 决策：
  - `KEEP`（分数 ≥ 0.5 或低阈值）：原状派工，按现有 fake/real 分流。
  - `TRUNCATE`（分数 < 0.5 或低阈值）：原状派工，但在 init prompt / 通知 / digest 里降权。
  - `HUMAN`（落在 uncertainty band 0.30–0.70）：强制暂停本项目，下一轮派发之前等维护者覆核。
- Jev 任一异常（`error != null` / 失败 / 超时 / 网络）→ 立即 `fallback to current behavior`，绝不改变 worker pool。
- active routing 不直接修改 fake / real 分流；只调整"是否派工"与"派工时携带的额外上下文"。
- 不改 Bitable schema；sidecar 仍是 `jev-shadow/*.json`。

## 2. 决策准入（Decision Gate）

| 概率 | 含义 | Phase 4 决策 |
|---|---|---|
| probability < 0.30 | 极低分（< threshold_low） | `KEEP_VERBATIM`，不派工 |
| 0.30 ≤ probability < 0.50 | 不确定带，per-rule 应送人审 | `HUMAN_REVIEW`，本轮跳过此项目，等维护者覆核 |
| 0.50 ≤ probability ≤ 0.70 | 高分带，per-rule 应送人审 | `HUMAN_REVIEW`，本轮跳过此项目，等维护者覆核 |
| probability > 0.70 | 极高分（> threshold_high） | `TRUNCATE_HEAD`，原状派工但在 init prompt 注入 `jev.window_size` 等降权 |
| probability == null | api_key_missing / 超时 / 错误 | `FALLBACK`，按当前状态派工，记录 `jev.error` |

可配置阈值由 `harness-config.yml`（feishu 侧使用 patrol-config）或 `jev-shadow-hooks/patrol-shadow-config.json` 的 `decision.thresholds` 段提供，默认 `{low:0.30, high:0.70}`。

## 3. 控制器入口

- `D:\gitAll\patrols\feishu-projects-patrol\scripts\Invoke-JevProjectDecision.ps1`
  - 接收 (Project, Stage, Priority, FakeMode, PromotionModel) → 决策字符串
  - dot-source `JevShadow.ps1` 的 `Invoke-JevShadowDecision`；不动现有 `Invoke-ProjectRound`。
- `D:\gitAll\patrol-scaffold\scripts\Invoke-JevItemDecision.ps1`
  - 接收 (Item, Promotion, Mode) → 决策字符串
  - dot-source `JevShadow.ps1` 的 `Invoke-PatrolItemJevShadow`；插入 `patrol-loop.ps1` 的 `Get-DueProject` 之后、`Invoke-Fake/Real` 之前。
- 两处入口均默认 disabled（`config.decision.enabled=false`），启用时受 `jev_shadow.enabled=true` 的同一闸门约束。

## 4. 维护者启用流程

1. 跑 JEV_PATROL_PHASE3_RUNBOOK.md 收集 ≥30 条真实 shadow 样本。
2. 运行 `Run-BuildJevShadowEval.ps1`（见 §5）输出每项目命中率、TP/FP/FN/TN。
3. 维护者人工评审：将每条 `observed.status / current_stage` 与 Jev action 作交叉表。
4. 当 `TP + TN` 显著大于 `FP + FN`（参考阈值 ≥ 75%）、且 uncertainty band 的样本 ≥ 60% 已被维护者覆核 → 才允许将 `config.decision.enabled` 由 false 切到 true。
5. 启用后立即进入第二个观察期：所有 active 决策仍然只发"标注"（worker pool 派工不变），仍只写 sidecar；只有当第二期样本稳定后才允许真正影响派工。

## 5. 评测

`D:\gitAll\patrols\feishu-projects-patrol\scripts\Run-BuildJevShadowEval.ps1`
- 输入：`state\jev-shadow\*.json` + `state\project-results\*.json`（人工覆核字段已写回时）`n- 已落库：`feishu\scripts\JevDecision.ps1`（Resolve-JevProjectDecision + Format-JevDecisionForPrompt，离线合同 12/12）+ `scaffold\scripts\JevDecision.ps1`（Resolve-JevItemDecision + Format-JevItemDecisionForPrompt，离线合同 9/9）。两个 controller 都默认 disabled；启用由 `config.decision.enabled=true` 与 `JevShadowEnabled` 双闸门共同控制。
- 输出：`reports\jev-shadow-eval-<stamp>.{json,md}`
- 指标：per-project 命中率、uncertainty band 命中率、global TP/FP/FN/TN、avg probability、avg latency。
- 模式：`manual`（默认，仅汇总）/ `auto`（要求每条 sidecar 含人工 ground truth 字段 `observed.human_label`）。

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Jev 错误路由到 non-KEEP 导致跳过 worker | sidecar `error != null` → 强制 `FALLBACK`，永不影响派工 |
| Jev 网络抖动阻塞派工 | 单次失败立刻 fallback，不重试不堆积；`jev.latency_ms > 1500` 一律 FALLBACK |
| uncertainty band 误判 | 强制人工覆核；首次启用阶段只用 `manual` 模式，不开 auto ground-truth |
| 阈值被人为调高 | `config.decision.thresholds` 走 git diff review，不允许 inline overwrite |
| key 泄露 | 永远走临时进程环境变量；评测脚本 `Sync-BitableRoundResult` 仍是双闸门（`config.sync_back && -SyncBack`），默认关闭 |

## 7. 当前证据

- shadow sidecar 已落地两条 concrete：feishu `14/14` + scaffold `9/9`。
- digest 已可生成：feishu `14/14` + scaffold `10/10`。
- BitAbleAdapter `16/16`，`Start-CodexAutoPatrolLoop.ps1` 路径已恢复（`auto-deploy` 侧 wrapper 同步）。
- 真 API 多次实测：Live API `10/10`、Roundtrip `13/13`、Heuristic vs Jev `12/12`；Java 真 HTTP shadow `1/1`；最新运行 5/5 decision 全部带 probability/action；故障注入 `error != null` → `probability=null` 验证通过。
- 不足：尚未有真实样本窗口 30 条以上 → §4 不可被勾选；本文只是设计稿，不是启用授权。

## 8. 下一步

- 把本文随 plan 一起在下一轮 Bitable 同步里贴到 `recvvP9I0XrgTJ` 的“相关文档”。
- 若维护者拒绝开窗：把 §4 改为"重新收集样本窗口"并补一份更鲁棒的评测器。
- 若维护者开窗：在样本稳定后起草 Phase 5 L5 schema 升级（仅在 active 决策被允许生效之后）。