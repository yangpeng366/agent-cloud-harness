import test from "node:test";
import assert from "node:assert/strict";
import { buildTaskFocusLineBase } from "../../main/resources/web/dialogue/task-focus-line-plan.js";

test("task focus line surfaces partial timeout in header status", () => {
    const focusLine = buildTaskFocusLineBase({
        status: "waiting_human",
        control_node: "human_gate",
        metadata: {
            execution_status: "partial_timeout"
        }
    });

    assert.equal(focusLine, "waiting_human / human_gate / 部分结果待确认");
    assert.equal(focusLine.includes("partial timeout"), false);
});

test("task focus line surfaces human gate recovery", () => {
    const focusLine = buildTaskFocusLineBase({
        status: "waiting_human",
        control_node: "human_gate",
        metadata: {
            failure_class: "partial_result_or_quality_risk",
            recovery_stage: "human_gate_required"
        }
    });

    assert.equal(focusLine, "waiting_human / human_gate / human gate · 部分结果待确认");
});

test("task focus line humanizes deterministic backend failures", () => {
    const focusLine = buildTaskFocusLineBase({
        status: "waiting_human",
        control_node: "human_gate",
        metadata: {
            failure_class: "worker_backend_deterministic",
            recovery_stage: "human_gate_required"
        }
    });

    assert.equal(focusLine, "waiting_human / human_gate / human gate · 能力不匹配");
    assert.equal(focusLine.includes("worker_backend_deterministic"), false);
});

test("task focus line keeps handoff queued state", () => {
    const focusLine = buildTaskFocusLineBase({
        status: "active",
        control_node: "scheduler",
        metadata: {
            recovery_stage: "auto_handoff_scheduled",
            auto_handoff_target: "deepseek"
        }
    });

    assert.equal(focusLine, "active / scheduler / handoff queued");
});
