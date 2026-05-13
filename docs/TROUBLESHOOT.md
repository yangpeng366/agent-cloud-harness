# Troubleshoot

## 1. Gotchas — 已知坑点

### G01: 控制动作接口处于 GET/POST 双兼容期

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java`, `SessionHandler.java`
- **现象**: 任务和 session 控制动作已经有正式 `POST` 接口，但历史 `GET` 兼容路径仍在。
- **风险**: 外部继续调用旧 `GET` 时，仍可能被预取或缓存层误触发。
- **规避方式**: 新接入统一改用 `POST /api/v1/tasks/{id}/pause|resume|continue|escalate|handoff` 和 `POST /api/v1/sessions/{id}/pause|resume|close`；仅把 `GET` 当作过渡兼容。

### G02: 任务列表过滤键名存在双写兼容期

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java`
- **现象**: 当前同时兼容 `?state=active` 和 `?status=active`。
- **风险**: SDK 或外部脚本可能继续分叉。
- **规避方式**: 对外文档优先统一成 `status`，`state` 仅保留为历史兼容。

### G03: tool-aware 执行器已支持多步工具链，但仍有单轮上限

- **位置**: `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`
- **现象**: 复杂任务已经能在单轮内走“检索 -> 读取 -> 写回”这类最多 3 步工具链，但如果命中 guard 或达到上限，仍会提前收敛。
- **原因**: 当前上限是 `MAX_TOOL_ROUNDS = 3`，并带 `repeated_tool_guard`、`no_progress_guard`、`max_tool_rounds_reached` 等保护。
- **规避方式**: 把它当成“最小可观测工具链”，不要假设它已经是无限多轮 agent loop；排查时看 `/tool_trace` 和最终 `guard` 原因。

### G04: 路由结果现在受 learning memory 和 model mode 共同影响

- **位置**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **现象**: worker readiness 看起来正常，但最终没有命中你以为的“最强”或“最熟悉” worker。
- **原因**: 当前路由不再只看 capability/readiness，还会同时考虑 `model_mode`、orchestration stage、learning memory preferred worker hint，以及候选集收窄后的 `fallback_reason`。
- **规避方式**: 排查时不要只看 `assigned_worker`；同时查看 `/api/v1/tasks/{id}/select_worker`、`/runtime_context`、`/judgment_trace`、`/live_flow` 里的 route metadata。

### G05: 根路径不再是“空白页”，而是前端入口

- **位置**: `src/main/java/com/agentcloud/server/NioHttpServer.java`
- **现象**: 访问 `http://localhost:8080/` 会直接跳到 `/dialogue/`。
- **规避方式**: 调试前端问题时，明确区分 `/dialogue/` 和 `/console/` 两套页面；API 调试仍走 `/api/v1/*`。

## 2. 常见错误场景

### 2.0 worker 执行失败后，什么时候该自动切 worker，什么时候该停到人工确认

- **位置**: `ControlNodeGraph.continue/handoff/human_gate`, `/dialogue/`
- **现象**: 当前 task 已经创建，但执行回执里出现 worker/provider 失败，例如 thread 丢失、provider session 失效、不可读错误输出。
- **不要直接拍脑袋处理**:
  - 不是所有失败都适合自动切 worker
  - 也不是所有失败都应该让用户手工选
- **统一策略**: 参考 `docs/WORKER_FAILURE_RECOVERY_POLICY.md`
  - `worker_runtime_transient`：先同 worker 冷重试，再自动 handoff 一次
  - `task_environment_blocked`：默认不要自动切，优先 `human_gate`
  - `partial_result_or_quality_risk`：默认人工确认

### 2.0.1 `/dialogue/` 里为什么“聊天”现在会直接进入 task，而不是只记一条 session note

- **当前位置**: `/dialogue/` 主 composer, `ChatFacadeService`
- **当前口径**:
  1. `/dialogue/` 的默认“聊天”现在等于 `task_auto`
  2. 如果当前上下文里已经有 task，这轮输入会作为该 task 的 continuity turn
  3. 如果当前只有 session，没有 task，这轮输入会 materialize 成新 task
- **为什么这样改**:
  1. 真实项目场景里，用户通常希望“聊天即推进任务”，而不是先收到一条“已记录到会话”的空回执
  2. `message_only` 仍保留在 façade/API 层，但不再是 `/dialogue/` 主路径

### 2.0.1.a `task_auto` 后端已经建了 task，但页面还没切到这个 task

- **典型表现**:
  1. `/v1/chat/completions` 这轮用户输入已经被写成 `task_brief`
  2. `/api/v1/sessions/{id}/tasks` 也已经出现新 task
  3. 但 `/dialogue/` 当前 hash 还停在只有 `session=`，没有及时带上新的 `task=`
