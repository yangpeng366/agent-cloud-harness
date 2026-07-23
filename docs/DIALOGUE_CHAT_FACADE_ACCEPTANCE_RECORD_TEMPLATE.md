# `/dialogue/` Chat-First + Chat Facade 验收记录模板

> 用途：把 [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)
> 里的自动化证据和 8 条真实页面路径验收，收成一次可归档的记录。

---

## 1. 基本信息

- 日期：
- 执行人：
- 分支 / 提交：
- 运行环境：
  - Java：
  - 端口：
  - façade surface：
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port _____`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -KeepHarnessRunning -Port _____`

手工验收建议：

- 先运行 `Start-DialogueChatFacadeManualAcceptance.ps1`
- 直接使用返回 JSON 里的 `manual_acceptance.recommended_order`
- 优先按 `chat -> responses` 顺序点验
- raw `Run-DialogueBrowserAcceptanceProbe.ps1 -Surface both` 当前不应视为稳定 green gate
- starter-level `BrowserProbeSurface=both` 现在可以用来产出统一 prep bundle
  - 它的真实行为是内部串行跑 `chat` / `responses` 再聚合结果
- scripted browser evidence 的解释仍建议按 surface 分开看
- starter 当前不会自动写**正式** acceptance record 到 `docs/`
  - `record_suggestion` 仍只作为当天正式记录文件名建议
  - 但 starter 现在会在 `.tmp` 下尝试自动生成：
    - 一份未勾选的 A-H seed：`manual_acceptance.record_seed_output_path`
    - 一份可继续回填的 record draft：`manual_acceptance.record_draft_output_path`
  - 即便生成成功，它们也只是辅助草稿，不等于正式记录，更不等于真实人工验收已完成
  - 未实际执行的 Java/Node 测试和 A-H 手工路径仍应保持未勾选
- starter 的 `manual_acceptance` 现在还会直接给出：
  - `result_json_path`
  - `recommended_screenshot_dir`
  - `record_seed_output_path`
  - `record_seed_generated`
  - `record_seed_error`
  - 若骨架已自动生成，还会直接带一份 `record_seed_probe`
  - 若 starter 跑的是 unified both-surface prep bundle，还会直接带一份 `starter_probe`
  - 每条 A-H 路径的最小操作提示 / 前置条件
  - 每条 A-H 路径对应的 `candidate_pngs`
  - `command_examples`
  - `record_seed`
  - 其中 `command_examples.render_record_seed` 可直接生成一段可复制的 A-H markdown 骨架
    - 当前骨架顶部还会带出 `base_url / dialogue_url / responses_dialogue_url / result_json_path / record_seed_output_path / recommended_screenshot_dir / completion_gate`
    - 同时会把 `keep_running / chat_browser_probe / responses_browser_probe / probe_record_seed_output / probe_starter_output` 收进同一份 markdown 头部
  - `command_examples.render_record_seed_to_file` 可把这段骨架落到 `record_seed_output_path`
  - `command_examples.probe_record_seed_output` 可直接验证这条半自动骨架链
  - `command_examples.probe_starter_output` 可直接验证 unified both-surface prep bundle 聚合结果
  - `command_examples.render_manual_backfill_template_to_file` 可生成 A-H 手工回填模板 JSON
  - `command_examples.apply_manual_backfill_to_record` 可把已填写的回填模板 merge 回正式 acceptance record
  - `command_examples.probe_manual_backfill_output` 可验证这条回填 helper 链本身
  - 按最新 contract，这三条 backfill helper 命令也应直接进入 seed / draft 头部，避免人工回填时还要回头翻 starter JSON
  - 建议优先按这些路径收集或核对 PNG，再回填到第 3 节
- starter 当前会把完整返回 JSON 自动落到 `manual_acceptance.result_json_path`
  - 因此后续执行 `render_record_seed*` / `probe_record_seed_output` 时，不再需要调用方先手动把 stdout 重定向到 `.tmp\dialogue-manual-<port>.json`
