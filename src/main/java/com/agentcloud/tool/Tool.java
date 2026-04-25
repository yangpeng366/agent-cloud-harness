package com.agentcloud.tool;

/**
 * 最小工具接口。
 */
public interface Tool {
    String name();

    ToolResult invoke(ToolRequest request) throws Exception;
}
