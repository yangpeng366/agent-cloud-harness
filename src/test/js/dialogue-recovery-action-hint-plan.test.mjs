import test from "node:test";
import assert from "node:assert/strict";
import { recoveryActionHint } from "../../main/resources/web/dialogue/recovery-action-hint-plan.js";

// UI 验收标准 #2: waiting_human 时页面显示人工动作入口，而不是只显示一个错误。

test("recoveryActionHint returns action hint for 环境阻塞 when waiting for human", () => {
    assert.equal(recoveryActionHint("环境阻塞", "等待人工确认"), "先修环境后继续");
});

test("recoveryActionHint returns action hint for 部分结果待确认 when waiting for human", () => {
    assert.equal(recoveryActionHint("部分结果待确认", "等待人工确认"), "先复核已有结果");
});

test("recoveryActionHint returns empty when not waiting for human", () => {
    assert.equal(recoveryActionHint("环境阻塞", "自动切换 worker"), "");
    assert.equal(recoveryActionHint("环境阻塞", ""), "");
    assert.equal(recoveryActionHint("环境阻塞", null), "");
});

test("recoveryActionHint returns empty for unknown failure class even when waiting for human", () => {
    assert.equal(recoveryActionHint("未知错误", "等待人工确认"), "");
    assert.equal(recoveryActionHint(null, "等待人工确认"), "");
});

test("recoveryActionHint handles null/undefined inputs gracefully", () => {
    assert.equal(recoveryActionHint(null, null), "");
    assert.equal(recoveryActionHint(undefined, undefined), "");
});

// P3: goal_progress_blocked 场景
test("recoveryActionHint returns goal blocked hint when waitingReason contains subgoal blocked", () => {
    assert.equal(
        recoveryActionHint("未知错误", "等待人工确认", "subgoal blocked requires human gate"),
        "子目标被阻塞，请解除阻塞或调整子目标"
    );
});

test("recoveryActionHint returns goal blocked hint even when recoveryStage is not 等待人工确认", () => {
    assert.equal(
        recoveryActionHint(null, null, "subgoal blocked requires human gate"),
        "子目标被阻塞，请解除阻塞或调整子目标"
    );
});

test("recoveryActionHint returns empty when waitingReason does not contain subgoal blocked", () => {
    assert.equal(recoveryActionHint(null, null, "other reason"), "");
});

test("recoveryActionHint returns empty when waitingReason is null", () => {
    assert.equal(recoveryActionHint(null, null, null), "");
});
