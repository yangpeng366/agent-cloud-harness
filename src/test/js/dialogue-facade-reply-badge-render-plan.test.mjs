import test from "node:test";
import assert from "node:assert/strict";
import { renderFacadeReplyBadgeHtml } from "../../main/resources/web/dialogue/facade-reply-badge-render-plan.js";

test("facade reply badge render plan emits latest progress badge html", () => {
    const html = renderFacadeReplyBadgeHtml({
        badgeText: "latest progress",
        badgeTone: "active"
    }, {
        escapeHtml: (value) => String(value)
    });

    assert.match(html, /task-badge/);
    assert.match(html, /data-tone="active"/);
    assert.match(html, /latest progress/);
});

test("facade reply badge render plan returns empty html when no badge is needed", () => {
    const html = renderFacadeReplyBadgeHtml(null, {
        escapeHtml: (value) => String(value)
    });

    assert.equal(html, "");
});
