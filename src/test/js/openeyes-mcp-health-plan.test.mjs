import test from "node:test";
import assert from "node:assert/strict";

import { buildOpenEyesMcpHealthPlan } from "../../main/resources/web/console/openeyes-mcp-health-plan.js";

test("openeyes mcp health plan projects a healthy stdio channel", () => {
    const plan = buildOpenEyesMcpHealthPlan({
        available: true,
        status: "healthy",
        server_name: "openeyes",
        server_version: "1.26.0",
        tool_count: 13
    });

    assert.equal(plan.tone, "done");
    assert.equal(plan.label, "healthy");
    assert.equal(plan.headline, "OpenEyes MCP 可用");
    assert.equal(plan.detail, "openeyes 1.26.0 · 13 tools");
});

test("openeyes mcp health plan keeps disabled and unhealthy states distinct", () => {
    assert.deepEqual(
        { tone: buildOpenEyesMcpHealthPlan({ status: "disabled" }).tone,
          label: buildOpenEyesMcpHealthPlan({ status: "disabled" }).label },
        { tone: "paused", label: "disabled" }
    );
    const unhealthy = buildOpenEyesMcpHealthPlan({ status: "unhealthy", error: "startup timeout" });
    assert.equal(unhealthy.tone, "failed");
    assert.equal(unhealthy.detail, "startup timeout");
    assert.equal(buildOpenEyesMcpHealthPlan().label, "unhealthy");
});
