import test from "node:test";
import assert from "node:assert/strict";
import { buildContinuitySummaryPlan } from "../../main/resources/web/dialogue/continuity-summary-plan.js";

test("continuity summary plan keeps short preview visible and pushes overflow chips behind drawer", () => {
    const plan = buildContinuitySummaryPlan({
        summary: "这是一个很长的 continuity summary，需要默认只显示一段更短的预览文本，避免右侧 inspector 常驻区域过高。".repeat(3),
        openQuestions: ["q1", "q2", "q3"],
        nextCandidates: ["n1", "n2", "n3"]
    });

    assert.ok(plan.previewText.length <= 160);
    assert.deepEqual(plan.visibleChips, ["open: q1", "open: q2", "open: q3", "next: n1"]);
    assert.deepEqual(plan.hiddenChips, ["next: n2", "next: n3"]);
    assert.equal(plan.hasDrawer, true);
    assert.match(plan.drawerSummary, /还有 2 条 continuity chip/);
    assert.match(plan.drawerSummary, /展开完整摘要/);
});

test("continuity summary plan stays flat when summary and chips are already short", () => {
    const plan = buildContinuitySummaryPlan({
        summary: "short summary",
        openQuestions: ["q1"],
        nextCandidates: ["n1"]
    });

    assert.equal(plan.previewText, "short summary");
    assert.deepEqual(plan.visibleChips, ["open: q1", "next: n1"]);
    assert.deepEqual(plan.hiddenChips, []);
    assert.equal(plan.hasDrawer, false);
    assert.equal(plan.drawerSummary, "");
});
