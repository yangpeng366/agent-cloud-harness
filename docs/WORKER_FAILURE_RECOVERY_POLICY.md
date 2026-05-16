# Worker Failure Recovery Policy

这份文档回答一个具体问题：

> 当 `/dialogue/` 里的聊天已经 materialize 成 task，但当前 worker 执行失败时，系统应该自动切换 worker，还是停下来等用户手动处理？

当前结论不是“永远自动切”或“永远人工选”，而是：

- **先分类失败类型**
- **只对“明显是 worker/runtime/provider 级瞬时故障”的失败做自动恢复**
- **对“很可能换 worker 也一样失败”或“已经产生部分副作用/部分结果”的失败，默认进 `human_gate` 或至少要求人工确认**

当前源码状态还要明确一件事：

- 这条恢复链**已经不只是设计**
- `ControlNodeGraph.continueNode(...)` 里已经会对 `worker_runtime_transient` 失败：
  - 先触发 **同 worker 冷重试 1 次**
  - 再触发 **自动 handoff 1 次**
  - 预算耗尽后进入 `human_gate`
- 也就是说，当前还缺的主要不是“是否自动恢复”，而是：
  - 分类覆盖面是否够完整
  - 文档/测试是否把“真实已落地行为”写清
  - `/dialogue/` 可见性是否足够直观
- 另外，当前恢复链还有两个真实实现边界需要明确：
  - **temporary unavailable 是进程内 TTL 状态**，不是跨重启持久化的 worker 黑名单
  - **历史 task 的旧 worker metadata 需要在 scheduler 入口自愈**，否则旧的 `assigned_worker / target_worker / preassigned_selection_reason` 会继续污染 route trace

---

## 1. 当前真实问题

以当前真实案例为例：

- `/dialogue/#session=session_0a10560c8a5e4672&task=task_f88eef3f8d0c4efb&details=open`
- `task_progress` 里出现：
  - `����: û���ҵ����� "15252"��`

结合 `/api/v1/tasks/{id}/live_flow` 和 `judgment_trace`，当前可以确认：

- 失败来自 worker/provider 执行边界，而不是 `/dialogue/` 前端自己生成
- `execution_status=failed`
- 当前失败更像 **provider runtime / thread resume / app-server 会话丢失**
- 这类失败通常不是“任务本身不可做”，而是“当前 worker 的执行会话坏了”

这类失败如果一律让用户手工选 worker，体验会很差；但如果一律自动切 worker，也会引入错误切换、重复执行和副作用放大。

---

## 2. 设计原则

### 2.1 自动恢复只处理“执行宿主坏了”，不处理“任务本身坏了”

系统应该自动恢复的，是下面这类问题：

- app-server 线程丢失
- provider 会话失效
- worker 进程没起来
- 短暂超时 / 连接中断 / readiness 瞬时失败

系统不该自动恢复的，是下面这类问题：

- repo/tool/env 本身缺文件、缺命令、缺权限
- 任务目标不清、需求冲突、结果质量不够
- 已经写了一半代码/文件/外部副作用，继续自动切 worker 风险高

### 2.2 优先“同 worker 冷重试”，其次“自动 handoff 到候选 worker”

对 runtime/provider 级失败，推荐顺序是：

1. **同 worker 冷重试一次**
2. **若仍失败，再自动 handoff 到候选 worker 一次**
3. **若第二次仍失败，进入 `human_gate`**

原因：

- 第一跳先排除“线程坏了，但 worker 本身没问题”
- 第二跳再排除“这个 worker/backend 当前确实不可用”
- 两跳都失败，就不应该再盲目自动切

### 2.3 自动切换必须保留显式 trace

任何自动恢复都不能做成“静默换 worker”。最少要留下：

- `failure_class`
- `recovery_policy`
- `auto_recovery_attempt_count`
- `previous_worker`
- `target_worker`
- `fallback_reason`
- `handoff_reason`
- `recovery_decision_source=auto_policy`

否则后面用户只会看到“任务怎么突然换了 worker”，没法排查。

---

## 3. 失败分类

建议先把 worker round 失败分成四类。

### 3.1 `worker_runtime_transient`

定义：

- 当前 worker/backend 自身执行环境坏了
- 换同 worker 的新线程/新会话，或者换另一个同能力 worker，有机会恢复

典型信号：

- `failed to start codex app-server`
- `thread not found`
- `session expired`
- `connection reset`
- `timeout`
- `provider unavailable`
- `readiness=false`

策略：

- 允许自动恢复
- 先同 worker 冷重试 1 次
- 再自动切候选 worker 1 次

### 3.2 `worker_backend_deterministic`

定义：

