package com.agentcloud.tool;

import java.util.Map;

/**
 * 工具调用请求。
 */
public record ToolRequest(
    String sessionId,
    String taskId,
    String workerId,
    String toolName,
    Map<String, Object> arguments,
    Map<String, Object> taskMetadata
) {
    public ToolRequest(String sessionId,
                       String taskId,
                       String workerId,
                       String toolName,
                       Map<String, Object> arguments) {
        this(sessionId, taskId, workerId, toolName, arguments, Map.of());
    }

    public ToolRequest {
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (workerId == null) workerId = "";
        if (toolName == null) toolName = "";
        if (arguments == null) arguments = Map.of();
        if (taskMetadata == null) taskMetadata = Map.of();
    }
}
