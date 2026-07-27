# Provider Progress

## 当前状态

- 2026-07-21 方向调整：Codex 多 provider 的协议差异（Responses 与 Chat Completions 转换、渠道优先级、Key 轮换、模型名混淆兜底）已由本机 CCX 网关统一收敛，harness 不再负责 provider 差异收敛，只消费规范化的 provider run metadata / status。codex-openai / xfyun / deepseek profile lane、codebuddy/deveco CLI 接入这类 provider 专项工作从主线优先级降级，只在接入新协议或读面诊断时推进。约束：不要把 CCX 的渠道编排、Key 轮换、模型名映射逻辑复制进 harness；harness 侧 provider lane 配置只保留指向 CCX 网关加模型名这一层。新方向主入口为 ../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md。
- `provider/` 已正式升级为 `README.md + PROGRESS.md` 的轻量工作区，是当前第二个启用主题级进度面的业务专题。
- 当前活跃推进主要集中在四条线：free-first/manual-window 路由与恢复、codex 多 profile lane、codebuddy/deveco CLI protocol 接入、provider selection/live_flow/console 读面收口。
- 当前 `runs/README.md` 已启用，用来聚合 provider 主题下分散的 dated execution evidence；现阶段仍不启用 `tasks/`、`archive/`，root-level `docs/*.md` 继续作为设计、契约与 dated 执行证据本体落点，`PROGRESS.md` 只负责把当前活跃主线串起来。

## 已完成
- 2026-07-26: 路由契约兜底 -- pinned worker 须满足 auto_route_task_types。WorkerRouter.selectWorker 的 pinned 分支原先只校验 workspace 访问，openclaw-native 因 tool_aware backend 被补 host tools + cwd scope 致 hasLocalWorkspaceAccess=true，能把含本地写文件意图的 research 任务锁死在 tool-suggest 路径。修复两层联动：(a) normalizeTaskTypeForRouting 在 expectsWorkspaceMutation（动作词+文件信号同时命中）时把 research 提升为 coding，使 codex 进入候选；(b) pinned 分支新增 pinnedNotAllowedForTaskType = shouldApplyAutoRouteTaskTypeContract(taskType) && !autoRouteAllowedForTaskType(pinned, taskType)，与 workspace 校验并列，不满足则绕过 pin 回退 selectWorkerWithoutPinned，fallbackReason 写明 not allowed for taskType=coding by auto_route_task_types contract。对合法 pin（codex/coding）无影响。新增 WorkerRouterRouteTraceTest.researchTaskWithWorkspaceMutationBypassesPinnedOpenclawAndRoutesToCodex；router+control 合计 142 测试 0 失败。详见 ../CCX_RND_CASE_DEBUG_EXECUTION_RECORD_2026-07-25.md 路由优化小节。
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