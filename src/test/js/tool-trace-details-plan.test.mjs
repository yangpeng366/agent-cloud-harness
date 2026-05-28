import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
    buildToolTraceStatusLabel,
    buildToolTraceSummary,
    buildToolTraceTouchedPaths
} from "../../main/resources/web/dialogue/tool-trace-plan.js";

const dialogueJs = readFileSync(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");
const consoleJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");

test("tool trace summary prioritizes structured fields before free text", () => {
    const summary = buildToolTraceSummary({
        execution_id: "exec_1",
        touched_paths: ["docs/a.md", "src/B.java", "README.md", "pom.xml"],
        result_summary: "wrote files"
    });

    assert.equal(summary, "exec exec_1 · paths docs/a.md, src/B.java, README.md +1 · wrote files");
});

test("tool trace summary supports camelCase API fields and fallback", () => {
    assert.equal(
        buildToolTraceSummary({
            executionId: "exec_2",
            touchedPaths: ["src/A.java"],
            resultSummary: "ok"
        }),
        "exec exec_2 · paths src/A.java · ok"
    );
    assert.equal(buildToolTraceSummary({}), "no summary");
});

test("tool trace path projection limits noisy path lists", () => {
    assert.equal(
        buildToolTraceTouchedPaths({ touched_paths: ["a", "b", "c", "d", "e"] }),
        "paths a, b, c +2"
    );
    assert.equal(buildToolTraceTouchedPaths({ touched_paths: [] }), null);
});

test("tool trace status label preserves existing fallback behavior", () => {
    assert.equal(buildToolTraceStatusLabel({ status: "running", success: true }), "running");
    assert.equal(buildToolTraceStatusLabel({ success: true }), "succeeded");
    assert.equal(buildToolTraceStatusLabel({ success: false }), "failed");
    assert.equal(buildToolTraceStatusLabel({}), "failed");
});

test("dialogue and console use the shared tool trace plan", () => {
    assert.match(dialogueJs, /from "\.\/tool-trace-plan\.js"/);
    assert.match(dialogueJs, /buildToolTraceSummary\(tool, \{ preview \}\)/);
    assert.match(dialogueJs, /buildToolTraceStatusLabel\(tool\)/);

    assert.match(consoleJs, /from "\.\.\/dialogue\/tool-trace-plan\.js"/);
    assert.match(consoleJs, /buildToolTraceSummary\(tool, \{ preview \}\)/);
    assert.match(consoleJs, /buildToolTraceStatusLabel\(tool\)/);
});
