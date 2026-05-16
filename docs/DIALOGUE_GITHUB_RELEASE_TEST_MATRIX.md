# `/dialogue/` GitHub 上架前页面功能测试矩阵

这份文档只回答一件事：

- 在项目公开上架 GitHub 之前，`/dialogue/` 页面功能需要做哪些**比较完整**的测试、调试和留证据动作。

它不替代下面几份文档：

- `STARTUP_GUIDE.md`
- `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`

关系分工：

- `STARTUP_GUIDE.md`
  - 负责怎么构建、怎么起 fresh 隔离实例
- `DIALOGUE_UI_VALIDATION_RUNBOOK.md`
  - 负责 `/dialogue/` UI 的启动顺序、shell/layout、light business smoke
- `DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
  - 负责 richer browser acceptance、continuity contract 和手工验收路径
- `DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
  - 负责把“上架 GitHub 前页面功能到底要测什么”收成一份发布前矩阵

---

## 1. 目标

GitHub 首发前，页面相关的目标不应只停留在“页面能打开”或“Node 单测是绿的”。更完整的最小目标应拆成五类：

1. **静态壳层不回退**
   - `/dialogue/` 仍然是 transcript-first / chat-first shell
   - 关键静态资源与 ESM import graph 没断
2. **轻量前端业务不回退**
   - 基本会话与发送链仍可在真实浏览器里走通
3. **façade continuity 不回退**
   - `task_auto / task_required / manual-start / task note / follow-up / responses surface`
     这些主路径在真实页面中仍符合当前 contract
4. **人工观察层面没有明显 UI 回归**
   - transcript、thread rail、details、composer 的主次关系仍符合当前 chat-first 设计
5. **调试材料可追溯**
   - 失败后能立刻拿到 report、截图、console/requestfailed、服务端日志和验收记录入口
   - 若 scripted browser 取证走 `both`，还要确认 `chat_surface` 和 `responses_surface` 都非空，且截图目录同时存在 `chat-*.png` 与 `responses-*.png`

---

## 2. 分层测试矩阵

上 GitHub 前，推荐把 `/dialogue/` 页面测试拆成下面五层。不要用某一层的绿灯替代全部结论。

### 2.1 Layer A：Java HTTP 回归

目的：

- 保证 `/dialogue/` 路由和 façade HTTP contract 没被改坏

当前证据来源：

- `src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java`
  - `dialogueRouteServesTranscriptFirstShell()`
  - `dialogueRouteServesAppJavascript()`
  - `dialogueRouteServesImportedJavascriptModules()`
- `src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java`

建议命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

通过标准：

- `/dialogue/` 静态壳层路由为绿
- `/dialogue/app.js` 和 import graph 为绿
- `chat_completions / responses` continuity HTTP contract 为绿

不能替代的内容：

- 不能证明真实浏览器页面排版没回退
- 不能证明前端交互 smoke 为绿

### 2.2 Layer B：Node 单测

目的：

- 保证前端 helper、markup contract、request contract、reply contract 没漂移

当前覆盖来源：

- `src/test/js/*.test.mjs`

重点类别：

- composer/request
  - `dialogue-composer-plan.test.mjs`
  - `dialogue-composer-request-plan.test.mjs`
  - `dialogue-composer-markup-plan.test.mjs`
- task selection / shell contract
  - `dialogue-task-selection-plan.test.mjs`
  - `dialogue-shell-markup-plan.test.mjs`
- façade reply / stream / response
  - `dialogue-facade-client-plan.test.mjs`
  - `dialogue-facade-response-plan.test.mjs`
  - `dialogue-facade-stream-plan.test.mjs`
  - `dialogue-facade-reply-*.test.mjs`
- path matrix
  - `dialogue-phase6-path-matrix.test.mjs`
  - `dialogue-responses-path-matrix.test.mjs`

建议命令：

```powershell
node --check src\main\resources\web\dialogue\app.js
node --test src\test\js\*.test.mjs
```

通过标准：

- `node --check` 通过
- Node 单测全绿

不能替代的内容：

- 不能证明真实页面布局没回退
- 不能证明 Puppeteer 下基本业务交互是绿的

### 2.3 Layer C：Shell / Layout 验证

目的：

- 保证 `/dialogue/` 第一屏仍是 transcript-first shell，而不是又退回 task-first / mini-console

当前入口：

- `scripts/screenshot.js`

建议命令：

```powershell
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json
```

建议保留的产物：