- starter 当前还会尝试把一份未勾选的 A-H markdown 骨架自动落到 `manual_acceptance.record_seed_output_path`
  - 若 `record_seed_generated=true`，可直接打开这份 `.md` 继续人工回填
  - 若同时返回 `record_seed_probe`，可把其中的 `preview` 当作“骨架首段已生成”的直接辅助证据
  - 即便如此，它仍只是“骨架准备”，不是正式 acceptance record，更不等于真实人工验收已完成
- starter 当前还会尝试把一份更完整的 acceptance record draft 自动落到 `manual_acceptance.record_draft_output_path`
  - 若 `record_draft_generated=true`，可直接在这份 `.md` 上继续补 A-H 手工结果
  - 若同时返回 `record_draft_probe`，可把其中的 header / section 检查结果当作“草稿已落盘”的辅助证据
  - 这份 draft 仍默认保留未完成 gate，不会自动把 A-H 勾成已通过
- 若需要把真实手工结果结构化回填，而不是直接手改 markdown
  - 可先生成一份 backfill template JSON：
    `command_examples.render_manual_backfill_template_to_file`
  - 当前最小稳定字段应包括：
    - `paths[].passed`
    - `paths[].input`
    - `paths[].observed_result`
    - `paths[].notes`
  - 填完后再用：
    `command_examples.apply_manual_backfill_to_record`
  - 当前 helper 只会 merge 第 3 节 A-H 各路径的 `Passed / Input / Observed result / Notes`
  - 它不会自动改最终 gate，也不会把“本轮真实页面验收已完成”直接勾成通过
- 若需要验证 backfill helper 链本身是否可用
  - 可运行：
    `command_examples.probe_manual_backfill_output`
  - 这只证明 markdown merge helper 可用，不等于真实 A-H 人工验收已完成
- 若 starter 同时带了 `-RunBrowserProbes -BrowserProbeSurface both`
  - 若同时返回 `starter_probe`，可把其中的聚合检查结果当作“unified both-surface prep bundle 已真实落盘”的辅助证据
  - 当前最小稳定字段应包括：
    - `allow_both_in_one_run = true`
    - `browser_probe_surface = both`
    - `chat_surface_property_count`
    - `responses_surface_property_count`
    - `chat_png_count`
    - `responses_png_count`
  - 它只证明 starter 侧 both 聚合 bundle 完整，不等于 A-H 人工验收已完成
- 若 starter 同时带了 `-RunBrowserProbes`
  - 返回 JSON 里的 `manual_acceptance.browser_probe_screenshot_dir`
    通常会和 `recommended_screenshot_dir` 一致
  - `browser_probe.screenshot_dir` 和每条路径的 `screenshot_path`
    可直接作为当天人工验收的辅助取证来源
- 若要验证 starter 的 `record_seed_output_path + render_record_seed_to_file`
  - 可运行：
    `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18234.json`
  - 这只证明 A-H markdown 骨架可以半自动落盘，不等于真实人工验收已完成
  - 当前 probe 除了 `preview`，还会检查：
    - `## Run Metadata`
    - `## Useful Commands`
    - `Base URL / Result JSON / Completion Gate`
    - A/H 节和 Entry URL
- 如果用了 `Run-DialogueBrowserAcceptanceProbe.ps1 -ScreenshotDir ...`
  - 可直接把生成的 PNG 文件路径当作“最小取证”之一回填到第 3 节备注
  - 建议按 surface 分开保存：
    - `chat`：`.tmp/dialogue-browser-screens-<port>/chat-*.png`
    - `responses`：`.tmp/dialogue-browser-screens-<port>/responses-*.png`
  - 例如：
    - A `default task_auto` 可回填 `chat-default-task-auto.png`
    - F `stream fallback` 可回填 `chat-stream-fallback.png` 或 `responses-stream-fallback.png`
    - H `#facade=responses + task_required` 可回填 `responses-auto-start-task.png`

