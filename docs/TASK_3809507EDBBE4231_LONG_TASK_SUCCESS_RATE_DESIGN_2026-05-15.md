# task_3809507edbbe4231 长任务成功率改进设计

## 1. 背景

真实失败任务：

- `session_695854b5c95b4a62`
- `task_3809507edbbe4231`

用户意图：

- 根据文档  
  `D:\gitAll\agent-cloud-harness\docs\XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md`
- 修改本地代码仓库  
  `D:\gitAll\articleeditor\`

这是一个典型的：

- 本地仓库改动
- 多轮阅读 + 代码实现
- 长任务 / 代码任务

而不是普通 `message/continuation`。

---

## 2. 真实证据

### 2.1 live task 状态

`GET /api/v1/tasks/task_3809507edbbe4231/live_flow` 与 SQLite 均显示：

- `status=waiting_human`
- `control_node=human_gate`
- `assigned_worker=claude`
- `task.metadata.task_type=continuation`
- `recovery_stage=human_gate_required`
- `auto_same_worker_retry_count=1`
- `auto_handoff_count=1`
- `auto_handoff_target=openclaw-native`

### 2.2 执行链

按 events / artifacts / decisions 还原：

1. 第一轮 `codex`
   - `route_source=ready_fallback`
   - `task_type=continuation`
   - `selected_worker=codex`
   - `durationMs=151092`
   - `summary` 和 `output_text` 非常长，带 `thread not found`
   - `execution_status=timeout`
   - judgment 先给了 `same-worker retry`

2. 第二轮 `codex`
   - `durationMs=151301`
   - `outputLength=1487238`
   - 仍然是巨大噪声输出，且仍带 `thread not found`
   - judgment 触发 `auto_handoff`
   - `target_worker=claude`

3. 第三轮 `claude`
   - `durationMs=2359`
   - `outputLength=0`
   - `summary=worker claude failed: thread not found (23524)`
   - 自动恢复预算耗尽，进入 `waiting_human / human_gate`

### 2.3 额外日志事实

当前 `8080` 实例是旧代码，数据库路径：

- `.tmp/agent_cloud_new.db`

说明旧行为仍在线上：

- 任务创建时仍落成 `task_type=continuation`
- route preview 被 `assigned_worker=claude` 钉成 `task_pinned/preassigned`
- auto handoff 目标仍可能落到不合适的 worker

---

## 3. 根因拆解

这次不是单点故障，而是三层问题叠加。

### 3.1 入口判型过宽

chat façade 把“修改本地代码仓库”的请求默认建成 `continuation`。

后果：

- `WorkerRouter` 无法基于 `coding` 语义稳定选 worker
- learning memory 也会继续强化错误的 `continuation` 路由经验

### 3.2 orchestration planner 输出门控太松

当前 orchestrated planner 阶段，只要轮次结束，就可能继续向后流转。  
但这条真实 case 里，`codex` 实际返回的是：

- 巨大噪声输出
- 带 `thread not found`
- 不像可执行 delegation brief

旧行为仍把它作为后续恢复 / handoff 的上游输入，导致：

- 长输出污染 artifact / summary
- handoff 判断被脏 planner 输出带偏

### 3.3 恢复换 worker 没保住代码语义

`thread not found` 后的 auto handoff 旧逻辑没有把“当前任务本质上是 coding”作为强约束。

后果：

- 代码任务可能换去 `openclaw-native` 这类 `browser/doc/message/search` worker
- 或者在 provider-native cli 之间盲切，缺少更稳的优先级

---

## 4. 目标行为

### 4.1 创建任务时

如果 chat 请求明显是：

- 修改本地 repo
- 修改 `.java/.js/.ts/.py/.xml` 等代码文件
- 带“修改/修复/实现/补测试/fix/patch/refactor”等动作

则默认建模成：

- `task_type=coding`

### 4.2 planner 阶段结束时

只有当 planner 输出满足“可委派执行 brief”最低合同，才允许转入 `execution_pending` 或后续 handoff。

最低合同至少包括：

- 非失败态
- 非空输出
- 不包含明显 provider/runtime failure
- 输出长度在合理范围内
- 至少有简明 `summary` 或 `suggested_next_step`

如果 planner 输出是：

- `thread not found`
- `provider unavailable`
- 巨量噪声 / 日志型输出
- 明显不是 delegation brief

则不应把它当成正常 planner 成果继续下传。

### 4.3 recovery 阶段

如果当前任务是 `coding`：

- auto handoff 必须优先保住代码语义
- 优先选 coding-capable worker
- `openclaw-native` 不能在代码任务上抢到高优先级

---

## 5. 拟改动

### 5.1 Layer A：ChatFacade 任务判型

文件：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`

