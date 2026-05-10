import test from "node:test";
import assert from "node:assert/strict";
import { buildRouteBoxPlan } from "../../main/resources/web/dialogue/route-box-plan.js";

test("route box plan keeps worker/source/reason primary and pushes extra context into drawer", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "task_pinned",
        routeReason: "task pinned worker still satisfies current route constraints",
        taskType: "continuation",
        candidateWorkers: ["codex", "kimi"],
        routeChips: ["mode: strong only", "learning: applied"],
        cognitionTimeline: [{ stage: "route" }, { stage: "execution" }]
    });

    assert.equal(plan.worker, "codex");
    assert.equal(plan.routeSource, "task_pinned");
    assert.equal(plan.routeReason, "task pinned worker still satisfies current route constraints");
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开 route 细节 · 3 组补充 / 2 条 timeline");
});

test("route box plan omits drawer when no secondary route details exist", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "router",
        routeReason: "default route"
    });

    assert.equal(plan.hasDrawer, false);
    assert.equal(plan.drawerSummary, "");
});
