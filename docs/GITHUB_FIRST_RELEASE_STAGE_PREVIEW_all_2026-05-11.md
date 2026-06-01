# GitHub First Release Stage Preview

> Generated from temporary git index simulation; the real index is not modified.

HEAD: `f1846bdcacfbcc1683ae59b670b08d8d6d9a30b7`

## Repository Baseline

### Simulated staged files

- README.md
- STARTUP_GUIDE.md
- docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md
- docs/API_CONTRACTS.md
- docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md
- docs/GITHUB_RELEASE_CHECKLIST.md
- docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md
- docs/HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md
- docs/PROJECT_EVALUATION_AND_NEXT_PLAN.md
- docs/TROUBLESHOOT.md

### Simulated diff stat

 README.md                                 |  34 +++
 STARTUP_GUIDE.md                          |  41 ++++
 docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md   |   5 +-
 docs/API_CONTRACTS.md                     |  13 +-
 docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md |   9 +-
 docs/GITHUB_RELEASE_CHECKLIST.md          |  21 +-
 docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md     |   2 +-
 docs/HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md | 333 ++++++++++++++++++++++++++++++
 docs/PROJECT_EVALUATION_AND_NEXT_PLAN.md  | 294 ++++++++++++++++++++++++++
 docs/TROUBLESHOOT.md                      |  47 +++++
 10 files changed, 793 insertions(+), 6 deletions(-)

## chat-first / facade product line

### Simulated staged files

- src/main/java/com/agentcloud/agent/providers/BuiltinAgentProviders.java
- src/main/java/com/agentcloud/agent/providers/LocalCliAgentProvider.java
- src/main/java/com/agentcloud/cli/Main.java
- src/main/java/com/agentcloud/engine/TaskService.java
- src/main/java/com/agentcloud/engine/router/WorkerRegistry.java
- src/main/java/com/agentcloud/engine/router/WorkerRouter.java
- src/main/java/com/agentcloud/server/NioHttpServer.java
- src/main/java/com/agentcloud/server/TaskHandler.java
- src/main/java/com/agentcloud/server/WebConsoleHandler.java
- src/main/java/com/agentcloud/server/WorkerHandler.java
- src/main/java/com/agentcloud/worker/ClaudeProtocol.java
- src/main/java/com/agentcloud/worker/CodexAppServerWorkerExecutor.java
- src/main/java/com/agentcloud/worker/CopilotProtocol.java
- src/main/java/com/agentcloud/worker/CursorProtocol.java
- src/main/java/com/agentcloud/worker/DeepSeekProtocol.java
- src/main/java/com/agentcloud/worker/ExecutionOutcome.java
- src/main/java/com/agentcloud/worker/GeminiProtocol.java
- src/main/java/com/agentcloud/worker/GenericCliProtocol.java
- src/main/java/com/agentcloud/worker/KimiProtocol.java
- src/main/java/com/agentcloud/worker/OpenCodeProtocol.java
- src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java
- src/main/java/com/agentcloud/worker/ProviderExecutionSupport.java
- src/main/java/com/agentcloud/worker/ProviderProtocol.java
- src/main/java/com/agentcloud/worker/ProviderProtocolDiscovery.java
- src/main/java/com/agentcloud/worker/ProviderProtocolRegistry.java
- src/main/java/com/agentcloud/worker/ReasonixProtocol.java
- src/main/java/com/agentcloud/worker/WorkerExecutionResult.java
- src/main/java/com/agentcloud/worker/WorkerPromptHeaderBuilder.java
- src/main/resources/web/dialogue/app.css
- src/main/resources/web/dialogue/app.js
- src/main/resources/web/dialogue/index.html
- src/test/java/com/agentcloud/agent/AgentProviderSupportTest.java
- src/test/java/com/agentcloud/agent/providers/LocalCliAgentProviderTest.java
- src/test/java/com/agentcloud/cli/MainConfigTest.java
- src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java
- src/test/java/com/agentcloud/engine/router/WorkerRegistryDynamicProviderTest.java
- src/test/java/com/agentcloud/engine/router/WorkerRouterRouteTraceTest.java
- src/test/java/com/agentcloud/server/ApiErrorContractHttpTest.java
- src/test/java/com/agentcloud/server/TaskHandlerLiveFlowHttpTest.java
- src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java
- src/test/java/com/agentcloud/worker/CodexAppServerWorkerExecutorTest.java
- src/test/java/com/agentcloud/worker/ProviderCliWorkerExecutorTest.java
- src/test/java/com/agentcloud/worker/ProviderProtocolDiscoveryTest.java
- src/test/js/dialogue-transcript-layout-plan.test.mjs

