package com.agentcloud.tool;

/**
 * 最小工具接口。
 */
public interface Tool {
    String name();

    default String description() {
        return "";
    }

    default String argumentContract() {
        return "";
    }

    ToolResult invoke(ToolRequest request) throws Exception;
}
