import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

test("composer mode switch only exposes auto and task buttons", () => {
    const html = readFileSync(new URL("../../main/resources/web/dialogue/index.html", import.meta.url), "utf8");

    const modeTokens = Array.from(html.matchAll(/data-composer-mode="([^"]+)"/g)).map((match) => match[1]);
    assert.deepEqual(modeTokens, ["auto", "task"]);
    assert.equal(html.includes('data-composer-mode="followup"'), false);
    assert.equal(html.includes('id="followupButton"'), true);
    assert.equal(html.includes('id="clearFollowupButton"'), true);
    assert.equal(html.includes('id="composerAdvanced"'), true);
    assert.equal(html.includes('id="composerRoutingMeta">默认聊天推进</div>'), true);
    assert.equal(html.includes('id="submitTaskButton" type="submit">发送</button>'), true);
});
