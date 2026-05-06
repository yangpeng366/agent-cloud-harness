package com.agentcloud.runtime.model;

/**
 * phase-1 最小 continuation action。
 *
 * 先统一 runtime judgment 与控制流之间的动作命名，
 * 后续再逐步扩展到 prompt judgment / trace / checkpoint。
 */
public enum ContinuationAction {
    CONTINUE("continue"),
    HALT("halt"),
    PAUSE("pause"),
    HANDOFF("handoff"),
    ESCALATE("escalate"),
    RETRY("retry");

    private final String value;

    ContinuationAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static ContinuationAction fromValue(String value) {
        if (value == null || value.isBlank()) {
            return CONTINUE;
        }
        for (ContinuationAction action : values()) {
            if (action.value.equalsIgnoreCase(value.trim())) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown continuation action: " + value);
    }
}