改动：

- 当显式 `task_type` 缺失，或仅是通用 `continuation` 时
- 根据 `intent/goal/title/workspace/repo_path/working_directory/target_path` 做轻量推断
- 识别“本地代码仓库改动”并提升到 `coding`

当前这层已开始落地，但需要补文档并继续作为正式合同。

### 5.2 Layer B：Planner 输出门控

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

新增一层 planner-delegation guard，作用于 orchestrated planner 阶段：

- 如果本轮 worker 是 planner / strong planner_executor
- 且输出包含 runtime/provider failure 信号
- 或输出长度明显异常大
- 或缺少可执行 brief

则：

- 不进入正常 delegation handoff
- 直接走 failure recovery
- 或回到 same-worker cold retry
- 或进入 human gate

建议先收一个保守门槛：

- `output_text.length > 12000` 且包含 `thread not found / provider unavailable / failed to start / timeout`
- 视为 planner output invalid for delegation

### 5.3 Layer C：Recovery 代码语义保留

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

改动：

- `coding` 任务在 `thread not found` / provider transient failure 时
- handoff 候选优先：
  - `preferred_worker`
  - `codex`
  - `cursor`
  - `copilot`
  - `opencode`
  - `codebuddy`
  - `trae`
  - `deepseek`
  - `claude`
- `openclaw-native` 不作为代码任务恢复的一线候选

这层当前也已开始落地，但还需要与 planner-output gate 联动。

### 5.4 Layer D：Route 诊断可视化

文件：

- `TaskHandler` / `live_flow` 聚合链

改动方向：

- 当前 `/select_worker` 只看到“现在被 pin 到谁”
- 对排障不够

建议增加两个概念：

- `current_pinned_route`
- `recovery_unpinned_recommendation`

这样页面上能直接看出：

- 现在为什么是 `claude`
- 如果解除 pin，系统真正建议换谁

当前已落地到返回结构：

- `GET /api/v1/tasks/{id}/select_worker`
- `GET /api/v1/tasks/{id}/live_flow` 的 `route_preview`

新增字段：

- `current_pinned_route`
- `recovery_unpinned_recommendation`

含义：

- `current_pinned_route`：解释当前为什么被 `assigned_worker / task_pinned` 钉住
- `recovery_unpinned_recommendation`：去掉 pin 之后，按当前 `task_type / model_mode / readiness` 系统真正建议的 worker

### 5.5 Layer E：运行期统一 task_type 归一化

文件：

- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`

问题：

- Layer A 只覆盖“chat 新创建任务”的判型
- 但真实线上仍存在大量已经落库的 `task_type=continuation` 存量任务
- 这些任务即使内容明显是“根据文档修改本地仓库 / 改某个 `.java` 文件 / 补测试”
- 在 `select_worker / live_flow.route_preview / recovery handoff` 上，仍会继续按 `continuation` 语义路由

真实证据：

- `task_3809507edbbe4231` 当前已进入 `waiting_human / human_gate`
- 但 task metadata 仍是 `task_type=continuation`
- 这使得 route / recovery 仍可能保留过宽语义

目标：

- 保留持久化原始 `task.metadata.task_type`
- 但新增一个统一的“有效 task_type”推断口径
- 当显式 task_type 缺失，或仍是通用 `continuation` 时
- 允许系统基于：
  - `title`
  - `goal`
  - `intent`
  - `workspace / repo_path / working_directory / target_path`
  - 已有 failure / route metadata
- 在运行期把有效类型提升为 `coding`

约束：

- 只做轻量推断，不修改非代码任务语义
- GET 型观测接口不应偷偷持久化 task_type
- 页面上最好同时保留：
  - 原始 `task_type`
  - route 实际采用的 `effective task_type`

最低落地：

- `WorkerRouter.selectWorker(...)` 不再只盲信 `task.metadata.task_type`
- `select_worker` 与 `live_flow.route_preview` 对存量 continuation 代码任务，也能给出 `coding` 语义的推荐
- recovery handoff 的 unpinned 预览不再被旧 `continuation` 语义拖偏

### 5.6 Layer F：provider 级恢复降级策略

文件：

- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`

