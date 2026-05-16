import test from "node:test";
import assert from "node:assert/strict";
import {
    buildMessageExpansionPlan,
    hasExpandedTaskOutcomeContent
} from "../../main/resources/web/dialogue/message-expansion-plan.js";

test("task progress expands to include worker output and next step", () => {
    const plan = buildMessageExpansionPlan(
        {
            message_type: "task_progress",
            content: "任务《demo》已完成一轮推进。进展：摘要。当前：active / continue。",
            metadata: {
                summary_preview: "摘要",
                output_text: "line 1\nline 2",
                next_step: "继续写测试"
            }
        },
        "摘要"
    );

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent.includes("Worker Output"), true);
    assert.equal(plan.fullContent.includes("line 1"), true);
    assert.equal(plan.fullContent.includes("下一步"), true);
});

test("failure summary alone still enables expandable task outcome content", () => {
    const plan = buildMessageExpansionPlan(
        {
            message_type: "task_progress",
            content: "failed",
            metadata: {
                failure_summary_readable: "worker claude failed: thread not found (19120)"
            }
        },
        "worker claude failed: thread not found (19120)"
    );

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent.includes("Failure Summary"), true);
    assert.equal(plan.fullContent.includes("thread not found (19120)"), true);
    assert.equal(hasExpandedTaskOutcomeContent({
        message_type: "task_progress",
        metadata: {
            failure_summary_readable: "worker claude failed: thread not found (19120)"
        }
    }), true);
});

test("stale shell full content falls back to readable failure summary for expansion", () => {
    const plan = buildMessageExpansionPlan(
        {
            message_type: "task_progress",
            content: "failed",
            metadata: {
                full_content: "failed\n\nWorker Output\n\n\nArtifact Content\n\n\n下一步\nInspect failure trace.",
                failure_summary_readable: "worker claude failed: thread not found (19120)",
                next_step: "Inspect failure trace."
            }
        },
        "worker claude failed: thread not found (19120)"
    );

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent.startsWith("Failure Summary"), true);
    assert.equal(plan.fullContent.includes("thread not found (19120)"), true);
    assert.equal(plan.fullContent.includes("Worker Output"), false);
    assert.equal(plan.fullContent.includes("Artifact Content"), false);
    assert.equal(plan.fullContent.includes("下一步"), true);
});

test("task result does not duplicate explicit full content", () => {
    const plan = buildMessageExpansionPlan(
        {
            message_type: "task_result",
            content: "short summary",
            metadata: {
                full_content: "完整结果正文\n第二行"
            }
        },
        "short summary"
    );

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent, "完整结果正文\n第二行");
});

test("non expandable user message stays collapsed", () => {
    const plan = buildMessageExpansionPlan(
        {
            role: "user",
            message_type: "user_note",
            content: "hello world",
            metadata: {}
        },
        "hello world"
    );

    assert.equal(plan.needsExpand, false);
    assert.equal(plan.fullContent, "hello world");
});

test("task outcome helper detects full worker result payload", () => {
    assert.equal(hasExpandedTaskOutcomeContent({
        message_type: "task_progress",
        metadata: {
            output_text: "full worker output"
        }
    }), true);
    assert.equal(hasExpandedTaskOutcomeContent({
        message_type: "task_progress",
        metadata: {
            next_step: "only next step"
        }
    }), false);
});
