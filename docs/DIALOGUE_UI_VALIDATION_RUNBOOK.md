# `/dialogue/` UI 验证 Runbook

这份 runbook 只回答一件事：

- 当前 `/dialogue/` 改壳层、跑 Puppeteer、留验证证据时，应该按什么顺序启动、验证、记录结果。

它不替代下面几份文档：

- `STARTUP_GUIDE.md`
- `docs/DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
- `docs/TEXT_ENCODING_COMPATIBILITY_PLAN.md`
- 当前发布前预检记录：
  - `docs/DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`

关系分工是：

- `STARTUP_GUIDE.md`
  - 负责“怎么起服务”，也是 `/dialogue/` 本地 UI 验证的统一启动入口
- `DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
  - 负责“为什么要往 codex 的 chat shell 靠”
- `DIALOGUE_UI_VALIDATION_RUNBOOK.md`
  - 负责“具体怎么验证 `/dialogue/` UI”
- `DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
  - 负责 richer continuity / façade acceptance 证据
- `DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
  - 负责上 GitHub 前，“页面功能到底要完整测哪些层”的发布前矩阵
- `TEXT_ENCODING_COMPATIBILITY_PLAN.md`
  - 负责区分“仓库内部 UTF-8”与“外部进程输出编码兼容”

如果当前目标是“先把 `/dialogue/` UI 改稳”，优先看这份 runbook。
如果当前目标是“上 GitHub 前页面功能要做哪些比较完整的测试”，优先同时看：

- `docs/DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`

---

## 1. 当前验证分层

`/dialogue/` 当前推荐按三层验证，不要混用脚本：

### 1.1 Shell / Layout

脚本：

- `scripts/screenshot.js`

职责：

- 打开 `/dialogue/`
- 输出截图
- 输出结构化 layout report
- 校验 transcript-first shell 是否仍成立

不负责：

- 创建 session
- 发消息 / 发任务
- façade continuity

### 1.2 Light Business Smoke

脚本：

- `scripts/dialogue-business-smoke.js`

职责：

- 用最轻的前端业务路径做 smoke
- 验证 UI 基本交互没有被改坏

目标路径：

- 创建 session
- default `task_auto`
- manual-start task
- continue-current note
- default `task_auto` 后的第一页结果可见性
  - 当前选中 task 时，顶部应能看到 pinned `latest round output` 或等价的 `messageSummary` 短结果
  - 推荐优先抓稳定 selector：`[data-testid="pinned-latest-round-output"]`

不负责：

- richer browser acceptance
- stream fallback 证据
- `chat/responses` 全路径 continuity contract

### 1.3 Richer Browser Acceptance

脚本 / 文档：

- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`

职责：

- façade continuity
- `chat` / `responses` richer browser path
- task lifecycle control path
  - `/dialogue/` 任务动作按钮必须调用正式 `POST /api/v1/tasks/{id}/pause|resume|continue|escalate|recover`
  - `pause / resume` 这类状态动作必须能在 session message 中投影为 `task_action`
  - 浏览器验收里不应出现 `legacy_control_route=true`，这只能来自历史 `GET` 兼容入口
- acceptance record 素材

### 1.4 Recovery Job UI Probe

脚本：

- `scripts/recovery-job-ui-probe.js`

职责：

- 构造一个 failed task fixture
- 在真实 `/dialogue/` 或 `/console/` 页面点击恢复入口
- 断言浏览器请求走 `POST /api/v1/tasks/{id}/recover?async=true`
- 断言详情区能看到 `恢复任务`、请求 id 和恢复 action/mode 的中文摘要

不负责：

- 执行真实长任务恢复
- 验证 provider 后续 worker round 的完整成功率
- 替代 `Run-TaskRecoveryAcceptanceProbe.ps1` 的后端恢复策略验收

---

## 2. 推荐启动方式

做 `/dialogue/` UI 验证时，推荐始终用隔离数据库起实例，避免本机历史 session/task 污染结果。

参考命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Background `
  -Port 18386 `
  -StdOutPath .tmp\server-18386.out.log `
  -StdErrPath .tmp\server-18386.err.log `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db")
```

说明：

- 这条命令与 `STARTUP_GUIDE.md` 当前 `/dialogue/` 本地验证小节保持一致
- 如果要换端口，数据库文件名也应一起换，避免复用旧验证数据
- `scripts/Run-HarnessWithJava21.ps1 -Background` 现在会先把运行 JAR 复制到 `.tmp/runtime-jars/` 再启动，避免后续本机重建 `target\*.jar` 时把正在运行的 `/dialogue/` 静态资源链打坏
- `scripts/Run-HarnessWithJava21.ps1 -Background` 现在还会在端口已被占用时直接失败，避免 Puppeteer 误打到旧实例
- 如果你改的是 `src/main/resources/web/dialogue/*`，要按“重新构建 + 重启实例”处理；当前运行实例不会热加载这些前端资源
- 如果你改的是 `scripts/screenshot.js`、`scripts/dialogue-business-smoke.js` 这类本地验证脚本，下次直接运行脚本就会生效
- 即使你已经重新构建，只要还是在看旧的后台实例，它也仍然在使用启动时复制出来的 `.tmp/runtime-jars\...` 运行 JAR；要看新的 `/dialogue/` 资源，必须起 fresh 实例
- 如果工作区源码和真实 `8080` 页面行为明显不一致，优先按“旧 runtime / stale build”排障，不要先把问题归因为前端逻辑本身
- 如果真实 `8080` 页面仍显示 `01/21 22:04` 这类明显错误时间，或 `task_progress` 还只有旧摘要/乱码，优先判断当前实例是否仍在跑旧/坏的运行 JAR；不要在未 fresh restart 的前提下直接判定“代码没生效”
- 如果本机构建链直接报 `mvn` 找不到，要先修构建环境；否则你后面看到的所有 `/dialogue/` 页面行为，都可能只是旧构建的假象
- `Run-DialogueBrowserAcceptanceProbe.ps1` 会优先从 PATH 解析 `node`，PATH 漂移时会回退到常见本机 Node runtime；仍找不到时再用 `-NodePath <path-to-node.exe>` 显式指定，避免验收脚本因为当前 shell 环境漏配 PATH 而误判页面失败

---

## 3. 推荐验证顺序

### Step A：先确认服务健康

```powershell
curl.exe http://localhost:18386/api/v1/health
```

预期：

- `status=up`

### Step B：先跑 shell / layout

```powershell
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json
```

当前 shell 验证关心的是：