- **当前结论**:
  1. 这不表示后端没 materialize task
  2. 更准确地说，这是前端没有及时追上新 task 选择
  3. 若 provider 执行链较长、请求尚未返回，这个 gap 会更明显：页面可能一直停在 session-only shell，直到 façade 响应结束
- **更合理的目标行为**:
  1. 页面先显示 `task_pending` 回执
  2. 然后在请求未结束时，短轮询同 session 的 task 列表
  3. 一旦发现新的 unseen task，就立刻把当前 selection/hash 追过去
- **排查顺序**:
  1. 先查 `sessions/{id}/messages`，确认这一轮是不是 `task_brief`
  2. 再查 `sessions/{id}/tasks`，确认新 task 是否已经存在
  3. 只有这两步都没结果时，才继续怀疑 `task_auto` 语义失效

### 2.0.1.b `继续当前任务` 看起来像生效了，但其实又新建了一条 task

- **典型表现**:
  1. 页面上已经勾了“继续当前任务”
  2. 但提交后 `sessions/{id}/tasks` 里出现的是一条新 task
  3. 原 task 的 `live_flow` / related messages 里只看到一条 `continuity_scope=session` 的 `task_brief`
- **当前结论**:
  1. 这不表示后端不会继续当前任务
  2. 更准确地说，是前端请求没有把当前 task continuity 正确绑定进 façade 请求
  3. 因此前端体感像“继续当前任务”，实际落库却仍是“materialize 一条新 task”
- **更合理的目标行为**:
  1. 只要当前已选中 task，且用户勾了“继续当前任务”
  2. 这轮输入就应该绑定到当前 task
  3. 即使页面表面仍使用 `task_required` 风格文案，也不应再 materialize 新 task
- **排查顺序**:
  1. 先查 `sessions/{id}/tasks`，确认是否真的新增了 task
  2. 再查原 task 的 `live_flow.related_messages`，确认这轮输入是不是只作为 `continuity_scope=session` 的 `task_brief`
  3. 最后再回看前端请求分流，重点看 `continueCurrentTaskId / task_id / task_mode`

### 2.0.2 `/dialogue/` 里出现乱码，但 details/live_flow 也没有真正结果，应该怎么理解

- **现象**: 主聊天流里出现类似 `����: ...` 的 `task_progress / task_result`
- **不要误判**:
  1. 这通常不是前端自己编码坏了
  2. 更常见的是当前 worker/provider 返回了不可读失败输出，前端只是把摘要投影出来
- **当前 UI 原则**:
  1. 主聊天流优先显示可读失败摘要
  2. 原始失败 trace 下沉到 `details / live_flow / judgment_trace`
- **当前编码口径**:
  1. 仓库文件、HTTP、前端静态资源继续严格使用 UTF-8
  2. 只有外部进程输出（shell/cmd/powershell/git/provider-native cli）走“UTF-8 优先 + 自适应兜底”
  3. 详细策略见 `docs/TEXT_ENCODING_COMPATIBILITY_PLAN.md`

### 2.1 服务启动失败，提示无法打开数据库

- **典型表现**: 启动阶段抛出 SQLite 或文件权限相关异常。
- **可能原因**:
  1. `${user.home}\\.agentcloud\\` 不可写
  2. `schema.sql` 未打入资源包
- **排查步骤**:
  1. 确认 `${user.home}\\.agentcloud\\` 目录存在且可写。
  2. 检查打包后的 JAR 是否包含 `schema.sql`。
  3. 查看控制台或 `server.err.log`。

### 2.2 关键迁移后 packet / checkpoint 不符合预期

- **典型表现**: `/pause`、`/escalate` 或 `/handoff` 返回成功，但 `resume_packets` / `checkpoints` 中固化内容和期望不一致。
- **可能原因**:
  1. 任务本身缺少足够的 `decision/artifact/event` 轨迹，packet 只能生成空摘要
  2. 调用的是 `handoff_packet` 预览接口，而不是正式 `/handoff`
  3. 看的是旧 packet，没有刷新当前 task 最新记录
- **排查步骤**:
  1. 先查 `resume_packets` 是否新增记录。
  2. 再查 `checkpoints` 表中的 `checkpoint_type` 是否为 `pause_before`、`escalate_before`、`handoff_before` 或 `halt_before`。
  3. 再看 `/api/v1/tasks/{id}/runtime_context` 与 `/judgment_trace` 是否已有输入轨迹。

### 2.3 Worker readiness 正常，但路由结果不符合预期