问题：

- 当前恢复链只会把失败的 worker 标记成 temporarily unavailable
- 但 `thread not found / session expired / provider unavailable` 这类错误，很多时候是 provider/session 级故障，不一定等于“这个 worker 永久不行”
- 现有 harness 已经记录了 `agent_runs`，并能统计：
  - provider failure rate
  - provider runtime stats
- 但这些统计还没有接入恢复选路

真实风险：

- 同一个 provider 下面的多个 worker 可能共享同一类会话态故障
- 只按 worker 名称切换，可能出现：
  - 明明是 provider 级故障，却在同 provider 内盲切
  - 或者反过来，因为某个 worker 一次 thread failure 就整台拉黑，过早丢掉可用的同 provider 冷启动机会

目标：

- 对 `thread not found / session expired / provider unavailable` 这类 provider/runtime transient failure
- `same-worker retry` 仍保留，用于冷启动重试
- 但进入 `auto_handoff` 选目标时
- 如果当前 provider 最近窗口内已连续出现 transient/provider failure
- 则优先避开同 provider 的其他 worker
- 在同等 coding 能力下，优先切到不同 provider

第一版落地范围：

- 不改 schema
- 不新增 provider 熔断表
- 直接复用 `agent_runs` 最近窗口
- 只对 `worker_runtime_transient` 恢复启用
- 判定尽量保守：
  - 最近 3 次里至少 2 次明确是 `thread not found / provider unavailable / session expired / timeout`
  - 才视为当前 provider 进入热失败窗口

这层不是替代 `WorkerRouter`，而是避免低价值自动切换链：

- `thread not found`
- `same provider another worker`
- `再次 thread not found`

### 5.7 Layer G：agent_runs 状态归一化修正

文件：

- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java`

问题：

- 当前 `AgentRunService.normalizeRunStatus(...)` 会把不少异常轮次折成 `completed`
- 例如：
  - `timeout`
  - `empty`
  - `blocked`
  - `unknown`
- 这会直接污染：
  - `/api/v1/runtime_health`
  - provider failure rate
  - provider runtime stats
  - Layer F 的 provider-aware recovery 上游统计可信度

目标：

- `agent_runs.status` 至少保留：
  - `completed`
  - `failed`
  - `cancelled`
  - `timeout`
  - `blocked`
  - `empty`
  - `unknown`
- runtime health 与 provider stats 中：
  - `timeout / blocked / empty / unknown` 不再计入 completed
  - 它们应作为失败或异常轮次参与 provider 风险统计

第一版边界：

- 不改 schema
- 不改 `/agent_runs` 查询参数结构
- 只修 `AgentRunService` 的归一化和统计口径
- 恢复链不仅看 worker readiness，还要看最近 provider run 事实

最小口径：

- same-worker retry：继续保留，但明确视为“清 continuation 元数据后的冷启动重试”
- auto handoff：优先避开最近短窗口内 failure rate 明显过高的 provider
- 若当前失败 worker 属于某 provider，且该 provider 最近连续失败明显，则优先切到不同 provider 的同类 coding worker

继续收口的缺口：

- 上述“cold-start retry”不能只停在设计语义
- 当恢复链进入：
  - `same_worker_retry_scheduled`
  - `auto_handoff_scheduled`
- task metadata 必须显式清掉上一轮 continuation/thread 线索，例如：
  - `provider_session_id`
  - `provider_thread_id`
  - `codex_thread_id`
  - `resume_provider_session_id`
- 否则同 worker 重试或换同类 worker 时，仍可能继续带着旧 provider thread/session 污染进入下一轮
- 这条合同需要直接由 `ControlNodeGraphActionResolutionTest` 回归锁住，不能只靠排障口径和实现现状默认保持
- 还需要至少一条控制图流转级回归，证明 recovery 后的下一轮 executor `TaskRuntimeContext.task().metadata()` 不会再捡回旧 thread/session id
- 执行器本身也要显式遵守这条语义：
  - 当 `recovery_stage=same_worker_retry_scheduled|auto_handoff_scheduled`
  - `CodexAppServerWorkerExecutor` 不应再走 `thread/resume`
  - `ProviderCliWorkerExecutor` 也不应再默认用 `sessionId/taskId` 拼出新的 `--resume/--session`
  - 恢复轮要明确视为 fresh session / fresh thread

约束：

- 不做重型调度器，不引入外部 scoring service
- 只用 harness 里已经存在的 `agent_runs` 和 provider readiness
- 先做保守降级，不改最终 completion 判定

最低落地：

- `ControlNodeGraph` 恢复选路时可以拿到“当前 worker 对应 provider”
- 对候选 worker 做 provider-aware 排序，避免优先落到最近失败过热的 provider
- 页面上后续最好能解释：
  - 当前选中的 worker/provider
  - 是否因为 provider failure 触发了降级

### 5.8 Layer H：provider 避让原因显式投影到 route/provider 观测面

文件：

- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`

问题：

- Layer F 已经能在恢复链里根据最近 `agent_runs` 判定“当前 provider 处于热失败窗口”
- 但这层信息现在主要只存在：
  - `ControlNodeGraph` 日志
  - 内部的 recovery handoff 判断
- 页面上的 `/select_worker`、`live_flow.route_preview`、`/provider_selection` 还看不出：
  - 是否因为 provider 热失败而避开了某个 provider
  - 被避开的 provider 是谁
  - 证据来自什么

真实影响：

- operator 能看到结果“为什么选了 codex / 为什么没继续选 claude”
- 但很难区分这是：
  - 普通 capability match
  - task pinned
  - 还是 provider failure 驱动的保守降级

目标：

- 在现有 route/provider 观测面上显式投影 provider 避让原因
- 至少覆盖：
  - `GET /api/v1/tasks/{id}/select_worker`
  - `GET /api/v1/tasks/{id}/live_flow` 的 `route_preview`
  - `GET /api/v1/tasks/{id}/provider_selection`

建议新增最小信号：

- `provider_deprioritized=true|false`
- `deprioritized_provider=<provider-id>`
- `deprioritization_reason=recent transient provider failures`

第一版边界：

- 不要求在普通 route 上真的改变 worker 选择算法
- 只要求：
  - 对 `recovery_unpinned_recommendation` 这类恢复视角预览
  - 或 provider 观测面
  - 能解释“如果现在要恢复，系统会尽量避开哪个 provider，为什么”
- 不引入新的数据库表
- 直接复用现有 `AgentRunService.shouldDeprioritizeProvider(...)` 与 provider failure 统计

最低落地：

- `RouteDiagnostic` 能携带 provider 避让解释
- `recovery_unpinned_recommendation` 在命中热失败 provider 时带上这组字段
- `provider_selection.metadata` 同步投影这组字段
- `/select_worker` 主返回体也应直接带：
  - `recovery_provider_deprioritized`
  - `recovery_deprioritized_provider`
  - `recovery_deprioritization_reason`
- `/runtime_health` 也应同步投影：
  - `metadata.deprioritized_providers`
  - `provider_stats[].metadata.provider_deprioritized`
  - `provider_stats[].metadata.deprioritization_reason`
- 这些 provider 恢复降级信号不应只停在 API：