- transcript-first 标题仍在
- visible composer mode 不超过 2 个
- advanced controls 默认折叠
- workspace 仍然比 details 更强
- rail 必须保持 secondary
- header 必须明显轻于 transcript
- summary 必须从属于 transcript
- thread drawer 默认折叠
- inspector 默认不大面积展开
- `responses` profile 能正确保留 `#facade=responses`
- 默认 `/dialogue/` shell 不应自动带出 `task=` hash
- session-scoped shell 下，composer 的 task-only 次级动作与上下文块默认隐藏
- 用户手工点开的“展开完整结果 / 展开详细内容”在轮询刷新后必须保持展开；几秒后自动收回应直接判定为前端展开状态丢失 bug
- 展开状态必须绑定到同一 `message id`；轮询、`live_flow` 刷新、重新渲染 message list 都不能把用户手工展开覆盖掉，除非用户主动收起或该消息已不在当前列表里
- 下方 active task thread 的 `最近输出 -> 展开完整结果` 也必须守住同样的 contract，但它不应继续复用“只认 message id 的展开键”；thread output 展开态应绑定到同一 `task id` 或当前 selected task identity，不能在 related/session message 轮询后被 `message id` 清理逻辑误删
- 选中 active task 时，如果 `live_flow.route_preview` / `provider_selection` 已经给出当前 route worker，第一页就必须能直接看见 `正在执行: <worker>`；不能只在 route drawer 或 modal 深处才能找到
- 如果 `live_flow.route_preview.recovery_unpinned_recommendation.provider_deprioritized=true`，第一页 route box 也必须直接解释“恢复阶段会优先避开 <provider>`；不能要求用户只看 raw JSON 或 live_flow 才知道恢复避让原因
- 当前 route box plan 已把 provider recovery 避让提升为 `primaryRecoveryNote`，首屏直接渲染“恢复阶段会优先避开 <provider>”与人话原因；drawer 只保留补充 chips / candidate / timeline。
- route drawer 的诊断 chips 也已在 plan 层人话化：`mode: / hint: / learning: / route/execution` 会显示为 `模式：/ 偏好：/ 学习记忆：/ 路由/执行：`，避免 drawer 继续像 raw router trace。
- task thread 里的 `Harness` bubble 应优先显示最近一条 `task_progress / task_result` 的叙述性内容与结果预览；如果后端已经有这类消息而 bubble 仍只剩 `failed / done` 这类单词，应直接判定为 **task-thread outcome preview seam**
- 对当前选中的 active task，如果 `live_flow.related_messages` 或 `session messages` 里已经存在更完整的最近一轮 `task_progress / task_result.content`，主气泡正文必须优先消费这条 outcome message；`runtime_context.active_context.continuity_summary` 只能作为次级兜底，不能用一个 terse `failed / done` 覆盖真实结果正文
- 对失败态 task，如果最新 worker artifact 的 `output_text / artifact_content` 为空，但 `failure_summary_readable` 已存在，`task_progress / task_result.full_content` 必须回退到这条可读失败摘要；新生成展开正文应使用 `失败摘要 / worker 输出 / 产物内容 / 恢复模式 / 执行轨迹` 这些中文分段，展开结果不应只剩 `failed / Worker Output / Artifact Content` 这种历史空壳
- 当前 selected task 的 Harness bubble 已把 `进展：failed/done` 这类低信息 outcome narrative 识别为 stale shell；当同一 `live_flow.task.metadata.failure_summary_readable` 存在时，首屏优先展示清洗后的失败摘要与恢复状态，不再让历史 task_progress 包装句挡住真实失败原因。
- 同样地，如果当前消息本身还没有显式 `full_content / output_text / artifact_content`，但已经有 `failure_summary_readable`，前端也应把它视为“可展开结果”；`展开完整结果` 至少要能展开出一段 `失败摘要`，而不是因为缺少 worker/artifact 字段就完全不给展开入口
- 如果历史 `task_progress / task_result.full_content` 自己就是旧的 `Worker Output / Artifact Content` 空壳，但同一条 message metadata 已经带了更可读的 `failure_summary_readable`，主聊天流的展开态也不应继续盲信旧壳；应优先展开成 `失败摘要 (+ 下一步)`，而不是把空壳正文重新暴露给用户
- 同样地，如果 `output_text / artifact_content` 虽然不为空，但本身明显是旧的长噪声或 mojibake，而 `failure_summary_readable` 已经更干净，`task_progress / task_result.full_content` 也不应把这两段原样拼回去；展开态仍应优先保留短可读 `失败摘要`，把脏原文继续留在 details / live_flow / artifact；旧英文 section 名只用于识别和修复历史空壳，新写入数据不应再生成这些英文分段
- 如果 `session messages` 里的最新 `task_progress.full_content` 仍是历史空壳，但当前 `live_flow.task.metadata.failure_summary_readable` 与恢复状态已经更完整，选中 task 的 thread output 仍必须优先展示这条更新后的失败摘要与 `failure_class / retry / handoff / human_gate`，不能被旧消息壳子压回去
- 如果历史 `failure_summary_readable` 本身仍是旧的长噪声（例如 prompt echo、目录 listing、provider 原始 trace 或 mojibake 段），第一页 thread output 也不能原样整段铺开；主视图必须先压成短可读失败摘要，把原始长文本继续留在 details / live_flow / artifact 路径
- 对 `thread not found / authentication required / connection reset / timeout / failed to start` 这类已知 provider/runtime 失败，主视图短失败摘要应使用 operator 可读中文，例如 `worker codex 失败：执行超时`，不能再把 `worker failed: timeout` 这类英文内部摘要顶到首屏
- 顶部 `selectedStatus` 不能只停留在 `waiting_human / human_gate` 这类低信息状态；如果当前任务 metadata 已有 `execution_status=partial_timeout`、`recovery_stage=human_gate_required` 或 `auto_handoff_scheduled`，首屏 header 应直接显示 `部分结果待确认 / 等待人工确认 / 移交已排队` 这类恢复状态，让用户不用先打开 details 才知道恢复链停在哪里
- 同样地，如果当前 focused task 的 `failure_summary_readable` 已经更干净，而 `task.summary` / `continuity_summary` 仍是历史脏摘要，第一页 `Harness` bubble、continuity 区和详情 modal 也必须优先显示这条干净失败摘要；不能继续把旧 `task.summary` 顶在最前面
- 对当前选中的 active task，第一页还应有更强的运行态条带：至少把 `执行中/最近执行 worker` 与当前 `status / control node` 放在结果气泡上沿，而不是只混在普通 badge 里
- 这条运行态条带最好显式分成两层：第一层是 `执行中/最近执行 worker + status/control node`，第二层是 `最近输出 + short failure/result`；不能退化成只有一段普通正文或一串 badge
- 这两层条带还应继续接近真正的执行面：`worker` 与 `status/control node` 最好拆成独立 headline/detail，而不是全挤在同一行长句里；`最近输出` 也应优先展示短结果 headline，避免用户先扫到一大段自然语言正文
- 移动端也要守住同一条 transcript-first contract：即使新增了更明显的 worker/output 条带，`430px` 左右窄屏下也不应让 header 或 composer 重新长到压过 transcript；否则应直接判定为 **narrow transcript dominance regression**
- transcript 主聊天流里的 `task_progress / task_result` 卡也应遵守同一条原则：在默认折叠态下就露出 `worker + 短结果预览`，而不是只剩 `failed / done` 一词；点击 `>` 只负责展开完整正文，不负责补回“这是谁跑出来的”这种第一屏关键信息
- 如果历史 `task_progress` 自己的 `metadata` 仍是旧壳，但当前已选中 task 的 `live_flow.task.metadata.failure_summary_readable` 更完整，transcript 主卡也应允许借用这份当前 task metadata，先把默认折叠态和展开态补成可读失败摘要，而不是机械继续显示 `failed`
- 这条 transcript 主卡纠偏规则不应只依赖 URL/hash 里的 `selectedTaskId`；只要当前页面已经聚焦到同一条 `live_flow.task`，主卡就应允许借用这条 focused task 的最新 outcome projection，避免因为前端选择态短暂漂移而继续显示旧 `failed`
- 当前已用 `dialogue-task-thread-preview-regression.test.mjs` 锁住这条边界：focused task transcript projection 只要求 `live_flow.task.id` 与 message task id 匹配，不依赖 hash 或 `selectedTaskId`，并会把旧 `Worker Output / Artifact Content` 空壳替换成清洗后的 failure summary。
- transcript 主卡的默认折叠态还应保持“短摘要优先”：`worker + short failure/result` 留在正文和 outcome strip；`failure_class / retry / handoff / human_gate / next step` 这类恢复细节继续留在 hint 或展开正文，不要重新把第一屏压成长句
- 多轮任务第一页的 worker 可见性还应再前置一层：不论是上半区 transcript 主卡，还是下半区 active task thread，都应形成稳定的 `worker / status / short output` 执行条带；用户不该先读一段自然语言后，才推断出“是谁在跑、跑到了哪一轮”
- 对当前选中的 active task，`task_progress / task_result` 默认折叠态最好接近 `codex/openclaw` 的 round output block：第一眼先看到 `执行中/最近执行 worker`、当前 `status / control node`、以及最近一轮短输出；展开 `>` 只负责补完整正文，不负责补第一屏关键信息
- 如果当前页面已经选中 task，transcript 顶部还应额外有一块 pinned `latest round output` 摘要，直接钉住该 task 最近一轮 worker 结果；不应要求用户先在 message list 里向下找那张 `task_progress / task_result` 卡
- 为了让 richer browser acceptance / 真实页探针更稳定，这块 pinned `latest round output` 最好提供稳定 selector，例如 `data-testid="pinned-latest-round-output"`；避免探针只能依赖样式类或文案猜节点
- 这块 pinned `latest round output` 不应严格依赖 `live_flow.task` 已完全挂好；只要当前 selected task 已确定、且 `session messages / related messages` 里已有对应 `task_progress / task_result`，顶部摘要也应能先渲染出来
- pinned `latest round output` 自身也应遵守“短摘要优先”：正文只保留 `worker + short failure/result`，`failure_class / retry / handoff / next step` 继续留在 foot 或展开区，不要把顶部摘要重新拉成长段状态播报
- 当 transcript 消息较少时，`message summary + message list` 这一组默认也应整体贴近底部 composer，而不是让消息卡停在上半区、把大块空白留在消息下方；若仍有剩余空白，也应优先上移到消息组之上
- 如果 transcript 下方还保留折叠态 `任务轨迹` summary，这个 summary 也应按同一条原则收成薄 footer strip；不能因为它本身像第二块 header，就重新制造“消息组和 composer 之间断一层”的错觉
- 如果 pinned `latest round output` 已经有独立的 `最近输出` 条带，正文应进一步退成可选 fallback，而不是和 output strip 重复同一句短结果
- 当前 `dialogue-task-thread-preview-regression.test.mjs` 已锁住 pinned 卡去重合同：当 `outcomeStrip.label=最近输出` 且 headline 已显示短失败摘要时，`showBody=false`，正文不再重复同一句 output preview。
- 下半区 active task thread 也应显式有一块 `最近输出` panel；如果第一页只能看到 `Harness` 正文，却没有独立的 output label / short result block，应直接判定为 **thread round-output visibility regression**
- 如果后端已经把失败细分成 `worker_runtime_transient / task_environment_blocked / worker_backend_deterministic / partial_result_or_quality_risk`，第一页至少要直接露出这条 `failure_class`；否则用户只能看到“auto handoff / human_gate”，但看不出为什么系统会做这个决定
- 但第一页也不该直接把这些恢复信号按原始枚举串裸露出来；像 `worker_runtime_transient / human_gate_required` 这种 token 只适合留在 API / live_flow / details，主视图更合理的行为是显示成短的人话标签，例如“临时运行失败 / 等待人工确认”
- 当前 `selectedStatus` focus line 已在 `human_gate_required` 时追加人话 failure class，例如 `等待人工确认 · 部分结果待确认 / 能力不匹配`；`dialogue-task-focus-line-plan.test.mjs` 覆盖不裸露 `worker_backend_deterministic` 原始枚举，也不再把新增恢复状态写成 `human gate / handoff queued`。
- 当前主卡 / pinned / thread 的 recovery detail 也已把首屏标签从 `failure · / recovery · / hint ·` 收成 `失败 · / 恢复 · / 建议 ·`，保留短信号但减少 control-plane 英文标签外露；`dialogue-task-thread-preview-regression.test.mjs` 覆盖不回退到旧标签。
- message card 与 pinned latest round output 的 failure badge 也已收成 `失败 · <failure class>`；`dialogue-recovery-label-render.test.mjs` 覆盖不回退到 `failure ·`。
- message card 的 learning hint 状态也应人话化：当 richer context 可见时显示 `提示 · <worker> 已应用 / 已观测未应用`，而不是 `applied / observed`；验收入口是 `node --test src/test/js/dialogue-message-card-plan.test.mjs`。
- message card 与 active task signal 的 route/tool 文案也不应保留 `via / steps`：route 显示 `来源：...`，tool chain 显示 `N 步`；同一个 `dialogue-message-card-plan.test.mjs` 覆盖 message card 计划层不回退。
- route drawer 的 chip 源头也不应主动生成 `mode: / hint: / learning: / route/execution`；`dialogue-route-box-plan.test.mjs` 同时覆盖旧输入兼容映射和 app.js 源头中文化。
- 右侧详情的 `实验对比` 指标也应遵守同一中文化口径：`runs / done / learned hint applied / avg tool steps / steps / cost` 不应作为用户可见文案；`dialogue-experiment-summary-plan.test.mjs` 静态锁住这些指标不回退。
- execution boundary 的基础 chip 也不应继续裸露 `exec / worker` 风格标签；`dialogue-execution-boundary-plan.test.mjs` 覆盖 `执行回合 / 执行方` 不回退。
- judgment/cognition timeline 的细节 chip 也应遵守同一中文化口径：`next/current/follow-up/action/route/status/aligned/diverged` 收成 `下一步/当前/跟进/动作/路由/状态/一致/不一致`；`dialogue-recovery-label-render.test.mjs` 静态锁住不回退。
- Mounted Context 对象卡也不应裸露 `retention / rehydrated / archive retrieval / external refresh / context reopen / refs / targets / next`；`dialogue-recovery-label-render.test.mjs` 静态锁住这些 chip 不回退。
- recovery detail 的操作短信号也已收口成 `重试 N / 移交 N -> worker / 建议移交 -> worker`，不再把 `retry / handoff / manual handoff candidate` 这类英文控制面标签直接放在第一页。
- `fresh_session` 恢复模式在 route chip 与 recovery detail 中也已显示为 `恢复：新会话 / 恢复 · 新会话`，不再把 `recovery: fresh session` 直接露在首屏。
- 同样是 `human_gate_required`，第一页的短解释也不该一律写成同一句；`环境阻塞` 应更像“先修环境后继续”，`部分结果待确认` 应更像“先复核已有结果再决定是否 handoff / 重试”
- richer browser acceptance / 真实页复看时，浏览器 console 默认不应再出现稳定可复现的静态资源 `404`；`/favicon.ico` 已通过 HTML data icon 与服务端 `204 No Content` 收口，后续新增稳定 `404` 应按回归处理
- 真实页首屏还应避免“先闪旧态再收敛”：如果 hash 已经带了 `task=...`，第一页不应先短暂显示 `selectedStatus=idle`、主卡正文=`failed`，几秒后才回到正确 worker/result；首轮加载应尽量先拿到 selected task 的 `live_flow` 再渲染主聊天流
- 当前已对带 `task=` 的 refresh 顺序做静态回归：先加载 session messages 但抑制 eager render，再由 `loadSelectedTask()` 拿到 `live_flow` 后统一渲染 message summary / pinned latest round output，避免首屏先闪旧 `failed/idle`。
- richer browser acceptance 里的 pinned `latest round output` gate 已从“只检查 selector 存在/标题存在”升级为要求该区域直接带 `worker / 执行中 / 最近输出 / 执行回合` 等执行信号，避免空 pinned 卡误判通过。

### Step C：再跑 light business smoke

```powershell
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json
```

注意：

- 这条 smoke 现在已经有 fresh 隔离实例下的真实绿灯
- 但它仍只是 light business smoke，不等于 richer continuity / acceptance 全覆盖
- 如果后续再失败，仍需要结合 report、browser console、requestfailed 和后端 API 证据一起判断
- 如果失败点是“default `task_auto` 后 session 已写入 `task_brief`、session tasks 也已出现新 task，但页面还停在 session-only shell”，应优先归类为 **pending auto-task catch-up seam**，而不是“后端没触发任务”
- 如果 default `task_auto` 后页面已经出现 `已提交任务，正在推进`，hash 已经带同一条 `task=...`，且 task thread / details 已经切到新 task，但 `#messageList` 尚未立刻渲染出原始 user intent，这不应判成 task_auto 失败；richer acceptance 的第一段 gate 应允许“消息原文可见”或“选中 task 收敛”二选一，后续 pinned latest-round output gate 再继续验证结果可见性
- 如果失败点是“点开完整结果后，过几秒又自动收回”，应优先归类为 **message-card expanded state lost on polling seam**，而不是“后端没返回完整结果”

