# Execution Envelope Phase-1 Status

<!-- 更新时间：2026-05-07 -->

## Why this note exists

This note compresses the recently landed execution-envelope and tool-trace stabilization slice into a repo-local status artifact.

It is meant to answer three questions quickly:

1. what actually landed in code
2. what guarantees are now stable at the single-round execution boundary
3. what the next engineering cut should be

## Scope of this slice

Phase-1 goal was intentionally narrow:

- do not replace current executor return contracts
- do not force `WorkerExecutor` or `ControlNodeGraph` to return a new envelope object yet
- do make single-round execution boundaries materially more traceable and inspectable

The result is a metadata-first execution envelope layer, centered on `WorkerExecutionResult`.

## Landed code surfaces

### 1. New envelope model

Added:

- `src/main/java/com/agentcloud/worker/model/WorkerExecutionEnvelope.java`

This provides an explicit model for a single execution boundary with fields including:

- `executionId`
- `sessionId`
- `taskId`
- `workerId`
- `startedAt`
- `finishedAt`
- `durationMs`
- `executionStatus`
- `result`
- `toolInvocationIds`
- `metadata`

### 2. Stable metadata projection on results

Updated:

- `src/main/java/com/agentcloud/worker/WorkerExecutionResult.java`

Key addition:

- `withEnvelope(...)`

Stable metadata keys now projected through result metadata:

- `execution_id`
- `execution_started_at`
- `execution_finished_at`
- `execution_duration_ms`
- `execution_status`
- `tool_invocation_ids`

This preserves compatibility while making execution boundary data available immediately to downstream code.

### 3. Router-level execution boundary creation

Updated:

- `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java`

`executeOneRound(TaskRuntimeContext context, String workerId)` now creates a synthetic execution id and records timing boundary data so executor results can consistently expose envelope metadata.

### 4. Tool-aware trace linkage

Updated:

- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

Single-round tool metadata now carries stronger linkage through:

- `tool_invocation_id`
- `tool_invocation_ids`
- `tool_chain_trace[*].tool_invocation_id`

This connects the round result to concrete `ToolInvocationRecord` rows instead of leaving tool history as an unlinked summary.

## Follow-up fix completed after the initial envelope slice

A later regression remained in:

- `ToolAwareWorkerExecutorMultiStepTest.probeThenNoToolStillAutoWritesDirectoryBundle`

Observed symptom:

- expected `auto_write_generation_mode = generated`
- actual `minimal_directory_fallback`

Root cause:

- `parseAutoWriteFilesDraft(...)` was force-marking a successfully parsed structured auto-write response as a failure via `empty_files`
- that mislabeled branch caused downstream metadata normalization to collapse into fallback semantics even when the reusable structured draft path was valid

Fix:

- relaxed `parseAutoWriteFilesDraft(...)` so a successfully parsed structured response is not automatically treated as an `empty_files` failure state

Practical effect:

- `probeThenNoToolStillAutoWritesDirectoryBundle` now preserves the reusable structured draft path
- `auto_write_generation_mode` remains on the generated path instead of degrading to `minimal_directory_fallback`

## Verified tests

Verified under Java 21 via `scripts/Test-WithJava21.ps1`:

- `com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest#probeThenNoToolStillAutoWritesDirectoryBundle`
- `com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest`
- `com.agentcloud.worker.ToolAwareWorkerExecutorMultiToolTest`
- `com.agentcloud.worker.WorkerExecutorRouterProviderNativeTest`
- `com.agentcloud.engine.RuntimeFactSetAssemblerTest`
- `com.agentcloud.engine.TaskServiceLiveFlowViewTest`

Observed targeted regression sets:

- executor/runtime slice: 23 tests run, 0 failures
- live-flow + runtime fact projection slice: 8 tests run, 0 failures

Also rechecked the previously noted concern:

- `RuntimeFactSetAssemblerTest.assembleBuildsFactSetFromRuntimeContextAndToolTrace`

Current status:

- passing
- current route preview remains aligned with pinned `codex`
- earlier `codex` vs `claude` mismatch should currently be treated as stale or already overtaken, not an active blocker

## What is now stable

Phase-1 now gives the repo a materially better single-round evidence boundary:

- each round can surface a stable `execution_id`
- start and finish timestamps can travel with the result
- duration is exposed consistently
- execution status is available as result metadata
- tool traces can be linked back to persisted tool invocation records
- multi-step auto-write flows keep more faithful generation-mode metadata
- runtime fact assembly now projects a first-class `executionBoundary`
- live-flow now carries `executionBoundary` directly through `TaskLiveFlowView`
- console live-flow rendering can show execution summary and route chips without scraping only metadata

This is enough to support better:

- runtime inspection
- artifact traceability
- experiment analysis
- live-flow explanation
- future execution-history summarization

## What did not change yet

This slice did **not** yet do the following:

- replace `WorkerExecutionResult` as the primary executor contract
- make `WorkerExecutionEnvelope` the canonical return type across the control graph
- unify all judgment paths on a first-class envelope object
- create a durable execution-history table separate from tool invocations and artifacts

Those are later cuts.

## Recommended next cut

The highest-leverage next step is:

### Phase-1.75: tighten operator-facing polish around the fact surface

Good targets:

1. live-flow / console polish
   - verify the new `Execution` summary card and route chips visually
   - consider showing `traceSummary` or linked invocation ids in a compact expandable block

2. judgment inputs
   - let execution/completion judgment read a compact structured execution-boundary summary instead of relying only on free-text output plus scattered metadata

3. execution-history follow-up
   - decide whether the next persistence cut should stay tool-trace derived or introduce a dedicated execution-history record

## Strategic interpretation

This slice fits the repo's broader direction cleanly:

- mounted context is the working-memory seam
- execution envelope becomes the single-round action seam
- judgment, packets, checkpoints, and traces can converge around shared structured runtime evidence

In other words, this is not just metadata cleanup.
It is the first durable narrowing of "one worker round" into a reusable runtime contract.
