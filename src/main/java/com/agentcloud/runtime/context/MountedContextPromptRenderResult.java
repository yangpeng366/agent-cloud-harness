package com.agentcloud.runtime.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mounted prompt 渲染结果与预算诊断。
 */
public record MountedContextPromptRenderResult(
    String prompt,
    int renderedPanelCount,
    int hiddenPanelCount,
    int renderedObjectCount,
    int hiddenObjectCount,
    int renderedSelectionTraceCount,
    int hiddenSelectionTraceCount
) {
    public static MountedContextPromptRenderResult empty() {
        return new MountedContextPromptRenderResult("", 0, 0, 0, 0, 0, 0);
    }

    public boolean hasPrompt() {
        return prompt != null && !prompt.isBlank();
    }

    public boolean budgetTruncated() {
        return hiddenPanelCount > 0
            || hiddenObjectCount > 0
            || hiddenSelectionTraceCount > 0;
    }

    public Map<String, Object> toMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mounted_context_rendered_panel_count", renderedPanelCount);
        metadata.put("mounted_context_hidden_panel_count", hiddenPanelCount);
        metadata.put("mounted_context_rendered_object_count", renderedObjectCount);
        metadata.put("mounted_context_hidden_object_count", hiddenObjectCount);
        metadata.put("mounted_context_rendered_selection_trace_count", renderedSelectionTraceCount);
        metadata.put("mounted_context_hidden_selection_trace_count", hiddenSelectionTraceCount);
        metadata.put("mounted_context_budget_truncated", budgetTruncated());
        return metadata;
    }
}
