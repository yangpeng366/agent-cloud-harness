import test from "node:test";
import assert from "node:assert/strict";
import { renderMessageSummaryStackHtml } from "../../main/resources/web/dialogue/message-summary-render-plan.js";

test("message summary render plan emits one primary card and secondary briefs", () => {
    const html = renderMessageSummaryStackHtml({
        primary: { role: "assistant" },
        secondary: [{ role: "system" }]
    }, {
        renderCard: (summary) => `<article data-primary="${summary.role}"></article>`,
        renderBrief: (summary) => `<aside data-brief="${summary.role}"></aside>`
    });

    assert.match(html, /data-primary="assistant"/);
    assert.match(html, /message-summary-briefs/);
    assert.match(html, /data-brief="system"/);
});

test("message summary render plan omits brief wrapper when there is no secondary summary", () => {
    const html = renderMessageSummaryStackHtml({
        primary: { role: "assistant" },
        secondary: []
    }, {
        renderCard: () => "<article></article>",
        renderBrief: () => "<aside></aside>"
    });

    assert.match(html, /<article>/);
    assert.doesNotMatch(html, /message-summary-briefs/);
  });
