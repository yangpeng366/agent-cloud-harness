# REFACTOR_PLAN — agent-cloud-harness 针对性重构

> 原则：不换栈、不重写，只在现有 Java 代码库上做针对性痛点修复。

## 痛点一：Worker 协议扩展成本高

**现状**：每新增一个 Agent 需实现完整 `ProviderProtocol` + `ProviderCliWorkerExecutor` 适配，涉及命令构造、事件流解析、错误映射、注册表注册等重复模式。当前 Codex / Pi / Trae / CodeBuddy / Deveco 共 5 个 Provider，代码模式高度雷同。

**问题**：新 Provider 接入需改 4-6 个文件、约 300-500 行代码，且每加一个都要手动注册到 `ProviderProtocolRegistry.defaultRegistry()`。

**方案**：
1. 抽象 `WorkerAdapter` 接口，合并命令构造 + 结果解析为单一方法
2. Provider 声明改为 YAML 配置驱动（在 `harness-config.yml` 中声明），无需改 Java 代码即可接入新 Provider
3. 保留现有 Protocol 体系为内部实现细节，对外暴露 `WorkerAdapter` 简化 API

**工作量**：3-5 天

**影响范围**：`engine/router/`、`worker/`、`agent/providers/`

---

## 痛点二：工具层扩展性不足

**现状**：工具在 `Main.java` 中硬编码注册，`ToolPolicy` 权限检查逻辑分散，工具 trace 与执行 trace 分离。

**问题**：新工具需改 `Main.java` + 实现 `Tool` 接口 + 手动注册；权限策略不支持声明式配置；工具调用 trace 未与 runtime trace 打通。

**方案**：
1. 引入 `ToolPlugin` 接口：`name / version / register(registry)`，工具自描述 + 自注册
2. `ToolPolicy` 改为声明式：YAML 配置 `allow / deny / scope`，运行时评估
3. 工具调用 trace 统一写入 `RuntimeCognitionSurface`，与 worker execution trace 同一条证据链

**工作量**：2-3 天

**影响范围**：`tool/`、`engine/memory/`、`server/`

---

## 痛点三：ControlNodeGraph 并发安全

**现状**：已做异步化 + per-task 锁，但 `continueNode` 中多步操作（judge → decide → state update）非原子，存在并发竞态风险。`schedulerNode` 超时保护与 recovery 逻辑可能竞态。

**问题**：极端场景下两个虚拟线程可能同时进入同一 task 的 `continueNode`，导致 judgment/decide 重复执行。

**方案**：
1. 将 `continueNode` 核心路径（judge → decide → state transition）包裹为 `synchronized(taskId)` 原子块
2. 引入 `TaskStateLock` 读写锁：读路径（query/render）用读锁，写路径（continue/finalize）用写锁
3. 补充并发测试用例：多线程同时 continue 同一 task

**工作量**：2-3 天

**影响范围**：`engine/ControlNodeGraph.java`

---

## 痛点四：配置覆盖闭环

**现状**：`harness-config.yml` 已支持 Worker Lane 声明式注册，但路由策略（free-first、tier-aware timeout、cost classification）仍硬编码在 `WorkerRouter` 中。

**问题**：调整路由优先级或超时策略需改 Java 代码 + 重建。

**方案**：
1. 将 `WorkerRouter` 路由策略参数化：`cost_class / timeout_seconds / selection_priority / capability_match_threshold` 全部从 YAML 读取
2. `WorkerRouterConfig` record：`Map<String, WorkerLaneConfig>` + `RoutingPolicyConfig`
3. 保留 sensible defaults，YAML 仅覆盖需定制的部分

**工作量**：2-3 天

**影响范围**：`engine/router/WorkerRouter.java`、`engine/router/WorkerRegistry.java`

---

## 痛点五：Frontend 状态管理

**现状**：前端（`/console/` 和 `/dialogue/`）使用 Vanilla JS，状态管理通过 DOM 操作实现，无组件化，无测试。

**问题**：复杂状态（多 task 并行、goal progress 实时更新、worker trace 流式渲染）维护困难，容易出 bug。

**方案**：
1. 引入轻量状态机（XState 浏览器版或自研微型状态机）管理 task lifecycle 状态转换
2. 核心面板组件（task badge、goal progress、worker trace）数据驱动，从 SSE/WebSocket 事件流渲染
3. 补充 Vitest 单元测试覆盖核心状态转换

**工作量**：3-5 天

**影响范围**：`src/main/resources/static/`

---

## 优先级排序

| 痛点 | 价值 | 风险 | 优先级 |
|------|------|------|--------|
| ① Worker 协议插件化 | 极高（新 Provider 接入成本降 80%） | 低 | **P0** |
| ③ ControlNodeGraph 并发安全 | 高（消除竞态风险） | 中 | **P0** |
| ② 工具层扩展性 | 高（生态可扩展性） | 低 | **P1** |
| ④ 配置覆盖闭环 | 中高（运维友好） | 低 | **P1** |
| ⑤ Frontend 状态管理 | 中（产品体验） | 中 | **P2** |

**建议执行顺序**：P0 先行（协议插件化 + 并发安全）→ P1（工具层 + 配置化）→ P2（前端）。

预计总工时：12-20 天。
