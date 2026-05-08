# Task Pack: executor-slice-autowrite-fix

Date: 2026-05-07
Owner: Codex
Repo: `D:\gitAll\agent-cloud-harness`

## Objective
Fix the remaining failing executor slice centered on:

- `ToolAwareWorkerExecutorMultiStepTest.probeThenNoToolStillAutoWritesDirectoryBundle`

Expected outcome:
- generated directory bundle path remains `generated`
- does not incorrectly degrade to `minimal_directory_fallback`

## Why this matters
This is the remaining local blocker before returning to the main roadmap line:
- mounted context Phase 2A convergence
- shared runtime cognition surface
- continuity/runtime contract hardening

## Suspected Root-Cause Areas
Inspect in order:
1. `generateAutoWriteFilesDraft(...)`
2. `parseAutoWriteFilesDraft(...)`
3. post-parse validation / normalization
4. `base_path` handling
5. transition into `minimalDirectoryFallbackDraft(...)`
6. any finalization path that overwrites generation mode semantics

## Constraints
- minimal scope
- no unrelated subsystem refactors
- preserve passing tests
- prefer fixing runtime behavior over relaxing assertions

## Validation
Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest
```

Then:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -Test com.agentcloud.worker.ToolAwareWorkerExecutorMultiToolTest,com.agentcloud.worker.ToolAwareWorkerExecutorMultiStepTest
```

## Deliver Back
Return:
- root cause
- files changed
- test results
- follow-up recommendation for next roadmap slice