---

## 2. 自动化证据

### 2.1 Local Harness Runner

- [ ] `dialogue_shell_probe` 通过
- [ ] `chat_probe` 通过
- [ ] `responses_probe` 通过

输出摘要：

```json
{
  "base_url": "",
  "dialogue_shell_probe": {},
  "chat_probe": {},
  "responses_probe": {}
}
```

### 2.2 Java HTTP / Handler 合同

- [ ] `WebConsoleHandlerHttpTest`
- [ ] `ChatFacadeHandlerHttpTest`

命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'
```

结果摘要：

```text
<填写通过/失败摘要>
```

### 2.3 前端 smoke

- [ ] `dialogue-shell-markup-plan.test.mjs`
- [ ] `dialogue-composer-markup-plan.test.mjs`
- [ ] `dialogue-composer-plan.test.mjs`
- [ ] `dialogue-composer-request-plan.test.mjs`
- [ ] `dialogue-facade-surface-plan.test.mjs`
- [ ] `dialogue-facade-client-plan.test.mjs`
- [ ] `dialogue-facade-response-plan.test.mjs`
- [ ] `dialogue-facade-stream-plan.test.mjs`
- [ ] `dialogue-facade-reply-kind.test.mjs`
- [ ] `dialogue-facade-reply-plan.test.mjs`
- [ ] `dialogue-facade-reply-highlight-plan.test.mjs`
- [ ] `dialogue-facade-reply-ui-consistency.test.mjs`
- [ ] `dialogue-phase6-path-matrix.test.mjs`
- [ ] `dialogue-responses-path-matrix.test.mjs`

结果摘要：

```text
<填写通过/失败摘要>
```

---

## 3. 真实页面路径验收

> 这里的路径名称与 runbook 保持一致。每条路径至少记录：
> - 是否通过
> - 页面入口
> - 观察到的 UI 反馈
> - 若失败，失败点在哪一层：shell / request / response / affordance / continuity
> - 至少一条最小取证：截图、task id、network 观察说明，三者至少其一
> - 每条路径只保留一行 `Path Note`；若同一路径出现重复 `Path Note`，应先修 starter / renderer 再回填正式记录
>
> 若先使用 scripted browser 取证，再做人工手点，可优先按下面的“现成 PNG 对照”回填：
> - A `default task_auto`
>   - `chat-default-task-auto.png`
> - B `message_only + task_id`
>   - `chat-task-note-attach.png`
>   - `responses-task-note-attach.png`
> - C `task_required`
>   - `chat-auto-start-task.png`
>   - `responses-auto-start-task.png`
> - D `follow-up + manual-start`
>   - `chat-followup-manual-start.png`
>   - `responses-followup-manual-start.png`
> - E `manual-start continuity`
>   - `chat-manual-start-continuity.png`
>   - `responses-manual-start-continuity.png`
> - F `stream fallback`
>   - `chat-stream-fallback.png`
>   - `responses-stream-fallback.png`
> - G `#facade=responses + message_only`
>   - `responses-task-note-attach.png`
> - H `#facade=responses + task_required`
>   - `responses-auto-start-task.png`
>
> 注意：这些 PNG 只是“最小取证”候选；若正式记录直接采用 scripted browser evidence，也应在备注里写明来源。
>
> 当前更实的 gate 拆分是：
> - A-H 自动化覆盖可接受 scripted browser evidence，只要它们对应当前真实可达 seam
> - A-H 严格人工签核仍需要人工复看和回填；不要用脚本输出自动勾 `通过`
> - 其中旧路径标签仍保留，但当前真实 seam 更接近 `task_note_attach`：
>   - B / G
> - 若使用 starter / browser probe 自动回填 A-H 自动化覆盖，仍应在备注中保留“来源于 scripted browser evidence”
> - 当前可先生成一份 scripted backfill JSON：
>   `powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceScriptedBackfillTemplate.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json > .\.tmp\dialogue-scripted-backfill-18276.json`
>   - A-H 会预填为 `scripted_coverage_passed=true`
>   - `passed` 仍保持 `false`，除非人工复看后再填写
>   - 这份 JSON 仍不等于 final gate 关闭
>
> 若 starter JSON 已落在 `.tmp/dialogue-manual-<port>.json`，也可先用下面的 helper 生成一段可复制的 A-H markdown 骨架，再粘贴回本模板：
> ```powershell
> powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 `
>   -InputJsonPath .\.tmp\dialogue-manual-18228.json
> ```
> 或：
> ```powershell
> powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 `
>   -InputJsonPath .\.tmp\dialogue-manual-18228.json > .\.tmp\dialogue-record-seed-18228.md
> ```
> 当前 helper 的稳定 contract 是“输出 markdown 到控制台”；若需要写入文件，请由外层命令或编辑器自行保存。
> 现在输出不只包含 A-H 条目，还会先给出一段 run metadata 和关键命令，便于直接拿去做人工回填。

