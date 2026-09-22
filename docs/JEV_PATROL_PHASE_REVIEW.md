# Jev-Patrol Phase 1-6 总览（PR jev-patrol-phase-3-4）

> 评审入口：14 commits / 16941+ insertions / 0 schema touched / 0 key exposed / 0 real-world mutation。
> 所有默认 disabled：feishu / scaffold 两 concrete 的 `jev_shadow.enabled=false` 与 `decision.enabled=false`，维护者单行 config 即可激活。

## 1. 仓库分层

| 仓库 | 角色 | 默认 |
|---|---|---|
| `yangpeng366/agent-cloud-harness` | 主代码（脚本 + 文档 + 离线合同） | 已 commit 并推送 PR |
| `D:\gitAll\patrols\feishu-projects-patrol` | 飞书 concrete（含 JevShadow / JevDecision / Invoke-ProjectJevDecision / BitAbleAdapter / Start-FeishuProjectsWorkLoop） | runtime 目录 |
| `D:\gitAll\patrol-scaffold` | 多 Provider 复用（含 JevShadow / JevDecision / Invoke-ItemJevDecision / Run-JevShadowSampleWindow / Run-BuildJevShadow{Digest,Eval}） | runtime 目录 |

## 2. PR 包含的提交（自下而上）

```
feat: Phase 4 controller entry points + cross-process JSON contracts
feat: sample window orchestrator (scaffold) + Phase 4 controller libs + runbook §8
feat: controlled sample window (ground truth injection via human_label)
docs(phase6): cascade + heatmap design drafts + plan/state sync
state: add JevShadowEval (uncertainty-band metrics) for Phase 4 monitoring
state: 12-sample shadow window (hit_rate=0.83 with TP bias warning)
state: real shadow round (4 sidecars, hit_rate=0.25) + open-window授权
state: Jev/OpenEyes 全面收口 + 真 key 回归
state: Phase 3 shadow 配套 digest + auto-deploy 桥接
state: Phase 3 shadow 已落地到真实飞书巡检入口
state: Phase 3 sidecar 配套 digest + auto-deploy 桥接
feat: scaffold main loop decision hook + cross-process log-file fix
state: Phase 4 hook wired into feishu main loop
```

## 3. Phase 1-6 状态表

| Phase | 状态 | 实现 | 离线合同 | 默认 | 关键文件 |
|---|---|---|---|---|---|
| 1 信息层 | ✅ 已做 | 用户批注 + 简要描述 | — | n/a | Bitable recvvP9I0XrgTJ |
| 1.5 heuristic 后处理 | ✅ 已做 | `JevPatrolL3Extract.ps1` + `Run-JevPatrolL3Extract.ps1 -Mode heuristic` | 19 PASS | n/a | `scripts/lib/JevPatrolL3Extract.ps1` |
| 2 Jev 后处理 | ✅ 已做 | `JevApi.ps1` + `Run-JevPatrolL3Extract.ps1 -Mode jev` | 35 PASS（live API / roundtrip / heuristic） | closed | `scripts/lib/JevApi.ps1` |
| 3 sidecar | ✅ 已做 | feishu 14/14 + scaffold 9/9 + bridge 16/16 + sample window 7/7 + controlled 6/6 | 67/0 | closed | `feishu/scripts/JevShadow.ps1` + `scaffold/scripts/JevShadow.ps1` + `scripts/lib/JevShadowControlledSample.ps1` |
| 3 digest | ✅ 已做 | feishu 14/14 + scaffold 10/10 | 24/0 | closed | `JevShadowDigest.ps1` (x2) + `Run-BuildJevShadowDigest.ps1` (x2) |
| 4 controller | ✅ 设计 + 库 | Resolve-Jev{Project,Item}Decision 12/12 + 9/9 | 12/0 + 9/0 | disabled | `JevDecision.ps1` (x2) + `Invoke-{Project,Item}JevDecision.ps1` |
| 4 eval | ✅ 已做 | TP/FP/FN/TN/hit_rate + uncertainty band 分维度 | 12/12 (offline) + 28 real shadow | n/a | `JevShadowEval.ps1` + `Run-BuildJevShadowEval.ps1` |
| 4 active hook | ✅ 已落地但默认关 | feishu Invoke-ProjectRound + scaffold patrol-loop 双钩子；HUMAN_REVIEW → continue 跳过 codex | 6/6 (feishu) + 6/6 (scaffold) | disabled | `Start-FeishuProjectsWorkLoop.ps1` + `patrol-loop.ps1` |
| 5 L5 schema | ✅ 草案 | 5 个 typeable 字段 | — | not started | `docs/JEV_PATROL_PHASE5_SCHEMA_DESIGN.md` |
| 6 cascade | ✅ 草案 | 跨窗口 keep_probability 联动网 | — | not started | `docs/JEV_PATROL_PHASE6_CASCADE_DESIGN.md` |
| 6 heatmap | ✅ 草案 | per-project B/C/F 健康度评分热力图 | — | not started | `docs/JEV_PATROL_PHASE6_HEATMAP_DESIGN.md` |

## 4. 关键 bug 与教训

1. **跨进程 PSCustomObject 序列化丢失**：直接 `$result = & pwsh -File endpoint.ps1` 时，返回值会被父进程 Format-Table 字符串化；改成写 `decisionLogPath` JSON 文件 + 父进程读文件，避免捕获格式化文本。
2. **PowerShell here-string 转义 + `'action'` 转义混搭**：在补丁中多次遇到 `$(.item_id)` 这种语法在 Write-Host 字符串中变成子表达式路径；`$($obj.prop)` 是正确写法。
3. **Jev 在 0.30-0.70 中段几乎不区分正负**：controlled 28 samples hit_rate=0.5 是去偏置对照，证实 Phase 4 设计保守默认（HUMAN_REVIEW）必要。
4. **TP 偏置**：纯 dry-run 全 positive 偏向让 hit_rate=0.83 不可信；controlled 样本注入 ground_truth 后 hit_rate=0.5 是更真实的信号。

## 5. 默认关闭 / 启用流程

```
feishu-projects-patrol-config.json.jev_shadow.enabled = true    # 状态：已就位
feishu-projects-patrol-config.json.decision.enabled  = false  # 状态：默认关闭
scaffold/jev-shadow-hooks/patrol-shadow-config.json.enabled = true    # 状态：已就位
scaffold/config/jev-decision.json.enabled = false            # 状态：默认关闭
```

维护者启用流程（按 `docs/JEV_PATROL_PHASE3_RUNBOOK.md` §6 + `docs/JEV_PATROL_PHASE4_DESIGN.md` §4）：

1. ≥30 条真实 shadow 样本（运行手册 §3-§5）。
2. `Run-BuildJevShadowEval.ps1` 给出 hit_rate ≥ 0.75。
3. 维护者人工评审 uncertainty band 内 ≥60% 样本。
4. 切换 `decision.enabled=true` 闸门。
5. 第二期 sample window 验证后，再起草 Phase 5 schema 升级。

## 6. 不触碰项

- Bitable schema 不动（除通过 `--as user` 同步 `recvvP9I0XrgTJ` 字段之外）。
- fake / real 派工分流不动。
- 凭据仅走临时进程环境变量；仓库与文档零暴露。
- patrol 仓不 commit（runtime-only）；维护者需要独立仓时可与我直接 `git init` + `git remote add`。