- 当前 worker/backend 的限制导致本轮无法继续，但不是瞬时故障

典型信号：

- 当前 provider 不支持所需工具
- 当前 backend 不支持目标模式
- 当前 worker 缺少必要 capability

策略：

- 不做同 worker 重试
- 可直接自动 handoff 一次到更匹配的候选 worker
- 若不存在更匹配候选，则进入 `human_gate`

### 3.3 `task_environment_blocked`

定义：

- 即使换 worker，大概率也会失败

典型信号：

- 文件不存在
- 目录不存在
- 命令不存在
- 权限不足
- 构建工具缺失
- 工作区本身状态损坏

策略：

- **默认不要自动切 worker**
- 直接进入 `human_gate` 或至少要求人工确认

原因：

- 这不是 worker 选择问题
- 换 worker 只会重复失败，增加噪音

### 3.4 `partial_result_or_quality_risk`

定义：

- 本轮不是纯失败，而是产生了部分结果、部分副作用或质量不确定

典型信号：

- 已写文件
- 已产出 artifact，但 judgment 判定未完成
- 结果质量差，需要换强模型复审
- tool 调用已发生，切 worker 可能重复执行

策略：

- 默认不要静默自动切
- 进入 `human_gate`，给出推荐动作：
  - `retry_same_worker`
  - `handoff_to_worker_x`
  - `continue_after_fix`

---

## 4. 推荐策略：混合式恢复

### 4.1 默认策略

对绝大多数任务，推荐默认策略如下：

1. `execution_status=failed`
2. 对失败输出做 `failure_classification`
3. 按分类执行：

| failure class | 自动同 worker 冷重试 | 自动切候选 worker | 默认人工确认 |
|---|---:|---:|---:|
| `worker_runtime_transient` | 是，1 次 | 是，1 次 | 超预算后 |
| `worker_backend_deterministic` | 否 | 是，1 次 | 无候选或再次失败 |
| `task_environment_blocked` | 否 | 否 | 是 |
| `partial_result_or_quality_risk` | 否 | 否 | 是 |

### 4.2 当前案例的推荐策略

对 `没找到线程 "15252"` 这类 provider runtime 失联问题，建议：

1. 标记 `failure_class=worker_runtime_transient`
2. 同 worker 冷重试一次
   - 不复用旧 provider thread / old app-server session
   - 直接新建本轮执行会话
3. 如果同 worker 冷重试仍失败
   - 自动 handoff 到下一候选 worker
4. 如果候选 worker 再失败
   - 进入 `human_gate`
   - UI 明确提示：
     - 已自动重试 1 次
     - 已自动切 worker 1 次
     - 当前需要人工决定是否继续

### 4.3 当前已落地的诊断与状态同步行为

对这类 `thread not found / provider unavailable / failed to start / timeout` 故障，当前代码里已经额外落了三层可观测性：

1. **recovery 决策日志**
   - `ControlNodeGraph` 会记录：
     - `failureClass`
     - `previousWorker`
     - `sameWorkerRetryCount`
     - `autoHandoffCount`
     - `handoffTarget`
   - 关键日志前缀：
     - `[Recovery] ...`

2. **worker temporary unavailable -> readiness 联动**
   - auto-handoff 前，失败 worker 会被标成 temporary unavailable
   - `WorkerRegistry.checkReadiness(...)` 会额外返回：
     - `runtime_available`
   - 在同一进程内，readiness reason 会变成：
     - `temporarily unavailable: ...`

3. **旧 task worker metadata 自愈**
   - 真实 task 若已经从 `codex -> openclaw-native` 切走，但 metadata 仍残留旧 worker
   - `schedulerNode(...)` 入口会自动修正：
     - `metadata.assigned_worker`
     - `metadata.target_worker`
     - `metadata.preassigned_selection_reason`
   - 关键日志前缀：
     - `[Scheduler] ... normalized worker metadata ...`

这三层一起的意义是：

- recovery 不再只是“内部自动切了 worker”，而是 route / readiness / trace 至少对齐到同一份状态
- 但这仍然**不是持久化熔断器**；一旦 harness 重启，temporary unavailable 状态会清空，worker 会重新按 provider/tool 检查回到普通 readiness

### 4.3 当前源码已实际落地的部分

结合当前 `ControlNodeGraph` 可以确认，下面这些不是计划，而是**已实现**：

1. `continueNode(...)` 会先调用 `maybePlanFailureRecovery(...)`
2. `worker_runtime_transient` 且 `auto_same_worker_retry_count < 1`
   - 会生成 `same_worker_retry_scheduled`
   - 并在同一次 `continue` 流程里直接回到 `schedulerNode(...)`
