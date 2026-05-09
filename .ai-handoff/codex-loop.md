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

## 10. 2026-05-08 proof summary diagnostic slice

### What changed

- added bounded `proof_summary` to runtime cognition execution/judgment surfaces and timeline entries so live-flow and judgment-trace payloads expose the same proof edge already present in mounted context metadata
- folded the same bounded `proof=...` fragment into persisted `execution_judgment` and `completion_judgment` summaries, so decision summaries no longer lose tool/evidence linkage after storage

### Files changed

- `src/main/java/com/agentcloud/model/RuntimeCognitionSurfaceView.java`
- `src/main/java/com/agentcloud/model/RuntimeCognitionTimelineEntryView.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/test/java/com/agentcloud/engine/TaskServiceLiveFlowViewTest.java`
- `src/test/java/com/agentcloud/server/TaskHandlerLiveFlowHttpTest.java`
- `src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java`

### Why this slice

Section 9 closed the metadata and mounted-prompt side of the proof edge, but operator-facing endpoint bodies still required metadata spelunking to see the same bounded evidence chain, and stored judgment summaries still collapsed back to plain action/status text.

This slice makes the three observer layers align:

- mounted prompt rendering
- live-flow / judgment-trace runtime cognition payloads
- persisted decision summaries

### Contract/result

- `RuntimeCognitionSurfaceView.ExecutionSurface` now exposes nullable `proofSummary`
- `RuntimeCognitionSurfaceView.JudgmentSurface` now exposes nullable `proofSummary`
- `RuntimeCognitionTimelineEntryView` now exposes nullable `proofSummary`
- `TaskService` now derives bounded proof text from `tool_invocation_ids` + `evidence_refs` for:
  - execution surface
  - execution/completion judgment surfaces
  - execution/judgment/checkpoint/resume/control-action timeline entries
  - timeline summary strings
- `ControlNodeGraph` now appends the same bounded proof fragment into persisted execution/completion judgment summaries using latest worker metadata

The proof fragment remains bounded:

- max 2 parts
- `tool:` and `evidence:` prefixes
- labels truncated at 72 chars

### Validation

Ran:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.engine.TaskServiceLiveFlowViewTest
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.server.TaskHandlerLiveFlowHttpTest
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.engine.ControlNodeGraphOrchestrationFlowTest
```

Repo script still executed full `mvn test`.

Result:

- `223 tests, 0 failures`

### Next likely slice

- if staying on the same Phase 2B line, push proof visibility one step further into any remaining human/operator textual diagnostics that still summarize judgments without mounted evidence reopen semantics
- otherwise, once the exact reopen-policy draft path is clarified, start the next seam around continuity-aware evidence reopen / remount policy instead of only bounded proof display

## 2026-05-09 - Context reopen pressure surfaced end-to-end

### What changed

- added `needsContextReopen` to `ExecutionDecision` and updated `PromptBasedJudgmentService` so execution judgment can explicitly ask for bounded archive reopen when mounted/active evidence is insufficient
- `ControlNodeGraph` now persists `needs_context_reopen`, `reopen_candidate_paths`, `reopen_candidate_count`, and `reopen_summary` into `execution_judgment` metadata, deriving candidates from mounted `ARCHIVE_HANDLES`
- orchestration adaptation now preserves `needsContextReopen` when planner output is rewritten into a `handoff` execution decision, so planner-side reopen pressure survives the planner-to-executor boundary
- `RuntimeFactSetAssembler`, `TaskService`, and `ContextObjectAdapter` now surface reopen pressure into runtime facts, runtime cognition surfaces, timeline entries, and mounted evidence decision objects

### Contract/result

- `RuntimeCognitionSurfaceView.JudgmentSurface` now exposes nullable `needsContextReopen`, `reopenCandidatePaths`, and `reopenSummary`
- `RuntimeCognitionTimelineEntryView` now exposes nullable `needsContextReopen`, `reopenCandidatePaths`, and `reopenSummary`
- mounted durable decision evidence now includes reopen metadata and `reopen_candidate` refs
- reopen candidates are bounded and currently sourced from mounted `ARCHIVE_HANDLES`, capped to 3 target paths

### Validation

Ran:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -MavenArgs '-Dtest=ControlNodeGraphOrchestrationFlowTest,TaskServiceLiveFlowViewTest,TaskHandlerLiveFlowHttpTest,RuntimeFactSetAssemblerTest'
```

Result:

- `20 tests, 0 failures`

### Next likely slice