### Step D：再跑 recovery job UI probe

当本轮改动涉及 `recover` 按钮、`recovery_jobs` 展示、task detail overview 或 failed/human_gate action plan 时，先跑这个探针，再进入 richer acceptance：

```powershell
node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:18386 --surface dialogue --report .tmp\recovery-job-ui-probe-18386.json --screenshot .tmp\recovery-job-ui-probe-18386.png
```

预期：

- report 中 `async_recover_request=true`
- report 中 `recovery_job_visible=true`
- 页面详情区能看到 `恢复任务` 与请求 id

如果这一步失败，应优先判断：

- 当前实例是否仍在跑旧 JAR
- `recover` 按钮是否被 action plan 隐藏
- `/api/v1/tasks/{id}/recovery_jobs?limit=5` 是否被页面读取
- details panel 是否未展开导致 recovery job panel 不可见

### Step E：最后再跑 richer acceptance

只有在 Step B 稳定、Step C 至少没有明显退化后，再继续跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18386
```

这条 richer acceptance 当前还承担 P5 的 task lifecycle 浏览器级验收：

- `manual-start task` 后，页面必须收敛到选中 task，hash 中有同一条 `task=...`
- 点击任务动作 `pause` 时，浏览器 Network 只能看到 `POST /api/v1/tasks/{id}/pause`
- `pause` 后，`GET /api/v1/sessions/{id}/messages` 必须能查到该 task 的 `task_action`，且 `metadata.request_method=POST`、`metadata.legacy_control_route` 不存在
- `pause` 后主动作必须切到 `resume`
- 点击 `resume` 时，浏览器 Network 只能看到 `POST /api/v1/tasks/{id}/resume`
- `resume` 后，同一条消息流必须能查到 `task_action(action=resume)`，并保持 `request_method=POST`；这条 browser gate 不要求 `POST /resume` HTTP 响应已经完成，因为真实 worker round 可能会让该请求持续到后续调度结束
- 这条验收只证明 `/dialogue/` 真实页面走正式 POST 控制面；历史 `GET` 兼容入口仍由 `TaskHandlerControlActionHttpTest.legacyGetPauseStillWorksAndIsMarkedForAudit()` 覆盖

---

## 4. 当前真实状态

基于当前仓库已有证据，`/dialogue/` 的 UI 验证状态应写实成下面这样：

### 4.1 已有真实绿灯

- `scripts/screenshot.js` 已升级成 multi-profile shell validator
- 已在 fresh 隔离实例上通过 `desktop / narrow / responses`
- `scripts/dialogue-business-smoke.js` 已在 fresh 隔离实例上通过：
  - create session
  - default `task_auto`
  - manual-start task
  - continue-current note
- 真实 `8080` 上新增的 pinned `latest round output` gate 已经能通过
- fresh `18386` 隔离样本里，`continue-current note` 也已经重新回到绿色：
  - `task_note`
  - `已记录到当前任务上下文，等待手动继续。`
- 之前那条 `#taskContinueCurrent` 偶发等待超时，应视为**已收口的旧 smoke-driver seam**，除非后续在 fresh 样本里再次复现

