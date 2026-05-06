package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个 mounted context panel。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MountedContextPanel(
    MountedContextPanelName name,
    String title,
    List<ContextObject> objects
) {
    public MountedContextPanel {
        if (name == null) name = MountedContextPanelName.ACTIVE;
        if (title == null || title.isBlank()) title = name.title();
        if (objects == null) objects = List.of();
    }
}
