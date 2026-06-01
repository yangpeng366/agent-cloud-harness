import test from "node:test";
import assert from "node:assert/strict";
import { renderFacadeReplyBadgeHtml } from "../../main/resources/web/dialogue/facade-reply-badge-render-plan.js";

test("facade reply badge render plan emits localized latest progress badge html", () => {
    const html = renderFacadeReplyBadgeHtml({
        badgeText: "最新进展",
        badgeTone: "active"
    }, {
        escapeHtml: (value) => String(value)
    });

    assert.match(html, /task-badge/);
    assert.match(html, /data-tone="active"/);
    assert.match(html, /最新进展/);
    assert.doesNotMatch(html, /latest progress/);
});

test("facade reply badge render plan returns empty html when no badge is needed", () => {
    const html = renderFacadeReplyBadgeHtml(null, {
        escapeHtml: (value) => String(value)
    });

    assert.equal(html, "");
});
