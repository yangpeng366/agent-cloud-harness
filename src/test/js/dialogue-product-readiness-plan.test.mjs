import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");
const html = readFileSync(new URL("../../main/resources/web/dialogue/index.html", import.meta.url), "utf8");
const css = readFileSync(new URL("../../main/resources/web/dialogue/app.css", import.meta.url), "utf8");
const routeBoxPlanJs = readFileSync(new URL("../../main/resources/web/dialogue/route-box-plan.js", import.meta.url), "utf8");
const taskActionPlanJs = readFileSync(new URL("../../main/resources/web/dialogue/task-action-plan.js", import.meta.url), "utf8");

test("dialogue shell defaults to lightweight details until a task is explicitly addressed", () => {
    assert.match(appJs, /detailsOpen:\s*false/);
    assert.match(appJs, /else if \(taskId\) \{\s*state\.detailsOpen = true;/);
    assert.match(appJs, /else \{\s*state\.detailsOpen = false;\s*\}/);
    assert.match(appJs, /async function selectTask\(taskId[\s\S]*setDetailsOpen\(true\);[\s\S]*await loadSelectedTask/s);
});

test("dialogue exposes model readiness without hiding the composer", () => {
    assert.match(html, /id="readinessBanner" hidden/);
    assert.match(html, /id="readinessBannerText"/);
    assert.match(html, /manual-start/);
    assert.match(css, /\.readiness-banner\s*\{/);
    assert.match(appJs, /function renderReadinessBanner\(\)/);
    assert.match(appJs, /state\.health\?\.llm/);
    assert.match(appJs, /renderReadinessBanner\(\);/);
});

test("dialogue downgrades auto progression when the LLM is unavailable", () => {
    assert.match(appJs, /const llmUnavailable = state\.health\?\.llm\?\.available === false;/);
    assert.match(appJs, /const autoStart = llmUnavailable \? false : dom\.taskAutoStart\.checked;/);
    assert.match(appJs, /const taskMode = llmUnavailable && context\.taskMode === "task_auto"[\s\S]*\? "task_required"[\s\S]*: context\.taskMode;/);
    assert.match(appJs, /已切到 manual-start，避免误报自动推进/);
});

test("dialogue surfaces async recovery request ids immediately", () => {
    assert.match(appJs, /function applyOptimisticRecoveryReceipt\(taskId, result, requestBody\)/);
    assert.match(appJs, /status:\s*"running"/);
    assert.match(appJs, /state\.recoveryJobs = mergeRecoveryJobs\(\[receipt\], taskId\);/);
    assert.match(appJs, /if \(action === "recover"\) \{\s*applyOptimisticRecoveryReceipt\(targetTaskId, result, requestBody\);/);
});

test("dialogue route box turns free-first manual-window and paid fallback metadata into human-readable hints", () => {
    assert.match(routeBoxPlanJs, /import \{ buildFreeFirstRoutePlan \} from "\.\/free-first-route-plan\.js";/);
    assert.match(routeBoxPlanJs, /const freeFirstRoute = buildFreeFirstRoutePlan\(source\);/);
    assert.match(routeBoxPlanJs, /const legacyControlNote = buildLegacyControlAuditPlan\(source\.legacyControlAudit \|\| source\.legacy_control_audit\);/);
    assert.match(routeBoxPlanJs, /primaryRecoveryNote: freeFirstRoute\.visible/);
    assert.match(appJs, /manualWindowRequired:\s*booleanValue\(/);
    assert.match(appJs, /recommendedManualProvider:\s*firstNonBlank\(/);
    assert.match(appJs, /freeCandidateWorkers:\s*normalizeTextList\(/);
    assert.match(appJs, /paidCandidateWorkers:\s*normalizeTextList\(/);
    assert.match(appJs, /costRouteStage:\s*firstNonBlank\(/);
    assert.match(appJs, /legacyControlAudit,/);
    assert.match(appJs, /routePlan\.legacyControlNote/);
});

test("dialogue task action area surfaces manual-window follow-up guidance from task metadata", () => {
    assert.match(taskActionPlanJs, /manual_followup_instruction/);
    assert.match(taskActionPlanJs, /buildFreeFirstRoutePlan\(metadata\)/);
    assert.match(taskActionPlanJs, /候选：\$\{manualWindowCandidates\.join\("、"\)\}。/);
    assert.match(appJs, /dom\.taskActions\.innerHTML = taskActionRender\.noteHtml \+ taskActionRender\.primaryHtml;/);
    assert.match(css, /\.task-action-note--manual-window\s*\{/);
});
