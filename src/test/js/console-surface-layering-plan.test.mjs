import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");
const html = readFileSync(new URL("../../main/resources/web/console/index.html", import.meta.url), "utf8");
const css = readFileSync(new URL("../../main/resources/web/console/app.css", import.meta.url), "utf8");

test("console inspector defaults to summary surface and syncs non-default surface in hash", () => {
    assert.match(appJs, /inspectorSurface:\s*"summary"/);
    assert.match(appJs, /const surface = normalizeInspectorSurface\(params\.get\("surface"\), params\.get\("inspector_surface"\)\);/);
    assert.match(appJs, /state\.inspectorSurface = "summary";/);
    assert.match(appJs, /if \(state\.inspectorSurface && state\.inspectorSurface !== "summary"\) \{\s*params\.set\("surface", state\.inspectorSurface\);/s);
});

test("console inspector can switch summary diagnostics and raw surfaces", () => {
    assert.match(appJs, /dom\.inspectorSurfaceSwitch\.addEventListener\("click", onInspectorSurfaceClick\)/);
    assert.match(appJs, /function onInspectorSurfaceClick\(event\)/);
    assert.match(appJs, /function setInspectorSurface\(surface\)/);
    assert.match(appJs, /function applyInspectorSurface\(\)/);
    assert.match(appJs, /button\.setAttribute\("aria-pressed", pressed \? "true" : "false"\);/);
    assert.match(appJs, /element\.hidden = surfaces\.length > 0 && !surfaces\.includes\(surface\);/);
    assert.match(appJs, /rawDetails\.open = surface === "raw";/);
    assert.match(appJs, /function inspectorSurfaceCopy\(surface\)/);
});

test("console inspector surface switch and card surfaces are present in html", () => {
    assert.match(html, /id="inspectorSurfaceSwitch"/);
    assert.match(html, /id="operatorSummary"/);
    assert.match(html, /data-inspector-surface="summary"/);
    assert.match(html, /data-inspector-surface="diagnostics"/);
    assert.match(html, /data-inspector-surface="raw"/);
    assert.match(html, /Operator Summary/);
    assert.match(html, /data-inspector-surfaces="summary diagnostics"/);
    assert.match(html, /data-inspector-surfaces="diagnostics"/);
    assert.match(html, /data-inspector-surfaces="raw"/);
    assert.match(html, /id="rawJsonDetails"/);
});

test("console inspector surface styling includes pills and hidden card support", () => {
    assert.match(css, /\.inspector-surface-switch\s*\{/);
    assert.match(css, /\.surface-pill\s*\{/);
    assert.match(css, /\.surface-pill\[aria-pressed="true"\]\s*\{/);
    assert.match(css, /\.inspector-card\[hidden\]\s*\{/);
    assert.match(css, /\.operator-summary-card\s*\{/);
});

test("console summary surface renders operator blocker and action summary", () => {
    assert.match(appJs, /operatorSummary:\s*document\.getElementById\("operatorSummary"\)/);
    assert.match(appJs, /dom\.operatorSummary\.innerHTML = emptyState\("当前任务还没有阻塞摘要、建议动作或恢复窗口。"\);/);
    assert.match(appJs, /dom\.operatorSummary\.innerHTML = renderOperatorSummary\(operatorSummaryPlan\);/);
    assert.match(appJs, /function renderOperatorSummary\(plan\)/);
    assert.match(appJs, /function buildOperatorSummaryPlan\(input\)/);
    assert.match(appJs, /建议动作：/);
    assert.match(appJs, /下一步：/);
    assert.match(appJs, /function sanitizeConsoleSummary\(rawValue,\s*workerHint = ""\)/);
    assert.match(appJs, /线程未找到/);
    assert.match(appJs, /执行超时/);
});

test("console operator summary reuses free-first route narration for paid fallback and manual-window blockers", () => {
    assert.match(appJs, /import \{ buildFreeFirstRoutePlan \} from "\.\.\/dialogue\/free-first-route-plan\.js";/);
    assert.match(appJs, /import \{ buildLegacyControlAuditPlan \} from "\.\.\/dialogue\/legacy-control-audit-plan\.js";/);
    assert.match(appJs, /const freeFirstRoute = buildFreeFirstRoutePlan\(routePreview\);/);
    assert.match(appJs, /const legacyControlAudit = buildLegacyControlAuditPlan\(/);
    assert.match(appJs, /freeFirstRoute\.visible \? freeFirstRoute\.headline : runtimeHealthDeprioritization\.headline/);
    assert.match(appJs, /freeFirstRoute\.detail,/);
    assert.match(appJs, /freeFirstRoute\.chip \|\| null/);
    assert.match(appJs, /legacyControlAudit\.visible \? legacyControlAudit\.chip : null/);
    assert.match(appJs, /兼容路由：/);
});
