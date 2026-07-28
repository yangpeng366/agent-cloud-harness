# Provider Progress

## 当前状态

- 2026-07-21 方向调整：Codex 多 provider 的协议差异（Responses 与 Chat Completions 转换、渠道优先级、Key 轮换、模型名混淆兜底）已由本机 CCX 网关统一收敛，harness 不再负责 provider 差异收敛，只消费规范化的 provider run metadata / status。codex-openai / xfyun / deepseek profile lane、codebuddy/deveco CLI 接入这类 provider 专项工作从主线优先级降级，只在接入新协议或读面诊断时推进。约束：不要把 CCX 的渠道编排、Key 轮换、模型名映射逻辑复制进 harness；harness 侧 provider lane 配置只保留指向 CCX 网关加模型名这一层。新方向主入口为 ../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md。
- `provider/` 已正式升级为 `README.md + PROGRESS.md` 的轻量工作区，是当前第二个启用主题级进度面的业务专题。
- 当前活跃推进主要集中在四条线：free-first/manual-window 路由与恢复、codex 多 profile lane、codebuddy/deveco CLI protocol 接入、provider selection/live_flow/console 读面收口。
- 当前 `runs/README.md` 已启用，用来聚合 provider 主题下分散的 dated execution evidence；现阶段仍不启用 `tasks/`、`archive/`，root-level `docs/*.md` 继续作为设计、契约与 dated 执行证据本体落点，`PROGRESS.md` 只负责把当前活跃主线串起来。

## 已完成
- 2026-07-27: dialogue continuation 路由补洞。真实任务 task_6fe50128734948ba 暴露 “d:/gitAll/articleeditor-tmp + Editor 页面参数补全/接口返回数据” 未命中 coding heuristic，continuation 曾 fallback 到 openclaw-native。已补 TaskTypeHeuristics/WorkerRouter：补全/补齐/完善/接入/对接/联调/接口 等动作词，d:/ 正斜杠路径识别，research/continuation/general/other 的本地 workspace mutation 均提升为 coding。新增 WorkerRouterRouteTraceTest.dialogueContinuationWithArticleEditorCompletionIntentRoutesToCodex；运行时 probe task_fd73599501204b62 证实 selected_worker_id=codex、候选不含 openclaw-native。
- 2026-07-26: 路由契约兜底 -- pinned worker 须满足 auto_route_task_types。WorkerRouter.selectWorker 的 pinned 分支原先只校验 workspace 访问，openclaw-native 因 tool_aware backend 被补 host tools + cwd scope 致 hasLocalWorkspaceAccess=true，能把含本地写文件意图的 research 任务锁死在 tool-suggest 路径。修复两层联动：(a) normalizeTaskTypeForRouting 在 expectsWorkspaceMutation（动作词+文件信号同时命中）时把 research 提升为 coding，使 codex 进入候选；(b) pinned 分支新增 pinnedNotAllowedForTaskType = shouldApplyAutoRouteTaskTypeContract(taskType) && !autoRouteAllowedForTaskType(pinned, taskType)，与 workspace 校验并列，不满足则绕过 pin 回退 selectWorkerWithoutPinned，fallbackReason 写明 not allowed for taskType=coding by auto_route_task_types contract。对合法 pin（codex/coding）无影响。新增 WorkerRouterRouteTraceTest.researchTaskWithWorkspaceMutationBypassesPinnedOpenclawAndRoutesToCodex；router+control 合计 142 测试 0 失败。详见 ../CCX_RND_CASE_DEBUG_EXECUTION_RECORD_2026-07-25.md 路由优化小节。运行时复跑（2026-07-27）已通过：task=e514ffcab3884f5f（research+写文件）经提升路由到 codex，done，产物首轮落地，不再退化到 openclaw-native/waiting_human。
- 2026-07-23: Phase 2 P1 配置驱动 Worker Lane 落地。新增 HarnessConfigLoader + HarnessConfig / WorkerLaneConfig / WorkerLaneProfileConfig record，WorkerRegistry.registerFromConfig 增量注册声明式 worker lane。harness-config.example.yml 样例包含主力 + 4 条免费 lane。HarnessConfigLoaderTest 7 场景 + WorkerRegistryConfigRegistrationTest 6 场景全绿。pom.xml 补 jackson-dataformat-yaml。配置不存在时回退到内置默认值，行为不变。
- 2026-07-23: 新增 FREE_MODEL_WORKER_LANE_PLAN.md，吸收 Obsidian harness加免费模型编排方案.md 的构想，收成配置驱动的落地计划。核心变化：从硬编码内置 worker lane 转向 harness-config.yml 声明式配置，operator 无需改 Java 代码即可接入 CCX 新渠道（4 条免费 lane: 硅基 9B、OpenRouter、智谱 Flash、GitHub GPT）。NEXT_EVOLUTION_PLAN.md 同步增加 Phase 2 方向定义。- 2026-07-22: P3 Trae protocol 注册落地。新增 `TraeProtocol`（`chat --mode agent`，`launchMode=app_server`，纯文本解析），注册进 `ProviderProtocolRegistry`。`TraeProtocolTest` 6 场景全绿。CCX+Pi+Harness+Advisor 集成计划 P1/P2/P3 全部完成。
- 2026-07-22: P2 Advisory Handoff 语义落地。`ControlNodeGraph.continueNode` 在 `escalate` 分支前增加 advisory handoff 判断：small-tier worker + ready strong-tier worker 时优先 handoff（`handoff_reason=advisory_consult`），无 strong-tier 时保持 `human_gate`。新增 `resolveAdvisoryHandoff`、`workerMetadata(String,String)`、`WorkerRouter.listReadyWorkers()`。`AdvisoryHandoffTest` 5 场景全绿。`API_CONTRACTS.md` + `SPEC.md` 已补合同。
- 2026-07-22: P1 Pi protocol 注册落地。新增 `PiProtocol`，把 Pi 事件流解析为 `WorkerExecutionResult`；注册进 `ProviderProtocolRegistry.defaultRegistry()`，让 Pi 成为 small-tier 可调度 worker。新增 `PiProtocolTest` 覆盖 buildPlan 命令构造、model/cwd 注入、事件流解析、错误标记、registry 注册 5 个场景。Focused regression `PiProtocolTest,CodeBuddyProtocolTest,DevecoProtocolTest` 全绿（14 tests, 0 failures），docs audit 0 violation。
- 2026-07-22: 新增 `CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md`，把 Obsidian 46 号文档的四层集成构想收成产品决策。核心判断：Pi 不另建执行层，而是作为 harness 的 small-tier worker 通过 ProviderCliWorkerExecutor 被调度；Trae Advisor 不是人在环，而是"低模型向高模型请教思路"的 advisory handoff 语义；CCX 边界不变；不引入新通信协议。落地优先级：P1 Pi protocol 注册、P2 advisory handoff 语义、P3 Trae protocol 注册。

