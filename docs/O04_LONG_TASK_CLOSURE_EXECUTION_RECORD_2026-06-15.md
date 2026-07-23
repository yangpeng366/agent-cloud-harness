# O04 Long Task Closure Execution Record 2026-06-15

## 背景

本轮目的是沿 `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md` 的 O04 主线，先用 focused regression 锁定长任务恢复链的真实收口问题，再决定是否调整 heuristics。

## Round 1: 聚合窄跑发现 O04 失败

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,ControlNodeGraphOrchestrationFlowTest,TaskServiceMessageReceiptTest,TaskServiceExperimentRunLifecycleTest"
```

结果：

- `PacketBuilderProtocolTest`
- `TaskServicePacketContractTest`
- `ConsolidationServiceProtocolTest`
- `TaskServiceMessageReceiptTest`
- `TaskServiceExperimentRunLifecycleTest`

以上聚合窄跑里，实际失败集中在 `ControlNodeGraphOrchestrationFlowTest` 的 3 个场景：

- `recoveryFallbackEmptyOutputStopsAtHumanGateAfterRetryAndSingleHandoff`
- `plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery`
- `localWorkspaceAccessRefusalDoesNotLeaveTaskActiveScheduler`

共同失败信号：

- 断言期望 `auto_handoff_count=1`
- 实际持久化 metadata 里该字段为 `null`

## Round 2: 读取 surefire 与测试源码定位共因

检查：

- `target/surefire-reports/com.agentcloud.engine.ControlNodeGraphOrchestrationFlowTest.txt`
- `src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

结论：

- 3 个失败场景都不是“还没发生 auto handoff”，而是“已经发生过一次 auto handoff，最后因为预算耗尽进入 human gate”。
- `ControlNodeGraph.applyRecoveryDirective(...)` 在 `!directive.autoHandoff()` 分支里会删除：
  - `auto_handoff_count`
  - `auto_handoff_target`
- 这里删 `auto_handoff_target` 是合理的，因为 human gate 不应继续宣称“已排队自动移交目标”。
- 但删 `auto_handoff_count` 会抹掉已经发生过的恢复历史，导致最终 `waiting_human / human_gate` 无法表达“这个任务已经自动移交过一次”。

## Round 3: 最小代码修复

修改：

- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

修复方式：

- human gate / same-worker retry 分支不再删除历史 `auto_handoff_count`
- 仍然删除 `auto_handoff_target`

补充回归：

- `src/test/java/com/agentcloud/engine/ControlNodeGraphActionResolutionTest.java`
- 新增 `applyRecoveryDirectiveRetainsAutoHandoffCountWhenHumanGateFollowsPriorHandoff`

新断言锁定：

- `recovery_stage=human_gate_required`
- 保留 `auto_handoff_count=1`
- 清掉 `auto_handoff_target`

## Round 4: Focused regression 回归通过

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ControlNodeGraphActionResolutionTest,ControlNodeGraphOrchestrationFlowTest"
```

结果：

- 退出码 `0`
- O04 三个失败场景全部恢复
- 新增 action-resolution 回归测试通过

## 结论

- 这次 O04 的真实问题不是 heuristics 判断错，而是 human gate 收口时误删了历史恢复链计数。
- 当前合同已经收口为：
  - 如果任务从未发生过 auto handoff，`human_gate_required` 不应平白带出 `auto_handoff_count`
  - 如果任务已经发生过 auto handoff，再进入 `human_gate` 时必须保留 `auto_handoff_count`
  - `auto_handoff_target` 不应在最终 human gate 里继续保留

## 验证入口

- `src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java`
- `src/test/java/com/agentcloud/engine/ControlNodeGraphActionResolutionTest.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
