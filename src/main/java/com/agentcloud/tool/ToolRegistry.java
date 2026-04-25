package com.agentcloud.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存工具注册表。
 */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tool != null && tool.name() != null && !tool.name().isBlank()) {
            tools.put(tool.name(), tool);
        }
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public List<String> listToolNames() {
        return List.copyOf(tools.keySet());
    }
}