- 2026-07-21: `/api/v1/tasks/{id}/provider_selection` 路由读面补回 `metadata.model_mode`，避免 `strong_only / small_only / orchestrated` 请求只显示 selected tier 而丢失原始路由模式；回归断言落在 `TaskHandlerProviderSelectionHttpTest.providerSelectionProjectsWorkerRouteToProviderView()`。
- `provider/README.md` 已从单纯专题入口升级为 `README.md -> PROGRESS.md -> 子线文档` 的工作区入口。
- 星火 glm5.1 的本地启动入口已补到 `scripts/Run-HarnessWithXfyunGlm51.ps1`，并同步写入 `STARTUP_GUIDE.md`；该脚本包装通用 Java 21 启动脚本，自动设置 `OPENAI_BASE_URL=https://maas-coding-api.cn-huabei-1.xf-yun.com/v2`、`OPENAI_MODEL=xopglm51`、`OPENAI_REVIEW_MODEL=xopglm51`、`OPENAI_WIRE_API=chat_completions`，但不把 API Key 写入仓库，只从 `-ApiKey` 或当前 `OPENAI_API_KEY` 环境变量读取。
- OmniRoute 的本地网关启动入口已补到 `scripts/Run-HarnessWithOmniRoute.ps1`，并同步写入 `STARTUP_GUIDE.md`；该脚本会在本机 `omniroute` 未监听时自动尝试拉起 `http://localhost:20128/v1`，默认设置 `OPENAI_MODEL=auto/coding`、`OPENAI_REVIEW_MODEL=auto`，并在启动 Harness 前校验 `/v1/models` 非空，避免“网关已起但上游 provider / combo 尚未配置”的假绿状态。当前文档口径也已明确：OmniRoute 在本仓库里先作为 OpenAI-compatible LLM 上游网关使用，不作为 `WorkerRegistry` 里的原生 provider lane；后续实施阶段、验收口径与风险清单已收口到 `OMNIROUTE_OPENAI_COMPATIBLE_GATEWAY_PLAN.md`。
- `provider/runs/README.md` 已启用，当前用于聚合 codex profile lane、worker priority / facade 接缝以及与 provider route 相关的 packet / control-route focused evidence，但不物理搬动 root-level dated 文档本体。
- free-first/manual-window 这条线已经补到了 route surface、task metadata、agent run metadata、`/dialogue/` route box 与 `/console/` operator summary 的前后端闭环。
- codex 多 profile lane 与 codebuddy/deveco CLI 接入都已形成“设计文档 + focused test + dated execution record”的可追踪主线。

