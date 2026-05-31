# `/dialogue/` 参照 Codex 收口方案

这份文档回答四个更具体的问题：

1. `F:\codex-openclaw-backup\repos\codex\codex` 里到底该借什么。
2. 当前 `/dialogue/` 为什么仍然显得像 control-plane 工作台，而不是单聊天壳层。
3. 这轮 UI 收口应该先改哪些面，不该动哪些底层对象语义。
4. `puppeteer-core` 应该如何分层验证壳层效果与前端业务功能，尤其是 `scripts/screenshot.js` 应该如何升级。

结论先说：

- `codex` 当前最值得借的是 **thread rail + transcript + bottom composer + on-demand status surfaces** 这套交互壳层。
- `codex` 参考源主要是 TUI，不是现成 web 前端；因此应该借 **信息架构和默认密度**，而不是逐行照搬代码。
- `STARTUP_GUIDE.md` 负责 `/dialogue/` 本地 UI 验证的统一启动入口；当前 `scripts/screenshot.js` 负责 shell/layout，`scripts/dialogue-business-smoke.js` 负责 light business smoke；前者不替代后者，后者也不替代 richer acceptance。
- 这次最合理的落地方式是：**先把 UI 改造目标和 Puppeteer 验证分层写进文档，再按文档迭代 `/dialogue/`；当前第三轮壳层收口已经落到真实 HTML/CSS/JS。**

---

## 1. 参考源结论：从 Codex 借“聊天壳层”，不借“底层对象模型”

参考源：

- `F:\codex-openclaw-backup\repos\codex\codex\codex-rs\tui\src\chatwidget\transcript.rs`
- `F:\codex-openclaw-backup\repos\codex\codex\codex-rs\tui\src\bottom_pane\chat_composer.rs`
- `F:\codex-openclaw-backup\repos\codex\codex\codex-rs\tui\src\chatwidget\session_header.rs`
- `F:\codex-openclaw-backup\repos\codex\codex\codex-rs\tui\src\chatwidget\status_surfaces.rs`

从这些文件能看到几个稳定结论：

### 1.1 Transcript 是第一视觉中心

`transcript.rs` 说明 `codex` 把 transcript 当成独立状态面维护，而不是附属在某个 task inspector 里的一个子区域。

对 `/dialogue/` 的启发：

- 中间主区应始终是 transcript-first
- task thread、route、judgment、tool trace 都应是 transcript 的附属上下文，而不是主舞台

### 1.2 Composer 是单一底部状态机，不是表单堆叠

`chat_composer.rs` 的重点不是“有多少按钮”，而是：

- 默认只有一个强入口：底部 composer
- slash command、attachment、history、queue、paste burst 都收在同一状态机下
- 高级能力不会长期抢主视图注意力

对 `/dialogue/` 的启发：

- 默认只保留一个强发送入口
- task-only 控制应该继续下沉
- 不要把 composer 做成“聊天输入框 + 任务表单 + debug 开关”的拼盘

### 1.3 Header 和 status surface 都应很克制

`session_header.rs` 很薄，`status_surfaces.rs` 也强调最小状态面。

对 `/dialogue/` 的启发：

- header 应该只保留当前 session / façade surface / 少量状态
- 不应默认让 metrics、route、task stats 占据第一屏
- 诊断信息应是 secondary surface，而不是常驻主阅读路径

### 1.4 不能直接抄的部分

`codex` 当前参考源是 TUI 体系，不是浏览器前端，也不是 task-native continuity harness。

因此不该直接照搬：

- TUI 键位和终端状态机
- `codex` 的内部 thread / activity / title status 模型
- 任何会弱化本项目 `task + packet + checkpoint + judgment` 基础能力的抽象

一句话：

- **借它的默认信息密度**
- **不借它的底层对象语义**

---

## 2. 当前 `/dialogue/` 的真实问题

当前 `/dialogue/` 虽然已经比早期版本更 chat-first，但仍然有三个真实问题：

### 2.1 主区仍然有 control-plane 工作台气质

当前页面默认仍然会让用户强烈感知：

- 任务链
- 回执摘要卡
- route / control node / experiment / inspector

这些对排障有价值，但默认密度仍然偏高。

### 2.2 Composer 仍然离 task system 太近

虽然已经下沉了不少控制项，但当前 composer 仍然保留显式模式和较近的高级参数入口，用户仍很容易意识到“底层其实是 task system”。

### 2.3 现有 Puppeteer 验证层级不对称

当前仓库里至少有三类相关脚本：

