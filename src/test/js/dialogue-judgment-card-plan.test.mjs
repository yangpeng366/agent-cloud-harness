import test from "node:test";
import assert from "node:assert/strict";
import { buildJudgmentCardBody, mapClosureContractFields } from "../../main/resources/web/dialogue/judgment-card-plan.js";

test("judgment card body keeps summary, execution boundary, and only a small number of diagnostics", () => {
    const body = buildJudgmentCardBody({
        rationale: "Judgment rationale: mounted context looks good and the route is aligned with execution.",
        executionLine: "packet boundary with mounted primary prompt mode and evidence refs",
        metrics: ["prompt mounted context primary", "mounted injected", "6 panels"],
        cognitionRows: [
            { label: "route", value: "codex via task pinned" },
            { label: "next", value: "continue expanding section two" },
            { label: "evidence", value: "artifact_1" }
        ]
    });

    assert.match(body, /Judgment rationale:/);
    assert.match(body, /Execution: packet boundary/);
    assert.match(body, /prompt mounted context primary · mounted injected/);
    assert.match(body, /route: codex via task pinned · next: continue expanding section two/);
    assert.doesNotMatch(body, /6 panels/);
    assert.doesNotMatch(body, /evidence: artifact_1/);
});


test("judgment card body surfaces decision_rationale and progress_detail when provided", () => {
    const body = buildJudgmentCardBody({
        rationale: "Judgment rationale: mounted context looks good.",
        decisionRationale: "goal: 1/3 done, 1 blocked, 1 open; execution continue (partially_done, medium alignment) -> checkpoint",
        progressDetail: "1/3 done, 1 blocked, 1 open; blocked: API integration"
    });

    assert.match(body, /Decision: goal: 1\/3 done, 1 blocked/);
    assert.match(body, /Progress: 1\/3 done, 1 blocked, 1 open; blocked: API integration/);
    assert.match(body, /Judgment rationale:/);
});

test("judgment card body omits closure-contract lines when absent (backward compatible)", () => {
    const body = buildJudgmentCardBody({
        rationale: "Judgment rationale: only legacy rationale present.",
        executionLine: "packet boundary"
    });

    assert.doesNotMatch(body, /Decision:/);
    assert.doesNotMatch(body, /Progress:/);
    assert.match(body, /Judgment rationale:/);
    assert.match(body, /Execution: packet boundary/);
});

test("mapClosureContractFields extracts snake_case and camelCase closure fields", () => {
    const snake = mapClosureContractFields({
        decision_rationale: "goal: 2/2 done -> done",
        progress_detail: "2/2 done",
        progress_summary: "2/2 subgoals done"
    });
    assert.equal(snake.decisionRationale, "goal: 2/2 done -> done");
    assert.equal(snake.progressDetail, "2/2 done");
    assert.equal(snake.progressSummary, "2/2 subgoals done");

    const camel = mapClosureContractFields({
        decisionRationale: "goal: 0/1 done, 1 open -> continue",
        progressDetail: "0/1 done, 1 open",
        progressSummary: "0/1 subgoals done"
    });
    assert.equal(camel.decisionRationale, "goal: 0/1 done, 1 open -> continue");
    assert.equal(camel.progressDetail, "0/1 done, 1 open");

    assert.deepEqual(mapClosureContractFields(null), {
        decisionRationale: "",
        progressDetail: "",
        progressSummary: ""
    });
});
