# Dialogue Progress

## 当前状态

- 2026-09-21: OpenEyes UIA native probe HW-12 落地 `scripts/Run-NativeAppAcceptanceProbe.ps1`。默认 Any VPN + 连接 + dry-run，只有 `-Go` 才真实点击；脚本解析 detect 原生 JSON、click 坐标与截图路径并输出结构化结果。Explorer `此电脑 / 剪切` dry-run PASS；Any VPN 当前无窗口，真实点击待补。

- 2026-07-21 方向调整：dialogue 主题新增活跃焦点——UI 页面展示结果 / 返回与执行中状态判断。状态语义收成 active / running / waiting_human / failed / partial / done 一致口径，要求 HTTP /continue 超时不把 active 任务渲染成 failed，/dialogue/ pinned 输出区分最新一轮结果与任务级结果，/console/ 区分 worker 级与 task 级状态。新方向主入口为 ../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md。
- 2026-07-29: /dialogue/ 任务详情 decision 列表接入长任务收口合同卡。judgment-card-plan.js 扩展 buildJudgmentCardBody 支持 decision_rationale / progress_detail（additive，向后兼容）+ 新增 mapClosureContractFields 把 judgment_trace 字段映射成 card 输入；app.js 在 decision list 首项渲染"长任务收口合同"卡（仅当 decide 已产出 decision_rationale 时，否则不渲染）。dialogue-judgment-card-plan.test.mjs 4 场景；全量 JS 套件 311/0。
- `dialogue/` 已正式升级为 `README.md + PROGRESS.md` 的轻量工作区，并已启用 `runs/README.md` 作为 acceptance / execution / precheck 聚合入口。
- 当前活跃推进主要集中在三条线：`/dialogue/` 的 chat-first 壳层与 pinned 输出、`/console/` 的 operator/Provider 诊断读面、browser acceptance 与 facade/release gate 收口。
- 现阶段仍不启用 `tasks/`、`archive/`；`runs/README.md` 只负责聚合 root-level dated 证据入口，不搬动文档本体，`PROGRESS.md` 继续负责把当前活跃主线串起来。

## 已完成
- 2026-07-22: UI 验收标准 #3 `partial` tone 缺口收口。`task-status-tone-plan.js` 和 `console-status-tone-plan.js` 补 `partial` -> `partial` 映射（独立于 done/failed/active），新增 4 个 JS 测试场景。全量 JS 套件 308 tests pass / 0 fail。

- 2026-07-22: `WEB_CONSOLE.md` 已补 Loop Activity 检测口径（P3）段：`last_loop_tick` 驱动的 active/stall/stale 活跃度判断 + `loopActivityDisplayHint` 映射展示建议。配套验证入口 `dialogue-loop-activity-detector-plan.test.mjs` 16 场景。
- 2026-07-22: P3 Loop activity detector 落地。新增 `loop-activity-detector-plan.js`（`detectLoopActivity` + `loopActivityDisplayHint`），基于 `last_loop_tick` 与当前时间差值判断 loop 活跃度（active/stall/stale/unknown）；新增 `dialogue-loop-activity-detector-plan.test.mjs` 16 场景。`recovery-action-hint-plan.js` 补 `goal_progress_blocked` 场景（waitingReason 含 "subgoal blocked" 时返回 "子目标被阻塞，请解除阻塞或调整子目标"），`app.js` 调用更新传 `waiting_reason`。全量 JS plan 套件 304 tests pass / 0 fail。
- 2026-07-21: P4 pinned outcome 子目标进度切片落地。新增 `task-subgoal-progress-plan.js`，把 `subgoal_status / subgoals / progress_summary` 投影为 `目标进度 / 已完成子目标 / 未完成子目标`，并接入 `/dialogue/` pinned outcome 卡，让 `partial / done` 结果不再只显示总状态，而是能看到已完成与未完成子目标。新增 `dialogue-task-subgoal-progress-plan.test.mjs` 覆盖混合 done/blocked/in_progress、subgoals fallback、summary-only 三类场景；同时修复 `app.js` 中 `toneForStatus / toneForPinnedTaskOutcome` 抽模块后残留的两个孤立 `}`，
- 2026-07-21: P4 UI 状态判断切片落地。新增可测试模块 `task-status-tone-plan.js`，把 `toneForStatus` / `toneForPinnedTaskOutcome` 收成统一口径：`waiting_human / human_gate` -> `paused`（不是 failed），`running` -> `active`；`app.js` 改为从该模块导入。新增 `dialogue-task-status-tone-plan.test.mjs` 10 类映射断言，全量 JS 测试 273 pass / 0 fail。`WEB_CONSOLE.md` 同步“状态与结果展示口径（P4）”段，与 `RuntimeJudgmentService` 的 `ContinuationAction` 对齐。

