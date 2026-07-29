# 控制图异步化竞态分析与 instance-identity 修复方案

> 背景：2026-07-28 `controlGraph.enter` 异步化（`TaskService.asyncEnterControlGraph`，per-task `enterLock`）解决了 HTTP 超时 kill worker round 线程导致事件/状态丢失的问题。但异步化把 worker round 放到虚拟线程、HTTP 立即返回，拓宽了“worker round 在跑 + 操作者并发触发控制动作”的窗口。本文核实由此引入的跨路径竞态，并提出 AgentENV instance-identity 风格的修复方案（见 `AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md` 启发点 1）。
> 性质：方案文档。方案 A（instance-identity 防 clobber，成功+失败双路径）已落地并验证；方案 C-resume（防双轮并发执行，resume tryLock-abort + recovery 回退 async continue）已落地并验证。

## 已核实的竞态

### 同路径：已被 enterLock 串行化（安全）

`asyncEnterControlGraph`（`TaskService.java:245`）对同一 task 用 `enterLocks[task.id()]` 的 `synchronized(lock)` 包住整个 `controlGraph.enter(latest)`（含 worker round）。enter 入口还 re-read `latest = taskDao.findById(...)`。因此同一任务多次 `createTask`/`continueTask` 的并发 enter 会被串行化，不会重跑。✅

### 跨路径：trigger* 与 recovery 绕过 enterLock（竞态）

以下路径直接 `taskDao.updateState` 或直接跑 worker round，**不获取 enterLock**：

- `triggerPause` / `triggerEscalate` / `triggerHandoff` / `triggerResume`（`ControlNodeGraph.java:2190` / `:2197` / `:2205` / `:2213`），由 `TaskService.pauseTask`/`escalateTask`/`handoffTask`/`resumeTask` 在 HTTP 线程同步调用。
- `recoverTask`（`TaskService.java:293`）：`prepareFreshSessionRecovery` + `taskDao.updateState(prepared)` 直接改状态；`resumeTask` 分支再调 `triggerResume`（跑 worker round）；`handoff` 分支调 `triggerHandoff`。`recoverTaskAsync`（`:332`）在独立 `agentcloud-recovery-` 虚拟线程跑。

### clobber 根因

`TaskDao.updateState`（`TaskDao.java:48`）是**无条件** `UPDATE tasks SET ... WHERE id = :id`，无版本号/状态乐观并发。`schedulerNode`（`ControlNodeGraph.java:257`）在 worker round 返回后直接 `Task moved = task.withControlNode("continue"); taskDao.updateState(moved);`，**不 re-read、不检查任务是否已被并发改动**。

### 具体场景

1. **pause 被 clobber**：enter() A 在跑 worker round（持 enterLock）；操作者 `pauseTask` -> `triggerPause` 直接 `updateState(paused/packet)`。A 的 round 结束，schedulerNode `updateState(continue)` 覆盖回 continue -> 暂停丢失，round 结果被强加。
2. **resume 双轮并发**：enter() A 在跑；`resumeTask` -> `triggerResume` -> `schedulerNode` 在 HTTP 线程再起一轮 worker round。同一任务两个 worker round 并发跑（双 `agent_run`、双 provider 调用、最后写者覆盖）。
3. **recovery clobber / 双轮**：`recoverTaskAsync` 线程 `prepareFreshSessionRecovery` 直接 `updateState`，与 A 的 round 互覆盖；`resumeTask` 分支触发场景 2。
4. **handoff/escalate clobber**：同理，`triggerHandoff`/`triggerEscalate` 的 `updateState` 被 A 的 round 回写覆盖。

## 修复方案与取舍

### 方案 A：instance-identity token（推荐，对齐 AgentENV）

借鉴 AgentENV `(sandboxId, sandboxInstanceId)` + “忽略非最新实例的 stop”：给每个任务的当前执行实例一个单调 token（存 task metadata `exec_instance`，无需改 schema）。