现有产物示例：

- `.tmp/dialogue-shell-report-18386.json`
- `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`
- `.tmp/dialogue-business-smoke-18386.json`

当前最新的 fresh 壳层收口与验证样本是：

- `http://localhost:18386`
- `.tmp/dialogue-shell-report-18386.json`
- `.tmp/dialogue-business-smoke-18386.json`

补充一条更贴近真实项目页的新证据：

- 在真实 `8080` 上，`scripts/dialogue-business-smoke.js` 现在已经能额外验证：
  - default `task_auto` 成功后
  - 第一屏顶部可稳定看到 pinned `latest round output`
  - 当前 smoke 输出里已抓到：
    - `selectedStatus = active / scheduler / worker codex`
    - pinned `latest round output`
    - `执行中 / worker codex · active / scheduler`
- 这说明“default task_auto 后第一页要直接看见 worker + 最近一轮 output summary”这条 contract 已经有真实浏览器 smoke 证据

最近一轮更贴近真实页面断层排查的新证据来自 fresh `18390`：

- `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18390 -Surface chat`
- 截图目录：`.tmp/dialogue-browser-screens-18390-chat-layout-v3`
- `chat_surface.task_note_attach` 现在还会附带一组 `layout_metrics`，用于约束“消息少时 transcript 中段不要再断一大块”：
  - `gapBetweenLastCardAndDrawer`
  - `gapBetweenDrawerAndComposer`
  - `drawerHeight`
  - `drawerSummaryHeight`
