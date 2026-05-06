# Harness Change Contract

用于记录每一次 Harness 组件修改的意图、风险、验证方式和回滚边界。

这个契约的目的不是增加流程负担，而是避免 Harness 逐步演进后变成不可解释的提示词和代码堆叠。每次修改都应该能回答：为什么改、预期修什么、可能破坏什么、怎么验证、怎么回滚。

## 模板

```md
# Harness Change Contract: <change-id>

## 1. 背景

- Trigger task / issue:
- Observation:
- First failure point:
- Related trace / evidence refs:

## 2. 修改范围

- Changed components:
  - [ ] Provider
  - [ ] Worker executor
  - [ ] Tool layer
  - [ ] Middleware / routing
  - [ ] Memory
  - [ ] Judgment / completion
  - [ ] Eval case
  - [ ] Docs only
- Changed files:

## 3. 预期收益

- Expected fixes:
- Expected behavior after change:
- Tasks or scenarios likely improved:

## 4. 回归风险

- Possible regressions:
- Components at risk:
- Existing scenarios that must still pass:

## 5. 验证计划

- Compile command:
- Unit / integration tests:
- Manual checks:
- Eval scenarios:

## 6. 结果

- Verification result:
- New failures:
- Token / runtime impact if relevant:

## 7. 决策

- Decision: keep / adjust / rollback
- Rollback files:
- Follow-up tasks:
```

## 当前示例：WorkerExecutionResult schema 漂移修复

```md
# Harness Change Contract: worker-result-schema-sync

## 1. 背景

- Trigger task / issue: Agent Provider skeleton 接入后尝试编译。
- Observation: 编译失败点不在 Provider skeleton，而在 ToolAwareWorkerExecutor 调用 WorkerExecutionResult 构造函数。
- First failure point: ToolAwareWorkerExecutor 仍按旧构造签名传参。
- Related trace / evidence refs: Maven compile output。

## 2. 修改范围

- Changed components:
  - [x] Worker executor
  - [x] Judgment / completion result schema usage
  - [ ] Provider
  - [ ] Tool layer
  - [ ] Memory
  - [ ] Docs only
- Changed files:
  - src/.../ToolAwareWorkerExecutor.java
  - 如有必要，补充相关测试或 eval case。

## 3. 预期收益

- Expected fixes:
  - 所有 WorkerExecutionResult 构造点同步到当前 schema。
  - 编译不再因旧签名调用失败。
- Expected behavior after change:
  - tool-aware worker 返回结果包含 executionStatus、evidenceRefs、unfinishedItems、tokenUsage、durationMs、metadata。
- Tasks or scenarios likely improved:
  - tool-aware worker 执行链。
  - provider skeleton 后续编译验证。

## 4. 回归风险

- Possible regressions:
  - 字段位置传错导致 tokenUsage/durationMs/metadata 被写入错误语义字段。
  - 未完成任务或证据引用为空时前端展示异常。
- Components at risk:
  - Web console result display。
  - Dialogue message result rendering。
  - Judgment completion flow。

## 5. 验证计划

- Compile command:
  - mvn compile 或仓库 Java 21 构建脚本。
- Unit / integration tests:
  - 最小 tool-aware task 执行。
- Manual checks:
  - 确认 WorkerExecutionResult 字段语义正确。
- Eval scenarios:
  - 增加 tool-aware worker result schema guard case。

## 6. 结果

- Verification result:
  - `mvn -q -Dtest=ToolAwareWorkerExecutorMultiToolTest test` 通过。
  - `mvn -q -DskipTests compile` 通过。
  - 以上命令均已通过 `. .\scripts\Use-Java21.ps1` 切到 Java 21。
  - 已补充 schema guard：tool-aware finalization 解析出的 `execution_status`、`evidence_refs`、`unfinished_items` 会穿过 `finalizeMultiToolResult` / `attachMultiToolMetadata`，不再在包装元数据时丢失。
- New failures: 未发现。
- Token / runtime impact if relevant: 不适用，本次只增加 schema guard 测试并修复字段透传。

## 7. 决策

- Decision: keep。
- Rollback files:
  - src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java
  - src/test/java/com/agentcloud/worker/ToolAwareWorkerExecutorMultiToolTest.java
- Follow-up tasks:
  - 后续若 WorkerExecutionResult 新增字段，应同步扩展 `executeOneRoundResultPreservesHarnessEvolutionSchemaFields`。
  - 可继续把 Harness Change Contract 从文档升级为 `.tmp/changes/` 下的结构化记录。
```

