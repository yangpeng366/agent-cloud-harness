# Budget Timeout Recovery Regression 2026-08-02

## 任务

- 修复探针任务 	ask_21f7c333c57e4514 在 worker_budget_exhausted 后无法自动 same-worker retry 的问题。
- 根因：fresh-session recovery 必须重置 blocked subgoal_status，否则 goal-progress 判断会立刻把 /continue 映射回 human_gate。

## 已落地

- src/main/java/com/agentcloud/engine/TaskService.java#L2343 的 prepareFreshSessionRecovery 已在 HEAD 中完成修复：
  - 将 blocked subgoal 回置为 pending
  - 清空 waiting_reason
  - 回写 status=active / control_node=scheduler
- 新增测试：WorkerBudgetExhaustedRecoveryTest.prepareFreshSessionRecoveryClearsBlockedSubgoalsAndResetsWaitingState
- 已跑回归：
  - WorkerBudgetExhaustedRecoveryTest
  - ControlNodeGraphOrchestrationFlowTest#sameWorkerRetryColdStartClearsProviderContinuationMetadataBeforeNextRoundExecution
  - ControlNodeGraphOrchestrationFlowTest#recoveryFallbackEmptyOutputStopsAtHumanGateAfterRetryAndSingleHandoff

## 现场状态

- 工作区状态：无 merge conflict。
- 未提交文件：
  - STATE.md
  - docs/evaluation/PROGRESS.md
  - docs/evaluation/runs/README.md
  - src/test/java/com/agentcloud/engine/WorkerExecutionTimeoutConfigTest.java
  - docs/LONG_STABILITY_SMOKE_25200S_EXECUTION_RECORD_2026-08-02.md
  - scripts/Run-LongStabilitySmoke.ps1
- live task 	ask_21f7c333c57e4514：
  - API JSON：status=active、control_node=scheduler、subgoal_status=[pending]
  - 持久化 SQLite 副本：同样是 ctive/scheduler/pending
  - 仍显示 ecovery_stage=manual_recover_scheduled 的原因是运行中 JAR 还是旧构建：D:\gitAll\agent-cloud-harness\.tmp\runtime-jars\agent-cloud-harness-0.1.0-SNAPSHOT-shaded-port9091-20260731-135115.jar
- 分支：ix/interrupt-readiness-20260802；尚未配置 upstream。

## 下一步

1. 重建 shaded JAR 并热替换 9091 实例。
2. 对 	ask_21f7c333c57e4514 重新执行自动 recovery，验证真实 25200s smoke 继续推进。
