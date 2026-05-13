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
- acceptance record 素材

---

## 2. 推荐启动方式

做 `/dialogue/` UI 验证时，推荐始终用隔离数据库起实例，避免本机历史 session/task 污染结果。

参考命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 `
  -Background `
  -Port 18328 `
  -StdOutPath .tmp\server-18328.out.log `
  -StdErrPath .tmp\server-18328.err.log `
  -JavaArgs @("-Ddb.path=d:\gitAll\agent-cloud-harness\.tmp\dialogue-smoke-18328.db")
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
- 如果本机构建链直接报 `mvn` 找不到，要先修构建环境；否则你后面看到的所有 `/dialogue/` 页面行为，都可能只是旧构建的假象

---

## 3. 推荐验证顺序

### Step A：先确认服务健康

```powershell
curl.exe http://localhost:18328/api/v1/health
```

预期：

- `status=up`

### Step B：先跑 shell / layout

```powershell
node .\scripts\screenshot.js --base-url http://localhost:18328 --report .tmp\dialogue-shell-report-18328.json
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

### Step C：再跑 light business smoke

```powershell
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18328 --report .tmp\dialogue-business-smoke-18328.json
```

注意：

- 这条 smoke 现在已经有 fresh 隔离实例下的真实绿灯
- 但它仍只是 light business smoke，不等于 richer continuity / acceptance 全覆盖
- 如果后续再失败，仍需要结合 report、browser console、requestfailed 和后端 API 证据一起判断
- 如果失败点是“default `task_auto` 后 session 已写入 `task_brief`、session tasks 也已出现新 task，但页面还停在 session-only shell”，应优先归类为 **pending auto-task catch-up seam**，而不是“后端没触发任务”

### Step D：最后再跑 richer acceptance

只有在 Step B 稳定、Step C 至少没有明显退化后，再继续跑：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18328
```

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

现有产物示例：

- `.tmp/dialogue-shell-report-18328.json`
- `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
- `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`
- `.tmp/dialogue-business-smoke-18328.json`

当前最新的 fresh 壳层收口与验证样本是：

- `http://localhost:18328`
- `.tmp/dialogue-shell-report-18328.json`
- `.tmp/dialogue-business-smoke-18328.json`

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

当前 `18328` 的 shell report 已经不只证明“页面能打开”，还明确覆盖了：

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
- `message_only`
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
- 当前 fresh 绿灯应以 `18328` 隔离实例为准；`18264` 不是当前成功样本，它正是这类启动时序性故障的一个真实例子
- `18328` 这轮还额外确认了更窄的 `thread rail + details` 列宽已经在 fresh 运行时真实生效：desktop / responses 下当前是 `196px / 292px`，而不是旧的 `220px / 360px`
- 同一轮里，desktop shell 的 transcript / composer 高度当前是 `575px / 284px`，说明这轮 composer 再减重后 transcript-first 仍然成立
- 右侧 details 的 header/empty/overview/action/card 默认高度也继续被压低，但现有 shell contract 没有被打坏
- 当前仍不能把这两条本地 smoke 视为最终产品 gate；richer continuity / acceptance 仍要靠独立 acceptance 工具链
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
  - 但 `continue-current note` 这一步现在暴露出新的真实业务问题：
    - 页面看起来像继续当前任务
    - 实际上却又 materialize 了一条新 task
  - 因此这一步当前更应归类为 **continue-current binding seam**，而不是 smoke 本身 flaky
  - 这条 seam 的目标 contract 也应明确写死：
    - 当前已选中 task + 勾选“继续当前任务”时
    - 前端请求必须绑定当前 task continuity
    - 不能再让这轮输入掉回 session-scoped `task_brief`
- fresh `18352` 这轮已经把这条 seam 收住了：
  - `default task_auto` 仍会 materialize 新 task 并及时切进 task 视图
  - `continue-current note` 现在会继续当前 task，而不是再新建 task
  - `scripts/dialogue-business-smoke.js` 在 `18352` 上已完整通过四条主路径
  - 当前 console 里剩余的一条 headless `404` 资源报错没有阻断页面功能，可先视为低优先级静态资源尾项

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
- 第三轮默认密度收口已经落到真实 HTML/CSS/JS，但更进一步的 details/status surface 收窄仍未完成
- richer continuity / acceptance 仍需独立 acceptance 工具链

---

## 7. 一句话结论

当前 `/dialogue/` UI 验证的最稳路径是：

- **先按 `STARTUP_GUIDE.md` 起隔离实例**
- **先跑 `scripts/screenshot.js` 看 shell**
- **再跑 `scripts/dialogue-business-smoke.js` 看轻量业务**
- **continuity / richer acceptance 仍走独立 acceptance 工具链**
