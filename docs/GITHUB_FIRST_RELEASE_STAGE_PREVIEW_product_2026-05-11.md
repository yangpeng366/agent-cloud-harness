# GitHub First Release Stage Preview

> Generated from temporary git index simulation; the real index is not modified.

HEAD: `5adc42f2c9a2f87011aad33a46aff983943f1268`

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