- `.tmp/dialogue-shell-report-<port>.json`
- `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`

当前应重点关注的 contract：

- transcript-first 标题仍在
- rail 仍是 secondary
- header 明显轻于 transcript
- summary 从属于 transcript
- thread drawer 默认折叠
- details 默认 folded 或 lightweight
- `responses` profile 能保留 `#facade=responses`
- 默认 shell 不自动选中 task
- session-scoped shell 下隐藏 task-only composer 动作和上下文块
- 消息不多时 transcript 不出现明显的“固定高度空白”
- `task_progress / task_result` 若带完整正文，主聊天流里要能直接展开，而不是只能跳 details
- 时间戳显示要与真实日期一致；若后端返回 epoch seconds 浮点数，聊天流和 details 仍必须显示正确年月日/时分，而不是 `01/21 22:04` 这类误日期

通过标准：

- JSON report 为绿
- 三张截图至少人工扫一遍，没有明显壳层回退

不能替代的内容：

- 不能证明 create session / 发送链 / task note / manual-start 仍可交互

### 2.4 Layer D：Light Business Smoke

目的：

- 保证最小前端主交互路径仍然通

当前入口：

- `scripts/dialogue-business-smoke.js`

建议命令：

```powershell
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json
```

当前 smoke 目标路径：

- create session
- default `task_auto`
- manual-start task
- continue-current note
- default `task_auto` 后的第一页结果可见性
  - active task 场景下，顶部应能抓到 pinned `latest round output` 或等价的 `messageSummary` 短结果

建议保留的产物：

- `.tmp/dialogue-business-smoke-<port>.json`

通过标准：

- report 通过
- 没有明显 `pageerror / requestfailed / hash 漂移 / shell 消失`

不能替代的内容：

- 不能证明 richer browser acceptance 全覆盖
- 不能替代 A-H 人工验收

### 2.5 Layer E：Richer Browser Acceptance

目的：

- 保证 façade continuity contract 在真实页面里仍成立

当前入口：

- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1`
- `scripts/dialogue-browser-acceptance-probe-runner.cjs`
- `scripts/Run-DialogueManualAcceptanceStarterProbe.ps1`
- `scripts/Start-DialogueChatFacadeManualAcceptance.ps1`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`

建议命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18386 -Surface chat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18386 -Surface responses
```

建议保留的产物：

- browser probe JSON
- `chat-*.png`
- `responses-*.png`

当前应至少覆盖的 richer path：

- `task_auto`
- `message_only`
- `stream fallback`
- manual-start task
- active task 首屏 outcome 可见性
  - 若当前页面已选中 task，transcript 顶部应能稳定抓到 pinned `latest round output`
  - 推荐使用稳定 selector：`[data-testid="pinned-latest-round-output"]`
  - 该 pinned block 至少应直接露出 `worker + short failure/result`，而不是只剩 `failed / done`
- task note attach
- manual-start continuity
- manual-start follow-up
- worker/runtime 失败时至少有一轮可读恢复回执：
  - readable failure summary
  - 若策略开启，能看出 same-worker retry / auto handoff / human_gate 三者之一
- `#facade=responses + message_only`
- `#facade=responses + task_required`

通过标准：

- `chat` surface 单跑为绿
- `responses` surface 单跑为绿
- 若使用 starter 统一 prep bundle：
  - starter JSON 里直接带有 `manual_acceptance.starter_probe`
  - `Run-DialogueManualAcceptanceStarterProbe.ps1` 为绿
  - `browser_probe.chat_surface / browser_probe.responses_surface` 都非空
  - screenshot 目录同时存在 `chat-*.png` 与 `responses-*.png`

验收口径说明：

- 当前 richer browser probe 下，默认 `自动` 模式的首发路径按真实产品态验证 `task_auto`
- 因此 `message_only` 不应再被理解成“默认自动发送不物化 task”
- 若要验真正 task-free 的 `message_only`，应显式切到 composer `聊天` 模式
- `task_note_attach` 这条 richer path 不应再把默认主路径上的 `#messageAttachTask` 常驻可见作为 gate
- 当前更稳的 scripted seam 是：
  - `continue-current note`
  - `manual-start continuity`
- `stream fallback` 也复用同一条默认发送 contract：
  - 允许 inline ack 为 `已记录 / 已提交任务，正在推进 / 任务已推进 / 任务已完成`
  - 若当前页面在这一步已经选中了 active task，也允许 continuity 类回执：`已写入当前任务上下文 / 任务已记录`
  - scripted browser gate 优先看：
    - 单次 façade POST
    - override 命中
    - `text/event-stream` 响应头
    - same-response JSON body
    - 合法 inline ack
  - 不再以 `taskCards === 0` 作为通过前提

