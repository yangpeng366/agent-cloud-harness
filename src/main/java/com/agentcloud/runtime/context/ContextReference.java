package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mounted context 中的可追踪引用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextReference(
    String refType,
    String targetPath,
    String label
) {
}
