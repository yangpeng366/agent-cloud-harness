package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Mounted context 对象的最小保留状态集合。
 */
public enum ContextRetentionState {
    PINNED("pinned"),
    HOT_RAW("hot_raw"),
    WARM_SUMMARY("warm_summary"),
    COLD_CAPSULE("cold_capsule"),
    ARCHIVED_HANDLE("archived_handle");

    private final String wireName;

    ContextRetentionState(String wireName) {
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
