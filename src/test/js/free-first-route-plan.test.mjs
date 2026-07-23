import test from "node:test";
import assert from "node:assert/strict";

import { buildFreeFirstRoutePlan } from "../../main/resources/web/dialogue/free-first-route-plan.js";

test("free-first route plan exposes manual window recommendation as a human-readable blocker", () => {
    const plan = buildFreeFirstRoutePlan({
        manual_window_required: true,
        recommended_manual_provider: "trae",
        manual_window_candidates: ["trae", "zcode"],
        fallback_reason: "free provider quota exhausted: deveco, codebuddy"
    });

    assert.equal(plan.visible, true);
    assert.equal(plan.chip, "手动窗口：trae");
    assert.match(plan.headline, /建议切到 trae 手动继续/);
    assert.match(plan.detail, /免费 provider 额度已耗尽/);
    assert.match(plan.detail, /候选：trae、zcode/);
    assert.match(plan.detail, /回填当前任务/);
});

test("free-first route plan exposes paid fallback narrative when router switches to paid_auto", () => {
    const plan = buildFreeFirstRoutePlan({
        cost_route_stage: "paid_auto",
        fallback_reason: "free_auto unavailable; fallback to paid_auto",
        free_candidate_workers: ["deveco", "codebuddy"],
        paid_candidate_workers: ["codex", "reasonix"]
    });

    assert.equal(plan.visible, true);
    assert.equal(plan.chip, "已切付费自动链路");
    assert.match(plan.headline, /已回退到付费 provider/);
    assert.match(plan.detail, /免费自动 provider 当前不可用/);
    assert.match(plan.detail, /免费候选：deveco、codebuddy/);
    assert.match(plan.detail, /付费候选：codex、reasonix/);
});
