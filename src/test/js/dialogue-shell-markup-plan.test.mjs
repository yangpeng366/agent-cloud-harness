import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

test("dialogue shell stays transcript-first with collapsed task thread and single composer surface", () => {
    const html = readFileSync(new URL("../../main/resources/web/dialogue/index.html", import.meta.url), "utf8");

    assert.equal(html.includes('id="workspaceSurfaceTitle">Session Transcript</h3>'), true);
    assert.equal(html.includes('id="threadDrawer" hidden'), true);
    assert.equal(html.includes("<strong>任务链</strong>"), true);
    assert.equal(html.includes('id="detailsToggleButton" type="button">查看任务面板</button>'), true);
    assert.equal(html.includes('id="composerRoutingMeta">默认聊天发送</span>'), true);
    assert.equal(html.includes('id="submitTaskButton" type="submit">发送</button>'), true);
    assert.equal(html.includes('id="messageHint">当前输入框也可当作 session message composer 使用。</p>'), true);
});
