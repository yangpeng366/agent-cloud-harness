# DECISIONS

- 2026-05-05: Keep the trace/flow API shape stable, and move aggregation behind `RuntimeFactSet` first.
- 2026-05-05: Prefer incremental internal refactors over replacing `JudgmentTraceView` / `TaskLiveFlowView` all at once.
- 2026-05-05: Continue converging internal progress/message projection onto `RuntimeFactSet` before introducing broader external contract changes.
- 2026-05-05: Promote `RuntimeFactSetAssembler` to a first-class `TaskService` dependency before expanding the next layer of runtime contracts.
