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
    assert.deepEqual(plan.routeChips, ["模式：strong only", "学习记忆：已应用"]);
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开路由细节 · 3 组补充 / 2 条轨迹");
});

test("route box plan humanizes route diagnostic chips", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "router",
        routeChips: [
            "mode: orchestrated",
            "hint: codex",
            "learning: observed, not applied",
            "route/execution diverged"
        ]
    });

    assert.deepEqual(plan.routeChips, [
        "模式：orchestrated",
        "偏好：codex",
        "学习记忆：已观察未应用",
        "路由/执行：不一致"
    ]);
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
    assert.deepEqual(plan.primaryRecoveryNote, {
        headline: "恢复阶段会优先避开 claude",
        detail: "最近窗口内出现了临时 provider 失败，恢复建议会先尝试其他 provider。"
    });
    assert.equal(plan.drawerSummary, "展开路由细节 · 1 组补充");
});

test("route box plan surfaces legacy control audit as a human-readable note", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "router",
        routeReason: "route selected",
        legacyControlAudit: {
            legacyControlRouteObserved: true,
            requestMethod: "GET",
            requestPath: "/api/v1/tasks/task-1/pause",
            replacementMethod: "POST",
            latestAction: "pause"
        }
    });

    assert.deepEqual(plan.legacyControlNote, {
        visible: true,
        headline: "检测到历史 GET 控制调用",
        detail: "最近一次是 GET /api/v1/tasks/task-1/pause；调用方应迁到 POST；最近动作：pause。",
        chip: "历史 GET 控制路由"
    });
});

test("route box plan keeps recovery fresh-session chip as drawer detail", () => {
    const plan = buildRouteBoxPlan({
        selectedWorker: "codex",
        routeSource: "task_pinned",
        routeReason: "pinned route",
        routeChips: ["recovery: fresh session"]
    });

    assert.deepEqual(plan.routeChips, ["恢复：新会话"]);
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开路由细节 · 1 组补充");
});

test("route drawer summary uses localized labels", () => {
    const withTimelineOnly = buildRouteBoxPlan({
        selectedWorker: "codex",
        cognitionTimeline: [{ stage: "route" }]
    });

    assert.equal(withTimelineOnly.drawerSummary, "展开路由轨迹 · 1 条");
    assert.equal(withTimelineOnly.drawerSummary.includes("route"), false);
    assert.equal(withTimelineOnly.drawerSummary.includes("timeline"), false);
});

test("dialogue route chip source does not emit raw English control labels", async () => {
    const { readFile } = await import("node:fs/promises");
    const appJs = await readFile(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

    assert.doesNotMatch(appJs, /`mode: \$\{humanizeToken\(modelMode\)/);
    assert.doesNotMatch(appJs, /`hint: \$\{preferredWorkerHint\}`/);
    assert.doesNotMatch(appJs, /"learning: applied"/);
    assert.doesNotMatch(appJs, /"learning: observed, not applied"/);
    assert.doesNotMatch(appJs, /"route\/execution aligned"/);
    assert.doesNotMatch(appJs, /"route\/execution diverged"/);
    assert.match(appJs, /`模式：\$\{humanizeToken\(modelMode\)/);
    assert.match(appJs, /`偏好：\$\{preferredWorkerHint\}`/);
    assert.match(appJs, /"学习记忆：已应用"/);
    assert.match(appJs, /"路由\/执行：一致"/);
});

test("dialogue route box reads legacy control audit from live flow cognition surface", async () => {
    const { readFile } = await import("node:fs/promises");
    const appJs = await readFile(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");
    const routeBoxPlanJs = await readFile(new URL("../../main/resources/web/dialogue/route-box-plan.js", import.meta.url), "utf8");
    const legacyAuditPlanJs = await readFile(new URL("../../main/resources/web/dialogue/legacy-control-audit-plan.js", import.meta.url), "utf8");

    assert.match(routeBoxPlanJs, /import \{ buildLegacyControlAuditPlan \} from "\.\/legacy-control-audit-plan\.js";/);
    assert.match(routeBoxPlanJs, /const legacyControlNote = buildLegacyControlAuditPlan\(source\.legacyControlAudit \|\| source\.legacy_control_audit\);/);
    assert.match(appJs, /const legacyControlAudit = cognitionSurface\.legacy_control_audit \|\| cognitionSurface\.legacyControlAudit \|\| \{\};/);
    assert.match(appJs, /legacyControlAudit,/);
    assert.match(appJs, /routePlan\.legacyControlNote/);
    assert.match(legacyAuditPlanJs, /检测到历史 GET 控制调用/);
});

test("dialogue task modal route labels avoid raw worker and empty English fallbacks", async () => {
    const { readFile } = await import("node:fs/promises");
    const appJs = await readFile(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");
    const modalSource = appJs.slice(
        appJs.indexOf("async function openTaskDetailModal"),
        appJs.indexOf("function renderProviderRunFiles")
    );

    assert.doesNotMatch(modalSource, /<label>Worker<\/label>/);
    assert.doesNotMatch(modalSource, /<label>选中 Worker<\/label>/);
    assert.doesNotMatch(modalSource, /<label>候选 Workers<\/label>/);
    assert.doesNotMatch(modalSource, /"unassigned"/);
    assert.doesNotMatch(modalSource, /"unknown"/);
    assert.doesNotMatch(modalSource, /"not specified"/);
    assert.doesNotMatch(modalSource, /"no result"/);
    assert.doesNotMatch(modalSource, /"tool"/);
    assert.doesNotMatch(modalSource, /\|\| "none"/);
    assert.match(modalSource, /<label>执行方<\/label>/);
    assert.match(modalSource, /<label>选中执行方<\/label>/);
    assert.match(modalSource, /<label>候选执行方<\/label>/);
});