### Simulated diff stat

 .../agent/providers/BuiltinAgentProviders.java     |   9 +
 .../agent/providers/LocalCliAgentProvider.java     |   6 +
 src/main/java/com/agentcloud/cli/Main.java         |  71 +++-
 .../java/com/agentcloud/engine/TaskService.java    |  75 ++++
 .../agentcloud/engine/router/WorkerRegistry.java   | 102 ++++-
 .../com/agentcloud/engine/router/WorkerRouter.java |  54 ++-
 .../java/com/agentcloud/server/NioHttpServer.java  |  38 +-
 .../java/com/agentcloud/server/TaskHandler.java    |  91 ++++
 .../com/agentcloud/server/WebConsoleHandler.java   |  29 +-
 .../java/com/agentcloud/server/WorkerHandler.java  |  29 +-
 .../java/com/agentcloud/worker/ClaudeProtocol.java | 253 +++++++++++
 .../worker/CodexAppServerWorkerExecutor.java       | 180 ++++++--
 .../com/agentcloud/worker/CopilotProtocol.java     | 249 +++++++++++
 .../java/com/agentcloud/worker/CursorProtocol.java | 250 +++++++++++
 .../com/agentcloud/worker/DeepSeekProtocol.java    | 154 +++++++
 .../com/agentcloud/worker/ExecutionOutcome.java    |   7 +
 .../java/com/agentcloud/worker/GeminiProtocol.java | 236 +++++++++++
 .../com/agentcloud/worker/GenericCliProtocol.java  | 246 +++++++++++
 .../java/com/agentcloud/worker/KimiProtocol.java   | 259 +++++++++++
 .../com/agentcloud/worker/OpenCodeProtocol.java    | 376 ++++++++++++++++
 .../worker/ProviderCliWorkerExecutor.java          | 195 ++++++++-
 .../worker/ProviderExecutionSupport.java           |  14 +-
 .../com/agentcloud/worker/ProviderProtocol.java    |  55 +++
 .../worker/ProviderProtocolDiscovery.java          | 472 +++++++++++++++++++++
 .../worker/ProviderProtocolRegistry.java           |  62 +++
 .../com/agentcloud/worker/ReasonixProtocol.java    | 181 ++++++++
 .../agentcloud/worker/WorkerExecutionResult.java   |  56 ++-
 .../worker/WorkerPromptHeaderBuilder.java          |  14 +-
 src/main/resources/web/dialogue/app.css            | 163 ++++++-
 src/main/resources/web/dialogue/app.js             | 391 ++++++++++++++++-
 src/main/resources/web/dialogue/index.html         |  13 +
 .../agentcloud/agent/AgentProviderSupportTest.java |  45 +-
 .../agent/providers/LocalCliAgentProviderTest.java |  80 ++++
 .../java/com/agentcloud/cli/MainConfigTest.java    |  29 ++
 .../ControlNodeGraphOrchestrationFlowTest.java     |  18 +-
 .../router/WorkerRegistryDynamicProviderTest.java  |  41 ++
 .../engine/router/WorkerRouterRouteTraceTest.java  |  85 ++++
 .../server/ApiErrorContractHttpTest.java           |  68 +++
 .../server/TaskHandlerLiveFlowHttpTest.java        |  48 +++
 .../TaskHandlerProviderSelectionHttpTest.java      |  53 ++-
 .../worker/CodexAppServerWorkerExecutorTest.java   |  92 ++++
 .../worker/ProviderCliWorkerExecutorTest.java      | 333 ++++++++++++++-
 .../worker/ProviderProtocolDiscoveryTest.java      | 212 +++++++++
 .../js/dialogue-transcript-layout-plan.test.mjs    |  46 ++
 44 files changed, 5362 insertions(+), 118 deletions(-)

## acceptance harness and operator docs

### Simulated staged files

- docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md
- docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md
- docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md
- scripts/Apply-DialogueAcceptanceManualBackfill.ps1
- scripts/Render-DialogueAcceptanceManualBackfillTemplate.ps1
- scripts/Render-DialogueAcceptanceScriptedBackfillTemplate.ps1
- scripts/Run-CodexPartialTimeoutSmoke.ps1
- scripts/Run-DialogueAcceptanceScriptedBackfillProbe.ps1
- scripts/Run-DialogueBrowserAcceptanceProbe.ps1
- scripts/Run-GitHubFirstReleaseCommitDryRun.ps1
- scripts/Run-GitHubFirstReleasePrecheck.ps1
- scripts/Run-GitHubFirstReleaseStagePreview.ps1
- scripts/Run-HarnessWithJava21.ps1
- scripts/dialogue-browser-acceptance-probe-runner.cjs
- scripts/dialogue-business-smoke.js
- scripts/provider-discovery-smoke.js

### Simulated diff stat

 ...LOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md |   8 +-
 docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md    |  22 +-
 docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md             | 205 ++++++++++++++++++
 scripts/Apply-DialogueAcceptanceManualBackfill.ps1 |   9 +
 ...er-DialogueAcceptanceManualBackfillTemplate.ps1 |   2 +-
 ...-DialogueAcceptanceScriptedBackfillTemplate.ps1 |   9 +-
 scripts/Run-CodexPartialTimeoutSmoke.ps1           |  76 +++++++
 ...Run-DialogueAcceptanceScriptedBackfillProbe.ps1 |  41 +++-
 scripts/Run-DialogueBrowserAcceptanceProbe.ps1     |   4 +
 scripts/Run-GitHubFirstReleaseCommitDryRun.ps1     |  49 ++++-
 scripts/Run-GitHubFirstReleasePrecheck.ps1         | 188 ++++++++++++++++-
 scripts/Run-GitHubFirstReleaseStagePreview.ps1     |  36 +++-
 scripts/Run-HarnessWithJava21.ps1                  |  30 ++-
 .../dialogue-browser-acceptance-probe-runner.cjs   | 187 +++++++++++++++--
 scripts/dialogue-business-smoke.js                 |  19 +-
 scripts/provider-discovery-smoke.js                | 229 +++++++++++++++++++++
 16 files changed, 1063 insertions(+), 51 deletions(-)

