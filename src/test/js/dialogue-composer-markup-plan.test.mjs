import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

test("composer mode switch only exposes auto message and task buttons", () => {
    const html = readFileSync(new URL("../../main/resources/web/dialogue/index.html", import.meta.url), "utf8");

    const modeTokens = Array.from(html.matchAll(/data-composer-mode="([^"]+)"/g)).map((match) => match[1]);
    assert.deepEqual(modeTokens, ["auto", "message", "task"]);
    assert.equal(html.includes('data-composer-mode="followup"'), false);
    assert.equal(html.includes('id="followupButton"'), true);
    assert.equal(html.includes('id="composerRouting"'), true);
    assert.equal(html.includes('id="composerAdvanced"'), true);
    assert.equal(html.includes('id="submitTaskButton" type="submit">发送</button>'), true);
});