## 当前示例：Harness schema 进入 live flow / experiment run

```md
# Harness Change Contract: worker-result-schema-live-flow

## 1. 背景

- Trigger task / issue: AHE 启发要求 Harness 演进字段不仅停留在 executor 返回值，还要进入可观察诊断面。
- Observation: `execution_status`、`evidence_refs`、`unfinished_items` 已在 `WorkerExecutionResult` 中保留，但 experiment/live_flow 聚合只带 tool chain 字段。
- First failure point: `ExperimentRunService.copyLatestWorkerMetadata` 没有复制这些 schema 字段。
- Related trace / evidence refs: `TaskServiceLiveFlowViewTest#getLiveFlowCarriesExperimentRunToolChainSummary`。

## 2. 修改范围

- Changed components:
  - [x] Middleware / routing
  - [x] Eval case
  - [ ] Worker executor
  - [ ] Provider
  - [ ] Tool layer
  - [ ] Memory
- Changed files:
  - src/main/java/com/agentcloud/engine/ExperimentRunService.java
  - src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java

## 3. 预期收益

- Expected fixes:
  - live_flow / experiment_run 元数据可直接看到 Worker 执行状态、证据引用和未完成项。
  - 后续 AHE 风格复盘可以从聚合视图读取失败证据，不必只翻原始 executor 返回。
- Expected behavior after change:
  - `flow.experimentRun().metadata()` 包含 `execution_status`、`evidence_refs`、`unfinished_items`。
- Tasks or scenarios likely improved:
  - live flow debugging。
  - experiment matrix failure analysis。
  - harness evolution trace review。

## 4. 回归风险

- Possible regressions:
  - 元数据体积略增。
  - 前端如果假设 metadata 只有标量，可能需要兼容 list 字段。
- Components at risk:
  - Experiment run metadata rendering。
  - Live flow JSON consumers。
- Existing scenarios that must still pass:
  - Tool chain summary projection。
  - Worker result schema guard。

## 5. 验证计划

- Compile command:
  - `. .\\scripts\\Use-Java21.ps1; mvn -q -DskipTests compile`
- Unit / integration tests:
  - `. .\\scripts\\Use-Java21.ps1; mvn -q "-Dtest=TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest" test`
- Manual checks:
  - 确认 PowerShell 下多测试名参数需要整体加引号，避免逗号被解析。
- Eval scenarios:
  - 多工具 worker round 产生 blocked + evidence refs + unfinished items。

## 6. 结果

- Verification result:
  - `mvn -q "-Dtest=TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest" test` 通过。
  - `mvn -q -DskipTests compile` 通过。
  - 以上命令均已通过 `. .\\scripts\\Use-Java21.ps1` 切到 Java 21。
- New failures:
  - 首次未加引号执行 `-Dtest=TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest`，PowerShell 将逗号解析为参数列表导致 parser error；已用引号修正。
- Token / runtime impact if relevant: metadata 多复制 3 个轻量字段，影响可忽略。

## 7. 决策

- Decision: keep。
- Rollback files:
  - src/main/java/com/agentcloud/engine/ExperimentRunService.java
  - src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java
- Follow-up tasks:
  - 可继续新增独立 `/harness_trace` 聚合视图，把 worker result、tool trace、judgment trace、agent run artifacts 压缩成 AHE 复盘输入。
```

## 当前示例：AHE harness trace 聚合视图

```md
# Harness Change Contract: task-harness-trace-view

## 1. 背景

- Trigger task / issue: AHE 论文强调自动复盘需要结构化轨迹压缩，当前已有 live_flow 但没有专门面向 Harness 演进的精简输入。
- Observation: worker result、tool trace、judgment trace、agent run artifacts 分散在多个接口/视图中。
- First failure point: 复盘方需要拼接 `/tool_trace`、`/judgment_trace`、`/live_flow` 才能判断执行状态、证据和未完成项。
- Related trace / evidence refs: `TaskServiceLiveFlowViewTest#getHarnessTraceCompressesAheReviewInputs`。

## 2. 修改范围