3. `worker_runtime_transient` 且已用掉 same-worker retry、但 `auto_handoff_count < 1`
   - 会生成 `auto_handoff_scheduled`
   - 并在同一次 `continue` 流程里直接 `handoffNode(..., true) -> schedulerNode(...)`
4. 两个预算都耗尽
   - 会生成 `human_gate_required`
   - 任务进入 `waiting_human / human_gate`

目前源码已补上的情况是：

- `task_environment_blocked` 已经独立分类，并直接进入 `human_gate`
- `worker_backend_deterministic` 已经独立分类，并优先尝试一次 `auto_handoff`
- `partial_result_or_quality_risk` 已经按最小边界独立分类，并直接进入 `human_gate`

本轮推进的优先顺序建议也明确一下：

1. **先落 `task_environment_blocked`**
   - 已完成
   - 它已经避免了“明明是缺文件/缺命令/权限问题，却还自动切 worker”这种无效恢复
2. 再考虑 `worker_backend_deterministic`
3. 最后再处理 `partial_result_or_quality_risk`
   - 已完成最小实现
   - 当前边界只覆盖“已有部分结果/已有副作用风险”的保守识别，不等于已经具备完整质量判断

这条最后一类 failure class 的最小实现边界也需要写实一点，避免和“理想设计”混在一起：

- 不要求一开始就理解所有质量问题
- 当前最小实现只识别 **已有部分结果/已有副作用风险** 这类最容易误自动恢复的信号
- 例如：
  - `produced_artifact=true`
  - `grounded_output_present=true`
  - `file_backed_artifact / directory_backed_artifact=true`
  - 已有 `tool_invocation_ids`
  - 已有 `unfinished_items`
- 这类情况即使 worker round 最终失败，也默认不要静默自动切 worker；应直接进入 `human_gate`

---

## 5. 与当前控制图的对齐方式

现有控制图已经有：

- `scheduler`
- `continue`
- `handoff`
- `human_gate`

所以不需要新造一套状态机。当前真实实现已经是在 `continue` judgment 之后补了一层恢复策略：

```text
worker round failed
    ->
continue node collects execution boundary
    ->
failure classification
    ->
if auto retry allowed:
    scheduler (same worker cold retry)
else if auto handoff allowed:
    handoff -> scheduler
else:
    human_gate
```

### 5.1 不建议直接在 `scheduler` 里静默换 worker

原因：

- `scheduler` 的职责是“初始路由/继续执行”
- 自动恢复属于“失败后的显式迁移”
- 迁移应该留 `handoff` trace 和 packet 证据

所以：

- **同 worker 冷重试** 可以直接回 `scheduler`
- **跨 worker 自动切换** 应尽量走 `handoff -> scheduler`

这样 observability 才完整。

---

## 6. 推荐新增 metadata / trace 字段

建议在 task metadata、decision metadata、assistant progress message metadata 里补：

- `failure_class`
- `failure_signature`
- `failure_summary_readable`
- `recovery_policy`
- `recovery_decision_source`
- `auto_recovery_attempt_count`
- `same_worker_retry_count`
- `auto_handoff_count`
- `previous_worker`
- `auto_handoff_target`
- `recovery_blocked_reason`

其中：

- `failure_signature` 用来做去重和预算控制
- `failure_summary_readable` 用来避免直接把 mojibake 暴露给 UI
- `failure_summary_readable` 还应是**短摘要**，不应原样携带长 prompt 回显、目录 listing、整段协议输出或大段乱码；这些原始内容继续下沉到 `details / live_flow / artifact`
- 这条“短摘要优先”不只适用于 `continue` 阶段的 recovery directive；`scheduler` 里合成 failed execution result 时，也应先清洗异常消息，再写入 `failure_summary_readable / output_text / artifact_content`，否则脏摘要会先持久化进 task/artifact，再被 UI 反复继承

---

## 7. UI 行为建议

`/dialogue/` 不应该把失败后的所有复杂策略都塞进主聊天流，但应该给出足够可操作的信息。

推荐：

### 7.1 主聊天流

失败回执显示为可读摘要，而不是原始乱码：

- `worker openclaw-native 返回了不可读错误输出；系统将先尝试冷重试。`
- 或：
  `worker openclaw-native 再次失败；系统已切换到 codex。`
- 或：
  `当前失败更像工作区/工具问题，已暂停等待人工确认。`

### 7.2 details panel

显示：

- `failure_class`
- `execution_status`
- `previous_worker -> target_worker`
- `auto_recovery_attempt_count`
- `recommended action`
- 如需原始失败输出、provider stderr、目录 listing、协议 trace，应继续放在 details / live_flow / artifact，而不是顶到主聊天流

