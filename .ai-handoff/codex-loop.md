# Codex Loop Handoff

Date: 2026-05-07
Workspace: `D:\gitAll\agent-cloud-harness`
Status: active
Main Executor: Codex
Mode: implementation handoff

## Goal

Continue pushing `agent-cloud-harness` as a **continuity-first runtime substrate**, not a thicker coding shell.

Immediate execution goal:
- stabilize the current executor/test slice
- then resume the mounted-context Phase 2A convergence path
- keep changes minimal, test-backed, and aligned with runtime contract hardening

## Current Strategic Frame

The project should be interpreted as:
- a continuity-first runtime harness
- a control surface for task identity over time
- a working-memory/runtime cognition substrate
- a lifecycle and evidence discipline layer for long-running agent work

Do not drift toward:
- prompt playground work
- shell convenience-only work
- broad feature sprawl
- retrieval-first expansion before continuity/lifecycle seams are hardened

## Current Code Reality

The repo already has meaningful seams in place:

### Working-memory seam
- `TaskRuntimeContext`
- `ActiveContext`
- `MountedContextView`
- `ContextViewBuilder`
- `MountedContextPromptRenderer`
- `PromptRenderingMode`

### Execution / judgment seam
- `DefaultWorkerExecutor`
- `ToolAwareWorkerExecutor`
- `PromptBasedJudgmentService`
- `ControlNodeGraph`

### Execution trace / evidence seam
- `ToolInvocationRecord`
- `WorkerExecutionResult`
- `WorkerExecutionEnvelope`
- tool trace metadata propagation

### Continuity assets
- `ResumePacket`
- `Checkpoint`
- task/event/artifact/decision persistence

## Documents Updated

Use these as current direction anchors:

### Workspace docs
- `C:\Users\47037\.openclaw\workspace\docs\AGENT_CLOUD_HARNESS_POSITIONING_DRAFT_2026-05.md`
- `C:\Users\47037\.openclaw\workspace\docs\AGENT_CLOUD_HARNESS_ROADMAP_V1_2026-05.md`
- `C:\Users\47037\.openclaw\workspace\docs\MOUNTED_CONTEXT_PHASE2A_CHECKLIST.md`

### Repo docs
- `docs/ARCHITECTURE.md` (updated to continuity-first runtime substrate framing)
- `docs/PHASE2_ROADMAP.md`
- `docs/HARNESS_EVOLUTION.md`
- `docs/HARNESS_CHANGE_CONTRACT.md`

## Recently Completed Implementation Work

### 1. Execution envelope Phase-1 seam
Added / connected:
- `WorkerExecutionEnvelope`
- stable metadata injection path for:
  - `execution_id`
  - `execution_started_at`
  - `execution_finished_at`
  - `execution_duration_ms`
  - `execution_status`
  - `tool_invocation_ids`
- route-level envelope wrapping in `WorkerExecutorRouter`

### 2. Tool trace ↔ invocation linkage
In `ToolAwareWorkerExecutor`:
- added `tool_invocation_id` into trace metadata
- added `tool_invocation_ids` into final metadata
- extended `tool_chain_trace` entries to carry `tool_invocation_id`

### 3. Image-input / visual-brief / grounded auto-write helpers
Filled previously missing helper paths in `ToolAwareWorkerExecutor`, including:
- image input diagnostics
- declared image path resolution
- visual brief resolution
- prompt helpers for visual brief and image diagnostics
- metadata attachment for image diagnostics

### 4. Auto-write metadata stabilization
Added / aligned fields such as:
- `image_input_count`
- `image_input_present`
- `image_input_used`
- `image_input_paths`
- `missing_image_paths`
- `auto_write_used_images`
- `visual_brief_present`
- `visual_brief_preview`
- `auto_write_generation_mode`

Also added fallback backfill behavior for `auto_grounded_directory_write` termination path.

### 5. Test progress already achieved
Known green at last solid checkpoint:
- `ToolAwareWorkerExecutorMultiToolTest`
- `WorkerExecutorRouterProviderNativeTest`

