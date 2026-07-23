import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");
const html = readFileSync(new URL("../../main/resources/web/console/index.html", import.meta.url), "utf8");
const css = readFileSync(new URL("../../main/resources/web/console/app.css", import.meta.url), "utf8");

test("console inspector overview reuses shared focus-line and four-card plan", () => {
    assert.match(appJs, /import \{ buildTaskFocusLineBase \} from "\.\.\/dialogue\/task-focus-line-plan\.js";/);
    assert.match(appJs, /import \{ buildTaskOverviewPlan \} from "\.\.\/dialogue\/task-overview-plan\.js";/);
    assert.match(appJs, /dom\.taskFocusLine\.textContent = "状态 \/ 控制节点";/);
    assert.match(appJs, /const focusLineBase = buildTaskFocusLineBase\(task, flow \|\| \{\}\);/);
    assert.match(appJs, /const overviewPlan = buildTaskOverviewPlan\(task, \{/);
    assert.match(appJs, /dom\.taskFocusLine\.textContent = overviewPlan\.focusLine;/);
    assert.match(appJs, /dom\.taskOverview\.innerHTML = overviewPlan\.cards\.map\(\(card\) => overviewCard\(card\.label, card\.value\)\)\.join\(""\);/);
    assert.match(appJs, /"未分配"/);
    assert.doesNotMatch(appJs, /"ad hoc"/);
    assert.doesNotMatch(appJs, /overviewCard\("工作节点"/);
    assert.doesNotMatch(appJs, /overviewCard\("工具 trace"/);
    assert.doesNotMatch(appJs, /overviewCard\("Tool chain"/);
    assert.doesNotMatch(appJs, /overviewCard\("Execution"/);
});

test("console inspector header exposes dedicated focus line element", () => {
    assert.match(html, /id="taskFocusLine"/);
    assert.match(html, />状态 \/ 控制节点</);
    assert.match(css, /\.task-focus-line\s*\{/);
});

test("console route and execution diagnostics avoid raw English worker labels", () => {
    assert.match(appJs, /`选中执行方 \$\{selectedWorker\}`/);
    assert.match(appJs, /`候选执行方：\$\{candidateWorkers\.join\(", "\)\}`/);
    assert.match(appJs, /<span class="task-badge" data-tone="active">执行回合<\/span>/);
    assert.match(appJs, /`执行回合：\$\{facts\.executionId\}`/);
    assert.match(appJs, /`执行方：\$\{facts\.workerId\}`/);
    assert.match(appJs, /"已记录执行边界"/);
    assert.doesNotMatch(appJs, /"unassigned"/);
    assert.doesNotMatch(appJs, />execution</);
    assert.doesNotMatch(appJs, /`id \$\{facts\.executionId\}`/);
    assert.doesNotMatch(appJs, /`worker \$\{facts\.workerId\}`/);
    assert.doesNotMatch(appJs, /"execution boundary captured"/);
});
