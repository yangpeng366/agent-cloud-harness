import test from "node:test";
import assert from "node:assert/strict";
import {
    buildChatFacadeRequest,
    buildFacadeRequest,
    buildResponsesFacadeRequest
} from "../../main/resources/web/dialogue/composer-request-plan.js";

test("composer request plan defaults plain chat path to task_auto", () => {
    const request = buildChatFacadeRequest({
        intent: "继续推进当前主题",
        sessionId: "session_auto_1",
        facadeModel: "agentcloud-default",
        derivedTitle: "继续推进当前主题"
    });

    assert.equal(request.metadata.task_mode, "task_auto");
    assert.equal(request.metadata.session_id, "session_auto_1");
});

test("composer request plan still preserves explicit message_only request when asked", () => {
    const request = buildChatFacadeRequest({
        intent: "先记一条草稿",
        sessionId: "session_1",
        facadeModel: "agentcloud-default",
        taskMode: "message_only",
        derivedTitle: "先记一条草稿"
    });

    assert.equal(request.stream, true);
    assert.equal(request.model, "agentcloud-default");
    assert.equal(request.messages[0].role, "user");
    assert.equal(request.messages[0].content, "先记一条草稿");
    assert.equal(request.metadata.task_mode, "message_only");
    assert.equal(request.metadata.session_id, "session_1");
    assert.equal(request.metadata.title, "先记一条草稿");
    assert.equal(request.metadata.task_type, "continuation");
    assert.equal(request.metadata.priority, "high");
    assert.equal(request.metadata.auto_start, true);
    assert.equal(request.metadata.task_id, undefined);
    assert.equal(request.metadata.parent_task_id, undefined);
});

test("composer request plan builds task_required request with manual-start controls", () => {
    const request = buildChatFacadeRequest({
        intent: "整理成新任务",
        sessionId: "session_2",
        facadeModel: "agentcloud-strong",
        taskMode: "task_required",
        title: "整理方案",
        derivedTitle: "整理成新任务",
        goal: "补全下一步",
        assignedWorker: "codex",
        modelMode: "strong_only",
        taskType: "coding",
        taskPriority: "medium",
        autoStart: false
    });

    assert.equal(request.model, "agentcloud-strong");
    assert.equal(request.metadata.task_mode, "task_required");
    assert.equal(request.metadata.title, "整理方案");
    assert.equal(request.metadata.goal, "补全下一步");
    assert.equal(request.metadata.assigned_worker, "codex");
    assert.equal(request.metadata.model_mode, "strong_only");
    assert.equal(request.metadata.task_type, "coding");
    assert.equal(request.metadata.priority, "medium");
    assert.equal(request.metadata.auto_start, false);
});

test("composer request plan preserves follow-up parent metadata", () => {
    const request = buildChatFacadeRequest({
        intent: "继续下一轮",
        sessionId: "session_3",
        facadeModel: "agentcloud-default",
        taskMode: "task_required",
        derivedTitle: "继续下一轮",
        followupParentTaskId: "task_parent_1"
    });

    assert.equal(request.metadata.parent_task_id, "task_parent_1");
    assert.equal(request.metadata.followup_parent_task_id, "task_parent_1");
});

test("composer request plan preserves referenced task note attach", () => {
    const request = buildChatFacadeRequest({
        intent: "补一条上下文",
        sessionId: "session_4",
        facadeModel: "agentcloud-default",
        taskMode: "message_only",
        derivedTitle: "补一条上下文",
        referencedTaskId: "task_note_1"
    });

    assert.equal(request.metadata.task_mode, "message_only");
    assert.equal(request.metadata.task_id, "task_note_1");
});

test("composer request plan preserves continue-current task continuity metadata", () => {
    const request = buildChatFacadeRequest({
        intent: "先记一轮 continuity，但不要继续执行",
        sessionId: "session_5",
        facadeModel: "agentcloud-default",
        taskMode: "task_required",
        derivedTitle: "先记一轮 continuity，但不要继续执行",
        continueCurrentTaskId: "task_existing_1",
        autoStart: false
    });

    assert.equal(request.metadata.task_mode, "task_required");
    assert.equal(request.metadata.task_id, "task_existing_1");
    assert.equal(request.metadata.auto_start, false);
    assert.equal(request.metadata.parent_task_id, undefined);
});

test("composer request plan requires intent and session id", () => {
    assert.throws(() => buildChatFacadeRequest({
        sessionId: "session_missing_intent",
        taskMode: "message_only",
        derivedTitle: "x"
    }), /intent is required/);
    assert.throws(() => buildChatFacadeRequest({
        intent: "x",
        taskMode: "message_only",
        derivedTitle: "x"
    }), /sessionId is required/);
});

test("composer request plan builds minimal responses facade request", () => {
    const request = buildResponsesFacadeRequest({
        intent: "继续整理方案",
        sessionId: "session_resp_1",
        facadeModel: "agentcloud-default",
        taskMode: "task_required",
        derivedTitle: "继续整理方案",
        goal: "收一版提纲"
    });

    assert.equal(request.stream, true);
    assert.equal(request.model, "agentcloud-default");
    assert.equal(request.input, "继续整理方案");
    assert.equal(request.previous_response_id, null);
    assert.equal(request.metadata.task_mode, "task_required");
    assert.equal(request.metadata.session_id, "session_resp_1");
    assert.equal(request.metadata.goal, "收一版提纲");
});

test("composer request plan routes buildFacadeRequest by facadeSurface", () => {
    const chat = buildFacadeRequest({
        intent: "记一条消息",
        sessionId: "session_route_chat",
        facadeSurface: "chat_completions",
        derivedTitle: "记一条消息"
    });
    const responses = buildFacadeRequest({
        intent: "生成一个 response",
        sessionId: "session_route_resp",
        facadeSurface: "responses",
        derivedTitle: "生成一个 response"
    });

    assert.equal(Array.isArray(chat.messages), true);
    assert.equal(chat.input, undefined);
    assert.equal(chat.metadata.task_mode, "task_auto");
    assert.equal(responses.messages, undefined);
    assert.equal(responses.input, "生成一个 response");
    assert.equal(responses.metadata.task_mode, "task_auto");
});
