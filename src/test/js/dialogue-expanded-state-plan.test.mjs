import test from "node:test";
import assert from "node:assert/strict";

function pruneExpandedState(expandedMessageIds, expandedThreadOutputTaskIds, relatedMessages, messages, tasks) {
    const visibleMessageIds = new Set(
        [...relatedMessages, ...messages]
            .map((message) => (typeof message?.id === "string" ? message.id : ""))
            .filter(Boolean)
    );
    const nextExpandedMessageIds = new Set(
        [...expandedMessageIds].filter((messageId) => visibleMessageIds.has(messageId))
    );
    const visibleTaskIds = new Set(
        tasks
            .map((task) => (typeof task?.id === "string" ? task.id : ""))
            .filter(Boolean)
    );
    const nextExpandedThreadOutputTaskIds = new Set(
        [...expandedThreadOutputTaskIds].filter((taskId) => visibleTaskIds.has(taskId))
    );
    return {
        expandedMessageIds: nextExpandedMessageIds,
        expandedThreadOutputTaskIds: nextExpandedThreadOutputTaskIds
    };
}

test("thread output expanded state survives message refresh when task remains visible", () => {
    const next = pruneExpandedState(
        new Set(["msg_result_old"]),
        new Set(["task_demo"]),
        [{ id: "msg_result_new", task_id: "task_demo" }],
        [{ id: "msg_user_1" }],
        [{ id: "task_demo" }]
    );

    assert.equal(next.expandedMessageIds.has("msg_result_old"), false);
    assert.equal(next.expandedThreadOutputTaskIds.has("task_demo"), true);
});

test("thread output expanded state is removed when the task itself disappears", () => {
    const next = pruneExpandedState(
        new Set(),
        new Set(["task_demo"]),
        [{ id: "msg_result_new", task_id: "task_demo" }],
        [{ id: "msg_user_1" }],
        []
    );

    assert.equal(next.expandedThreadOutputTaskIds.has("task_demo"), false);
});
