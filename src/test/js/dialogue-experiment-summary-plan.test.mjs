import test from "node:test";
import assert from "node:assert/strict";
import { buildExperimentSummaryPlan } from "../../main/resources/web/dialogue/experiment-summary-plan.js";

test("experiment summary plan keeps current mode primary and pushes comparison content into drawer", () => {
    const plan = buildExperimentSummaryPlan({
        experimentName: "batch_alpha",
        taskLabel: "current task",
        summaryChips: ["mode: orchestrated", "case: case_1"],
        currentMode: "orchestrated",
        modeSummaries: [
            { model_mode: "strong_only" },
            { model_mode: "orchestrated" },
            { model_mode: "small_only" }
        ],
        currentCase: {
            runs_by_mode: {
                strong_only: {},
                orchestrated: {},
                small_only: {}
            }
        },
        supportedModes: ["strong_only", "orchestrated", "small_only"],
        promptModeSummaries: {
            active_context_only: { run_count: 1 }
        }
    });

    assert.equal(plan.currentModeCard.model_mode, "orchestrated");
    assert.equal(plan.comparisonModeCards.length, 2);
    assert.equal(plan.hasPromptRollout, true);
    assert.equal(plan.hasCaseComparison, true);
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开 experiment 对比 · 2 mode 对比 / prompt rollout / 3 case 对照");
});

test("experiment summary plan omits drawer when only current mode headline is available", () => {
    const plan = buildExperimentSummaryPlan({
        currentMode: "strong_only",
        modeSummaries: [{ model_mode: "strong_only" }]
    });

    assert.equal(plan.currentModeCard.model_mode, "strong_only");
    assert.equal(plan.comparisonModeCards.length, 0);
    assert.equal(plan.hasDrawer, false);
    assert.equal(plan.drawerSummary, "");
});
