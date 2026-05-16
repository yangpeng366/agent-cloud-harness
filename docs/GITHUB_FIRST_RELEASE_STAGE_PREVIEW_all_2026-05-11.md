# GitHub First Release Stage Preview

> Generated from temporary git index simulation; the real index is not modified.

HEAD: `5adc42f2c9a2f87011aad33a46aff983943f1268`

## Repository Baseline

### Simulated staged files

- .github/ISSUE_TEMPLATE/bug_report.yml
- .github/ISSUE_TEMPLATE/config.yml
- .github/ISSUE_TEMPLATE/feature_request.yml
- .github/PULL_REQUEST_TEMPLATE.md
- .github/workflows/ci.yml
- .gitignore
- CODE_OF_CONDUCT.md
- CONTRIBUTING.md
- README.md
- SECURITY.md
- docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md
- docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md
- docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md
- docs/GITHUB_FIRST_RELEASE_FILESET.md
- docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md
- docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md
- docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md
- docs/GITHUB_RELEASE_CHECKLIST.md
- docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md

### Simulated diff stat

 .github/ISSUE_TEMPLATE/bug_report.yml              |  53 +++++
 .github/ISSUE_TEMPLATE/config.yml                  |   1 +
 .github/ISSUE_TEMPLATE/feature_request.yml         |  33 +++
 .github/PULL_REQUEST_TEMPLATE.md                   |  30 +++
 .github/workflows/ci.yml                           |  38 +++
 .gitignore                                         |   1 +
 CODE_OF_CONDUCT.md                                 |  31 +++
 CONTRIBUTING.md                                    |  55 +++++
 README.md                                          |  19 +-
 SECURITY.md                                        |  30 +++
 docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md       | 106 +++++++++
 docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md           | 203 ++++++++++++++++
 docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md       | 260 +++++++++++++++++++++
 docs/GITHUB_FIRST_RELEASE_FILESET.md               | 128 ++++++++++
 docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md          |  81 +++++++
 ..._FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md | 183 +++++++++++++++
 docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md          | 222 ++++++++++++++++++
 docs/GITHUB_RELEASE_CHECKLIST.md                   |  86 +++++++
 docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md              |  92 ++++++++
 19 files changed, 1647 insertions(+), 5 deletions(-)

## chat-first / facade product line

### Simulated staged files

- src/main/java/com/agentcloud/engine/ChatFacadeService.java
- src/main/java/com/agentcloud/server/WebConsoleHandler.java
- src/main/resources/web/console/app.js
- src/main/resources/web/dialogue/app.css
- src/main/resources/web/dialogue/app.js
- src/main/resources/web/dialogue/composer-plan.js
- src/main/resources/web/dialogue/composer-request-plan.js
- src/main/resources/web/dialogue/execution-boundary-plan.js
- src/main/resources/web/dialogue/facade-pending-plan.js
- src/main/resources/web/dialogue/index.html
- src/main/resources/web/dialogue/mounted-object-plan.js
- src/main/resources/web/dialogue/pending-auto-task-plan.js
- src/main/resources/web/dialogue/task-selection-plan.js
- src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java
- src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java
- src/test/js/dialogue-composer-inline-render-plan.test.mjs
- src/test/js/dialogue-composer-plan.test.mjs
- src/test/js/dialogue-composer-request-plan.test.mjs
- src/test/js/dialogue-execution-boundary-plan.test.mjs
- src/test/js/dialogue-facade-pending-plan.test.mjs
- src/test/js/dialogue-facade-reply-plan.test.mjs
- src/test/js/dialogue-mounted-object-plan.test.mjs
- src/test/js/dialogue-pending-auto-task-plan.test.mjs
- src/test/js/dialogue-task-selection-plan.test.mjs

### Simulated diff stat

 .../com/agentcloud/engine/ChatFacadeService.java   |  74 ++-
 .../com/agentcloud/server/WebConsoleHandler.java   |  38 +-
 src/main/resources/web/console/app.js              |   8 +-
 src/main/resources/web/dialogue/app.css            | 606 +--------------------
 src/main/resources/web/dialogue/app.js             | 148 ++++-
 src/main/resources/web/dialogue/composer-plan.js   |   5 +
 .../web/dialogue/composer-request-plan.js          |   5 +
 .../web/dialogue/execution-boundary-plan.js        | 114 ++++
 .../resources/web/dialogue/facade-pending-plan.js  |  27 +
 src/main/resources/web/dialogue/index.html         |   5 +
 .../resources/web/dialogue/mounted-object-plan.js  |   3 +
 .../web/dialogue/pending-auto-task-plan.js         |  36 ++
 .../resources/web/dialogue/task-selection-plan.js  |  51 ++
 .../server/ChatFacadeHandlerHttpTest.java          |  10 +-
 .../server/WebConsoleHandlerHttpTest.java          |  24 +
 .../dialogue-composer-inline-render-plan.test.mjs  |  24 +
 src/test/js/dialogue-composer-plan.test.mjs        |  12 +
 .../js/dialogue-composer-request-plan.test.mjs     |  17 +
 .../js/dialogue-execution-boundary-plan.test.mjs   |  31 ++
 src/test/js/dialogue-facade-pending-plan.test.mjs  |  26 +
 src/test/js/dialogue-facade-reply-plan.test.mjs    |  12 +
 src/test/js/dialogue-mounted-object-plan.test.mjs  |  11 +
 .../js/dialogue-pending-auto-task-plan.test.mjs    |  67 +++
 src/test/js/dialogue-task-selection-plan.test.mjs  |  55 ++
 24 files changed, 767 insertions(+), 642 deletions(-)

## acceptance harness and operator docs

### Simulated staged files

- docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md
- docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
- docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md
- scripts/Render-DialogueAcceptanceRecordSeed.ps1
- scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1
- scripts/Run-ChatFacadePathMatrixProbe.ps1
- scripts/Run-DialogueBrowserAcceptanceProbe.ps1
- scripts/Run-DialogueRecordSeedProbe.ps1
- scripts/Run-GitHubFirstReleaseCommitDryRun.ps1
- scripts/Run-GitHubFirstReleaseDryRun.ps1
- scripts/Run-GitHubFirstReleasePrecheck.ps1
- scripts/Run-GitHubFirstReleaseStagePreview.ps1
- scripts/Start-DialogueChatFacadeManualAcceptance.ps1
- scripts/dialogue-browser-acceptance-probe-runner.cjs

### Simulated diff stat

 ...FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md |  144 ++
 ...LOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md |  129 +-
 docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md    |  249 ++++
 scripts/Render-DialogueAcceptanceRecordSeed.ps1    |  117 ++
 .../Run-ChatFacadeAcceptanceWithLocalHarness.ps1   |    3 +-
 scripts/Run-ChatFacadePathMatrixProbe.ps1          |   44 +-
 scripts/Run-DialogueBrowserAcceptanceProbe.ps1     |  153 ++
 scripts/Run-DialogueRecordSeedProbe.ps1            |   61 +
 scripts/Run-GitHubFirstReleaseCommitDryRun.ps1     |  272 ++++
 scripts/Run-GitHubFirstReleaseDryRun.ps1           |  208 +++
 scripts/Run-GitHubFirstReleasePrecheck.ps1         |  300 ++++
 scripts/Run-GitHubFirstReleaseStagePreview.ps1     |  261 ++++
 .../Start-DialogueChatFacadeManualAcceptance.ps1   |  136 +-
 .../dialogue-browser-acceptance-probe-runner.cjs   | 1516 ++++++++++++++++++++
 14 files changed, 3557 insertions(+), 36 deletions(-)