### A. `default task_auto`

- [ ] 通过
- 页面入口：`/dialogue/`
- 输入：
- 观察结果：
- 备注：当前 A 路径优先参考 `chat-default-task-auto.png`；`responses-task-note-attach.png` 属于 G 路径，不再混挂到 A。

### B. `message_only + task_id`

- [ ] 通过
- 页面入口：`/dialogue/`
- 输入：
- 观察结果：
- 备注：可优先参考 `chat-task-note-attach.png`；若在 Responses surface 手点，可改挂 `responses-task-note-attach.png`

### C. `task_required`

- [ ] 通过
- 页面入口：`/dialogue/`
- 输入：
- 观察结果：
- 备注：可优先参考 `chat-auto-start-task.png`；若在 Responses surface 手点，可改挂 `responses-auto-start-task.png`

### D. `follow-up + manual-start`

- [ ] 通过
- 页面入口：`/dialogue/`
- 输入：
- 观察结果：
- 备注：可优先参考 `chat-followup-manual-start.png`；若在 Responses surface 手点，可改挂 `responses-followup-manual-start.png`

### E. `manual-start continuity`

- [ ] 通过
- 页面入口：`/dialogue/`
- 输入：
- 观察结果：
- 备注：可优先参考 `chat-manual-start-continuity.png`；若在 Responses surface 手点，可改挂 `responses-manual-start-continuity.png`

### F. `stream fallback`

- [ ] 通过
- 页面入口：`/dialogue/`
- Network / 观察结果：
- 是否只出现一次请求：
- 备注：可优先参考 `chat-stream-fallback.png`；若在 Responses surface 手点，可改挂 `responses-stream-fallback.png`

### G. `#facade=responses + message_only`

- [ ] 通过
- 页面入口：`/dialogue/#facade=responses`
- 输入：
- 观察结果：
- 备注：当前真实产品态下，这条更接近 `responses_surface.task_note_attach` seam；可优先参考 `responses-task-note-attach.png`

### H. `#facade=responses + task_required`

- [ ] 通过
- 页面入口：`/dialogue/#facade=responses`
- 输入：
- 观察结果：
- 备注：可优先参考 `responses-auto-start-task.png`

---

## 4. 缺口与结论

### 4.1 未覆盖项

- [ ] token-level streaming 仍未验收
- [ ] 完整 `/v1/responses` tool-call surface 仍未验收（最小 message item lifecycle 已有回归覆盖）
- [ ] 其余：

### 4.2 最终判断

- [ ] 本轮仅自动化通过，仍缺真实页面验收
- [ ] 本轮真实页面验收已完成，可作为 Phase 5/6 completion evidence

补充说明：

```text
<填写最终结论>
```