- enter() 入口捕获 `entryInstance = current exec_instance`。
- trigger* / recovery 在改状态前 `bumpExecInstance`（+1）。
- `schedulerNode` 在 worker round 返回后、apply 结果前，re-read 任务，若 `current exec_instance != entryInstance` -> 丢弃本轮结果（emit `stale_round_discarded` 事件），返回 DB 当前任务，不 clobber。
- 优点：非阻塞、稳健、精确对齐 AgentENV 范式；只丢弃过期结果，不阻塞控制动作。
- 代价：触及 enter/schedulerNode/continueNode + 4 个 trigger + recovery；需补测试。
- 局限：只防 clobber，不防“双轮并发执行”（场景 2 仍会并发跑两轮，但 instance-identity 保证只有最新轮的结果落库）。要彻底防双轮，需叠加方案 C 的 tryLock-abort。

### 方案 B：updateState 乐观并发

给 `updateState` 加 `WHERE id=:id AND (status=:expectedStatus OR version=:expected)`，返回 0 行则视为并发冲突。

- 优点：改动小、写时检测。
- 缺点：不防双轮；冲突后需 retry/丢弃逻辑，语义不如 instance-identity 直观；status 预期值在多处路径难统一。

### 方案 C：扩展 enterLock 到 trigger*/recovery（tryLock + abort-if-busy）

trigger*/recovery 用 `tryLock(enterLock)`：拿到则执行；拿不到（有 in-flight round）则 abort 并返回“任务执行中，稍后重试”。

- 优点：概念最简，既防 clobber 又防双轮。
- 缺点：`triggerResume` 本身要跑 worker round，若改 tryLock-abort 则 resume 在 round 在跑时直接拒绝（语义变化）；若改阻塞 acquire 则 HTTP/recovery 线程被 round 阻塞数百秒（与 enter-async 初衷冲突）。需对每个 trigger 分别决定 abort vs queue。

### 推荐

**方案 A 为主**（防 clobber，对齐 AgentENV，非阻塞），**叠加方案 C 的 tryLock-abort 用于 `triggerResume`/recovery-resume 分支**（防双轮）。两者结合：instance-identity 保证过期轮结果不落库，tryLock-abort 阻止 resume 在 round 在跑时再起一轮。

## 最小落地草图（方案 A + C-resume）

改动点（待 maintainer 确认后实施）：

1. `ControlNodeGraph`：加 `currentExecInstance(Task)` / `bumpExecInstance(Task)`（metadata `exec_instance` 单调整数）。
2. `schedulerNode`：worker round 返回后、`Task moved = ...; taskDao.updateState(moved);` 前，re-read + instance 检查；不匹配则 `emitEvent(... "stale_round_discarded" ...)` 并 `return taskDao.findById(task.id()).orElse(task)`。
3. `triggerPause`/`Escalate`/`Handoff`/`Resume`：状态变更前 `bumpExecInstance`。
4. `TaskService.recoverTask`：`prepareFreshSessionRecovery` 后 `updateState` 前 bump；resume 分支叠加 tryLock-abort。
5. 测试（参考现有 `ControlNodeGraphOrchestrationFlowTest` / `WorkerBudgetExhaustedRecoveryTest` 风格）：
   - `stalePauseNotClobberedByInFlightRound`：round 在跑时 pause，round 结束后任务仍 paused。
   - `staleResumeDiscardsOldRoundResult`：resume bump 后旧 round 结果被丢弃。
   - `resumeWhileRoundInFlightAbortsOrSerializes`：防双轮。

## 落地状态（方案 A，2026-07-28）

已落地并验证（成功+失败双路径 clobber 防护）：

- `ControlNodeGraph`：新增 `execInstance(Task)` / `bumpExecInstance(Task)`（metadata `exec_instance` 单调 token，`bumpExecInstance` 包级可见供 TaskService 用）；`schedulerNode` 在 worker round 前 capture `entryInstance`，round 返回后（成功路径）与 catch 入口（失败路径）re-read 并比对，不匹配则 emit `stale_round_discarded` 事件并返回 DB 当前任务，丢弃本轮结果。
- `triggerPause` / `triggerEscalate` / `triggerHandoff` / `triggerResume`（`ControlNodeGraph.java:2190` 区段）：状态变更前 `bumpExecInstance`。
- `TaskService.recoverTask`：`prepareFreshSessionRecovery` 后 `controlGraph.bumpExecInstance(prepared)` 再 `updateState`。