## 活跃子线

- free-first / paid fallback / manual window / recovery policy
- codex-openai / codex-xfyun / codex-deepseek profile lane 与 route/read surface
- codebuddy / deveco protocol、parser、resume、dispatch readiness
- provider selection / live_flow / agent run / console 诊断读面

## 下一步

- 如果 provider 主题开始并行推进两条以上实施线，再考虑补 `tasks/` 做子线拆分。
- 如果 provider 相关 execution/precheck 证据继续增多，再在 `runs/README.md` 下补更细的二级分组，而不是重新把入口退回 root-level 长名单。
- 每轮路由、profile 或 CLI 接入收口后，至少同步一个稳定基线文档，再把跨主题摘要写回 `STATE.md`。

## 风险

- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`WORKER_FAILURE_RECOVERY_POLICY.md`、`API_CONTRACTS.md` 与 `PROGRESS.md` 之间仍可能发生字段或口径漂移。
- provider 主题虽然已经有 `runs/README.md`，但 dated 文档本体仍在 root-level `docs/`；如果主题 README、总索引或 runs 入口不同步，仍会出现“目录存在但证据不可追踪”的入口漂移。
- OmniRoute 当前只是本地 LLM 网关包装入口，不会自动替代 Harness 自身的 worker/provider route 语义；如果 `/v1/models` 为空，Harness 即使读到了 `OPENAI_BASE_URL`，真实任务也仍会卡在 OmniRoute 上游未配置阶段。

- 2026-07-24: CCX ↔ Harness 双向对接落地。新增 Run-HarnessWithCcx.ps1 脚本（自动读 codex config.toml 中 CCX bearer token）。CCX Desktop 新增 harness-chat / harness-responses 渠道，使用专属模型名 harness / harness-strong / harness-fast，避免与其他渠道混淆。端到端验证通过。
---

## 2026-07-27 任务理解改 provider 推动 + 边界守护 - 方案设计

- 触发：session_4b63c81807094751 / task_eee02813bbe74049（articleeditor 导出 Word 标题缺失加开关）卡 human_gate。根因不是超时，而是硬编码关键词推断漏了"添加/导出/下载/开关" -> task_type 未提升 coding -> 无 workspace_root -> codex cwd 静默回退 harness 仓库 -> 跑错仓库烧 2.25M tokens / 906s -> partial_timeout -> RuntimeException -> retry 0ms 崩 -> human_gate。
- 盘点硬推断点 7 处：TaskTypeHeuristics / WorkerRouter.expectsWorkspaceMutation + normalizeTaskTypeForRouting / ControlNodeGraph(1092,4430,3750) / ToolAwareWorkerExecutor(2095,3555) / ProviderTaskContractNormalizer / CodexAppServerWorkerExecutor.resolveWorkingDirectory。多份关键词表重复且口径漂移，articleeditor 等项目名硬编码不可移植，H7 静默回退 user.dir 无安全边界最危险。
- 设计方向（详见 ../LLM_TASK_UNDERSTANDING_PLAN.md）：provider 推动 + 边界守护--理解/定位/执行下放给执行 agent（codex 自主从可配 workspace-aliases 清单定位仓库），harness 只提供仓库清单注入 prompt + H7 不静默回退 harness 仓库 + prompt 边界提示；不加前置 LLM 预判层、不重构 task_type（continuation fallback 单独小修）。
- 落地优先级：P0 alias registry 注入 prompt + H7 不回退 harness 仓库 + prompt 边界提示；P1 continuation 路由 fallback 单独修；可选 token guardrail。
- 与 DECISIONS 2026-07-22 关系：subgoal 状态迁移仍规则优先；任务理解/定位下放执行 agent，harness 只守边界，待 maintainer 确认新增决策。
- 状态：方案设计未落代码；本会话因系统页面文件耗尽（codex app-server 残留进程占内存）多次 shell 崩溃，文档已落盘，代码实现留待资源恢复后进行。
### 验证（2026-07-27）