### 6. 2026-05-08 continuity hardening update
- `ToolAwareWorkerExecutorMultiStepTest.probeThenNoToolStillAutoWritesDirectoryBundle` did not reproduce on the current Java-21 test run; do not treat it as an active blocker unless it fails again.
- aligned `ControlNodeGraph` judgment prompt-mode diagnostics with the actual runtime-context / latest-packet rendering mode
- preserved packet-only prompt-mode continuity in both `PacketBuilder` and `ConsolidationService` when task metadata is silent
- added targeted regressions:
  - `continueJudgmentPromptMetadataUsesLatestPacketPromptModeAlias`
  - `resumePacketKeepsPacketOnlyPromptModeAliasWhenTaskMetadataIsSilent`
  - `checkpointRefinedPacketKeepsPacketOnlyPromptModeAliasWhenTaskMetadataIsSilent`

### 7. 2026-05-08 mounted evidence Phase-2B slice
- extended `TaskRuntimeContext` / `TaskRuntimeContextBuilder` to carry recent `ToolInvocationRecord` entries without breaking older constructor call sites
- mounted-context `EVIDENCE` panel now treats tool invocations as first-class evidence alongside artifacts and events
- mounted-context `INDEX` and `ARCHIVE_HANDLES` now expose `tool_invocations` as a reloadable collection / history handle
- added targeted regressions:
  - `ContextViewBuilderTest.buildsMountedViewPanelsFromExistingRuntimeContext`
  - `TaskRuntimeContextBuilderMountedContextTest.buildAttachesMountedContextViewWithoutChangingExistingRuntimeFields`
- full Java-21 suite remained green after this slice: `221 tests, 0 failures`

### 8. 2026-05-08 durable judgment evidence slice
- mounted-context `EVIDENCE` now also promotes only durable runtime-proof decisions: `execution_judgment` and `completion_judgment`
- mounted-context `ACTIVE` still keeps the bounded recent-decision window; this duplication is intentional because `ACTIVE` preserves live deliberation continuity while `EVIDENCE` surfaces the subset that acts as execution proof
- `ContextObjectAdapter.decision(...)` now exposes judgment metadata such as `judgment_stage`, `selected_worker`, `action`, `status`, `alignment_level`, `next_step`, `suggested_next_action`, plus linked `tool_invocation_ids` / `evidence_refs`
- selection trace now includes `evidence_decision_window=x/y` so durable-evidence boundedness can be reasoned about separately from the generic `decision_window`
- regression intent:
  - durable judgments appear in `EVIDENCE`
  - non-durable route/debug decisions do not get promoted there
  - full Java-21 suite remained green after this slice: `221 tests, 0 failures`

## Historical Note

### Previous suspected blocker
`ToolAwareWorkerExecutorMultiStepTest.probeThenNoToolStillAutoWritesDirectoryBundle`

2026-05-08 status:
- current Java-21 suite run is green
- no code change was needed for this case in the current loop
- keep as historical context only; reopen only if the failure reproduces

### Separate older-line failure
`RuntimeFactSetAssemblerTest.assembleBuildsFactSetFromRuntimeContextAndToolTrace`
- expected provider `codex`
- actual `claude`
- still treat as separate unless it starts blocking mounted-context/runtime-contract work

## Immediate Next Task For Codex

The executor/test blocker described above is no longer the right next slice.

Resume the roadmap path from the mounted-context convergence side:
- keep Phase 2A in a green, evidence-backed state
- avoid reopening already-closed prompt-mode continuity gaps
- move the next implementation slice toward Phase 2B only after preserving current diagnostics and runtime-contract stability

## Validation Commands

Prefer Java 21 script path already used in this repo:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest
```

Then, if fixed:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.worker.ToolAwareWorkerExecutorMultiToolTest,com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest
```

If needed later, re-run the separate older-line test independently.

## After This Slice Turns Green

Return to the main roadmap path:

### Phase 2A convergence
1. strengthen `MountedContextPromptRenderer` tests
2. strengthen sparse/dense panel boundedness tests
3. verify `ACTIVE_CONTEXT_ONLY` / `MOUNTED_CONTEXT_SHADOW` / `MOUNTED_CONTEXT_PRIMARY`
4. align worker and judgment prompt-mode diagnostics
5. tighten prompt budget / panel caps / trace fields

### Then Phase 2B
- make mounted context the shared runtime cognition surface across:
  - execution
  - execution judgment
  - completion judgment
  - evidence interpretation
  - next likely slice: thread `evidence_refs` / `tool_invocation_ids` through prompt rendering and live-flow diagnostics so proof edges stay visible end-to-end

## Guardrails

### Do
- prefer minimal, localized fixes
- preserve current external contracts when possible
- keep metadata stable and explicit
- add/adjust targeted tests with each fix
- keep changes aligned with continuity/runtime-contract hardening

### Do not
- refactor unrelated subsystems during this slice
- widen scope into retrieval/memory platform work yet
- replace mounted-context seam with a brand new abstraction
- force broad interface churn unless necessary
- paper over the failing case with brittle test-only hacks

## Risks

- metadata backfill may hide a deeper branch-selection bug
- generated-vs-fallback semantics may be entangled with parser/normalizer rules
- test fixtures may encode assumptions that differ from runtime intent
- broad cleanup in `ToolAwareWorkerExecutor` could accidentally destabilize working green tests

## Recommended Output Back To OpenClaw

When done, report only these sections:

### Risks
- concise list

### NextSteps
- immediate next implementation steps after current fix

### PromptBackToCodex
- if another round is needed

### EvidenceToRecord
- test outcomes
- root cause found
- files changed
- contract changes if any

### SuggestedHandoffEdits
- what to append/update in this handoff file

## 9. 2026-05-08 proof edge continuity slice

### What changed

- threaded `tool_invocation_ids` through live-flow runtime cognition diagnostics instead of leaving them implicit inside `executionBoundary` or loose metadata
- made mounted-context prompt rendering expose bounded proof edges for evidence objects, so prompt-visible evidence now surfaces concrete tool links rather than only summaries

### Files changed

- `src/main/java/com/agentcloud/model/RuntimeCognitionSurfaceView.java`
- `src/main/java/com/agentcloud/model/RuntimeCognitionTimelineEntryView.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/runtime/context/MountedContextPromptRenderer.java`
- `src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java`
- `src/test/java/com/agentcloud/runtime/context/MountedContextPromptRendererTest.java`

### Why this slice

The previous Phase 2B slices already promoted tool evidence and durable judgments into mounted context, but two observer layers still hid the actual proof edge:

- mounted prompt rendering only emitted `title + summary`
- live-flow/runtime-cognition views emitted `evidence_refs` but not explicit `tool_invocation_ids`

That meant the same proof chain was available in raw metadata, but not visible end-to-end in the operator-facing or prompt-facing surfaces.

### Contract/result

- `RuntimeCognitionSurfaceView.ExecutionSurface` now exposes nullable `toolInvocationIds`
- `RuntimeCognitionSurfaceView.JudgmentSurface` now exposes nullable `toolInvocationIds`
- `RuntimeCognitionTimelineEntryView` now exposes nullable `toolInvocationIds`
- `TaskService` now fills those fields from execution boundary / decision metadata / continuity payloads
- `MountedContextPromptRenderer` now appends bounded `proof=...` segments for non-handle objects using:
  - `tool_invocation_ids`
  - `evidence_refs`
  - mounted `refs`

The renderer remains bounded: it only emits up to two proof-edge fragments per object and keeps archive/index panels compact.

### Validation

Ran:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.runtime.context.MountedContextPromptRendererTest,com.agentcloud.engine.TaskServiceLiveFlowViewTest
```

Repo script still executed full `mvn test`.

Result:

- `222 tests, 0 failures`

### Next likely slice

- carry the same proof-edge visibility into prompt/body diagnostics returned by judgment/live-flow endpoints, so rendered mounted context, runtime fact metadata, and persisted decision summaries all expose the same bounded evidence chain without requiring metadata spelunking