- 当前 gate 写实成：
  - `gapBetweenDrawerAndComposer <= 28`
  - `gapBetweenMessageBodyAndComposer <= 28`
  - `drawerSummaryHeight <= 28`
- fresh `18390` 的真实值当前为：
  - `gapBetweenLastCardAndDrawer = 10`
  - `gapBetweenDrawerAndComposer = 17`
  - `gapBetweenMessageBodyAndComposer` 后续应作为更直接 seam 指标一起记录
  - `drawerHeight = 23`
  - `drawerSummaryHeight = 23`
- 这说明“消息组 + collapsed thread drawer”已经基本被收成贴近 composer 的同一组底部栈；后续若再退化成中段断层，应优先检查这组布局阈值，而不是先怀疑消息数据没回来

补充合同：

- 用户肉眼看到的“`message-panel__body message-panel__body--stream-only` 到 `<section class="composer-panel">` 上方有大片空白”，不应只用 `thread drawer -> composer` 间距判断。
- probe 必须额外记录 `messagePanelBody.bottom -> composerPanel.top` 的直接间距，即使 collapsed thread drawer 被隐藏、DOM 结构变化或 drawer 高度为 0，也能判断消息区底部是否贴近 composer。
- 该指标和 `gapBetweenDrawerAndComposer` 同时存在：
  - `gapBetweenDrawerAndComposer` 约束 collapsed task drawer 自身不要制造断层。
  - `gapBetweenMessageBodyAndComposer` 约束用户指出的 message body 与 composer seam。
- 如果真实页再次出现空白，优先看这两个字段；不要只凭截图或 CSS 正则判断。

紧接着下一轮又收了一条和“完整结果展开”直接相关的前端 seam：

- 下半区 active task thread 的 `展开完整结果` 现在不再和主聊天流共用“只认 message id”的展开键
- 当前展开态已经拆成：
  - `expandedMessageIds`
  - `expandedThreadOutputTaskIds`
- 这样在 related/session message 轮询刷新后，task thread 的展开态不会再被 `message id` 清理逻辑误删
- 当前最小回归已补进：
  - `src/test/js/dialogue-expanded-state-plan.test.mjs`
  - 重点锁住：
    - task 仍可见时，thread output 展开态不能因为 message id 变化而丢失
    - task 自己消失时，展开态才允许被清掉
- 这条修复当前还有 fresh `18392` 的最小运行时证据：
  - `Build-WithJava21.ps1 -QuietMaven -SkipTests`
  - `Run-HarnessWithJava21.ps1 -Background -Port 18392`
  - `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18392 -Surface chat`
  - `screenshot.js --base-url http://localhost:18392 --report .tmp/dialogue-shell-report-18392.json`
  - 结果保持绿色，没有把现有 `task_note_attach / manual_start_continuity / thread output` 链打坏

这轮不只是“还能打开页面”，而是已经包含第二轮壳层收口后的真实 green run：

- 更薄的 header
- 更明显的 transcript-first 主区
- 更轻的底部 composer
- 更像 thread rail 的左侧最近会话列
- 窄屏下 transcript 仍然高于 composer，且不再出现 rail/workspace 并排挤压

在这一轮之后，默认视觉层又继续收了一层：

- session rail 更像真正的 thread preview，而不是窄条列表容器
- header 顶部状态 pills 更轻，更接近次级状态面
- details header 文案和入口更弱化，进一步拉开 transcript 与 inspector 的主次关系

这一轮之后，`scripts/screenshot.js` 和 `scripts/dialogue-business-smoke.js` 仍保持绿色。

第三轮收口继续压的是 transcript 周边辅助面板：

- `message summary` 再次收薄
- `thread drawer` 改成更轻的“任务上下文”折叠线

最近一轮真实排障还确认了一个更细的行为边界：

- default `task_auto`` 的后端 materialization` 现在已经成立
- 但如果 provider 执行链较长、façade 响应尚未结束，前端有机会短时间停在 session-only shell
- 因此 `/dialogue/` 更合理的 contract 应该是：
  1. 先显示 pending task submit
  2. 再在请求未结束时主动 catch up 同 session 下的新 task
  3. 一旦识别到 unseen task，就立刻把 selection/hash 切过去

如果后续再看到“聊天像没触发任务”，先按这个顺序排查，而不是先回退到 `message_only` 心智。

这轮之后，单独顺序运行的 shell screenshot 和 light business smoke 仍保持绿色。

当前 unified fresh `18386` 的 shell report 已经不只证明“页面能打开”，还明确覆盖了：

- `rail stays secondary`
- `header stays lighter than transcript`
- `summary stays subordinate to transcript`
- `thread drawer collapsed by default`
- `transcript dominates composer vertically`
- `default dialogue shell does not auto-select task`
- `session-scoped shell keeps task-only composer actions hidden`
- `session-scoped shell keeps composer context hidden`
- `default shell keeps details folded or lightweight`
- `details=open` 在 desktop / responses 下会真实显示右侧 details panel，而不是只改 hash/state

