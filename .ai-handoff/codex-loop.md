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

## Known Open Issue

### Primary open failure to finish first
`ToolAwareWorkerExecutorMultiStepTest.probeThenNoToolStillAutoWritesDirectoryBundle`

Observed symptom:
- expected `auto_write_generation_mode = generated`
- actual `minimal_directory_fallback`

Interpretation:
- this likely is **not** just metadata loss
- the case appears to truly flow into `minimalDirectoryFallbackDraft(...)`
- likely issue is upstream of metadata merge, around one of:
  - `generateAutoWriteFilesDraft(...)`
  - raw LLM draft shape
  - parser acceptance
  - `files[]` emptiness / validity checks
  - `base_path` alignment / normalization

### Separate older-line failure
`RuntimeFactSetAssemblerTest.assembleBuildsFactSetFromRuntimeContextAndToolTrace`
- expected provider `codex`
- actual `claude`
- treat as separate from the current executor slice unless it blocks current progress

## Immediate Task For Codex

Finish the remaining executor/test slice before branching back to broader roadmap work.

### First target
Investigate and fix why:
- `probeThenNoToolStillAutoWritesDirectoryBundle`
- lands in `minimalDirectoryFallbackDraft(...)`
- instead of preserving `generated`

### What to inspect
Prioritize these seams in `ToolAwareWorkerExecutor.java`:
- `generateAutoWriteFilesDraft(...)`
- `minimalDirectoryFallbackDraft(...)`
- `parseAutoWriteFilesDraft(...)`
- generated draft validity checks
- handling of `base_path`
- handling of empty/blank file content
- any finalization path that silently downgrades generated draft to fallback

### Likely hypothesis directions
1. LLM-produced draft parses but fails validation, causing fallback
2. `files[]` becomes empty after normalization/filtering
3. `base_path` is absent/mismatched, making generated draft non-acceptable
4. generated metadata is produced, but later path reconstruction still chooses fallback object

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