- turn reopen pressure from passive diagnostics into an explicit runtime policy surface, for example by distinguishing `reopen recommended` vs `reopen required` and by classifying candidate handles by evidence type / expected cost

## 2026-05-09 - Codex Windows wrapper launch and provider-readiness drift

### Incident

- real task: `task_2cd0bb782c5a4a9b`
- console URL: `/console/#session=session_ce11dfa0260f46f8&task=task_2cd0bb782c5a4a9b`
- observed symptom on the old 8080 instance:
  - planner round selected `codex`
  - task summary and worker artifact failed with `Cannot run program "codex" ... CreateProcess error=2`
  - task metadata drifted after orchestrated fallback/handoff: `assigned_worker=kimi`, but execution-side metadata still exposed stale `codex` executor identity

### Root causes

- Windows CLI wrapper mismatch:
  - host `Get-Command codex -All` resolved wrapper scripts such as `codex.cmd` / `codex.ps1`
  - Java `ProcessBuilder("codex")` still failed because raw `codex` was not directly launchable in this environment
  - provider detect/readiness and provider execution were not using a shared launch contract
- sparse current-round metadata drift:
  - when a later orchestration round returned only sparse worker metadata, downstream judgment/live-flow projection could fall back to stale planner-round `codex` route fields
  - this produced contradictory surfaces after `codex -> handoff -> kimi`
- broader horizontal drift:
  - built-in provider-backed workers could still look `ready` from worker readiness/tool checks even when provider detect already knew the backing CLI was unavailable
  - this made route preview and actual execution diverge

### What changed

- added Windows wrapper-aware `LocalCliProviderConfig.LaunchSpec`
  - direct executable remains `direct`
  - `.cmd` / `.bat` launch via `cmd.exe /c`
  - `.ps1` launch via PowerShell `-File`
- made provider detect and provider execution share the same `LaunchSpec`
  - `LocalCliAgentProvider`
  - `CodexAppServerWorkerExecutor`
  - `ProviderCliWorkerExecutor`
- execution metadata now records:
  - `cli_binary`
  - `cli_resolved_binary`
  - `cli_launch_mode`
  - `cli_command_preview`
- `ControlNodeGraph` now injects current-round route metadata into sparse `WorkerExecutionResult.metadata()` before persistence/projection, so later rounds do not fall back to stale planner metadata
- `AgentProviderRegistry` now exposes short-TTL cached `status(providerId)` and all provider-facing surfaces use it instead of recomputing detect independently
- `WorkerRegistry.checkReadiness()` now includes provider-backed readiness:
  - adds `checks["provider:<id>"]`
  - marks built-in provider workers not ready when provider detect says unavailable
  - surfaces `provider not registered: <id>` when a provider-backed worker has no registered provider
- `Main` now wires `AgentProviderRegistry` before `WorkerRegistry` so built-in workers are readiness-aware at boot

### Files changed

- `src/main/java/com/agentcloud/agent/AgentProviderRegistry.java`
- `src/main/java/com/agentcloud/agent/SimpleAgentDiscoveryService.java`
- `src/main/java/com/agentcloud/agent/providers/LocalCliAgentProvider.java`
- `src/main/java/com/agentcloud/agent/providers/LocalCliProviderConfig.java`
- `src/main/java/com/agentcloud/cli/Main.java`
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRegistry.java`
- `src/main/java/com/agentcloud/server/AgentHandler.java`
- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/java/com/agentcloud/store/DatabaseManager.java`
- `src/main/java/com/agentcloud/worker/CodexAppServerWorkerExecutor.java`
- `src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java`
- `src/test/java/com/agentcloud/agent/AgentProviderSupportTest.java`
- `src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java`
- `src/test/java/com/agentcloud/engine/router/WorkerRouterRouteTraceTest.java`
- `src/test/java/com/agentcloud/server/ApiErrorContractHttpTest.java`
- `src/test/java/com/agentcloud/worker/CodexAppServerWorkerExecutorTest.java`
- `src/test/java/com/agentcloud/worker/ProviderCliWorkerExecutorTest.java`

### Validation

Focused validation after the fix:

```powershell
. .\scripts\Use-Java21.ps1
$env:MAVEN_OPTS='-Xms128m -Xmx768m -XX:CICompilerCount=2 -XX:ActiveProcessorCount=4'
mvn '-DskipTests' test-compile
mvn '-Dtest=com.agentcloud.engine.router.WorkerRouterRouteTraceTest,com.agentcloud.agent.AgentProviderSupportTest,com.agentcloud.server.ApiErrorContractHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result:

- `35 tests, 0 failures`

### Similar-risk class to watch

- any path where `detect`, `readiness`, `route`, and `execute` derive availability from different sources can reproduce the same class of bug
- provider-backed workers were the first concrete case, but the same drift pattern is relevant to:
  - command-tool gated workers
  - future remote provider auth status vs cached route eligibility
  - any orchestration stage that persists sparse metadata and later projects from historical fallbacks

### Operator notes

- on Windows, do not assume `Get-Command codex` proving discoverability means `ProcessBuilder("codex")` is safe
- first inspect:
  - `/api/v1/agents/codex`
  - `/api/v1/workers/codex/readiness`
  - task `/live_flow` or `/agent_runs` metadata for `cli_launch_mode` and `cli_resolved_binary`

## 2026-05-09 Real-task follow-up: provider-backed suggest-only workers silently degrading to default LLM

### Incident

- real stuck task: `task_2cd0bb782c5a4a9b`
- operator symptom: console looked like "执行时找不到 codex"，但任务实际 pinned/selected worker 已经不是 `codex`
- actual route:
  - `/api/v1/tasks/{id}/provider_selection` showed `selected_worker=kimi`
  - prior stale run `arun_aca56126dbb04aa1` had `provider_id=kimi` but `worker_execution_status=empty`

### Root cause

- built-in `kimi` worker was `suggestOnly=true` but did not declare `execution_backend`
- `WorkerExecutorRouter` therefore hit `suggestOnly -> DefaultWorkerExecutor` fallback before provider-native routing
- this host had no generic OpenAI LLM configured, so the fallback path completed almost immediately as `empty`
- same structural risk existed for other provider-shaped small workers:
  - `hermes`
  - `pi`
  - `kiro`
- those workers were also missing explicit provider backend metadata, and readiness could still look acceptable even though current harness has no executor for them

### Changes in this slice

- `WorkerRegistry`
  - built-in `kimi/hermes/pi/kiro` now declare `execution_backend=provider_native_cli`
  - provider-backed readiness now adds `checks["executor_backend:<backend>"]`
  - workers whose provider backend is not implemented by the current harness are marked `not ready`
- `WorkerExecutorRouter`
  - provider-native / provider-app-server routing now wins before `suggestOnly` default fallback
  - explicit provider backend without executor support now fails fast instead of silently degrading
- `ProviderCliWorkerExecutor`
  - added real `kimi` CLI support using `--print --output-format stream-json`
  - parser extracts assistant text and session resume id
- new shared support matrix:
  - `src/main/java/com/agentcloud/worker/ProviderExecutionSupport.java`
  - keeps readiness / executor support lists aligned

### Real validation

- host validation:
  - `GET /api/v1/agents/kimi` => launch target `C:\\Users\\47037\\.local\\bin\\kimi.exe`, `launch_mode=direct`, `launch_available=true`
  - manual CLI probe returned `OK`
- real rerun:
  - restart command used correct DB path:
    - `java --enable-preview -Dserver.port=8080 -Ddb.path=D:\\gitAll\\agent-cloud-harness\\.tmp\\agent_cloud_new.db -jar target\\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`
  - `POST /api/v1/tasks/task_2cd0bb782c5a4a9b/continue`
  - server log:
    - `Routing worker to provider-native cli executor. worker=kimi type=kimi`
    - `Provider-native CLI round completed. provider=kimi worker=kimi status=completed exitCode=0 durationMs=72616`
  - new real run:
    - `arun_ca954de6c2aa43a9`
    - `provider_id=kimi`
    - `execution_backend=provider_native_cli`
    - `provider_output_parser=kimi_stream_json`
    - `cli_resolved_binary=C:\\Users\\47037\\.local\\bin\\kimi.exe`
    - `provider_session_id=session_ce11dfa0260f46f8`
- task content advanced successfully; remaining non-terminal status was due to separate judgment-side LLM-not-configured condition, not provider execution failure

### Similar-risk conclusion

- any worker that:
  - looks provider-backed to operators,
  - lacks explicit `execution_backend`,
  - or declares a backend the harness does not actually implement
  can silently drift between `route preview`, `readiness`, and `actual execution`
- current concrete watch list after this fix:
  - adding any new built-in CLI provider without also updating `ProviderExecutionSupport`
  - future remote provider backends where detect/readiness says ready but executor path is not wired
  - long-running synchronous `/continue` calls where the client times out first and operator misreads it as a stuck task