同时这轮 green run 也确认了 light business smoke 仍然保持通过：

- create session
- default `task_auto`
- default `task_auto pinned latest round output`
- manual-start task
- continue-current note

第四轮之后，transcript 顶部 role/scope filter 也已经下沉成默认折叠的 `筛选` drawer；当前最新的 `narrow` profile 单独复跑仍保持绿色。

第五轮默认密度收口继续压的是 transcript 和 composer 之间的辅助说明层：

- `message summary` 再次收薄
- composer mode/meta 条更轻
- composer footer 只保留最少 session/task context
- session rail 卡片更像 thread preview
- details header copy 与顶部 focus/status pills 进一步减重
- details panel 正文卡片和 header 默认块感更弱，更接近按需查看的 secondary surface
- sidebar 顶部与“新建 thread”区继续减高；composer 头部和辅助说明进一步压薄
- 这一轮又继续把 session card 收向“标题 + 一行 preview + 时间”，并把 composer footer、details header、overview/action 区的默认高度再压低一层；当前 empty-state screenshot 下，这种变化已经能直接看见
- 空态下的 composer 次级 task-only 动作也继续下沉了：`附着到当前任务 / 生成 follow-up / 清除关联` 不再常驻，只有真正进入 task 上下文时才露出来
- 底部主发送条这轮又继续压了一层：默认按钮尺寸更轻，高级参数 summary 更薄，empty-state 下的 bottom edge 更接近 chat input footer，而不是 control-plane 操作条
- 同一轮里，empty-state 下 footer 左侧的 session/task 上下文块也默认隐藏了；只有进入 task 上下文或 closed session 时才重新出现
- 最新这一轮又继续压了一层 task-state footer：在 task / follow-up 上下文下，底部 `messageHint` 默认不再重复出现；task-state footer 只保留更短的一行 task context
- 同一轮里，thread rail 也继续收成更纯的“时间 + 标题 + 一行 preview”，不再常驻 status/task badge；details 的 header、overview/action 区则继续减高
- 最新这一轮继续压的是 transcript 顶部辅助层：`筛选` drawer summary/chip 更薄，message summary 主卡宽度也被限制成更像上下文卡片，而不是横幅摘要面板
- 紧接着这轮又继续压 composer 下半区：mode/meta 提示更轻，高级参数 summary 更薄，空态默认文案也进一步收成更像单聊天输入器
- 最新这一轮继续压的是 transcript 顶部筛选和 composer 下半区的默认密度：filter summary 更短更薄，composer 的 label/inline hint/mode bar/advanced summary 进一步减重
- 紧接着这轮又继续把 rail 与 details 的解释性文案收短了一层：recent rail 只保留极短提示句；details header、更多操作、mounted context、route/judgment、experiment、artifact、tool trace 的 copy 也继续减短
- 最新这一轮继续压的是 transcript 下方的 task timeline：`任务上下文` 继续收成更轻的 `任务轨迹` 抽屉，chain head 默认不再并排常驻 `control node / start_mode`，整体更像按需查看的上下文轨迹，而不是第二主时间线
- 紧接着这轮又继续压的是左侧 recent rail：顶部 copy、更短的 `新 thread` 表单、较轻的 health pill / session card 默认高度，都进一步把左 rail 收成更纯的 thread list

这轮之后，单独顺序运行的 shell screenshot 和 light business smoke 仍保持绿色。

### 4.2 当前未收口项

- 历史上确实暴露过两类失败：
  - `create session` 等待条件与真实页面状态不同步
  - `puppeteer-core` 导航阶段偶发 `GET /dialogue/ net::ERR_ABORTED` / navigation timeout
- 还真实暴露过一类启动顺序风险：
  - 在 fresh 实例启动与本机重建并行时，后台 harness 可能直接报 `NoClassDefFoundError: com/fasterxml/jackson/databind/PropertyNamingStrategies`
  - 这类问题当前更应归类为本机 build/start sequencing 风险，而不是 `/dialogue/` 产品语义故障
- 这些问题当前已通过三类收口手段明显缓解：
  - Puppeteer 打开 `/dialogue/` 改为先等 `/api/v1/health`，再显式等 shell，而不是依赖脆弱的 `networkidle2`
  - 后台 harness 启动时改为复制 runtime jar，避免本机重建 `target\*.jar` 时把静态资源链打坏（此前曾实打实出现 `ZipFile invalid LOC header`）
  - 后台 harness 启动时如果端口已被占用，会直接失败，避免验证误打到旧实例
- 当前 fresh 绿灯应以 `18386` 隔离实例为准；`18264` 不是当前成功样本，它正是这类启动时序性故障的一个真实例子
- `18386` 这轮再次确认了更窄的 `thread rail + details` 列宽已经在 fresh 运行时真实生效：desktop / responses 下当前是 `196px / 292px`，而不是旧的 `220px / 360px`
- 同一轮里，`desktop / narrow / responses` 三个 profile 都为绿；其中 `narrow` 下当前 `header / transcript / composer` 是 `83px / 462px / 213px`，说明这轮移动端减重后 transcript-first 仍然成立
- 右侧 details 的 header/empty/overview/action/card 默认高度也继续被压低，但现有 shell contract 没有被打坏
- 当前仍不能把这两条本地 smoke 视为最终产品 gate；richer continuity / acceptance 仍要靠独立 acceptance 工具链
- 对真实项目页还要额外检查一条当前 seam：
  - 如果 `/api/v1/sessions/{id}/messages?task_id=...` 里最新 `task_progress / task_result` 已经带了 `metadata.full_content`
  - 但主聊天流里仍只看到短摘要，或要靠用户手点刷新才出现新结果
  - 当前应优先归类为 **前端结果可见性 / 活跃任务轮询 seam**，而不是“后端没拿到 agent 返回结果”
- 更合理的当前 contract 应该是：
  1. 选中 active task 时，`/dialogue/` 对该 task 采用更短周期的结果轮询
  2. 最新 `task_progress / task_result` 回到主聊天流后，默认仍可先显示摘要
  3. 但若后端已提供 `full_content / output_text / artifact_content`，页面必须明确提供“展开完整结果”入口，且对最新结果卡给予更强的默认可见性