验证：

- 新增 `src/test/java/com/agentcloud/engine/ControlNodeGraphStaleRoundGuardTest.java`（2 用例：成功路径 + 失败路径，round 期间并发 bump exec_instance + pause，断言 pause 不被 clobber）。`Tests run: 2, Failures: 0, Errors: 0`。
- 控制面 focused 回归（15 个测试类，215 用例）：仅 3 个 `LoopContinueTimeoutInvariantTest` 失败，经 `git stash` 验证为 **pre-existing**（原始代码同样失败，非本轮引入；根因是某 record* 方法 null-check 漂移致 continueTask 不再抛 NPE），与本改动无关。

## 落地状态（方案 C-resume，2026-07-28）

已落地并验证（防 resume 与在跑 worker round 并发起第二轮）：

- `TaskService.enterLocks` 由 `ConcurrentHashMap<String, Object>`（synchronized）改为 `ConcurrentHashMap<String, ReentrantLock>`，作为 enter/continue/resume 共享的 per-task round lock。`asyncEnterControlGraph` 异步路径改用 `lock.lock()/unlock()`，并移除 `enterLocks.remove()`（保留 lock 以保证 tryLock 跨路径共享无竞态；代价：每个曾进入过的 task 留一个 ~48B 的 ReentrantLock，本地/受控规模可接受，未来可在 task 终态时清理）。
- `resumeTask`：调用 `triggerResume` 前 `tryLock`；拿不到（有在跑 round，由另一线程持有）则 abort，task 状态不变、不跑 round，返回 `decision="resume_busy"` + reason；拿到则照常跑 round 后 `unlock`。这把 resume 的同步 round 纳入与 enter/continue 同一把锁的串行化，消除双轮。
- `recoverTask` 的 resume 分支：若 `resumeTask` 返回 `resume_busy`（恢复时恰有在跑 round），回退 `continueTask`（async，排在 enterLock 之后），不阻塞、不 stuck；恢复典型无在跑 round，命中 busy 极罕见。
- 死锁核实：`ControlNodeGraph` 不反向引用 `TaskService`、不起新线程，auto-continue burst 在单次 `enter()` 内同步链式跑（同一锁持有期内），故 resume 持锁跑 round 不会与 enterLock 自死锁；并发 continue/resume 只会排队或 abort。
- 验证：新增 `src/test/java/com/agentcloud/engine/ResumeInFlightGuardTest.java`（2 用例：在跑 round 时 resume abort 且 task 仍 paused / 无在跑 round 时 resume 正常推进）。控制面 focused 回归 17 类 223 用例 0 失败。

## 仍 deferred

- 回归基线入口：`ControlNodeGraphOrchestrationFlowTest`、`ControlNodeGraphStaleRoundGuardTest`、`ResumeInFlightGuardTest`、`WorkerBudgetExhaustedRecoveryTest`、`WorkerExecutionTimeoutConfigTest`。
- 可选增强：`resume_busy` 当前随 200 返回（`decision` 字段区分）；若需更强语义可映射 409 Conflict（需 TaskHandler 调整，非本轮范畴）。per-task ReentrantLock 终态清理待后续按 task 生命周期挂接。
## 参考入口

- 异步 enter + 锁：`src/main/java/com/agentcloud/engine/TaskService.java:245`
- enter / schedulerNode：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java:230`、`:257`
- trigger*：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java:2190`
- recovery：`src/main/java/com/agentcloud/engine/TaskService.java:293`、`:332`
- updateState（无条件）：`src/main/java/com/agentcloud/store/TaskDao.java:48`
- 研究背景：`AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md` 启发点 1