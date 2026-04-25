package com.agentcloud.tool;

import java.util.Map;

/**
 * 工具调用结果。
 */
public record ToolResult(
    boolean success,
    String summary,
    String output,
    Map<String, Object> metadata
) {
    public ToolResult {
        if (summary == null) summary = "";
        if (output == null) output = "";
        if (metadata == null) metadata = Map.of();
    }
}