- 最新这轮 fresh `18344` 还暴露了一个更具体的真实缺口：
  - 后端已经按 `task_auto` 成功 materialize 新 task
  - `POST /v1/chat/completions` 写入的是 `task_brief`
  - `/api/v1/sessions/{id}/tasks` 也已经能看到新 task
  - 但前端这时仍可能没有及时把当前页面切到新 task
  - 结果就是 business smoke 会卡在“没有进入 task 视图”，而不是卡在“task 没创建”
  - 从 `18346` 这轮 fresh 复验看，这个缺口更准确地说是：
    - 请求尚未返回前，UI 没主动追踪新 task materialization
    - 因此前端 hash / detailTitle 可能一直停在 session-only shell
- 这类失败当前应优先归类为：
  - `task_auto materialized, but UI selection did not catch up`
  - 不应误记成“默认聊天仍是 message_only”
- 紧接着 fresh `18348` 这轮又确认了一件更重要的事：
  - pending auto-task catch-up 方向已经有效
  - `scripts/dialogue-business-smoke.js` 这轮已能在默认 `task_auto` 路径下真正进入新 task 视图
  - 但页面 console 里仍暴露了一个纯前端 helper 缺口：`ReferenceError: delay is not defined`
  - 这类问题应继续归类为“前端实现残缺”，而不是再回退怀疑 `task_auto` 语义
- fresh `18350` 这轮又把状态再收窄了一层：
  - `delay()` helper 已补齐，default `task_auto` 仍能稳定切进新 task
  - 但当时 `continue-current note` 这一步又暴露出新的真实业务问题：
    - 页面看起来像继续当前任务
    - 实际上却又 materialize 了一条新 task
  - 因此当时更应归类为 **continue-current binding seam**，而不是 smoke 本身 flaky
  - 这条 seam 的目标 contract 也应明确写死：
    - 当前已选中 task + 勾选“继续当前任务”时
    - 前端请求必须绑定当前 task continuity
    - 不能再让这轮输入掉回 session-scoped `task_brief`
- fresh `18352` 这轮已经把这条 seam 收住了：
  - `default task_auto` 仍会 materialize 新 task 并及时切进 task 视图
  - `continue-current note` 现在会继续当前 task，而不是再新建 task
  - `scripts/dialogue-business-smoke.js` 在 `18352` 上已完整通过四条主路径
  - 后续已收口当时的 headless `404` 静态资源噪声：页面声明空 favicon，服务端 `/favicon.ico` 返回 `204 No Content`
  - 对应回归证据是 `WebConsoleHandlerHttpTest.rootFaviconReturnsNoContentForAcceptanceNoise()`
- fresh `18362` 这轮又把边界收得更细了一层：
  - `/dialogue/` 第一屏的 pinned `latest round output` 与 active task thread 现在已经能直接显示：
    - 当前/最近执行 worker
    - 当前 status / control node
    - 最近输出短摘要
  - 但同一轮也暴露出一个新的真实业务 seam：
    - manual-start task 提交后
    - 页面 hash 已切到新 task
    - 但 detail title 仍可能停在旧 task
    - 紧接着再点 `continue-current` 时，这轮输入会被当成新的 manual-start task 创建
  - 当前 `18362` 的真实落库证据是：
    - `sessions/{id}/tasks` 里新增了 `task_e4eab63214c841c3`
    - `sessions/{id}/messages` 里落的是：
      - user `task_brief`
      - assistant `task_receipt`
    - 而不是预期的：
      - user `task_note`
      - assistant `已记录到当前任务上下文，等待手动继续。`
  - 因此这一步当时更准确的归类应是 **continue-current selection drift seam**
  - 也就是：第一页 worker/output 可见性已经收住，但 manual-start 之后的 selected task / detail context 仍可能漂移，导致下一轮 continuity 绑错目标
- fresh `18366` 这轮又把这条 seam 收住了：
  - `submit manual-start task` 不再只等 hash 变化
  - 当前更稳的 browser smoke contract 是：
    - hash task 已切到新 task
    - thread 中 active card 的 `data-task-id` 已对齐
    - details title / selectedStatus 已对齐这条新 task
  - 在这个 settle 前提下，`continue-current note` 重新回到正确合同：
    - `sessions/{id}/messages?task_id=...` 里会新增：
      - user `task_note`
      - assistant `chat_reply`
    - assistant 内容为：`已记录到当前任务上下文，等待手动继续。`
  - 当前更稳的 smoke gate 也应以这条 task-scoped 回执为主，`composerInlineState` 只作辅助信号；不要再因为单条 inline 文案短暂漂移，就把已经正确的 continuity note 误判成失败
  - fresh `18366` 的 `scripts/dialogue-business-smoke.js` 已重新通过：
    - create session
    - default `task_auto`
    - default `task_auto pinned latest round output`
    - manual-start task
    - continue-current note
  - 后续真实 `8080` 长寿实例里又进一步收口了另一条晚到刷新 seam：
    - 旧 `auto-start` task 的晚到 progress/result 不应再把刚显式选中的 `manual-start` task 抢回去
    - 当前这条 selected-task late-refresh drift 已通过短时 stickiness 收口，并已在 fresh-restart `8080` richer probe 上复验通过
- 2026-05-27 真实 `8080` 复验记录：
  - 先杀掉旧 `8080` 监听进程，再 `Build-WithJava21.ps1 -SkipTests -QuietMaven`，并用隔离库 `.tmp/agent_cloud_after_fix.db` fresh restart
  - `scripts/screenshot.js --base-url http://localhost:8080 --report .tmp/dialogue-shell-report-8080-after-fix.json` 通过
  - `scripts/dialogue-business-smoke.js --base-url http://localhost:8080 --report .tmp/dialogue-business-smoke-8080-after-fix.json` 通过
  - `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp/dialogue-browser-screens-8080-after-fix-chat` 通过
  - 这轮 richer probe 覆盖了 `manual-start task`、`continue-current`、`followup manual-start`，以及任务动作 `POST /pause`、`POST /resume`
  - `manual-start` browser gate 不应再只绑定旧文案 `任务已记录`；当前真实页可能返回 `已提交任务，正在推进`，验收应以选中 task/hash/detail/thread 收敛为主，回执文案只作辅助
  - 浏览器探针取消中的 `live_flow` 请求不应再污染服务端日志；本轮 clean log 未出现 `TaskHandler error`、`connection reset`、`Broken pipe` 或 Windows 断连文本
- 同日补充复验：
  - `Run-DialogueBrowserAcceptanceProbe.ps1` 已能在当前 shell 找不到 `node` 的情况下自动解析到 Codex runtime Node，并在结果里输出 `node_path`
  - SSE façade 的浏览器取消请求也已收口；`ChatFacadeHandler` 对已知客户端断连不再输出 ERROR，也不会继续尝试写 `internal error`
  - 复验命令：`Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -ScreenshotDir .tmp/dialogue-browser-screens-8080-sse-fix-chat`
  - 复验日志：`.tmp/server-8080-sse-fix.out.log` / `.tmp/server-8080-sse-fix.err.log` 未出现 `ChatFacadeHandler error`、`ChatFacadeHandler I/O error`、`TaskHandler error`、`connection reset`、`Broken pipe` 或 Windows 断连文本
