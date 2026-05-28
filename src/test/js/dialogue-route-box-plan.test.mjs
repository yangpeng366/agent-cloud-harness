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

test("route box plan counts recovery provider deprioritization as drawer detail", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "task_pinned",
        routeReason: "pinned route",
        providerDeprioritization: {
            providerDeprioritized: true,
            deprioritizedProvider: "claude",
            chip: "recovery避开 claude",
            headline: "恢复阶段会优先避开 claude",
            detail: "最近窗口内出现了临时 provider 失败，恢复建议会先尝试其他 provider。"
        }
    });

    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开 route 细节 · 1 组补充");
});

test("route box plan keeps recovery fresh-session chip as drawer detail", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "task_pinned",
        routeReason: "pinned route",
        routeChips: ["recovery: fresh session"]
    });

    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开 route 细节 · 1 组补充");
});
