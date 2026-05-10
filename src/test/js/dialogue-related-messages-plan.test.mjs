import test from "node:test";
import assert from "node:assert/strict";
import { buildRelatedMessagesPlan } from "../../main/resources/web/dialogue/related-messages-plan.js";

test("related messages plan keeps only a small preview visible by default", () => {
    const plan = buildRelatedMessagesPlan([
        { id: "m1" },
        { id: "m2" },
        { id: "m3" },
        { id: "m4" },
        { id: "m5" }
    ]);

    assert.equal(plan.visibleCount, 3);
    assert.equal(plan.hiddenCount, 2);
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开更多关联消息 · 还有 2 条");
});

test("related messages plan omits drawer when preview already covers all messages", () => {
    const plan = buildRelatedMessagesPlan([{ id: "m1" }, { id: "m2" }]);

    assert.equal(plan.visibleCount, 2);
    assert.equal(plan.hiddenCount, 0);
    assert.equal(plan.hasDrawer, false);
});