建等价任务 task_0ce65a30636840af 带 workspace_root=D:\gitAll\articleeditor（auto_start=false）：
- 任务 metadata 正确接收 workspace_root / workspace_roots。
- select_worker 确认 selected_worker=codex / task_type=coding / route_source=capability_match（不再是 ready_fallback / task_pinned）。
- cwd 由 CodexAppServerWorkerExecutor.resolveWorkingDirectory 代码逻辑保证：metadata 有 workspace_root 时直接返回它，无需实跑验证。
- 结论：设计方向（provider 推动 + 给对 workspace）验证可行。根因确认是 chat facade 创建任务时不带 workspace（API 路径 /articleeditor/ 不被 ProviderTaskContractNormalizer 的 WINDOWS_ABSOLUTE_PATH 正则识别）。验证任务已 pause（control_node=scheduler），可 escalate/close 清理。
---

## 2026-07-27 P0 落地：provider 推动 + workspace 安全边界（已实现+验证）

按 ../LLM_TASK_UNDERSTANDING_PLAN.md 落地 P0 三件事，端到端验证通过。

### 代码改动
- `HarnessConfig`/`HarnessConfigLoader`：加 `workspaceAliases` 字段（Map<String,String>），解析 `harness:` 下的 `workspace-aliases` 节。
- `CodexAppServerWorkerExecutor`：
  - 构造加 `workspaceAliases` 参数（旧构造委托 `Map.of()`，兼容测试）。
  - `resolveWorkingDirectory`：metadata 无显式 workspace 时，新增 `inferWorkspaceFromAliases(context)` 按 intent 子串（大小写不敏感）命中别名解析 cwd；无 alias 命中且有 alias registry 时回退到 `neutralWorkspaceDir()`（首个 alias 父目录），不再静默回退 harness 自身 `user.dir` 仓库。
  - `buildPlan`/`buildExecJsonPlan`：prompt 末尾 append `buildWorkspaceGuidance(cwd)`：注入「Available Workspaces」清单 + 「Workspace Boundary」提示（仅在 target repo 工作、禁读 harness 自身 STATE.md/docs 源码、当前 cwd）。
- `Main.java`：加载 `harnessConfig` 后取 `workspaceAliases` 注入 `CodexAppServerWorkerExecutor` 构造。
- `harness-config.yml`（新建）：`harness.workspace-aliases` 注册 `articleeditor` / `articleeditor-tmp`。
- `HarnessConfigLoaderTest`：新增 `loadParsesWorkspaceAliases` 用例验证 alias 解析。

### 验证
- 编译通过（mvn -DskipTests compile）。
- 测试通过：`HarnessConfigLoaderTest`（含新用例）、`WorkerRouterRouteTraceTest` 全绿。
- 构建新 JAR + 重启 harness（CCX 环境变量从 codex config.toml 恢复：base_url=127.0.0.1:3688, model=codex, available=true）。
- 端到端验证 task_bbfe36747ce74239：intent 含 `/articleeditor/...`，不带 workspace_root，task_type=continuation。codex run 文件 prompt.txt 确认：
  - cwd = `D:\gitAll\articleeditor`（alias 推断命中，不再回退 harness 仓库）。
  - prompt 含「Available Workspaces」清单 + 「Workspace Boundary」提示。
  - 对比修复前：cwd=harness 仓库、无仓库清单、烧 2.25M tokens/906s，根因（cwd 静默回退 + 无仓库线索）已消除。

### 注意
- 重启 harness 必须带 CCX 环境变量（OPENAI_BASE_URL/OPENAI_API_KEY/OPENAI_MODEL），LlmConfig 从环境变量读；环境变量来源是 codex config.toml 的 ccx provider 节（token + base_url）。验证启动脚本逻辑已写入 .tmp/_start.py（从 toml 动态提取，不硬编码 token）。
- task_eee02813bbe74049（原卡住任务）仍在 human_gate，可在 dialogue escalate/close 清理。
---