- `scripts/screenshot.js`
- `scripts/test-interaction.js`
- `scripts/Run-DialogueBrowserAcceptanceProbe.ps1` + `scripts/dialogue-browser-acceptance-probe-runner.cjs`

但它们的职责边界并不清晰：

- `screenshot.js` 目前只做静态打开、截图和少量布局检查
- `test-interaction.js` 尝试做业务交互，但大量 selector 和页面假设已经过期
- browser acceptance probe 更偏 façade continuity / acceptance 证据，不应直接充当“UI 壳层设计回归测试”

### 2.4 当前真实任务流还有三个会直接伤害可见性的缺口

以当前真实链接为例：

- `http://localhost:8080/dialogue/#session=session_0a10560c8a5e4672&task=task_f88eef3f8d0c4efb&details=open`

当前仍然存在三类不够直观的问题：

1. `继续推进` 后，聊天流里通常只剩一条 `summary_preview`
   - `task_progress / task_result` 默认优先显示短摘要
   - 完整 `output_text / artifact_content / result body` 没有像 `codex` 那样的按需展开入口
   - 结果就是用户知道“发生过一轮推进”，但不知道到底产出了什么

2. transcript 中段仍可能出现明显空白
   - 当消息不多或选中详情面时，会形成“聊天气泡上方/下方大片空白”
   - 这和 `codex` 的紧凑 transcript-first 默认密度相反
   - 更合理的默认姿态不是让消息卡悬在上半区，而是让 `summary + 消息组` 整体贴近底部 composer，剩余空白优先上移
   - 如果下方还保留折叠态 `任务轨迹`，它也应被收成薄 footer strip；否则即使消息组已贴底，视觉上仍会像主聊天流和 composer 之间横插了一块次级 panel

3. agent/runtime 失败时，进展反馈不够可读
   - 当前失败有时会退化成 `不可读错误输出` 提示
   - 但页面没有显式说明“系统是否已自动重试 / 是否已自动切 worker / 是否已经进入 human_gate”
   - 用户只能看到一条失败摘要，感知不到恢复链有没有继续发生

---

## 3. 这轮 UI 收口建议：先抄 Codex 的壳层，再保留 Harness 的 continuity substrate

### 3.1 目标主布局

推荐把 `/dialogue/` 继续收成下面这个壳层：

```text
+--------------------------------------------------------------+
| session rail | session header                                |
|              +-----------------------------------------------+
|              | transcript                                    |
|              |                                               |
|              |                                               |
|              +-----------------------------------------------+
|              | bottom composer                               |
+--------------------------------------------------------------+
                           + optional inspector / drawer
```

核心原则：

- 默认只看 rail + header + transcript + composer
- inspector 不常驻
- task-only 诊断不抢第一屏

### 3.2 Header 继续收薄

header 只保留：

- 当前 session 标题
- 当前 façade surface：`chat` / `responses`
- 极少量状态 pill，例如 `session closed`、`manual-start pending`

不建议默认保留：

- 大块 metrics
- route summary
- task statistics

### 3.3 Transcript 再减默认噪音

当前 transcript 已经比以前干净，但还可以继续压：

- 默认只保留最近一张强摘要卡或干脆不保留摘要卡
- assistant/system 回执继续保留，但 route/tool/mode 只在必要时露出
- task chain 更像“可展开的上下文轨迹”，不是第二条主时间线

### 3.4 Composer 继续向单聊天框靠拢

建议目标：

- 默认只保留输入框 + 发送
- `聊天 / 新任务` 可保留，但要更轻
- `follow-up` 继续维持为隐式/派生路径，而不是主模式位
- task-only 能力继续下沉到 secondary sheet 或 drawer

### 3.6 `task_progress / task_result` 需要从“摘要卡”升级成“可展开结果卡”

这轮不建议再把完整结果长期常驻在主聊天流里，但也不能继续只剩摘要。推荐约束是：

- collapsed 状态：
  - 默认只显示 `summary_preview`
  - 仍保留 `next_step / trigger / completion / route` 这类轻量 signal
- expanded 状态：
  - 可以展开完整结果
  - 完整结果优先取：
    - `full_content`
    - `output_text`
    - `artifact_content`
    - 最后才回退到原始 message body
- 展开入口：
  - 直接在消息卡底部给一个 `>` / `展开详细内容`
  - 同一条卡片自己展开，不跳去右侧 details 才能看正文

这样更接近 `codex`：默认密度仍紧凑，但结果正文不是被摘要永久吃掉。

