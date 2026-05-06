package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Mounted runtime view 的固定 panel 槽位。
 */
public enum MountedContextPanelName {
    PINNED("pinned", "Pinned"),
    ACTIVE("active", "Active"),
    ANCESTOR("ancestor", "Ancestor"),
    SIBLING("sibling", "Sibling"),
    EVIDENCE("evidence", "Evidence"),
    INDEX("index", "Index"),
    ARCHIVE_HANDLES("archive_handles", "Archive Handles");

    private final String wireName;
    private final String title;

    MountedContextPanelName(String wireName, String title) {
        this.wireName = wireName;
        this.title = title;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public String title() {
        return title;
    }

    @Override
    public String toString() {
        return wireName;
    }
}