## 2026-07-28 P0 根治：控制图 enter 异步化（worker round 结果不再丢失）

### 根因
task_150378d838a249ab（分析 articleeditor 撤回日志）卡 scheduler：codex agent_run 已 completed（358s, exit_code=0, 1.4MB 分析），但任务事件只有 task_created/intake/scheduler 三个，无 worker_round 事件，task 停 active/scheduler。
定位：`TaskService.createTask`/`continueTask` 同步调用 `controlGraph.enter(t)`，在 chat facade 的 HTTP 虚拟线程里阻塞执行整个控制图（含 358s worker round）。NioHttpServer 请求超时后虚拟线程被回收，控制图在 `recordCompletedAgentRun`（写 agent_run）之后、`emit worker_round`（写事件 + 推进 task 状态）之前被终止，结果丢失。即"codex 跑完了但 harness 没记录"。

### 修复
`ControlNodeGraph.enter` 异步化：
- 新增 `asyncEnterControlGraph(task, action)`：用虚拟线程（`Thread.ofVirtual`）执行 enter，HTTP 立即返回。
- per-task 锁（`ConcurrentHashMap<String,Object> enterLocks` + synchronized）：防同一 task 并发 enter（如 create 后立即 continue）重复跑 worker round；enter 前重查最新 task 状态。
- 异常 catch 记录日志，不静默丢失。
- `createTask` autoStart + `continueTask` 改调 `asyncEnterControlGraph`，不再同步 `controlGraph.enter(t)`。
- 同步回退开关 `agentcloud.controlgraph.sync_enter`：测试用同步（保证控制图语义可断言），生产默认异步。pom.xml surefire 设 `sync_enter=true`（仿 `worker.priority.config.enabled` 模式）。

### 验证
- 编译通过；控制图测试（ControlNodeGraphOrchestrationFlowTest 17/0、ControlNodeGraphActionResolutionTest、GoalProgressAutoUpdateTest）+ TaskService 测试（AutoStart 7/0、ControlActionProjection 6/0、PacketContract 7/0、MessageReceipt 9/0、ParentTask 4/0）全绿。
- 构建新 JAR 重启 harness（生产异步）。recover task_150378d838a249ab：worker_round 事件落盘（`Worker round completed. worker=codex outputLength=1440887 durationMs=358141`），控制图推进到 `done`（completed_at 写入）。对比修复前卡 scheduler 无 worker_round 事件，根因消除。

### 附带发现（未修，次要）
- 任务 summary 提取的是 codex 协议日志头部（ERROR ReasoningSummaryPartAdded），非分析结论；codex 结论在 last_message.md。summary 提取逻辑可后续优化。
- alias 子串匹配对"分析类/日志类"任务会误命中（日志含"articleeditor"把 cwd 拉到仓库），不影响分析但可后续收窄（只读/分析类任务不应改 cwd）。
---

## 2026-07-28 provider-driven 精化：移除 alias 子串推断

### 决策
workspace 定位完全 provider-driven。移除 `CodexAppServerWorkerExecutor.inferWorkspaceFromAliases`（alias 子串匹配从 task goal/intent/title 猜 cwd），改为：
- 显式 `workspace_root`（metadata/API）-> 直用（操作者提供，非推断）。
- 无显式 workspace -> 中性目录（alias 公共父目录）+ prompt 仓库清单 -> codex 自主 `cd` 定位。
路由关键词表（H1/H2/H4/H5）保留为 worker 选择兜底，不影响 cwd，不急于移除。

### 代码改动
- `CodexAppServerWorkerExecutor.resolveWorkingDirectory`：删除 `inferWorkspaceFromAliases` 调用块，流程变为 显式 metadata -> worker.toolScope -> neutralWorkspaceDir -> user.dir。
- `buildWorkspaceGuidance`：prompt 强化——明确指示 codex「Identify which workspace matches, then cd into that directory before doing any work」，并标注 cwd 为 neutral start。