- Changed components:
  - [x] Middleware / routing
  - [x] Eval case
  - [x] API
  - [ ] Worker executor
  - [ ] Provider
  - [ ] Tool layer
  - [ ] Memory
- Changed files:
  - src/main/java/com/agentcloud/model/HarnessTraceView.java
  - src/main/java/com/agentcloud/engine/TaskService.java
  - src/main/java/com/agentcloud/server/TaskHandler.java
  - src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java

## 3. 预期收益

- Expected fixes:
  - 新增 `GET /api/v1/tasks/{id}/harness_trace`，直接返回 AHE 复盘所需的压缩视图。
  - 聚合 execution status、evidence refs、unfinished items、route preview、experiment run、judgments、tool invocations、agent run events/artifacts。
  - 当 experiment run / artifact 投影缺失时，从 tool invocation metadata 兜底恢复 Harness schema 字段。
- Expected behavior after change:
  - 调用方无需拼多个接口即可生成 Harness 复盘输入。
- Tasks or scenarios likely improved:
  - Harness 自动演进。
  - 失败轨迹复盘。
  - Eval 回归分析。

## 4. 回归风险

- Possible regressions:
  - 新接口复用 `experimentRunService.refresh`，读取时会刷新 experiment run 投影。
  - 返回体包含多个聚合列表，limit 过大时响应变大。
- Components at risk:
  - TaskHandler 路由匹配。
  - TaskService 聚合逻辑。
- Existing scenarios that must still pass:
  - live_flow。
  - tool-aware schema guard。

## 5. 验证计划

- Compile command:
  - `. .\\scripts\\Use-Java21.ps1; mvn -q -DskipTests compile`
- Unit / integration tests:
  - `. .\\scripts\\Use-Java21.ps1; mvn -q "-Dtest=TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest" test`
- Manual checks:
  - 确认 `/harness_trace` 路由放在 `/tool_trace` 之前，避免后缀误判。
- Eval scenarios:
  - blocked tool chain + evidence refs + unfinished items。

## 6. 结果

- Verification result:
  - `mvn -q "-Dtest=TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest" test` 通过。
  - `mvn -q -DskipTests compile` 通过。
  - 后续补充 HTTP contract 后，`mvn -q "-Dtest=TaskHandlerProviderSelectionHttpTest,TaskServiceLiveFlowViewTest,ToolAwareWorkerExecutorMultiToolTest" test` 通过。
  - 补充 tool invocation metadata fallback 后，`mvn -q "-Dtest=TaskServiceLiveFlowViewTest,TaskHandlerProviderSelectionHttpTest" test` 通过。
  - 补充 optional DAO 与字段命名契约检查后，`mvn -q "-Dtest=TaskHandlerProviderSelectionHttpTest,TaskServiceLiveFlowViewTest" test` 通过。
  - 以上命令均已通过 `. .\\scripts\\Use-Java21.ps1` 切到 Java 21。
- New failures:
  - HTTP contract 初次接入时暴露 `TaskService#getHarnessTrace` 在轻量 fixture 中强依赖 `runtimeContextBuilder`，返回 500；已调整为 runtime context 缺省时 judgment 字段为空，但 trace 仍可返回。
  - 后续检查发现 trace 过度依赖 experiment run 投影；已增加从 `tool_invocations.metadata` 兜底补齐 `execution_status`、`evidence_refs`、`unfinished_items` 与 tool chain 摘要字段。
  - 再次检查发现轻量构造路径可能不注入 `toolInvocationDao`，已让 live_flow / harness_trace / tool_trace 在 DAO 缺省时返回空工具轨迹而不是 500。
  - API 文档曾使用旧字段名 `judgment_action` / `suggested_next_action`，已校正为实际 JSON 字段 `recommended_action` / `recommended_next_step`，并补 HTTP 断言避免旧字段回流。
- Token / runtime impact if relevant: 新接口只按 limit 拉取相关列表，默认沿用现有 bounded limit。

## 7. 决策

- Decision: keep。
- Rollback files:
  - src/main/java/com/agentcloud/model/HarnessTraceView.java
  - src/main/java/com/agentcloud/engine/TaskService.java
  - src/main/java/com/agentcloud/server/TaskHandler.java
  - src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java
  - src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java
- Follow-up tasks:
  - 后续可增加 trace compression prompt/materializer，把 HarnessTraceView 转成 AHE 修改建议输入。
```