### 7.3 手工动作

当自动策略停在 `human_gate` 时，UI 最少应给：

- `重试当前 worker`
- `切换到候选 worker`
- `继续保持人工确认`

---

## 8. 第一阶段落地建议

不要一步做到“全自动多 worker 自愈”。建议分三步：

### Phase A: 先把失败变可读

先做：

- 失败分类
- readable failure summary
- `/dialogue/` 不再直接显示 mojibake

先不做自动切 worker。

### Phase B: 同 worker 冷重试

只对 `worker_runtime_transient`：

- 自动冷重试 1 次
- 同时留下 trace
- 当前实现应先把 **worker executor 异常** 收成标准 failed round，而不是直接把控制图炸断
- 这样 `/dialogue/`、`task_progress`、`live_flow`、`judgment_trace` 才能看到同一条失败链
- 当前实现应优先只覆盖最小可判定信号：
  - `execution_status=failed`
  - `failure_summary_readable` 或 `output_text / artifact_content` 命中 runtime/provider 失联关键词
  - 例如 `thread not found / session expired / provider unavailable / failed to start / connection reset / timeout`

### Phase C: 自动 handoff 一次

在 Phase B 通过后，再加：

- 候选 worker 自动 handoff 1 次
- 成功后把结果反写 learning memory
- 当前实现边界应保持保守：
  - 只对 `worker_runtime_transient` 生效
  - 只允许一次 same-worker retry 和一次 auto handoff
  - 超出预算直接进 `human_gate`
  - 当前实现优先使用 `WorkerRouter.RouteResult.fallbackWorkers`，没有候选再回退到 `candidateWorkers`
  - 不做“多跳轮询所有 worker”，避免静默放大副作用

### Phase D: human_gate 收尾

最后补：

- 预算耗尽后自动进 `human_gate`
- UI 给出推荐动作而不是裸失败

---

## 9. 最终建议

对“像当前这种失败”：

- **不建议直接让用户每次手工选 worker**
- **也不建议一失败就静默自动切别的 worker**

更稳的策略是：

1. **先判定是否是 runtime/provider 瞬时故障**
2. **若是，先同 worker 冷重试一次**
3. **仍失败，再自动 handoff 一次到候选 worker**
4. **再失败，进入 `human_gate`，由用户决定**

这条策略最符合当前项目已有的 `scheduler / continue / handoff / human_gate` 结构，也最容易在 `/dialogue/` 上给出清楚的回执与排障入口。

---

## 9. 和 `/dialogue/` 可见性直接相关的补充约束

自动恢复如果只发生在后端 trace 里，用户体验仍然会很差。`/dialogue/` 至少应直接露出下面这些字段：

- `failure_summary_readable`
- `failure_class`
- `auto_same_worker_retry_count`
- `auto_handoff_count`
- `auto_handoff_target`
- `recovery_policy`
- `recovery_stage`

推荐 UI 表现：

1. 主聊天流里的 `task_progress / task_result`
   - collapsed 时显示一行可读摘要
   - 仍要能看出：
     - `same-worker retry 1/1`
     - `handoff to codex 1/1`
     - `entered human_gate`
   - 若失败正文或 worker output 可用，也应允许直接在聊天流里展开完整内容，而不是只能跳去 details
2. 右侧 details / live_flow
   - 保留完整 raw trace 与 packet/handoff 证据

这样“自动恢复”才不是静默发生，而是用户能直接判断：

- 系统只是短暂出错并已自愈
- 还是已经尝试过恢复，当前需要人工介入

---

## 10. 当前最小实现范围（2026-05-13）

当前代码层的最小恢复链只应承诺下面这些行为：

1. `scheduler` 里 worker executor 抛异常时
   - 不再直接中断整个控制图
   - 而是转成一轮 `execution_status=failed` 的标准 worker round

2. `continue` 里只识别最小 `worker_runtime_transient`
   - 命中 runtime/provider 失联关键词
   - 且当前预算未超

3. 自动恢复预算固定为：
   - `same-worker retry`: 最多 1 次
   - `auto handoff`: 最多 1 次

4. transcript / details 可见字段至少包括：
   - `failure_class`
   - `failure_summary_readable`
   - `recovery_policy`
   - `recovery_stage`
   - `auto_same_worker_retry_count`
   - `auto_handoff_count`
   - `auto_handoff_target`

5. 当前实现**不承诺**：
   - 自动修复 `task_environment_blocked`
   - 自动清理历史 mojibake 消息
   - 多候选 worker 的多跳轮询
   - 基于 learning memory 的复杂恢复策略闭环