### 验证
- 编译通过；CodexAppServerWorkerExecutorTest 23/0（新增 3 用例：codexDoesNotInferCwdFromAliasSubstringInTaskText / codexFallsBackToNeutralWorkspaceWhenNoExplicitCwdAndAliasesConfigured / codexWorkspaceGuidanceTellsAgentToCdIntoTarget）。
- HarnessConfigLoaderTest 8/0、WorkerRouterRouteTraceTest 34/0 无回归。
- 新 JAR 构建部署重启（PID 21644），health=up，LLM available。

### 根因回顾
P0（2026-07-27）落地 alias registry + 边界守护后，`inferWorkspaceFromAliases` 仍用子串匹配从 task 文本猜 cwd。分析类任务（日志含 "articleeditor"）会被误拉到该仓库 cwd。移除后，codex 从 prompt 仓库清单自行 cd，消除误命中类问题，符合「provider 推动 + 边界守护」方向。
---

## 2026-07-28 瞬态失败三连修复：initialize 超时 / blocked subgoal / ERROR 日志污染

### 背景
task_1aabd291e2514073（opm-content-writer 数字人配置调研）首次 worker round 因 codex state DB backfill 冷启动超 30s 致 initialize 超时 -> human_gate。手动 recover 后第二次 worker round 成功（888KB 输出，完整数字人配置参数），但 subgoal 仍标 blocked -> 又卡 human_gate。排查发现三个独立根因。

### 根因与修复

**1. initialize 超时太短（30s 硬编码）**
- 位置：CodexAppServerWorkerExecutor.HANDSHAKE_TIMEOUT_MS=30_000L，用于所有 JSON-RPC request（含 initialize）。
- codex state DB backfill 冷启动本身就要等 30s+，harness 的 30s 超时必触发。
- 修复：新增 INITIALIZE_TIMEOUT_MS（可配，默认 90s），initialize 专用；handshake 保持 30s（thread/start、turn/start 足够）。两者均可通过系统属性 `agentcloud.providers.codex.initialize_timeout_ms` / `handshake_timeout_ms` 覆盖。request 方法新增 timeoutMs 重载。

**2. recovery 不重置 blocked subgoal**
- 位置：TaskService.prepareFreshSessionRecovery。
- 第一次失败后 subgoal 标 blocked，recovery 不重置 -> autoUpdateSubgoalStatus 只迁移 in_progress/pending，找不到可迁移的 subgoal -> 永远 blocked -> 永远 human_gate。
- 修复：prepareFreshSessionRecovery 新增 resetBlockedSubgoals（处理 List/Map 两种格式），将 blocked 重置为 pending；同时清除 failure_summary_readable。

**3. 非 JSON ERROR 日志污染 output/summary**
- 位置：CodexAppServerWorkerExecutor.JsonRpcSession.nextEnvelope。
- JSON 解析失败的行被 appendOutput 当任务输出，codex 内部 ERROR 日志（ReasoningSummaryDelta without active item）混入 last_message.md 和 summary。
- 修复：新增 isCodexInternalLog 过滤器（ANSI 转义码 + ERROR/WARN，或时间戳前缀日志行），非 JSON 行先过滤再决定是否 appendOutput。

### 验证
- 编译通过；CodexAppServerWorkerExecutorTest 23/0、TaskServiceAutoStart 7/0、GoalProgressAutoUpdate 11/0、TaskServiceControlActionProjection 6/0、TaskServicePacketContract 7/0、ControlNodeGraphOrchestrationFlow 17/0、TaskServiceMessageReceipt 9/0、WorkerRouterRouteTrace 34/0（共 114/0）。
- 新 JAR 部署重启。task_1aabd291e2514073 recover 后 subgoal 正确从 blocked 重置为 pending，任务进入 active/scheduler 跑 fresh worker round。

### 超时配置参考
| 配置项 | 默认 | 说明 |
|--------|------|------|
| `agentcloud.providers.codex.initialize_timeout_ms` | 90000 | codex app-server initialize 超时（含 state DB backfill 冷启动） |
| `agentcloud.providers.codex.handshake_timeout_ms` | 30000 | thread/start、turn/start 等 JSON-RPC 请求超时 |
| `agentcloud.providers.codex.turn_activity_timeout_ms` | 180000 | 单轮活动超时（无输出间隔） |
| `agentcloud.providers.codex.turn_max_duration_ms` | -- | 单轮最大执行时间 |