- 同日控制动作与富验收补充：
  - fresh `8080` 实例：PID `36800`，日志 `.tmp/server-8080-control-fix-v8.out.log` / `.tmp/server-8080-control-fix-v8.err.log`，隔离库 `.tmp/agent_cloud_control_fix_v8.db`
  - `scripts/screenshot.js --base-url http://localhost:8080 --report .tmp/dialogue-shell-report-8080-both-fix-v6.json` 通过
  - `scripts/dialogue-business-smoke.js --base-url http://localhost:8080 --report .tmp/dialogue-business-smoke-8080-both-fix-v6.json` 通过
  - `Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -Surface chat -DebugPort 19253 -UserDataDir .tmp/edge-dialogue-browser-probe-chat-v15 -ScreenshotDir .tmp/dialogue-browser-screens-8080-chat-fix-v15` 通过
  - 本轮收口了两条真实前端 seam：
    - pending auto/manual task 选择在有 submitted intent 时必须等 intent 匹配的新 task，不能被上一轮晚到 auto-start task 抢占
    - task control action 以当前 active task card 为准，并在 `loadSelectedTask()` 后把 live_flow 返回的新 task 状态同步回 `state.tasks`，避免 pause 后 UI 被 stale task list 渲回 active
  - richer probe 的 `pause/resume` 现在以正式 `task_action` 投影里的 `request_method=POST` 与 `request_path=/api/v1/tasks/{id}/{action}` 作为 route 证据，同时继续检查 UI 状态收敛；这是为了避开探针自身反复 `/messages?limit=120` 造成的 fetch record 抖动
  - `Surface both` 与单独 `Surface responses` 在当前工作站仍出现长请求 / headless Edge 资源抖动，表现为浏览器侧 fetch `status=0` 或串行阶段超时；本轮不把 `both` 记为绿灯，后续应在新 fresh 实例、少残留 Edge 进程、单 surface 串行条件下重跑
  - 严格日志检查未发现 `ChatFacadeHandler error`、`ChatFacadeHandler I/O error`、`TaskHandler error`、`connection reset`、`Broken pipe` 或 Windows 断连文本；`GET /api/v1/health` 返回 `up`

因此当前更准确的结论是：

- shell / layout validation：已有 fresh 隔离实例下的真实绿灯
- light business smoke：已有 fresh 隔离实例下的真实绿灯
- richer browser acceptance：继续走单独 acceptance 工具链

额外注意：

- `scripts/screenshot.js` 和 `scripts/dialogue-business-smoke.js` 不要在同一实例上并发跑
- `dialogue-business-smoke.js` 会主动创建 session / task 并推进 hash；如果并发执行，可能把 shell screenshot 的报告污染成“带业务状态的页面快照”
- 更稳的顺序是：
  1. 先单独跑 `scripts/screenshot.js`
  2. 再单独跑 `scripts/dialogue-business-smoke.js`
  3. 如果要再次确认 shell report，不要在 smoke 还跑着时重开 screenshot
  4. 若并发执行后只有 narrow profile 红灯，而单独重跑 narrow 立即恢复绿色，优先视为验证串行规约被破坏，而不是先判定 `/dialogue/` CSS 回归
  5. 若 narrow profile 在 shell-only fresh 实例上仍持续红灯，优先检查移动端下是否仍有“视觉上隐藏但仍占布局”的 composer/context 块，以及 `lede / modeHint / inline state / textarea` 是否一起把 transcript 挤瘦

---

## 5. 结果记录建议

如果只是做 UI 壳层回归，不需要把结果塞进 façade acceptance record。

更合理的记录方式是：

- shell / layout 结果：
  - 记录到本地 `.tmp/` 产物
  - 必要时在 `DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md` 里补写实状态
- richer continuity / façade acceptance：
  - 继续记录到 `DIALOGUE_CHAT_FACADE_ACCEPTANCE_*` 体系

---

## 6. 当前不要过度宣称的点

即使 shell screenshot 已通过，也不要把下面这些话写成已完成事实：

- “前端业务功能已验证通过”
- “`dialogue-business-smoke.js` 已稳定”
- “`/dialogue/` 已完成 codex 风格改造”

目前可以写：

- `/dialogue/` 已有 fresh 隔离实例下的 shell / layout 验证证据
- `/dialogue/` 已有 fresh 隔离实例下的 light business smoke 证据
- 第三轮默认密度收口已经落到真实 HTML/CSS/JS；details/status surface 已开始收口，当前已覆盖 partial timeout provider 诊断、worker recovery action 与 header recovery state
- 消息元信息标签已完成中文化收口：`会话续跑`、`任务绑定`、`任务 · <id>`、`N 条消息` 替代旧的 control-plane 英文文案；浏览器验收探针按 task identity badge 语义匹配，不再依赖 `task-bound` / `task ·` 固定英文显示
- Provider run 排障面板与 worker round 展开区已将可见文件/诊断标签收口为中文：`运行目录`、`最后输出`、`事件日志`、`标准输出`、`运行元数据`、`Provider 诊断`、`Provider 运行文件`；底层 `kind=last_message|events|stdout|metadata|prompt` API 合同不变
- partial timeout 首屏语义已收口为“部分结果待确认”：message signal、header focus line、execution surface 摘要不再直接展示裸 `partial timeout / max duration / chars`，而是显示 `部分结果待确认`、`达到最大时长`、`已有输出 N/M 字符`，让用户能直接判断“有中间结果，需继续或移交”
- transcript signal 前缀已从 control-plane 英文收口为中文显示：`provider/route/trigger/completion/action/tools` 仍作为内部 entry key 保留，但页面与 summary 里显示为 `诊断/路由/触发/完成/动作/工具`
- execution boundary / judgment mounted context 读面已把 `exec/run/last/stdout/meta/prompt` 与 `mounted rendered/panels/objects/traces/budget truncated` 收口为中文标签，避免 details 中的 worker round 诊断继续像 raw trace
- richer continuity / acceptance 仍需独立 acceptance 工具链

---

## 7. 一句话结论

当前 `/dialogue/` UI 验证的最稳路径是：

- **先按 `STARTUP_GUIDE.md` 起隔离实例**
- **先跑 `scripts/screenshot.js` 看 shell**
- **再跑 `scripts/dialogue-business-smoke.js` 看轻量业务**
- **continuity / richer acceptance 仍走独立 acceptance 工具链**
