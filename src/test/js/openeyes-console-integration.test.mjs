import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  buildOpenEyesUiAssertionPlan,
  shouldRenderOpenEyesUiAssertion
} from "../../main/resources/web/console/openeyes-ui-assertion-plan.js";

const appSource = readFileSync(
  fileURLToPath(new URL("../../main/resources/web/console/app.js", import.meta.url)),
  "utf8"
);

test("console imports OpenEyes UI assertion plan", () => {
  assert.match(appSource, /import \{\s+buildOpenEyesUiAssertionPlan,\s+shouldRenderOpenEyesUiAssertion\s+\}/);
  assert.match(appSource, /runtime-health__openeyes-ui-assertion/);
});

test("selected task without OpenEyes calls renders skipped plan", () => {
  const plan = buildOpenEyesUiAssertionPlan({
    task: { id: "task-ui", title: "OpenEyes acceptance" },
    toolInvocations: [{ toolName: "search_text", success: true }]
  });

  assert.equal(plan.status, "skipped");
  assert.equal(plan.callCount, 0);
});

test("selected task with successful OpenEyes calls renders aggregate", () => {
  const plan = buildOpenEyesUiAssertionPlan({
    task: { id: "task-ui" },
    toolInvocations: [
      { toolName: "openeyes", success: true, elapsedMs: 120, metadata: { subcommand: "windows" } },
      { toolName: "openeyes", success: true, elapsedMs: 180, metadata: { subcommand: "capture" } }
    ]
  });

  assert.equal(plan.callCount, 2);
  assert.equal(plan.successRate, 1);
  assert.equal(plan.averageElapsedMs, 150);
  assert.equal(plan.lastSubcommand, "capture");
});

test("disabled OpenEyes MCP suppresses UI assertion card", () => {
  const selectedTask = { id: "task-ui" };
  assert.equal(shouldRenderOpenEyesUiAssertion({ available: false, status: "disabled" }, selectedTask), false);
  assert.equal(shouldRenderOpenEyesUiAssertion({ available: true, status: "healthy" }, selectedTask), true);
  assert.equal(shouldRenderOpenEyesUiAssertion({ available: true, status: "healthy" }, null), false);
});
