# Evaluation 评估专题入口

评估专题只负责一件事：把"控制流是否真的按设计走"这一类问题，用探针任务+证据链的方式跑出来并沉淀。本入口不写代码设计，也不做实现讨论，那些分别回到 continuity/、provider/、dialogue/、elease/。

## 阅读顺序

1. 先读 PROGRESS.md：当前 §4 验证线的进度、卡点、未结清项。
2. 再读 uns/（如启用）：按日期组织的探针任务运行证据。
3. 还需要回到专题细节，按 PROGRESS.md 中的指针打开对应 uns/<date>/... 或专题 rchive/。

## 边界

- 本专题的"实验"指的是探针任务（probe task），不是单元/集成测试。
- 单元/集成测试在 src/test/java/ 下，由 ControlNodeGraphOrchestrationFlowTest、GoalProgressAutoUpdateTest、AdvisoryHandoffTest 等覆盖，不在本专题入口里展开。
- 探针任务通过 API POST /api/v1/tasks 提交，必须带 xperiment_name 与 intent，否则不算评估证据。

## 写回规则

- 探针结果先写回 uns/<date>/<task_id>-<short>.md，再提炼到 PROGRESS.md。
- 跨主题结论（如某个修复同时影响 continuity）回到对应主题入口，本专题只留指针。
- 评估口径变化（如新增 invariant）必须同步 docs/SPEC.md，并把对应测试回写到 src/test/java/。

## 已知陷阱

- 探针任务一定要带 subgoal_status: in_progress，否则 utoUpdateSubgoalStatus 不会按设计跑完。
- orchestration_stage 字段不会自动从 xecution_pending 升到 xecution_active，需要先确认 control graph 是否走对了路径，再下"模型输出质量差"的结论。
- ccx-free / codex-free 在 reading 类型任务上的输出质量不能用作 control flow 是否达成的判据；control flow 达成只看 ssigned_worker + 
ode=scheduler 是否被实际派发到目标 worker。