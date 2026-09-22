# DECISIONS

## Jev Runtime

- **Jev context scoring 非侵入装配**：Main 只在 `feature_flags.jev.context_scoring=true` 时包装 `PromptBasedJudgmentService`；关闭时不实例化 scorer、不访问 HTTP。缺 key、超时、HTTP 错误和解析异常一律 fallback 到原 judgment。命中结果必须带 `runtime_facts.jev_prefilter_decision` 进入 `/judgment_trace`，避免只省 token 却失去可解释性。

## 架构决策

- **控制图异步化**：`createTask/continueTask` 不再在 HTTP 虚拟线程同步执行 `controlGraph.enter`，改为异步 + per-task 锁。理由：worker round 长耗时（数百秒）会触发 HTTP 超时，导致事件与状态丢失。
- **Worker 超时 tier-aware**：strong tier（如 codex）600s，其余 300s，支持 `-Dharness.worker.timeout.seconds` 系统属性绝对覆盖。数据依据：codex p95=331s，旧 120s 砍掉 49% 轮次。
- **Budget timeout 分类**：`worker_budget_exhausted` 首次 same-worker retry，二次直接 human_gate 并提示 raise timeout 或拆分任务，不跨 sibling lane。
- **False-done Guardrail**：`expectsToolExecution && !hasExecutionProof` 时不再误标 done，写 `subgoal_judgment_source=evidence_gap_no_tool_proof`。
- **Recovery 强制重置 blocked subgoal**：`prepareFreshSessionRecovery` 必须将 blocked subgoal 重置为 pending，清除 stale failure summaries。

## Worker 路由

- **Free-first 路由**：cost-stage 分层（free_auto → paid_auto → manual_window），manual_window 只推荐不自动进入候选。
- **配置驱动 Worker Lane**：`harness-config.yml` YAML 声明式注册，不硬编码。CCX 渠道变更频繁，配置驱动避免每次改 Java + 重建。
- **Worker 双 lane 架构**：codex-main（paid_auto）+ codex-free（free_auto），CCX 负责模型路由，harness 不关心 CCX 背后渠道。
- **Task Type 驱动路由**：含本地写文件意图的 research 自动提升为 coding，解决 research→openclaw-native 伪完成问题。
- **Codex Profile 二层路由**：进入 codex family 后按 design/implement/verify 阶段路由到 openai/xfyun/deepseek profile，或显式 pin。

## CCX 集成

- **专属模型名**：CCX 配置 harness / harness-strong / harness-fast 模型名，避免与其他渠道负载均衡混淆。
- **CCX 边界**：CCX 只负责 provider 网关层的 API 转换、渠道/Key/failover；harness 不复制 CCX 逻辑。
- **CCX 健康检查**：启动时 precheck + 手动 refresh，不做自动 re-sync。

## 协议与扩展

- **Provider 接入统一 CLI Protocol**：Pi / Trae / CodeBuddy / Deveco 均通过 `ProviderCliWorkerExecutor` + Protocol 接入，不引入 gRPC/WebSocket。
- **Advisory Handoff**：small tier worker 遇到 ready strong tier 时自动 advisory handoff（`handoff_reason=advisory_consult`），不是人在环。
- **Handoff 深度限制**：`MAX_HANDOFF_DEPTH=3`，超限直接入 human_gate。
- **非 JSON 行过滤**：codex 向 stdout 混入 ANSI 着色内部日志，`isCodexInternalLog` 过滤，只保留真实输出。

## 数据与持久化

- **Resume/Handoff Packet**：machine-readable 最小字段集，跨 worker handoff 不丢 typed continuity 字段。
- **Goal Progress 优先**：`resolveAction` 优先消费 goal progress 而非单轮 execution result。
- **LLM-assisted Subgoal Update**：仅在 ambiguous 场景（非 completed/failed/running）触发 LLM fallback，规则优先。
- **Sublime 式配置**：默认自动发现 + 用户覆盖（harness-config.yml），YAML 而非 JSON。

## 文档治理（精简版）

- **核心原则**：文档为代码服务，不为文档而文档。
- **三层入口**：`README.md`（公开）→ `docs/README.md`（开发分流）→ `docs/<topic>/README.md`（主题入口）。
- **文档即合同**：`API_CONTRACTS.md` / `SPEC.md` / `ARCHITECTURE.md` 为稳定基线，其余为可变文档。