- `/console/` inspector、runtime health、provider/detail/run detail 这条 operator 读面已经连续多轮收口成中文首屏，并补齐 focused JS 契约。
- `/dialogue/` pinned 最近输出、manual-window action note、recovery receipt/readiness banner 等产品读面已经接进现有验证链，不再只停在 raw metadata。
- `dialogue/README.md` 已从单纯专题入口升级为 `README.md -> PROGRESS.md -> 子线文档` 的工作区入口。
- `dialogue/runs/README.md` 已新增，当前 acceptance / precheck / execution evidence 现在有了主题内聚合入口，不再只能从 root-level dated 文档长名单回看。

## 活跃子线

- chat-first / transcript / pinned output / task action / recovery receipt
- console / operator / provider / runtime health / run detail / worker round 诊断读面
- facade acceptance / UI validation / release gate / browser probe
- free-first/manual-window 与 legacy GET control audit 在前端读面的解释层

## 下一步

- 如果 `dialogue` 主题继续并行推进两条以上实施线，再考虑补 `tasks/` 做子线拆分。
- 如果 acceptance/precheck/browser 证据开始持续密集新增，再考虑补 `runs/` 聚合 dated 记录。
- 如果 `runs/README.md` 后续继续膨胀到需要分批次索引，再在 `runs/` 下面补更细的二级 README，而不是直接把 root-level dated 文档物理迁走。
- 每轮 UI/console 收口后，至少同步 `WEB_CONSOLE.md` 或最贴近的 runbook，再把跨主题摘要写回 `STATE.md`。

## 风险

- `WEB_CONSOLE.md`、`DIALOGUE_UI_VALIDATION_RUNBOOK.md`、`DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md` 与 `PROGRESS.md` 之间仍可能发生口径漂移。
- `dialogue/` 虽然已经有 `runs/README.md`，但 acceptance/precheck 文档本体仍在 root-level `docs/`；若入口不同步，仍可能回退成“有索引但结论没回收到 runbook/基线”的状态。

- 2026-09-21: OpenEyes worker tool FEAT-04 path A 落地。OpenEyesTool 把 yes CLI 透传进 ToolRegistry；harness-config.yml 新增 yes_mcp.{enabled,register_as_tool} 默认 false；HostToolAvailability / WorkerHandler 同步加入 openeyes capability 白名单；mvn test 51/51 全绿；真启动 nabled=true 日志确认 enabled，nabled=false 日志回到 disabled，回滚 1 行 config。
- 2026-09-21: OpenEyes UIA native probe HW-12 推进到验收通过。Run-NativeAppAcceptanceProbe.ps1 默认 dry-run,-AppTitleContains '此电脑' -ButtonNameContains '剪切' -Go 真实点击 mode=real_click 成功,click_output clicked @ (267,257),截图 22146 bytes;Any VPN 主窗口待手动启 VPN 后补一次。
- 2026-09-21: FEAT-04 path B 端侧 plan 落地。uildOpenEyesUiAssertionPlan 接收 	ool_invocations,输出 tone/status/callCount/successRate/elapsed/lastSubcommand/expectation 9 字段,5/5 单测。ToolInvocationDao 加 4 个 SQL 查询 (countByTool/countByToolAndSuccess/elapsedMillisByTool/listRecentByTool),OpenEyesToolCallMetrics ready。下一步把 health API + console 卡接通。