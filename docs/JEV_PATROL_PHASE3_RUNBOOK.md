# Jev × 飞书巡检 Phase 3 运行手册

> 目的：在不修改现有派工的前提下，收集一次真实的 Jev 评分 → 人工结果对照，用于 Phase 4 active routing 评估。
> 三层均默认关闭。本手册是唯一开启流程。

## 1. 准备

- 确认 `TYPESAFE_API_KEY` 仅放在临时进程环境变量里。不要写进任何 JSON、log、Bitable 或仓库。
- key 配额检查：当前 Jev 单次调用约 300~700ms，单个巡检 round ≤ 30 次调用。1.2 亿 token 公开额度可支撑数十轮 shadow。
- 维护者拍板后，由维护者授权“开始 shadow window”，Codex 才会按本手册执行。

## 2. 飞书 sidecar 开启（`Start-FeishuProjectsWorkLoop.ps1`）

```
$env:TYPESAFE_API_KEY = '<key>'
# 配置：feishu-projects-patrol-config.json -> jev_shadow.enabled = true
# 轮次：单 round（nssm / schtasks 一律不动，先人工 -Once）
pwsh -NoProfile -File D:\gitAll\patrols\feishu-projects-patrol\Start-FeishuProjectsWorkLoop.ps1 -Once
```

产物：

- `state\jev-shadow\<project>-<stamp>.json`
- `state\project-results\<project>-<stamp>.json`（含 `jev_shadow_artifact` 字段）

## 3. patrol-scaffold sidecar 开启

```
$env:TYPESAFE_API_KEY = '<key>'
# 配置：jev-shadow-hooks\patrol-shadow-config.json -> enabled = true
# 单元样例：
Copy-Item sample-tasks.json state\bitable-tasks-cache.json
pwsh -NoProfile -File D:\gitAll\patrol-scaffold\scripts\patrol-loop.ps1 `
  -TasksFile state\bitable-tasks-cache.json `
  -JevShadowConfigPath jev-shadow-hooks\patrol-shadow-config.json `
  -JevShadowOutputDir downloads\jev-shadow `
  -Once -DryRun
# 真实 Bitable 入口（auto-deploy 侧）：
pwsh -NoProfile -File D:\gitAll\auto-deploy\Start-CodexAutoPatrolLoop.ps1 `
  -BitableConfigPath D:\gitAll\patrols\feishu-projects-patrol\feishu-projects-patrol-config.json `
  -JevShadowConfigPath D:\gitAll\patrol-scaffold\jev-shadow-hooks\patrol-shadow-config.json `
  -Once -DryRun
```

## 4. 收集 digest

```
pwsh -NoProfile -File D:\gitAll\patrols\feishu-projects-patrol\scripts\Run-BuildJevShadowDigest.ps1
pwsh -NoProfile -File D:\gitAll\patrol-scaffold\scripts\Run-BuildJevShadowDigest.ps1
```

产物两份 `<repo>\reports\jev-shadow-summary-<stamp>.{json,md}` 与
`<repo>\downloads\reports\jev-shadow-summary-<stamp>.{json,md}`。

## 5. 人工评审与决策

每个 sidecar 的 `observed.status / observed_stage / observed.worker_mode` 与
人工维护者对项目方向的判断做对照：

- 真阳性：`action=KEEP_VERBATIM` 且 high priority 项目最终人工评 in-progress / blocked；
- 真阴性：`action=TRUNCATE_HEAD` 且项目实际推进不大；
- 假阳性：Jev 高分但实际无事；
- 假阴性：Jev 低分但其实有进展（要进 uncertainty band 0.30–0.70 必审）。

样本量 30~50 条即可视为中等置信度的对照证据。

## 6. 收尾 / 关窗

```
Remove-Item Env:TYPESAFE_API_KEY
# 配置：enabled = false
```

## 7. 失败兜底（任何一步都成立）

- 任何 Jev 调用抛错 → 写 `jev.error`，主循环继续；不会绕过 fake/real 分流或跳过 worker。
- 任何 Bitable 写错 → 抛错以 `auth required / token expired`；`ReauthFile` + 飞书通知一次。
- 任何 `Run-BuildJevShadowDigest.ps1` 跑空 → 仍然产 json + md，含 `total=0`。