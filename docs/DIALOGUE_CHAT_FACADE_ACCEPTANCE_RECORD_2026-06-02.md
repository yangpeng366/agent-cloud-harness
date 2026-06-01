# Dialogue Chat Facade Acceptance Record — 2026-06-02

> 本记录只承接 2026-06-02 的自动化真实浏览器证据。它证明 `chat` 与 `responses` 两个 surface 的 richer browser lifecycle seam 已通过真实后端 `pause` / `resume` 路径，不等于严格人工 A-H 逐条手点已完成。

## 1. 运行元数据

- Date: 2026-06-02
- Operator: Codex
- Base URL: `http://localhost:18457`
- Surface: `both`
- Lifecycle mode: `real`
- Report: `.tmp/dialogue-browser-real-both-18457/probe-output.json`
- Screenshots: `.tmp/dialogue-browser-screens-18457-real-both`

命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 `
  -BaseUrl http://localhost:18457 `
  -Surface both `
  -LifecycleMode real `
  -DebugPort 19279 `
  -UserDataDir .tmp\edge-dialogue-browser-probe-real-both-18457 `
  -ScreenshotDir .tmp\dialogue-browser-screens-18457-real-both `
  -NodeMaxOldSpaceMb 1024
```

启动条件：

- JDK21
- 隔离 DB：`.tmp\dialogue-browser-real-both-18457\agent_cloud.db`
- 关闭启动 preflight warmup：`-Dagentcloud.dispatch.preflight.warmup=false`
- dispatch cache / unavailable：`120000ms` / `600000ms`
- Codex 验收用短 turn 参数：`turn_activity_timeout_ms=3000`、`turn_max_duration_ms=5000`、`coding_turn_max_duration_ms=5000`、`partial_timeout_min_output_chars=1`

## 2. 自动化证据

- [x] `surface=both`
- [x] `lifecycle_mode=real`
- [x] `chat_surface.surface=chat_completions`
- [x] `responses_surface.surface=responses`
- [x] chat `pause` 使用真实 `POST /api/v1/tasks/task_6ca41fc0065044b8/pause`
- [x] chat `resume` 使用真实 `POST /api/v1/tasks/task_6ca41fc0065044b8/resume`
- [x] chat lifecycle 期间 `hashTaskId == selectedTaskId == task_6ca41fc0065044b8`
- [x] responses `pause` 使用真实 `POST /api/v1/tasks/task_7c30c2e057694238/pause`
- [x] responses `resume` 使用真实 `POST /api/v1/tasks/task_7c30c2e057694238/resume`
- [x] responses lifecycle 期间 `hashTaskId == selectedTaskId == task_7c30c2e057694238`

截图束包含：

- `chat-default-task-auto.png`
- `chat-task-note-attach.png`
- `chat-auto-start-task.png`
- `chat-followup-manual-start.png`
- `chat-manual-start-continuity.png`
- `chat-stream-fallback.png`
- `responses-default-task-auto.png`
- `responses-task-note-attach.png`
- `responses-auto-start-task.png`
- `responses-followup-manual-start.png`
- `responses-manual-start-continuity.png`
- `responses-stream-fallback.png`

## 3. 当前结论

自动化 richer browser acceptance 的 `real` lifecycle gate 已覆盖 `chat` 和 `responses` 两个 surface。`pause` / `resume` 均走正式 POST 控制接口，并且 action target、URL hash、选中 task 三者保持一致。

边界：

- 这不是人工手点记录；严格人工 A-H gate 仍应单独保持未完成，直到有人按 runbook 手动确认并写入记录。
- 本轮使用短 Codex turn 参数，证明 browser lifecycle / projection / continuity seam，不证明 10-15 分钟 coding task 默认策略的吞吐或稳定性。
- 启动关闭了 preflight warmup，只降低验收环境的启动资源压力，不改变普通 task 调度语义。

## 4. 2026-06-02 口径复核

本轮后续复核发现 runbook / helper 里曾存在一处容易误读的口径：`scripted backfill` 会把 A-H 自动化证据预填为 `passed=true`。这会和 release checklist 中“严格人工 A-H 逐条手点仍未完成”的 gate 产生冲突。

已收口：

- `Render-DialogueAcceptanceScriptedBackfillTemplate.ps1` 现在使用 `scripted_coverage_passed=true` 表示自动化覆盖已通过。
- 同一 JSON 中的 `passed` 保持 `false`，专门留给人工复看 / 签核。
- `Run-DialogueAcceptanceScriptedBackfillProbe.ps1` 已验证 A-H 全部路径都有 scripted coverage，同时 `residual_human` 仍保留 A-H。
- `Render-DialogueAcceptanceManualBackfillTemplate.ps1` 现在把人工回填模板标为 `manual_review_required`，避免空白人工模板也显示成 scripted evidence。
- `Apply-DialogueAcceptanceManualBackfill.ps1` 会拒绝 `scripted_browser_evidence_available` 或 `scripted_coverage_passed=true` 的记录直接写入 `Passed=true`，防止误用 scripted JSON 关闭人工 gate。

验证：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceScriptedBackfillProbe.ps1 `
  -InputJsonPath .\.tmp\dialogue-manual-18276.json
```

结果：`ok=true`，`scripted_coverage_prefilled=A-H`，`residual_human=A-H`，`scripted_misuse_rejected=true`。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1 `
  -InputJsonPath .\.tmp\dialogue-manual-18276.json
```

结果：人工模板仍可正常手工置 `passed=true` 后 apply；这证明防护没有破坏真正的 manual backfill 流程。
