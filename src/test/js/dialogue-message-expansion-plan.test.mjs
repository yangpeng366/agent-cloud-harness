import test from "node:test";
import assert from "node:assert/strict";
import {
    buildMessageExpansionPlan,
    hasExpandedTaskOutcomeContent,
    hasExpandedWorkerRoundContent
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

test("provider diagnostics alone enable expandable task outcome content", () => {
    const message = {
        message_type: "task_progress",
        content: "failed",
        metadata: {
            provider_error: "codex turn completion timed out",
            provider_turn_status: "timeout",
            provider_failure_class: "provider_runtime_transient",
            provider_failure_reason: "turn timed out",
            provider_retryable: true
        }
    };
    const plan = buildMessageExpansionPlan(message, "failed");

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent.includes("Provider 诊断"), true);
    assert.equal(plan.fullContent.includes("错误: codex turn completion timed out"), true);
    assert.equal(plan.fullContent.includes("回合状态: timeout"), true);
    assert.equal(plan.fullContent.includes("失败类型: provider_runtime_transient"), true);
    assert.equal(plan.fullContent.includes("失败原因: turn timed out"), true);
    assert.equal(plan.fullContent.includes("可重试: true"), true);
    assert.equal(plan.fullContent.includes("Provider Diagnostics"), false);
    assert.equal(hasExpandedTaskOutcomeContent(message), true);
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

test("worker round expands to compact provider diagnostics and run file hints", () => {
    const message = {
        message_type: "worker_round",
        content: "Codex 执行了一轮，状态 partial_timeout，已产出部分结果。",
        metadata: {
            execution_status: "partial_timeout",
            output_preview: "已修改 app.js，但测试还没跑完。",
            output_chars: 640,
            partial_output_chars: 640,
            partial_timeout_min_output_chars: 200,
            provider_turn_status: "partial_timeout",
            provider_failure_reason: "max duration reached",
            provider_retryable: true,
            provider_last_message_path: "D:\\runs\\codex\\last_message.md",
            provider_stdout_path: "D:\\runs\\codex\\stdout.log",
            provider_protocol_trace: ["raw trace should stay out"]
        }
    };
    const plan = buildMessageExpansionPlan(message, "已修改 app.js，但测试还没跑完。");

    assert.equal(plan.needsExpand, true);
    assert.equal(plan.fullContent.includes("已修改 app.js"), true);
    assert.equal(plan.fullContent.includes("Provider 诊断"), true);
    assert.equal(plan.fullContent.includes("失败原因: max duration reached"), true);
    assert.equal(plan.fullContent.includes("输出指标"), true);
    assert.equal(plan.fullContent.includes("部分输出字符数: 640"), true);
    assert.equal(plan.fullContent.includes("部分超时阈值: 200"), true);
    assert.equal(plan.fullContent.includes("Provider 运行文件"), true);
    assert.equal(plan.fullContent.includes("最后输出: D:\\runs\\codex\\last_message.md"), true);
    assert.equal(plan.fullContent.includes("标准输出: D:\\runs\\codex\\stdout.log"), true);
    assert.equal(plan.fullContent.includes("Provider Diagnostics"), false);
    assert.equal(plan.fullContent.includes("last message:"), false);
    assert.equal(plan.fullContent.includes("raw trace should stay out"), false);
    assert.equal(hasExpandedWorkerRoundContent(message), true);
});

test("worker round without compact diagnostics stays collapsed", () => {
    const message = {
        message_type: "worker_round",
        content: "Codex 执行完成。",
        metadata: {}
    };
    const plan = buildMessageExpansionPlan(message, "Codex 执行完成。");

    assert.equal(plan.needsExpand, false);
    assert.equal(hasExpandedWorkerRoundContent(message), false);
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
