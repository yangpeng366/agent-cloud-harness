import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildExperimentSummaryPlan } from "../../main/resources/web/dialogue/experiment-summary-plan.js";

test("experiment summary plan keeps current mode primary and pushes comparison content into drawer", () => {
    const plan = buildExperimentSummaryPlan({
        experimentName: "batch_alpha",
        taskLabel: "当前任务",
        summaryChips: ["模式：orchestrated", "用例：case_1"],
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
    assert.equal(plan.drawerSummary, "展开实验对比 · 2 个模式对比 / 提示词 rollout / 3 个用例对照");
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

test("dialogue experiment summary copy does not expose raw English metric labels", () => {
    const appJs = readFileSync(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

    assert.match(appJs, /模式：\$\{humanizeToken\(currentMode\) \|\| currentMode\}/);
    assert.match(appJs, /用例：\$\{taskCaseKey\}/);
    assert.match(appJs, /验收：\$\{humanizeToken\(acceptanceResult\) \|\| acceptanceResult\}/);
    assert.match(appJs, /长度桶：\$\{humanizeToken\(taskLengthBucket\) \|\| taskLengthBucket\}/);
    assert.match(appJs, /次运行/);
    assert.match(appJs, /次运行/);
    assert.match(appJs, /验收通过/);
    assert.match(appJs, /学习偏好已应用/);
    assert.match(appJs, /平均工具步数/);
    assert.match(appJs, /步骤 \$\{String/);
    assert.match(appJs, /成本 \$\{formatDecimal/);
    assert.doesNotMatch(appJs, /`mode: \$\{humanizeToken\(currentMode\) \|\| currentMode\}`/);
    assert.doesNotMatch(appJs, /`case: \$\{taskCaseKey\}`/);
    assert.doesNotMatch(appJs, /`acceptance: \$\{humanizeToken\(acceptanceResult\) \|\| acceptanceResult\}`/);
    assert.doesNotMatch(appJs, /`bucket: \$\{humanizeToken\(taskLengthBucket\) \|\| taskLengthBucket\}`/);
    assert.doesNotMatch(appJs, /`runs: \$\{String\(numberOrNull\(summary\.total_runs, summary\.totalRuns\) \?\? 0\)\}`/);
    assert.doesNotMatch(appJs, /done ·/);
    assert.doesNotMatch(appJs, /learned hint applied/);
    assert.doesNotMatch(appJs, /avg tool steps/);
    assert.doesNotMatch(appJs, />missing</);
    assert.doesNotMatch(appJs, />[^<]* runs</);
    assert.doesNotMatch(appJs, /steps \$\{String/);
    assert.doesNotMatch(appJs, /cost \$\{formatDecimal/);
});
