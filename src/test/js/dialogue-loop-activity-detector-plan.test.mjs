import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { detectLoopActivity, loopActivityDisplayHint } from "../../main/resources/web/dialogue/loop-activity-detector-plan.js";

const NOW = 1700000000000; // fixed now for deterministic tests

describe("detectLoopActivity", () => {
    it("active when tick is very recent", () => {
        const result = detectLoopActivity(new Date(NOW - 5000).toISOString(), NOW);
        assert.equal(result.activity, "active");
        assert.equal(result.ageMs, 5000);
    });

    it("stall when tick is moderately old", () => {
        const result = detectLoopActivity(new Date(NOW - 20000).toISOString(), NOW);
        assert.equal(result.activity, "stall");
        assert.equal(result.ageMs, 20000);
    });

    it("stale when tick is very old", () => {
        const result = detectLoopActivity(new Date(NOW - 60000).toISOString(), NOW);
        assert.equal(result.activity, "stale");
        assert.equal(result.ageMs, 60000);
    });

    it("unknown when no tick provided", () => {
        const result = detectLoopActivity(null, NOW);
        assert.equal(result.activity, "unknown");
        assert.equal(result.ageMs, null);
    });

    it("unknown when tick is invalid", () => {
        const result = detectLoopActivity("not-a-date", NOW);
        assert.equal(result.activity, "unknown");
        assert.equal(result.ageMs, null);
    });

    it("respects custom thresholds", () => {
        const result = detectLoopActivity(new Date(NOW - 15000).toISOString(), NOW, 5000, 20000);
        assert.equal(result.activity, "stall");
    });

    it("active at exact active threshold", () => {
        const result = detectLoopActivity(new Date(NOW - 10000).toISOString(), NOW);
        assert.equal(result.activity, "active");
    });

    it("stall at exact stall threshold", () => {
        const result = detectLoopActivity(new Date(NOW - 30000).toISOString(), NOW);
        assert.equal(result.activity, "stall");
    });
});

describe("loopActivityDisplayHint", () => {
    it("done task shows done regardless of loop", () => {
        const result = loopActivityDisplayHint({ activity: "active", ageMs: 1000 }, "done");
        assert.equal(result.displayStatus, "done");
    });

    it("failed task shows failed regardless of loop", () => {
        const result = loopActivityDisplayHint({ activity: "stale", ageMs: 60000 }, "failed");
        assert.equal(result.displayStatus, "failed");
    });

    it("waiting_human shows paused", () => {
        const result = loopActivityDisplayHint({ activity: "stale", ageMs: 60000 }, "waiting_human");
        assert.equal(result.displayStatus, "paused");
    });

    it("human_gate shows paused", () => {
        const result = loopActivityDisplayHint({ activity: "stale", ageMs: 60000 }, "human_gate");
        assert.equal(result.displayStatus, "paused");
    });

    it("active loop with active task shows running", () => {
        const result = loopActivityDisplayHint({ activity: "active", ageMs: 1000 }, "active");
        assert.equal(result.displayStatus, "running");
    });

    it("stall loop shows stall", () => {
        const result = loopActivityDisplayHint({ activity: "stall", ageMs: 20000 }, "active");
        assert.equal(result.displayStatus, "stall");
    });

    it("stale loop shows stale", () => {
        const result = loopActivityDisplayHint({ activity: "stale", ageMs: 60000 }, "active");
        assert.equal(result.displayStatus, "stale");
    });

    it("unknown loop shows unknown", () => {
        const result = loopActivityDisplayHint({ activity: "unknown", ageMs: null }, "active");
        assert.equal(result.displayStatus, "unknown");
    });
});
