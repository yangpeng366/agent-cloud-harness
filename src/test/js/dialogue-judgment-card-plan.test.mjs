import test from "node:test";
import assert from "node:assert/strict";
import { buildJudgmentCardBody } from "../../main/resources/web/dialogue/judgment-card-plan.js";

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