- **典型表现**: 任务分配给 fallback worker，或没有按预期命中某个 planner/executor。
- **可能原因**:
  1. 任务 `metadata.task_type` 缺失，路由回落到 `general`
  2. worker 虽 `ready=true`，但 capability 不包含目标任务类型
  3. `model_mode=small_only/strong_only/orchestrated` 收窄了候选 tier
  4. learning memory preferred worker hint 未命中当前 tier，因而触发 fallback
  5. worker 声明了 `git/shell/powershell/cmd`，但 `/readiness` 里的 `tool:<name>` 检查失败，导致它被排除在 ready 候选集之外
- **排查步骤**:
  1. 查看任务 `metadata_json` 中的 `task_type`、`model_mode`。
  2. 调用 `/api/v1/workers` 和 `/api/v1/workers/{id}/readiness`，重点看 `metadata.host_tool_availability` 与 `checks.tool:<name>`。
  3. 再查 `/api/v1/tasks/{id}/select_worker`，看 `route_source/why_selected/fallback_reason`。
  4. 如果是 orchestrated 流程，再查 `/api/v1/tasks/{id}/live_flow` 看当前 stage。

### 2.4 `/dialogue/` 里消息已写入，但页面不显示

- **典型表现**: `POST /api/v1/sessions/{id}/messages` 返回成功，但 `/dialogue/` 仍然为空。
- **可能原因**:
  1. 当前页面选中的 `session` 不是消息实际写入的那个 session
  2. 消息绑定了 `task_id`，但当前右侧查看的是另一个 task
  3. 前端 URL hash 仍锁在旧的 `session/task`
  4. 页面筛选器把目标消息过滤掉了
- **排查步骤**:
  1. 先直接调用 `GET /api/v1/sessions/{id}/messages?limit=20`，确认消息已落库。
  2. 如果看顶部消息流，先把过滤器切回 `all + all`，避免被 `assistant/system` 或 `task-only/session-only` 过滤掉。
  3. 如果看 `Related Messages`，优先调用 `GET /api/v1/tasks/{taskId}/live_flow?limit=10`，确认 `related_messages` 里是否已经带出目标消息。
  4. 若消息是 session 级普通连续聊天消息，检查 `related_messages[*].metadata.continuity_scope` 是否为 `session`；这类消息现在会并入当前 task 的 related message surface，但不会带 `task_id`。
  5. 只有在旧实例或 `live_flow.related_messages` 缺字段时，才再回退检查 `GET /api/v1/sessions/{id}/messages?task_id={taskId}`。
  6. 确认 `/dialogue/` 左侧当前选中的 session 与 API 返回的 `session_id` 一致。

### 2.4.1 任务已经 `done/failed`，为什么同一个 session 里还能继续聊天或继续发任务

- **典型表现**:
  1. 某个 task 已经进入 `done` 或 `failed`
  2. 但 `/dialogue/` 里仍然可以继续发 `user_note`
  3. 同一个 session 下还能继续创建 follow-up task
- **这是当前设计，不是 bug**:
  1. `task` 的终态只表示这一个工作单结束
  2. `session` 更接近 thread / conversation；只要 session 仍是 `active`，就允许继续聊天和继续发新任务
  3. 真正的阻断条件不是 `task.done/failed`，而是 `session.closed`
- **当前 contract**:
  1. `POST /api/v1/sessions/{id}/messages` 在 `session=active|paused` 且实现允许的前提下仍可写入
  2. `POST /api/v1/tasks` 只要 `session_id` 对应的 session 没有 `closed`，就仍可创建新任务
  3. 只有向 `closed session` 写 message / task 时，才会返回 `400 session is closed`
- **排查步骤**:
  1. 先看 `GET /api/v1/sessions/{id}`，确认 `status` 是否真的是 `closed`
  2. 不要只看当前 task 的 `status=done/failed` 就判断 session 应该不可继续
  3. 如果页面上主按钮被禁用，再看 `/dialogue/` composer 是否提示 `closed session`

### 2.4.2 `/dialogue/` 里“发布任务/发送消息”像点不了

- **典型表现**:
  1. composer 主按钮是灰的，或者点击后看起来没有响应
  2. 当前 URL 还停在某个旧 session 上，例如 `#session=...`
- **可能原因**:
  1. 当前选中的 session 已经是 `closed`
  2. 输入为空
  3. 当前在 `follow-up` 模式，但没有有效父任务
- **当前恢复路径**:
  1. `/dialogue/` 现在会在 closed-session 场景明确显示 warning，而不再 silent fail
  2. composer 下方会直接给出 `新建会话并继续` 按钮
  3. 点击后会创建一个新 session，并保留当前输入草稿，继续发送
- **排查步骤**:
  1. 先看 composer inline warning 是否提示 `closed session`
  2. 再看左侧 session rail 当前高亮的是不是旧的已关闭 session
  3. 如果只是想继续聊，不需要 reopen 已完成 task，直接点 `新建会话并继续`

### 2.5 `/tool_trace` 为空，或 `live_flow` 中没有工具轨迹