### 3.7 默认进展反馈要能看出“有没有真的继续推进”

对真实用户来说，`继续推进` 后最想知道的是：

- 这轮有没有重新进入 worker round
- 如果失败，是不是只是同 worker 冷重试
- 如果还是失败，是否已经自动 handoff 到别的 worker
- 到底是停在 `human_gate`，还是仍在自动恢复

因此 `/dialogue/` 里的 `task_progress / task_result / task_action` 最少要能露出：

- readable failure summary
- failure class
- auto retry count
- auto handoff count
- 当前恢复动作（retry / handoff / human_gate）

### 3.8 `worker_round` 必须进入主对话流，但不能变成日志流

背景：

- 真实任务中，Codex 已经产生 provider run、artifact、Codex JSONL 和大量中间输出，但主 transcript 只看到最终 `task_progress`。
- 用户在主对话流里看不到“Codex 跑过哪几轮、产出了什么、为什么被截断或移交”，只能去 details / SQLite / JSONL 排查。
- 这会造成误读：页面像是 Codex 没返回，实际只是 worker round 没被投影到 session message。

设计原则：

- 主对话流应该展示每一轮 worker execution 的人可读锚点。
- 主对话流不应默认塞入 1MB+ raw output、stdout、JSONL 或 ANSI log。
- artifact 仍是一等事实表，session message 只是面向用户的 projection。
- `worker_round` projection 必须适用于 Codex、DeepSeek、Kimi、Claude、OpenClaw 等所有 worker，而不是 Codex 特例。

后端投影合同：

- `ControlNodeGraph` 在写入 worker artifact 后，同步追加一条 `session_messages`。
- 推荐字段：
  - `role=assistant`
  - `message_type=worker_round`
  - `task_id=<current task>`
  - `content=<压缩摘要>`
- `content` 示例：
  - `Codex 执行了一轮，状态 partial_timeout，已产出部分结果，耗时 15m，等待继续或移交。`
  - `DeepSeek 执行了一轮，状态 completed，产出 535 字结果。`
  - `Kimi 执行失败，状态 failed，原因 provider unavailable。`
