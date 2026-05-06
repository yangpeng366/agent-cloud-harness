package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 面向 mounted view 的地址化上下文对象。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextObject(
    String id,
    String path,
    ContextObjectType type,
    String parentPath,
    String title,
    String summary,
    String contentPreview,
    Instant createdAt,
    ContextRetentionState retentionState,
    List<ContextReference> refs,
    List<ContextReference> sourceRefs,
    Map<String, Object> metadata
) {
    public ContextObject {
        if (id == null) id = "";
        if (path == null) path = "";
        if (parentPath == null) parentPath = "";
        if (title == null) title = "";
        if (summary == null) summary = "";
        if (contentPreview == null) contentPreview = "";
        if (type == null) type = ContextObjectType.HANDLE;
        if (retentionState == null) retentionState = ContextRetentionState.WARM_SUMMARY;
        if (refs == null) refs = List.of();
        if (sourceRefs == null) sourceRefs = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