- **典型表现**: 任务执行过后，`/api/v1/tasks/{id}/tool_trace` 返回空数组。
- **可能原因**:
  1. 任务没有命中带工具能力的 worker，而是走了 `DefaultWorkerExecutor`
  2. worker 注册时 `suggest_only=true` 或 `tool_capabilities=[]`
  3. tool planning 判定 `needs_tool=false`
  4. 在多步工具链里提早命中 guard
- **排查步骤**:
  1. 先查 `/api/v1/tasks/{id}`，确认 `assigned_worker`。
  2. 再查 `/api/v1/workers`，确认该 worker 的 `tool_capabilities`、`tool_scope`、`suggest_only`。
  3. 再查 `/api/v1/tasks/{id}/live_flow?limit=10`，确认是否已有 judgment 但没有 `tool_invocations`。
  4. 查看日志中的 tool planning / tool invocation 相关输出。

### 2.6 本地文档试点任务没有命中 `kimi-local-doc`

- **典型表现**: 明明注册了 `kimi-local-doc`，但任务仍然分给了内置 `doc` worker。
- **可能原因**:
  1. 任务使用的是 `task_type=doc`，而不是试点专用 `task_type=local_doc`
  2. `kimi-local-doc` 没带 `local_doc` capability
  3. worker 注册时 `ready=false`
- **排查步骤**:
  1. 查看任务创建请求中的 `task_type`
  2. 查看 `/api/v1/workers` 中 `kimi-local-doc` 的 capability 列表
  3. 对照 `docs/LOCAL_DOC_WORKER_PILOT.md`

### 2.7 `mvn package` 直接报“无效的目标发行版: 21”

- **典型表现**: Maven 在编译阶段直接失败，报错类似 `invalid target release: 21`。
- **可能原因**:
  1. `JAVA_HOME` 指向 Java 8 或其他低版本 JDK
  2. `mvn -v` 显示 Maven 正运行在非 Java 21 环境
- **解决方案**: 切换到 Java 21 后再运行构建；推荐直接用仓库脚本：

```powershell
.\scripts\Test-WithJava21.ps1
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

### 2.8 Shaded JAR 能启动，但 JSON 接口报 `BufferRecyclers`

- **典型表现**:
  1. 服务启动日志正常
  2. 但首次访问 `/api/v1/health`、`/api/v1/tasks/...` 这类 JSON 接口时失败
  3. 日志里出现 `java.lang.NoClassDefFoundError: com/fasterxml/jackson/core/util/BufferRecyclers`
- **已确认根因**:
  1. 进程环境里残留了全局 `CLASSPATH`
  2. 其值指向旧的 Java 8 运行库
- **解决方案**:

```powershell
. .\scripts\Use-Java21.ps1
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

### 2.8.1 `/api/v1/health` 正常，但 `/v1/chat/completions`、`/v1/responses`、`/v1/models` 返回 `404`

- **典型表现**:
  1. `GET /api/v1/health` 返回 `status=up`
  2. 但 `POST /v1/chat/completions`、`POST /v1/responses`、`GET /v1/models` 都返回 `404 not found`
- **已确认根因**:
  1. 启动时用了旧的非 shaded JAR，产物里缺少较新的 façade 类
  2. 进程因此能启动基础 `/api/v1/*`，但不会真正注册当前源码里的 `/v1/*` façade 路由
- **解决方案**:
  1. 优先使用仓库脚本默认值启动，它现在默认指向：
     `target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`
  2. 如果手工启动，也显式使用 shaded JAR：

```powershell
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
# 或
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

- **快速验证**:
  1. 先打 `GET /v1/models`
  2. 再跑 `.\scripts\Run-ChatFacadeAcceptanceProbe.ps1 -BaseUrl http://localhost:18080`

### 2.8.2 `Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 跑通了，但 `.tmp` 下还看到 `chat-facade-acceptance*.log`

- **典型表现**:
  1. acceptance runner 返回成功 JSON
  2. 但 `.tmp` 目录里仍然能看到 `chat-facade-acceptance*.log`
- **当前真实结论**:
  1. 现行脚本在默认模式下已经不会为**新运行**继续留下日志文件
  2. 如果你看到固定名 `chat-facade-acceptance.out.log/.err.log`，或很早时间戳的 `chat-facade-acceptance-<port>-<time>.log`，通常是旧版本 runner 留下的历史文件
  3. 只有显式传 `-KeepServerLogs` 时，runner 才会保留本次运行的唯一日志文件，并在 JSON 结果里回传具体路径
- **处理方式**:
  1. 若只是排障结束后的目录清理，直接手动删除 `.tmp\chat-facade-acceptance*.log`
  2. 若需要保留本次运行日志用于定位问题，显式加 `-KeepServerLogs`
  3. 若怀疑现行脚本仍泄漏日志，先在空目录状态下重新跑一次，再对比新增文件，而不要把旧遗留日志误判成当前运行结果

