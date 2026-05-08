package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 已挂载上下文对象的逻辑类型。
 */
public enum ContextObjectType {
    TASK("task"),
    CONSTRAINT("constraint"),
    ACTIVE_CONTEXT("active_context"),
    SESSION_MESSAGE("session_message"),
    DECISION("decision"),
    ARTIFACT("artifact"),
    TOOL_INVOCATION("tool_invocation"),
    CHECKPOINT("checkpoint"),
    RESUME_PACKET("resume_packet"),
    EVENT("event"),
    INDEX("index"),
    HANDLE("handle"),
    CAPSULE("capsule");

    private final String wireName;

    ContextObjectType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @Override
    public String toString() {
        return wireName;
    }
}
