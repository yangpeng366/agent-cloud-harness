# GitHub First Release Precheck 2026-05-11

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

- Passed; see console output for exact Node test details

### 3. Java HTTP regression

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
```

Result:

- Maven test run passed
- ChatFacadeHandlerHttpTest passed
- WebConsoleHandlerHttpTest passed

### 4. first release dry-run

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

### 5. first release commit dry-run

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

### 6. first release stage preview

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
- /dialogue/ A-H real manual acceptance is still not complete
- GitHub Actions has not yet been verified on a real remote GitHub repository

## Conclusion

A real local precheck exists for the current first-release slice, but it is still only a precheck.

> local precheck passed for the current first-release slice, while manual dialogue acceptance and real remote GitHub validation are still outstanding.