### 2.8.3 `/dialogue/` 的 shell screenshot 或 `dialogue-business-smoke.js` 出现 flaky / 500 / 导航超时

- **典型表现**:
  1. 用隔离 DB 启动本地实例后，`scripts/screenshot.js` 可以稳定输出 desktop/narrow/responses 三张截图和 JSON report
  2. `scripts/dialogue-business-smoke.js` 可能在 `create session` 或页面导航阶段失败
  3. 某些情况下，`/dialogue/` 本身会返回 `500 console render failed`
- **当前真实结论**:
  1. 这不应直接判定成 `/dialogue/` 产品功能回归
  2. 这类问题历史上暴露过：
     - 等待条件过度依赖脆弱 DOM 文案
     - `puppeteer-core` 导航阶段偶发 `GET /dialogue/ net::ERR_ABORTED` / navigation timeout
  3. 还真实出现过另外一类非产品性故障：后台实例运行期间又重建 `target\\*.jar`，导致 `WebConsoleHandler` 在分发 `/dialogue/` 静态资源时抛 `ZipFile invalid LOC header (bad signature)`，页面因此返回 `500 console render failed`
  4. 当前已验证的收口手段是：
     - Puppeteer 打开 `/dialogue/` 改为先等 `/api/v1/health`，再显式等 shell，而不是依赖脆弱的 `networkidle2`
     - `scripts/Run-HarnessWithJava21.ps1 -Background` 改为先复制 runtime jar 到 `.tmp\\runtime-jars\\` 再启动
     - `scripts/Run-HarnessWithJava21.ps1 -Background` 现在会在端口已被占用时直接失败，避免验证误打到旧实例
  5. 在 fresh 隔离实例 `http://localhost:18328` 上，`scripts/screenshot.js` 与 `scripts/dialogue-business-smoke.js` 当前都已经通过；因此当前更准确的口径是“已有真实绿灯，但 richer acceptance 仍需单独工具链”，而不是“business smoke 仍未收口”
  6. 最近这轮 `/dialogue/` 还真实改动了壳层 HTML/CSS：左 rail 更像 recent thread list，header 更薄，details 入口更轻，顶部状态更弱化；如果只看到旧截图，不要把历史 `18268/18276/18282` 壳层样本误当成当前页面
  6. `18264` 这类失败当前更应优先判定为 build/start sequencing 风险：当 fresh 启动和本机重建并行时，后台 harness 曾真实报过 `NoClassDefFoundError: com/fasterxml/jackson/databind/PropertyNamingStrategies`
- **建议处理方式**:
  1. 先按 `STARTUP_GUIDE.md` 或 `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md` 用隔离 DB 起实例
  2. 先看 `scripts/screenshot.js` 是否通过
  3. 再跑 `scripts/dialogue-business-smoke.js`
  4. 如果出现 `/dialogue/` 返回 `500 console render failed`，优先检查服务端日志里是否有 `ZipFile invalid LOC header`；若有，先 fresh 重启实例，不要直接记成前端回归
  5. 即使本地两条 smoke 都通过，也不要把它当成 richer continuity / acceptance 的替代品

### 2.8.4 明明已经重新构建，但 `/dialogue/` 页面看起来还是旧的

- **典型表现**:
  1. 已经改了 `src/main/resources/web/dialogue/index.html|app.css|app.js`
  2. 也重新跑了 `mvn package` 或 `Build-WithJava21.ps1`
  3. 但浏览器里看到的 `/dialogue/` 还是旧样式
