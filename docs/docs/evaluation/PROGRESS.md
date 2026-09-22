# Evaluation PROGRESS

> 本文件只跟踪 §4 控制流验证线的活跃进度。历史任务、过期探针、详细 trace 链接下沉到 uns/<date>/。

## 当前主线：§4.1 编排 handoff 验证

### §4.1 #5 utoUpdateSubgoalStatus execution_pending 守卫 — ✅ 完成 + 验证

- 改动位置：src/main/java/com/agentcloud/engine/ControlNodeGraph.java:1483
- 守卫逻辑：当 model_mode=orchestrated 且 orchestration_stage=execution_pending（planner→executor handoff 窗口）时，跳过 subgoal 自动完成，避免 esolveAction 把 ction=handoff 短路成 done。
- 单元/集成测试：3 个套件全绿（ControlNodeGraphOrchestrationFlowTest 17/0、GoalProgressAutoUpdateTest 11/0、AdvisoryHandoffTest 12/0）。
- 真实探针：	ask_6886b7bacc1c4ace on 9091（PID 40468）在 14:00:50 出现首次真实的 worker=codex-free node=scheduler stage=execution_pending action=handoff — 修复前从未发生过。
- 修复前 JAR bytecode 缺该守卫，修复后 JAR（gent-cloud-harness-0.1.0-SNAPSHOT-shaded-port9091-20260731-135115.jar）javap 可见 isOrchestrated + xecution_pending 字符串常量与 goto 跳过分支。

### §4.1 #6 codex-free（ccx-free）模型输出质量 — ⚠️ Open（provider 问题，不是 control flow）

- 现象：探针任务 	ask_6886b7bacc1c4ace 在 14:00:50 由 codex-free 真实执行了一轮（exit_code=0, durationMs=68953, partial_output_chars=4671），但 output_text 完全是乱码（片段：###\nupdate_command MATLAB\narguments {" "\ncommand \ Get Get-Start -contentofoffromtheTheMarkdownththedocsREADME.md），无 tool call。
- 续轮现象：15:16 这轮 codex-free 直接 timeout（worker_budget_exhausted → same_worker_retry(budget)），模型现在连乱码都不稳定。
- 结论：control flow 修复对 §4.1 #5 是闭合的；§4.1 #6 是模型能力问题，不在 control flow 范围。
- 跟进建议：
  1. 单独评估 ccx-free 模型是否需要替换，或为 reading 类型任务做小模型 fallback 规则。
  2. 设计 handoff-loop circuit breaker：当 scalate_from_small_tier 触发且 task goal 显式要求 	arget_worker 时，跳过 escalation 直接进入 human_gate，避免无谓 retry。

### §4.1 #7 探针 fixture 文档面缺失 — ✅ 已修复

- 现象：续轮 judgment 明确写到 "the specified docs/README.md is absent"。
- 根因：仓库里 docs/ 整个目录在最近一次清理后只剩 .tmp/，目标文件本来就不在。task 探针依赖的 fixture 文件被误清。
- 修复：本次把 docs/README.md（首标题 # Docs README）、docs/evaluation/README.md、docs/evaluation/PROGRESS.md、STATE.md、DECISIONS.md 等基础文档面补回，task 现在可以读到目标文件。

## 探针任务运行证据

| 任务 ID | 实验名 | 时间窗 | 关键事件 | 结论 |
| --- | --- | --- | --- | --- |
| 	ask_6886b7bacc1c4ace | p1-postfix-real-handoff-20260731-1355 | 13:55→15:46 | codex planner → codex-free 执行 → escalation → codex 多轮循环 → human_gate | §4.1 #5 验证通过；§4.1 #6 暴露 provider 质量问题；§4.1 #7 因 docs 缺失卡住，已修复 |

## 本轮状态（截至 15:46）

- 任务当前 status=waiting_human, control_node=human_gate, assigned_worker=codex。
- 最新 judgment：Completion judgment: incomplete，理由是 "Worker round failed and automatic recovery budget is exhausted."。
- codex 在 15:41:51 被 dispatch，15:46:50 timeout（durationMs=904205），outputLength 太小无法收敛。
- 修复文档后并未跑通完整 cf→codex→cf 收敛，因为 codex provider 本身 hang 住了。

## 待结清项

1. **源码仍在仓库外**：src/main/java/com/agentcloud/engine/ControlNodeGraph.java 目前仅剩 JAR bytecode，控制流旁支无法进一步修复。
2. **ccx-free / codex 模型稳定性**：和 control flow 正交，单独走 provider 决策线。
3. **restart task 前**：如果需要从 human_gate 重新触发，建议先记录 	ask_6886b7bacc1c4ace 的最终状态，再决定是重跑同一任务还是新建探针。