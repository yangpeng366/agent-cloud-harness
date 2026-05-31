import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appSource = readFileSync(
    new URL("../../main/resources/web/dialogue/app.js", import.meta.url),
    "utf8"
);

test("selected task refresh loads messages without rendering before live flow render", () => {
    const refreshStart = appSource.indexOf("async function refreshAll(loud)");
    const loadMessagesNoRender = appSource.indexOf("await loadMessages(selectedSessionId, { render: false });", refreshStart);
    const loadSelectedTask = appSource.indexOf("await loadSelectedTask(state.selectedTaskId, false);", refreshStart);
    const renderMessagesInLoadSelectedTask = appSource.indexOf("renderMessages();", appSource.indexOf("async function loadSelectedTask"));

    assert.notEqual(refreshStart, -1);
    assert.notEqual(loadMessagesNoRender, -1);
    assert.notEqual(loadSelectedTask, -1);
    assert.ok(loadMessagesNoRender < loadSelectedTask);
    assert.notEqual(renderMessagesInLoadSelectedTask, -1);
});

test("loadMessages render option can suppress eager transcript render", () => {
    assert.match(appSource, /async function loadMessages\(sessionId = state\.selectedSessionId, options = \{\}\)/);
    assert.match(appSource, /const shouldRender = options\.render !== false;/);
    assert.match(appSource, /if \(shouldRender\) \{\s*renderMessages\(\);\s*\}/s);
});