### 5.9 Layer I：cold-start recovery 显式投影到第一页消息与 route 说明

文件：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/resources/web/dialogue/app.js`

问题：

- 当前 recovery 相关第一屏信号已经有：
  - `failure_class`
  - `recovery_stage`
  - `auto_same_worker_retry_count`
  - `auto_handoff_count`
  - `auto_handoff_target`
- 但 operator 仍需要自己推断：
  - 这一轮到底是不是冷启动恢复
  - 旧 provider session/thread 是否已经被清掉
- 这会让 `same_worker_retry_scheduled / auto_handoff_scheduled` 看起来和普通续跑轮差别不大。

目标：

- 对 cold-start recovery 增加一条最小、稳定、可投影的执行模式信号
- 让消息卡和 route box 第一屏就能看见：
  - 这轮恢复会走 fresh session / fresh thread
  - 不是沿用旧 continuation 的普通续跑

建议最小合同：

- task / artifact / session message metadata 新增：
  - `recovery_execution_mode=fresh_session`
- 第一版只在以下 recovery stage 写入：
  - `same_worker_retry_scheduled`
  - `auto_handoff_scheduled`
- `human_gate_required` 不写这个字段，避免把“等待人工确认”误说成仍在冷启动执行。

边界：

- `recovery_execution_mode` 是执行模式，不是新的 `recovery_stage`
- 不引入第二套状态机
- 不要求额外建表；直接复用 task metadata、worker artifact metadata 和 session message metadata 投影

最低落地：

- `ControlNodeGraph.applyRecoveryDirective(...)` 在 cold-start recovery 时写入：
  - `recovery_execution_mode=fresh_session`
- `TaskService.appendRuntimeFactMessageMetadata(...)` 把该字段带进：
  - `task_progress`
  - `task_result`
- `/dialogue/` 的 `messageCardRecoveryDetail(...)` 直接补一条短文案：
  - `fresh session`
- route box 的 drawer chip 也补一条：
  - `recovery: fresh session`
  - `/console/` 的 `runtime_health` 需要直接显示“当前恢复降级窗口”与受影响 provider
  - `/console/` 与 `/dialogue/` 的 route box 需要直接解释“恢复阶段会优先避开 <provider>”
  - operator 不应再只靠 raw JSON / live_flow / 日志反推 provider 避让原因
- 这条 UI 合同还需要 browser 级验证：
  - 可以用页面级 fetch override/fixture，只覆盖 `runtime_health + live_flow/provider_selection`
  - 目标是直接断言 `/console/` 已真实渲染：
    - `当前恢复降级窗口：<provider>`
    - provider comparison 行内避让说明
    - route box 的恢复避让说明
- 至少要有 HTTP 回归锁住：
  - 有热失败 provider 样本时，`live_flow.route_preview.recovery_unpinned_recommendation` 能看到避让原因
  - `/provider_selection` 能看到对应 metadata
  - `/select_worker` 不展开子对象时也能直接看到恢复视角的 provider 避让提示
  - `/runtime_health` 能解释当前哪些 provider 处于恢复降级窗口

---

## 6. 实施顺序

1. 先文档化这条真实失败链和目标行为。
2. 再补 planner-output gate。
3. 然后把 recovery 语义和 route 诊断补齐。
4. 最后用窄回归锁住：
   - repo 修改请求会被判成 `coding`
   - planner 巨量失败输出不会继续当 delegation brief
   - `coding` 任务恢复时不会优先切去 `openclaw-native`
   - 存量 `continuation` 代码任务在 `select_worker/live_flow` 上也会按 `coding` 语义路由

---

## 7. 预期收益

做完后，这类“本地代码仓库长任务”的成功率提升，主要体现在：

- 一开始就更容易选对 worker
- 中途 provider 异常时不再把坏 planner 输出继续放大
- 恢复换 worker 时不容易丢失代码语义
- 页面和 live_flow 更容易解释“为什么失败、接下来该换谁”
- 老任务和新任务的 task_type 口径不再分裂