注意：

- 不要把 `-BrowserProbeSurface both` 当默认 gate；当前经验仍建议分开跑
- 当前真实预检样本见：
  - `docs/DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`
  - `chat` fresh sample: `18338`
  - `responses` fresh sample: `18340`

### 2.6 Layer F：真实人工手点

目的：

- 用真实人眼和真实页面流确认 `/dialogue/` 没被自动化漏掉的 UI 回归打坏

当前入口：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

当前最重要的人工路径：

- A-H 八条真实页面路径

GitHub 首发前的最低要求建议：

- 至少做一轮 A-H 手点
- 把观察结果回填到 acceptance record
- 若使用 scripted browser PNG 作辅助证据，要明确标注“辅助取证，不等于手点完成”

---

## 3. 推荐执行顺序

GitHub 上架前，不建议无序地“想到什么测什么”。更稳的顺序是：

### Step 1：先做本地构建

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -QuietMaven -SkipTests
```

### Step 2：先跑 Java HTTP 回归

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

### Step 3：再跑 Node 单测

```powershell
node --check src\main\resources\web\dialogue\app.js
node --test src\test\js\*.test.mjs
```

### Step 4：起 fresh 隔离实例

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Background -Port 18386 -StdOutPath .tmp\server-18386.out.log -StdErrPath .tmp\server-18386.err.log -JavaArgs @('-Ddb.path=D:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18386.db')
```

### Step 5：跑 shell/layout

```powershell
node .\scripts\screenshot.js --base-url http://localhost:18386 --report .tmp\dialogue-shell-report-18386.json
```

### Step 6：跑 light business smoke

```powershell
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18386 --report .tmp\dialogue-business-smoke-18386.json
```

### Step 7：跑 richer browser acceptance

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18386 -Surface chat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18386 -Surface responses
```

### Step 8：做 A-H 人工手点并回填

参考：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

---

## 4. 调试与排障清单

如果 GitHub 上架前某一层失败，建议按下面顺序排障：

### 4.1 先确认是不是旧实例

常见误判：

- 改了 `src/main/resources/web/dialogue/*`
- 但是没重新 build 或没起 fresh 实例
- 实际看到的还是旧 JAR

快速判断：

- 看当前端口是不是 fresh 起的
- 看 `.tmp/runtime-jars/` 是否是新的启动副本
- 不要一边 build 一边复用旧后台实例

### 4.2 再看是不是壳层问题还是业务问题

- `screenshot.js` 失败
  - 优先怀疑壳层/布局/静态资源/页面打开
- `dialogue-business-smoke.js` 失败
  - 再看是不是 create session / hash / task selection / inline state 问题
- browser acceptance 失败
  - 再看是不是 richer continuity contract 或 façade path 回归

### 4.3 浏览器侧要收哪些信息

至少记录：

- shell report JSON
- business smoke JSON
- browser acceptance PNG / JSON
- `pageerror`
- `requestfailed`
- 当前 URL/hash

### 4.4 服务端要收哪些信息

至少记录：

- `.tmp/server-<port>.out.log`
- `.tmp/server-<port>.err.log`
- `/api/v1/health`

如果出现本机资源问题，再额外检查：

- `hs_err_pid*.log`
- `replay_pid*.log`

---

## 5. GitHub 首发前最低 gate

如果目标只是“页面功能别太冒险地上 GitHub”，最低建议 gate 是：

- Layer A Java HTTP 回归通过
- Layer B Node 单测通过
- Layer C shell/layout 通过
- Layer D light business smoke 通过
- Layer E browser acceptance 至少分 surface 各跑一轮
- Layer F A-H 至少完成一轮人工手点并回填记录

如果这些没做完，就不应把“页面功能已完整验证”写进对外说明。

---

## 6. 当前边界

当前仓库已经具备的真实能力是：

- Java HTTP 回归存在
- Node 单测存在
- `/dialogue/` shell/layout validator 存在
- `/dialogue/` light business smoke 存在
- richer browser acceptance 存在
- acceptance record/runbook 存在

但这些层级彼此并不等价。上 GitHub 前，真正比较完整的页面功能测试，应该至少把这几层都串一次，而不是只看 CI 或只看截图。