- **已确认根因**:
  1. `/dialogue/` 静态资源由 `WebConsoleHandler` 从运行时 JAR 的 classpath 读取，不是直接从源码目录读取
  2. `Run-HarnessWithJava21.ps1 -Background` 会先复制运行 JAR 到 `.tmp\runtime-jars\` 再启动
  3. 所以后续重新构建，只会生成新的 `target\*.jar`，不会热更新已经跑起来的实例
  4. 如果把“重新构建”和“fresh 后台启动”并行跑，新的后台实例也可能先复制到旧的 `target\*.jar`，表现成：
     - 源码里已经有新 CSS / 新 HTML
     - 浏览器实际拿到的 `/dialogue/app.css` 仍是旧版本
     - 页面看起来像“前端改动没生效”
  5. 当前这类问题已经在 `18328` fresh 样本上被真实对照过：必须先等 build 完成，再单独启动新实例；否则像 `thread rail + details` 列宽这类 CSS 收口，可能只停留在源码里，服务端实际返回的 `app.css` 仍是旧值
- **处理方式**:
  1. 改了 `src/main/resources/web/dialogue/*` 后，按“重新构建 + 重启 fresh 实例”处理
  2. 不要指望刷新浏览器就能看到改动
  3. 如果只是改了 `scripts/screenshot.js`、`scripts/dialogue-business-smoke.js` 这类本地验证脚本，则不需要 Maven 构建；下次直接运行脚本即可
  4. 更稳的顺序是：**先等构建完成，再单独启动 fresh 实例**

### 2.8.5 工作区源码已经改了，但 `8080` 上真实页面还是旧语义

- **典型表现**:
  1. 工作区里已经把默认聊天改成“直接推进 task”
  2. 真实页面却仍然显示：
     - `已记录到当前会话。如需进入 harness 执行，请使用 task_auto 或 task_required`
  3. 或者工作区里已经把不可读失败输出改成可读摘要
  4. 真实页面里仍然直接出现 `����...` 一类 mojibake
- **已确认根因**:
  1. 当前运行中的实例仍然在吃旧的运行 JAR
  2. 或者本机构建脚本没有真正成功执行，新的前端资源根本没被重新打包
  3. 因此“源码状态”和“真实运行页面状态”会短时间分叉
- **快速判断方式**:
  1. 先看当前 `java.exe` 进程命令行，确认它加载的是哪一份 JAR
  2. 再确认 build 是否真的完成，而不是中途因为 `mvn` 缺失或 PATH 问题直接失败
  3. 再判断这是不是页面逻辑回归
- **处理方式**:
  1. 不要先继续改前端
  2. 先修好 build / fresh start 链
  3. 确认新实例真的吃到新资源后，再复验 `/dialogue/`

### 2.8.6 `Build-WithJava21.ps1` / `Test-WithJava21.ps1` 报 `mvn` 找不到

- **典型表现**:
  1. `The term 'mvn' is not recognized`
  2. 目标 JAR 虽然还在 `target/`，但它可能只是旧构建
- **风险**:
  1. 你会误以为“已经重新构建”
  2. 实际上 fresh 实例仍在吃旧语义页面
- **处理方式**:
  1. 先确认本机是否安装 Maven
  2. 若已安装但未进 PATH，优先让仓库脚本自动解析本机 Maven 可执行路径
  3. 只有在 build 真正成功之后，再起 fresh 实例验证 `/dialogue/`

### 2.9 Windows 上 `codex` / provider CLI 明明能在 PowerShell 里找到，但任务执行时报 `CreateProcess error=2`

- **典型表现**:
  1. `/api/v1/agents/codex` 或 PowerShell `Get-Command codex -All` 看起来都能解析到 `codex`
  2. 真实任务在 `scheduler` 或首轮 planner/executor 执行时报错
  3. task summary / worker artifact 中出现 `Cannot run program "codex" ... CreateProcess error=2`
- **已确认根因**:
  1. Windows 环境里 `codex` 实际是 wrapper 脚本，如 `codex.cmd` / `codex.ps1`
  2. 旧链路里 provider detect 和真正执行没有共用同一套 launch contract
  3. PowerShell 能解析 wrapper，不代表 Java `ProcessBuilder("codex")` 也能直接启动
- **当前修复后的可观测字段**:
  1. worker execution metadata / live flow / agent run metadata 中会带：
     - `cli_binary`
     - `cli_resolved_binary`
     - `cli_launch_mode`
     - `cli_command_preview`
  2. provider detail 中会带 launch metadata：
     - `binary`
     - `configured_binary`
     - `launch_target`
     - `launch_mode`
     - `launch_available`
- **排查步骤**:
  1. 先在宿主机执行：

```powershell
Get-Command codex -All
```

  2. 再查 provider 状态：

```powershell
curl http://localhost:8080/api/v1/agents/codex
curl -X POST http://localhost:8080/api/v1/agents/codex/refresh
```

  3. 再查 worker readiness：

```powershell
curl http://localhost:8080/api/v1/workers/codex/readiness
```

  4. 如果已经执行过任务，再查：
     - `/api/v1/tasks/{id}/live_flow`
     - `/api/v1/agent_runs?task_id={id}&provider_id=codex`
  5. 确认 `cli_launch_mode` 是否为：
     - `direct`
     - `cmd_file`
     - `powershell_file`

### 2.10 `/workers/{id}/readiness` 显示 ready，但 provider 实际不可执行或未注册

- **典型表现**:
  1. worker 是内置 provider-backed worker，如 `codex` / `claude`
  2. 旧版本 `/readiness` 只看 host tool / API key / backend 标志，看起来是 `ready=true`
  3. 实际执行时 provider detect 或 CLI 启动失败
- **当前机制**:
  1. provider-backed worker 的 readiness 现在会增加 `checks["provider:<id>"]`
  2. 如果 provider detect 失败，worker readiness 会被同步拉低
  3. 如果 worker 指向 provider backend，但当前 harness 没接入该 backend，还会增加 `checks["executor_backend:<backend>"]`
  4. 如果 worker 指向 provider backend，但 provider 没注册，会返回 `provider not registered: <id>`
- **排查步骤**:
  1. 查看 `/api/v1/workers/{id}/readiness`
  2. 重点看：
     - `checks.provider:<id>`
     - `checks.executor_backend:<backend>`
     - `reason`
  3. 再对照 `/api/v1/agents/{id}` 看 provider 侧 `ready/reason/metadata`
- **说明**:
  1. 这是刻意收紧的契约
  2. 目标是避免“路由层判 ready，但执行层必失败”的 detect/readiness drift

### 2.11 任务已经 handoff 到别的 worker，但 live flow / judgment 仍显示旧 worker 身份

- **典型表现**:
  1. `task.assigned_worker` 已经是 `kimi` 或其他 fallback worker
  2. 但 `live_flow`、judgment metadata、worker artifact 里仍然看到上一轮 `codex` / planner 的 `selected_worker`、`selected_model_tier`
  3. 常见于 orchestrated planner round 之后的 sparse executor round
- **已确认根因**:
  1. 某些后续 round 返回的 `WorkerExecutionResult.metadata()` 很稀疏
  2. 下游 projection 在缺字段时会退回历史 metadata，造成 current-round worker identity 漂移
- **当前修复**:
  1. `ControlNodeGraph` 会在持久化前把当前 round 的 route metadata 注入到执行结果 metadata
  2. 即使执行器只返回 sparse metadata，当前 round 的 `selected_worker`、`selected_model_tier`、`execution_role`、`why_selected` 等也不会被上一轮覆盖
- **排查步骤**:
  1. 同时看：
     - `task.assigned_worker`
     - `/api/v1/tasks/{id}/live_flow`
     - `/api/v1/tasks/{id}/judgment_trace`
     - `/api/v1/agent_runs?task_id={id}`
  2. 如果仍看到旧 worker 身份，重点检查当前 round 的 `worker_metadata` 是否缺失 route fields

### 2.12 任务明明已经路由到 provider worker，但执行结果是 `empty` 或看起来卡住

- **典型表现**:
  1. `/api/v1/tasks/{id}/provider_selection` 或 task metadata 显示已经选中了 `kimi` / `hermes` / `pi` / `kiro` 这类 provider worker
  2. `agent_runs` 很快结束，`duration_ms` 很短，metadata 里却是 `worker_execution_status=empty`
  3. operator 体感上像“任务卡住了”或“找不到 codex / provider”
- **已确认根因**:
  1. 某些 suggest-only provider worker 早期没有显式 `execution_backend`
  2. `WorkerExecutorRouter` 因此把它们静默降级到 `DefaultWorkerExecutor`
  3. 如果当前宿主又没有配置通用 LLM，执行会以 `empty` 收尾，看起来像 worker 卡死
  4. 同一类问题还会出现在 provider 自己 ready，但当前 harness 根本没接入对应 executor 的场景
- **当前修复**:
  1. 内置 provider worker 现在显式声明 `execution_backend`
  2. `suggest_only=true` 不再优先于 provider-native / provider-app-server 路由
  3. 显式 provider backend 但无执行器支持时，router 会 fail fast，而不是静默退回 default LLM
  4. `readiness` 现在会额外暴露 `checks.executor_backend:<backend>`，把 `hermes` / `pi` / `kiro` 这类当前未接入执行器的 worker 直接标成 `not ready`
- **真实案例**:
  1. `task_2cd0bb782c5a4a9b` 实际 pinned 到的是 `kimi`，不是 `codex`
  2. 修复后新 run `arun_ca954de6c2aa43a9` 通过 `kimi` provider-native CLI 成功执行，耗时约 72 秒
  3. 如果 `POST /continue` 客户端超时，不代表任务没跑；先回看服务端日志和 `/api/v1/agent_runs?task_id={id}`
- **排查步骤**:
  1. 查 `/api/v1/tasks/{id}/provider_selection`
  2. 查 `/api/v1/workers/{workerId}/readiness`
  3. 查 `/api/v1/agent_runs?task_id={id}`
  4. 重点看 run metadata：
     - `execution_backend`
     - `cli_resolved_binary`
     - `cli_launch_mode`
     - `provider_output_parser`
     - `worker_execution_status`

## 3. 调试技巧

### 3.1 本地调试入口

- **日志**: 默认输出到控制台；当前工作区有 `server.out.log` 样本。
- **数据库**: 直接用 SQLite CLI 或 DB Browser 打开 `${user.home}/.agentcloud/agent_cloud.db`。
- **前端入口**:
  - `http://localhost:8080/` 会重定向到 `/dialogue/`
  - `http://localhost:8080/dialogue/` 查看 session message 与 task 回执
  - `http://localhost:8080/console/` 查看任务、route、packet、experiment 面板

### 3.2 常用调试命令

```bash
# 编译并打包
mvn package

# 启动服务
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar

# 创建一个 coding 任务
curl -X POST http://localhost:8080/api/v1/tasks -H "Content-Type: application/json" -d "{\"title\":\"demo\",\"task_type\":\"coding\",\"source\":\"user\",\"priority\":\"high\",\"intent\":\"fix bug\"}"

# 查看 worker 列表
curl http://localhost:8080/api/v1/workers
```

### 3.3 关键断点位置

| 场景 | 建议断点位置 | 说明 |
|------|------------|------|
| 创建任务后为何分配到某个 worker | `src/main/java/com/agentcloud/engine/router/WorkerRouter.java` 的 `selectWorker` | 可以同时看到 tier 选择、learning hint、fallback reason |
| 单轮执行为什么停在某个阶段 | `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` 的 `schedulerNode` / `continueNode` | 能看到 route、artifact、judgment、state transition |
| runtime context 为什么不完整 | `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java` 的 `build` | 可确认 active context、latest packet/checkpoint、learning hints 的拼装 |
| judgment 为什么判成 continue/pause/done | `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java` | 能直接看 prompt 输入与结构化输出 |
| 工具链为什么提前结束 | `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` 的 `executeMultiToolRound` | 可看 `planner_no_additional_tool`、`repeated_tool_guard`、`no_progress_guard` |
| checkpoint / refined packet 内容异常 | `src/main/java/com/agentcloud/engine/ConsolidationService.java` | 可确认 artifact/decision/event 的抽取与 protocol payload |
| 实验汇总为什么不对 | `src/main/java/com/agentcloud/engine/ExperimentRunService.java`, `ExperimentMatrixService.java` | 可追踪 run metadata 聚合与按 mode/case 汇总 |

## 4. 配置相关注意事项

| 配置项 | 位置 | 常见错误 | 正确做法 |
|--------|------|---------|---------|
| `server.port` | JVM System Property | 忘记传导致端口冲突排查方向错误 | 明确在启动参数中覆盖 |
| `user.home` | 运行环境 | 使用受限账户导致无法创建 `.agentcloud` | 确保运行用户有写权限 |
| `schema.sql` | `src/main/resources/schema.sql` | 打包缺失导致启动失败 | 保持资源文件在主资源目录 |
| LLM 配置 | 环境变量 / 系统属性 | 本地未配置就期待 prompt judgment 生效 | 对照 `LlmConfig` 的读取项补齐 |

## 5. 性能隐患

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| P01 | `src/main/java/com/agentcloud/server/TaskHandler.java` | 列表接口仍以固定上限查询为主，缺少完整分页 | 中 | 增加分页参数和总量查询 |
| P02 | `src/main/java/com/agentcloud/engine/ConsolidationService.java` | consolidation 在 API 线程内同步执行 | 中 | 后续可异步化或限流 |
| P03 | `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` | 多步工具链仍是单轮内固定上限 | 中 | 若要长链任务，需要更明确的多轮代理协议 |

## 6. 安全注意事项

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| S01 | `src/main/java/com/agentcloud/server` | 全部 API 无鉴权、无租户隔离 | 高 | 增加认证和最小权限控制 |
| S02 | `src/main/java/com/agentcloud/server/WorkerHandler.java`, `SkillHandler.java` | 允许外部注册 worker/skill；虽已有基础字段校验，但仍无权限边界 | 中 | 增加权限控制、白名单与更严格的字段/范围校验 |
| S03 | `src/main/java/com/agentcloud/server/*Handler.java` | 500 已统一脱敏为 `internal error`；风险主要转移到服务端日志暴露面 | 低 | 保持 HTTP 脱敏，日志只在受控环境可见 |

## 7. 运维检查清单

- [ ] 启动用户对 `${user.home}/.agentcloud/` 目录具备写权限。
- [ ] 监听端口 `8080` 或自定义 `server.port` 未被占用。
- [ ] 启动日志中已出现 `NIO HTTP Server started` 和 endpoint 列表。
- [ ] `/api/v1/health` 返回 `status=up`。
- [ ] `skills`、`workers` 基础数据符合当前运行环境预期。
- [ ] 若依赖暂停恢复流程，先验证 `checkpoints` 与 `resume_packets` 是否都按预期写入。
- [ ] 若启用了工具能力，确认 `/api/v1/tasks/{id}/tool_trace` 与 `/api/v1/tasks/{id}/live_flow` 中都能看到 `tool_invocations`。