- `metadata` 至少包含：
  - `worker_id`
  - `execution_status`
  - `artifact_id`
  - `agent_run_id`
  - `provider_id`
  - `provider_thread_id`
  - `provider_session_id`
  - `provider_turn_status`
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_run_dir`
  - `provider_event_log_path`
  - `provider_last_message_path`
  - `provider_run_metadata_path`
  - `duration_ms`
  - `output_chars`
  - `output_preview`
  - `partial_output`
  - `truncated`

去重要求：

- 同一个 `artifact_id` 只投影一次 `worker_round` message。
- retry / handoff 后的新 worker round 必须各自投影，因为这是用户理解执行轨迹的关键。
- `task_progress / task_result` 继续保留，用于总结任务级状态；`worker_round` 用于解释每轮 worker 执行。

前端呈现合同：

- `renderMessageCard` 增加 `message_type=worker_round` 卡片样式。
- collapsed 默认显示：
  - worker 名称
  - 状态：`completed / failed / timeout / partial_timeout / cancelled`
  - 耗时
  - 输出短摘要
  - provider thread / run id 的短展示
- expanded 显示：
  - `output_preview`
  - provider run 文件路径
  - artifact id
  - recovery 建议动作
- 对 `partial_timeout` 特殊显示：
  - 文案用“部分结果”而不是“失败”
  - 提供“继续 Codex thread”和“手动移交”操作入口
  - 不默认显示为红色致命错误

验收标准：

- 真实任务中每次 worker round 后，主 transcript 能看到一条 `worker_round` 卡。
- Codex 有输出但被截断时，主 transcript 显示 `partial_timeout` 和部分结果摘要。
- 用户不打开 details，也能知道 Codex 跑过、耗时多久、输出在哪里、下一步是继续还是移交。
- 大输出不会默认渲染进 DOM；展开预览必须有长度上限，并指向 provider run 文件。

落地记录（2026-05-31）：

- `ControlNodeGraph` 已在 worker artifact 写入后同步投影 `message_type=worker_round` 的 session message。
- Codex app-server executor 已改为活动超时 + 最大硬上限，并在有输出的超时场景标记 `partial_timeout`。
- Dialogue 主 transcript 已支持 `worker_round` 卡片、provider run 路径摘要、`partial_timeout` 的“部分结果”文案。
- `partial_timeout` worker round 卡片已提供“继续 Codex thread”和“手动移交”入口：继续入口触发当前 task 的 `POST /continue`，移交入口打开 details 的 handoff 控件。
- `SessionService.listMessages(session, task_id)` 已补历史回填：当旧任务已有 `worker_output/worker_round` artifact 但缺少 `worker_round` session message 时，读取消息流会按 `artifact_id` 去重补投影，保证旧 Codex/DeepSeek 回合也进入主 transcript。
- `SessionService.listMessages(session)` 的无 task 过滤主消息流也已补同一套历史回填，避免 `/dialogue/` 首屏只调用 `GET /api/v1/sessions/{id}/messages?limit=...` 时漏掉 Codex worker round。
- `worker_round` session message 已做 protocol trace payload 收敛：主消息流只保留 `provider_protocol_trace_count` 与最多 20 条 `provider_protocol_trace_preview`，完整 `provider_protocol_trace` 仍留在 worker artifact / live flow / provider run 文件里，避免 transcript 首屏 payload 膨胀。
- `task_progress / task_result` 的 provider diagnostics 也已采用同一套 trace 摘要策略，避免 assistant lifecycle 回执绕过 `worker_round` 的 payload 收敛约束。
- `/v1/chat/completions` façade 的 `agentcloud.reply_source` 已识别 `worker_round`，避免 Codex round 被降级成泛化 `task_state`。
- Dialogue façade 反馈 helper 已将 `worker_round` 分类成“执行回合已更新”，toast / inline / latest badge 不再落回“任务已发布”。
- 真实回归样本 `session_45e4fe12b765435d / task_e59573c1306e4e74` 已验证：session 主消息流返回 `worker_round=4`，其中 Codex 回合 `codex_worker_round=2`，且 `full_trace_messages=0`。

### 3.5 Inspector 继续当 secondary surface

保留这些能力，但不作为主阅读路径：

- route
- judgment
- experiment summary
- tool trace
- mounted context
- raw continuity artifacts

---

## 4. 不要改的边界

这次是 UI 壳层重排，不是底层 control-plane 重写。

因此不建议一起动的东西：

- `/api/v1/tasks/*` 的 control-plane 语义
- `/v1/chat/completions` 的 continuity contract
- packet / checkpoint / judgment 的对象结构
- acceptance runbook 的 A-H 业务定义

也就是说：

- UI 可以更像 `codex`
- 但 backend 仍然是 `agent-cloud-harness`

---

## 5. Puppeteer-core 验证应该怎么分层

### 5.1 第一层：壳层 / 视觉结构验证

这层由 `scripts/screenshot.js` 承担，但它需要升级，不应停留在“打开页面并截图”。

建议把 `scripts/screenshot.js` 收成：

- 访问 `/dialogue/`
- 可选访问 `/dialogue/#facade=responses`
- 输出截图
- 同时输出一份结构化 layout report，例如：
  - rail 是否存在
  - transcript 主区是否占主宽度
  - details 是否默认关闭或非主列
  - composer 是否只有一个主输入区
  - 高级参数是否默认折叠

这层要回答的问题是：

- “页面像不像 chat shell”
- 而不是“业务是否端到端成功”

### 5.2 第二层：前端业务交互 smoke

这层不建议继续依赖当前 `scripts/test-interaction.js` 的旧 selector。

当前更合理的落地是：

1. 保留 `scripts/test-interaction.js` 作为旧探索脚本，不再把它当作主验证入口
2. 由新的 `scripts/dialogue-business-smoke.js` 承担轻量前端业务 smoke

这层当前目标应覆盖这些真实交互路径：

- 创建 session
- `message_only` 发送
- `task_required + auto_start=false` 创建 manual-start task
- 选中 task 后继续 note attach
- 生成 follow-up
- 校验当前 selected task / hash / inline receipt 是否同步

这层回答的问题是：

- “前端业务交互是否仍然能跑通”

### 5.3 第三层：acceptance / continuity 证据

这层已经由现有 acceptance 工具链承担：

- `Run-DialogueBrowserAcceptanceProbe.ps1`
- `dialogue-browser-acceptance-probe-runner.cjs`
- `DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`

它们更适合验证：

- façade continuity contract
- `chat` / `responses` richer browser path
- stream fallback
- acceptance record 素材

这层不应该被拿来替代“设计壳层回归测试”。

---

## 6. `scripts/screenshot.js` 的建议升级方向

当前 `scripts/screenshot.js` 的最新状态是：

- 能找到 Edge
- 能打开 `/dialogue/`
- 能输出截图
- 能输出结构化 JSON report
- 已支持三种固定 profile：
  - `desktop`
  - `narrow`
  - `responses`
- 已开始对下面这些壳层约束做显式断言：
  - transcript-first 标题仍在
  - composer advanced 默认折叠
  - workspace 不应比 details 更弱
  - rail 必须保持 secondary，不应逼近主区宽度
  - header 必须明显轻于 transcript 主区
  - summary 必须从属于 transcript，而不是变成横幅面板
  - thread drawer 默认必须折叠
  - inspector 折叠卡默认不应大面积展开
  - `responses` profile 应正确带 `#facade=responses`

但它仍然不是业务交互 smoke，还不应承担：

- 创建 session
- 发消息 / 发任务
- note attach
- follow-up
- selected task / hash / inline receipt 同步校验

也就是说，它现在已经从“只截图”升级成了壳层验证器，但仍然只负责 shell，不负责前端业务流程。

当前最新的 `18328` 样本已经说明，这些更强的 shell 断言是有价值的：

- `rail stays secondary`
- `header stays lighter than transcript`
- `summary stays subordinate to transcript`
- `thread drawer collapsed by default`

这样后续继续改 `/dialogue/` 时，不会又退回“只靠截图肉眼判断像不像 chat shell”。

与它配套的第二层脚本现在已经有了：

- `scripts/dialogue-business-smoke.js`

它当前只覆盖最稳的几条主路径：

- 创建 session
- `message_only` 发送
- `task_required + auto_start=false` 创建 manual-start task
- `continue current` note attach

它不替代 acceptance probe，也不负责 richer continuity contract。

建议继续把它保持成这三种固定 profile：

1. `shell-desktop`
2. `shell-narrow`
3. `shell-responses`

三种固定 profile，而不是只拍一张默认截图。

当前 shell screenshot 可直接运行：

```powershell
node .\scripts\screenshot.js
```

或：

```powershell
node .\scripts\screenshot.js --profile desktop,narrow,responses
```

当前业务 smoke 可直接运行：

```powershell
node .\scripts\dialogue-business-smoke.js
```

基于 `STARTUP_GUIDE.md` 当前推荐的隔离数据库启动链，当前真实状态已经推进到最新一轮：

- 先完成构建，再使用 `scripts/Run-HarnessWithJava21.ps1` + `-Ddb.path=.tmp\\dialogue-smoke-18328.db` 启动 fresh 隔离实例
- `scripts/screenshot.js` 已在 `desktop / narrow / responses` 三个 profile 下通过壳层断言
- `scripts/dialogue-business-smoke.js` 已在同一 fresh 隔离实例下通过 light business smoke，覆盖：
  - create session
  - `message_only`
  - manual-start task
  - continue-current note
- 对应产物示例：
- `.tmp/dialogue-shell-report-18328.json`
- `.tmp/dialogue-business-smoke-18328.json`
  - `.tmp/dialogue-shell-screens/dialogue-shell-desktop.png`
  - `.tmp/dialogue-shell-screens/dialogue-shell-narrow.png`
  - `.tmp/dialogue-shell-screens/dialogue-shell-responses.png`

基于同样的隔离启动链，第二轮 codex 风格壳层收口已经真实落地到 `/dialogue/`：

- 中间主区进一步收成 transcript-first shell
- header 更薄，默认文案更接近 thread/chat，而不是 control-plane 工作台
- composer 继续收成单底部聊天框主路径，但保留必要的 task publish seam
- 右侧 task details 继续当 secondary surface，不抢第一屏

对应最新 fresh 绿灯样本是：

- `http://localhost:18328`
- `.tmp/dialogue-shell-report-18328.json`
- `.tmp/dialogue-business-smoke-18328.json`

其中 `18328` 的 shell validator 还额外锁住了几条更强的断言：

- `transcript dominates composer vertically`
- `rail stays secondary`
- `header stays lighter than transcript`
- `summary stays subordinate to transcript`
- `default dialogue shell does not auto-select task`
- `session-scoped shell keeps task-only composer actions hidden`
- `session-scoped shell keeps composer context hidden`
- `default shell keeps details folded or lightweight`
- `details=open` 在 desktop / responses 下会真实显示右侧 details panel，而不是只改 hash/state

也就是窄屏下 transcript 主区必须明显高于 composer，而不是再次退化回“底部表单比聊天流更高”。

在 `18328` 这轮 fresh 样本下，最近几轮 codex 风格壳层收口已经真实落地：

- 左侧 rail 宽度、卡片密度和顶部文案已收成更像真正的 recent thread rail
- header 进一步变薄，details 入口弱化成次级动作
- transcript 继续保持第一视觉中心
- bottom composer 更接近单聊天框主路径
- 窄屏 grid 已修正，不再出现 rail / workspace 并排挤压
- 顶部状态 pills 进一步降噪，不再像小型控制台栏
- details header 进一步弱化成真正的 secondary panel，而不是工作台主标题
- 右侧 details panel 不再停留在“逻辑上 open / 视觉上不可见”的旧缺口；`details=open` 现在会真正拉出 inspector 列

这轮没有改变 `/dialogue/` 的业务路径，只继续收壳层和默认视觉密度。

随后第三轮默认密度收口也已经在同一条 `/dialogue/` 壳层线上落地：

- `message summary` 再次收薄，弱化为更像 transcript 顶部导读，而不是横幅面板

第三轮默认密度收口也已经继续落地：

- `message summary` 再次收薄，弱化成 transcript 顶部导读，而不是横幅面板
- composer mode/meta 条继续压薄，默认更像底部 chat tray，而不是控制条
- composer footer 只保留最少 session/task context，不再像小型控制说明区
- session rail 卡片更接近 thread preview，而不是小型任务摘要卡
- details header copy 与顶部 focus/status pills 进一步减重，继续拉开 transcript 与 secondary surface 的主次关系
- details panel 本身也更像悬浮次级面：header 更薄、正文卡片更轻、默认块感更弱
- sidebar 顶部与“新建 thread”区也继续压薄；composer 头部和辅助说明继续减高，更接近单聊天壳层
- 最新这一轮又继续压了一层默认高度：session card 更接近“标题 + 一行 preview + 时间”，composer footer 的 session/task context 更轻，details header 与 overview/action 区也进一步减高
- 当前 empty-state desktop screenshot 也已经能直接看见这轮变化：session rail 更像 thread list，composer 下半区和 details 上半区都不再像小型工作台
- 同一轮里，composer 的空态次级 task-only 动作也被继续下沉：没有 task 上下文时不再长期露出 `附着到当前任务 / 生成 follow-up / 清除关联`
- 最新这一轮又进一步收的是 bottom edge：主发送按钮和 ghost 按钮尺寸更轻，高级参数 summary 更薄，默认更像 chat input footer，而不是操作台尾栏
- 同一轮里，empty-state 下 footer 左侧的 session/task 上下文块也默认隐藏了；只有进入 task 上下文或 closed session 时才恢复，主发送路径更接近单聊天输入器
- 最新这一轮又继续压了一层 task-state footer：在 task / follow-up 上下文下，message composer 的底部辅助 `messageHint` 默认不再重复出现；task-state footer 只保留更短的一行 task context
- 紧接着这轮又继续把 thread rail 和 details 默认密度再压轻一层：session card 现在默认只保留时间、标题和一行 preview，不再常驻 status/task badge；details 的 header、overview/action 区也继续减高，更接近真正的次级 side surface
- 最新这一轮则继续压 transcript 顶部的辅助层：`筛选` drawer 的 summary 和 chip 区更薄，message summary 主卡也收成更窄的上下文卡片，避免它再次长成横幅面板
- 紧接着这轮又继续压 composer 下半区：mode/meta 提示更轻，高级参数 summary 更薄，空态默认文案也进一步收成更像单聊天输入器的口径
- 最新这一轮继续压的是 details 默认密度：header copy 更短，overview/action 区和 section card 的默认高度进一步减小，更接近真正的次级 side surface
- 紧接着这轮又继续把 rail 与 details 的解释性文案收短了一层：recent rail 只保留极短提示句；details header、更多操作、mounted context、route/judgment、experiment、artifact、tool trace 的说明文案都继续减短
- 最新这一轮又继续压的是 workspace subbar 和 composer head：`Session Transcript` 下方辅助文案更短，composer lede 也收成更像单聊天输入器的默认提示，输入框最小高度进一步下降

当前这轮之后，在 fresh `18328` 隔离实例上，`desktop / narrow / responses` shell screenshot 和 `dialogue-business-smoke.js` 仍保持绿色；但这仍然只代表当前壳层收口仍在推进，不等于整套 codex 风格 UI 改造已经完成。

最新这一轮又继续压的是 transcript 下方的 task timeline：

- transcript 下方的 `任务上下文` 继续收口成更轻的 `任务轨迹` 抽屉
- drawer header copy 更短，强调“按需查看”
- chain headline 从 `tasks` 改成更轻的 `rounds`
- chain head 默认不再并排常驻 `control node / start_mode`
- task bubble、round rail、time、signals、foot 整体继续减小默认密度

这轮之后，`scripts/screenshot.js` 和 `scripts/dialogue-business-smoke.js` 在 `18328` 隔离实例上仍保持绿色；说明 transcript-first 主区和 light business path 没有被这轮 task timeline 收口打坏。

紧接着这轮又继续压的是左侧 recent rail：

- rail 顶部 copy 收成更短的一句
- `新会话` 明确收成 `新 thread`
- `Recent` 区默认不再强调那段说明文案
- health pill、surface switch、session card 的默认高度和字号继续减小

这轮之后，左 rail 更接近纯 thread list，而不是带说明区和小卡片光泽的 mini 工作台；`18328` 上的 shell screenshot / light business smoke 仍保持绿色。

最新这一轮 fresh `18328` 还额外确认了四件事：

- 更窄的 `thread rail + details` 列宽 (`196px / 292px`) 已经通过 fresh 运行时真实生效，而不是只停留在源码里
- desktop shell 的 transcript / composer 高度当前是 `575px / 284px`，说明这轮 composer 再减重后 transcript-first 仍然成立
- 右侧 details 的 header、empty state、overview/action/card 默认高度又继续压低一层，但现有 shell contract 没被打坏
- 右侧 details 默认密度又继续压低一层：header/empty state/overview/action/card padding 更小，更接近按需查看的 side surface
- transcript 顶部筛选和 composer 下半区也继续压低默认密度：`message-panel__filters` 的摘要更短更薄，composer 的 label / inline hint / mode bar / 参数 summary 更接近单聊天输入器
- `details-collapsed / sidebar-collapsed` 相关列宽也已经和这轮更窄主布局保持一致，不再残留旧的 `220px / 360px`
- 时间显示问题已经收口：后端 `created_at / updated_at` 真实可能返回 epoch seconds 浮点数；前端若直接 `new Date(value)`，会把 `1778640974.603...` 误渲染成错误日期。`/dialogue/` 与 `/console/` 现在都统一先走 `normalizeTimestampValue(...) / timestampMs(...)`，避免两个前端对同一任务显示不同时间

这轮收口靠的是三件基础设施修正，而不是 `/dialogue/` 产品语义本身发生了根本变化：

- Puppeteer 打开 `/dialogue/` 改成先等 `/api/v1/health`，再显式等 shell，而不是依赖脆弱的 `networkidle2`
- `scripts/Run-HarnessWithJava21.ps1 -Background` 改成先复制 runtime jar，再启动后台实例，避免本机重建 `target\\*.jar` 时把运行中的静态资源链打坏；此前这条问题已真实表现为 `WebConsoleHandler` 里的 `ZipFile invalid LOC header`
- `scripts/Run-HarnessWithJava21.ps1 -Background` 现在会在端口已被占用时直接失败，避免 Puppeteer 误打到旧实例
- `src/main/resources/web/dialogue/app.js` 已收掉当前壳层下的旧 DOM 节点直接访问，避免初始化阶段再因缺失节点触发 `Cannot set properties of undefined`
- `scripts/dialogue-business-smoke.js` 已改成 DOM 级 checkbox 辅助，而不是依赖当前环境不可用的 `isChecked()/check()/uncheck()` 调用

仍需保留一个真实边界：

- `18264` 这类 fresh 实例曾真实出现 `NoClassDefFoundError: com/fasterxml/jackson/databind/PropertyNamingStrategies`
- 当前更应把它归类为 build/start 并行触发的本机时序性启动故障，而不是 `/dialogue/` 产品功能本身的回归

当前更准确的结论是：

- `shell/layout validation` 已有 fresh 隔离实例下的真实绿灯
- `light business smoke` 已有 fresh 隔离实例下的真实绿灯
- 第三轮 codex 风格壳层收口已经落到真实 `/dialogue/` HTML/CSS/JS，而不是仍停留在纯文档阶段
- 更进一步的 details / status surface 继续收口仍未开始
- 但仍不应把它写成“前端业务功能已完全验证通过”，因为 richer continuity / acceptance 仍需独立 acceptance 工具链

最新这一轮继续补几个和长任务恢复直接相关的 UI 缺口：

- `/dialogue/` 的 task action plan 接入 `recover`，`waiting_human / waiting / human_gate / failed` 优先显示“自动恢复”，避免用户只能在 `resume / continue / handoff` 之间猜
- `recover` 动作走 `POST /api/v1/tasks/{id}/recover?async=true`，默认 `mode=auto`，和后端最近失败任务恢复入口保持一致，同时避免浏览器请求被真实 worker 长执行拖住
- task detail 额外读取 `/api/v1/tasks/{id}/recovery_jobs?limit=5`，在 overview 中显示最近异步恢复 job 的 `status / request_id / action / execution_mode / target_worker / error`
- 新增浏览器验收脚本 `scripts/recovery-job-ui-probe.js`：用真实 session/task 打开 `/dialogue/` 或 `/console/`，拦截 recover/recovery_jobs 响应，断言按钮请求包含 `recover?async=true` 且详情区能看到 `Recovery Job` 与 `request_id`
- `message-panel__body--stream-only` 下的短 transcript 改成底部栈布局：`message-stream` 使用 `margin-top:auto`，剩余空白优先留在消息组上方，消息组、折叠任务轨迹和 composer 之间保持紧凑
- 这轮只收敛已知空白 seam 与恢复动作入口，不改变 transcript-first 主结构和 details panel 的展开语义

紧接着这一轮补的是 `/console/` 与 `/dialogue/` 的时间口径对齐：

- `/console/` 的 session/task 排序不再直接 `new Date(epochSeconds)`，改用和 `/dialogue/` 一致的 `timestampMs(...)`
- `/console/` 的 `formatTime(...)` 统一先归一化 epoch seconds / epoch milliseconds / ISO string，再渲染
- 这能避免同一个 task 在 Dialogue 显示当前日期、Console 却显示成 1970 年附近错误日期，减少 operator 对“最近任务/最近失败任务”的误判
- 验收入口：`node --test src/test/js/console-time-normalization.test.mjs`

---

## 7. 文档落地建议

建议这样落：

### 7.1 这份文档作为 `/dialogue/` UI 壳层专项方案

职责：

- 解释为什么参考 `codex`
- 解释借什么、不借什么
- 定义 Puppeteer 分层验证

### 7.2 主方案只保留短链接

`docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md` 继续作为大方案文档，但不要继续把 `codex` 壳层设计细节塞进去；只在相关章节引用这份专项方案。

### 7.3 `WEB_CONSOLE.md` 只描述当前状态与验证入口

`docs/WEB_CONSOLE.md` 不需要变成设计稿，只需补一句：

- `/dialogue/` 的下一步壳层收口参考 `DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
- 壳层截图与业务 smoke 分别由 `scripts/screenshot.js` 和 `scripts/dialogue-business-smoke.js` 承担
- 如果要跑这两层验证，启动方式优先参考 `STARTUP_GUIDE.md` 里隔离 DB 的 `/dialogue/` 本地验证小节

### 7.4 单独维护 UI 验证 runbook

为了避免 `/dialogue/` 的启动、截图、light business smoke、acceptance richer path 继续散在多份文档里，建议把实际执行顺序固定在：

- `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md`

它负责：

- `/dialogue/` UI 的启动顺序
- `screenshot.js` / `dialogue-business-smoke.js` 的职责边界
- 当前真实绿灯与未收口项

---

## 8. 建议分阶段实施

### Phase A：文档冻结参考边界

先完成：

- 这份专项方案
- 主方案短链接
- `WEB_CONSOLE.md` 的验证入口说明

### Phase B：只改壳层，不碰 continuity contract

优先改：

- header
- transcript 默认密度
- composer 默认入口
- inspector 默认打开方式

暂时不动：

- `/v1/chat/completions` contract
- task / packet / judgment semantics

### Phase C：补 Puppeteer 分层验证

至少做两件事：

- 升级 `scripts/screenshot.js`
- 以 `scripts/dialogue-business-smoke.js` 取代 `scripts/test-interaction.js` 作为主业务 smoke 入口

### Phase D：再回到 acceptance 证据链

壳层改完后，再重新跑：

- browser acceptance probe
- `/dialogue/` 人工验收记录

---

## 9. 一句话方案结论

这次 `/dialogue/` 不该继续往“任务工作台”加控件，而应该：

- **照着 Codex 抄壳层**
- **保留 Harness 的 continuity substrate**
- **把 Puppeteer 验证拆成 shell screenshot 和业务 smoke 两层**

这样既能把页面做得更像真正的对话台，也不会把现在已经稳定的 task-native 能力链打坏。
