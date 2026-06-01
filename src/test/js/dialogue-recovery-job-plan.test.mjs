import test from "node:test";
import assert from "node:assert/strict";
import { buildRecoveryJobPlan } from "../../main/resources/web/dialogue/recovery-job-plan.js";

test("recovery job plan hides empty state", () => {
    const plan = buildRecoveryJobPlan([]);

    assert.equal(plan.visible, false);
    assert.deepEqual(plan.cards, []);
});

test("recovery job plan summarizes latest async recovery job", () => {
    const plan = buildRecoveryJobPlan([{
        id: "recovery_123",
        status: "running",
        recommended_action: "fresh_session_resume",
        recovery_execution_mode: "fresh_session",
        target_worker: "codex",
        provider_failure_class: "provider_runtime_transient",
        accepted_at: "2026-05-17T10:00:00Z"
    }], {
        formatTime: () => "05/17 18:00"
    });

    assert.equal(plan.visible, true);
    assert.equal(plan.status, "running");
    assert.equal(plan.tone, "active");
    assert.equal(plan.requestId, "recovery_123");
    assert.equal(plan.summary, "运行中 / 新会话恢复");
    assert.deepEqual(plan.cards.map((item) => item.label), ["恢复任务", "请求", "动作", "模式"]);
    assert.equal(plan.cards[3].value, "新会话");
    assert.deepEqual(plan.chips, [
        "执行方 codex",
        "失败 临时运行失败",
        "受理 05/17 18:00"
    ]);
    assert.equal(plan.chips.some((chip) => /^worker /.test(chip)), false);
});

test("recovery job plan surfaces failed error summary", () => {
    const plan = buildRecoveryJobPlan([{
        id: "recovery_failed",
        status: "failed",
        recommendedAction: "auto_handoff",
        errorMessage: "provider unavailable"
    }]);

    assert.equal(plan.tone, "failed");
    assert.equal(plan.error, "provider unavailable");
});

test("recovery job plan surfaces async handoff target", () => {
    const plan = buildRecoveryJobPlan([{
        id: "recovery_handoff",
        status: "succeeded",
        recommended_action: "handoff",
        recovery_execution_mode: "fresh_session",
        target_worker: "claude",
        provider_failure_class: "provider_runtime_transient"
    }]);

    assert.equal(plan.visible, true);
    assert.equal(plan.tone, "done");
    assert.equal(plan.summary, "已完成 / 移交");
    assert.deepEqual(plan.cards.find((item) => item.label === "动作"), {
        label: "动作",
        value: "移交"
    });
    assert.equal(plan.chips.includes("执行方 claude"), true);
    assert.equal(plan.chips.includes("worker claude"), false);
    assert.equal(plan.chips.includes("失败 临时运行失败"), true);
});

test("recovery job plan surfaces provider failure evidence", () => {
    const plan = buildRecoveryJobPlan([{
        id: "recovery_provider_error",
        status: "accepted",
        recommended_action: "resume",
        provider_failure_class: "provider_runtime_transient",
        metadata: {
            failure_evidence_source: "agent_run.metadata.provider_error",
            failure_evidence: "codex turn completion timed out"
        }
    }]);

    assert.equal(plan.visible, true);
    assert.equal(plan.failureEvidence, "codex turn completion timed out");
    assert.equal(plan.failureEvidenceSource, "agent_run.metadata.provider_error");
    assert.deepEqual(plan.cards.find((item) => item.label === "失败证据"), {
        label: "失败证据",
        value: "codex turn completion timed out"
    });
    assert.equal(plan.chips.includes("证据 codex turn completion timed out"), true);
});

test("recovery job plan marks interrupted jobs as manual attention", () => {
    const plan = buildRecoveryJobPlan([{
        id: "recovery_interrupted",
        status: "interrupted",
        recommended_action: "resume",
        error_message: "harness restarted before async recovery completed",
        completed_at: "2026-05-17T10:30:00Z"
    }], {
        formatTime: () => "05/17 18:30"
    });

    assert.equal(plan.visible, true);
    assert.equal(plan.status, "interrupted");
    assert.equal(plan.tone, "manual");
    assert.equal(plan.error, "harness restarted before async recovery completed");
    assert.equal(plan.summary, "需人工确认 / 继续执行");
    assert.equal(plan.chips.includes("完成 05/17 18:30"), true);
});
