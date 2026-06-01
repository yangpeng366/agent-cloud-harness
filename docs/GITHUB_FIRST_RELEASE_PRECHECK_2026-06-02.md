# GitHub First Release Precheck 2026-06-02

> Purpose: capture one real local precheck run for the current first-release slice.

## Executed Commands

### 1. dialogue frontend entry syntax check

```powershell
node --check src/main/resources/web/dialogue/app.js
```

Result:

- Exit code: 0

### 2. dialogue JS smoke tests

```powershell
node --test src/test/js/*.test.mjs
```

Result:

- Skipped

### 3. Java HTTP regression

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

Result:

- Skipped

### 4. Provider discovery smoke

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven
node .\scripts\provider-discovery-smoke.js --port 18432 --report .\.tmp\provider-discovery-smoke\report.json
```

Result:

- Build exit code: 0
- Smoke exit code: 0
- Report: D:\gitAll\agent-cloud-harness\.tmp\provider-discovery-smoke\report.json
- Passed: True
- Validates `providers.yaml` dynamic provider appears in `/api/v1/agents` and `/api/v1/workers`, and worker list readiness matches runtime readiness
- 2026-06-02 follow-up: `node .\scripts\provider-discovery-smoke.js --port 18461 --report .\.tmp\provider-discovery-smoke-18461\report.json --work-dir .\.tmp\provider-discovery-smoke-18461` passed, additionally validating a provider configured with `binary` but no `protocol` is conservatively inferred as `native_cli_text`, projected to worker inventory, and marked with `provider_protocol_inferred=true`.

### 5. Codex partial timeout smoke

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1
```

Result:

- Exit code: 0
- Report: D:\gitAll\agent-cloud-harness\.tmp\codex-partial-timeout-smoke\report.json
- Passed: True
- Validates Codex partial output communication failure, max-duration hard limit, ControlNodeGraph human gate projection, provider thread continuation metadata, and Dialogue worker_round actions

### 6. Dialogue A-H scripted/manual backfill gate

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceScriptedBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json
```

Result:

- Scripted exit code: 0
- Manual exit code: 0
- Scripted coverage prefilled: A, B, C, D, E, F, G, H
- Residual human gate: A, B, C, D, E, F, G, H
- Scripted misuse rejected: True
- Manual apply still works: True
- Validates scripted browser evidence cannot mark strict manual A-H Passed=true, while intentional manual backfill still works

### 7. first release dry-run

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseDryRun.ps1 -WriteMarkdown
```

Result:

- Artifact: D:\gitAll\agent-cloud-harness\docs\GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md
- Stable sections present:
  - include
  - evidence_only
  - defer
  - review

### 8. first release commit dry-run

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown
```

Result:

- Artifact: D:\gitAll\agent-cloud-harness\docs\GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md
- Stable main groups present:
  - Repository Baseline
  - chat-first / facade product line
  - acceptance harness and operator docs
- Current unmatched_count = 0

### 9. first release stage preview

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown
```

Result:

- Artifact: D:\gitAll\agent-cloud-harness\docs\GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md
- Stable simulated staged diff groups present:
  - Repository Baseline
  - chat-first / facade product line
  - acceptance harness and operator docs

## Still Outstanding

- README.md still uses a published repo placeholder and has not yet been filled with a real public repository URL
- /dialogue/ strict A-H manual click-through acceptance is still not complete; scripted current-reachable seam evidence exists but does not close this manual gate
- GitHub Actions has not yet been verified on a real remote GitHub repository

## Conclusion

A real local precheck exists for the current first-release slice, but it is still only a precheck.

> local precheck passed for the current first-release slice, while manual dialogue acceptance and real remote GitHub validation are still outstanding.

