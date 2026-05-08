package com.agentcloud.runtime.context;

/**
 * mounted prompt budget metadata key 常量，避免多处白名单漂移。
 */
public final class MountedContextPromptBudgetSupport {
    public static final String RENDERED_PANEL_COUNT = "mounted_context_rendered_panel_count";
    public static final String HIDDEN_PANEL_COUNT = "mounted_context_hidden_panel_count";
    public static final String RENDERED_OBJECT_COUNT = "mounted_context_rendered_object_count";
    public static final String HIDDEN_OBJECT_COUNT = "mounted_context_hidden_object_count";
    public static final String RENDERED_SELECTION_TRACE_COUNT = "mounted_context_rendered_selection_trace_count";
    public static final String HIDDEN_SELECTION_TRACE_COUNT = "mounted_context_hidden_selection_trace_count";
    public static final String BUDGET_TRUNCATED = "mounted_context_budget_truncated";

    private MountedContextPromptBudgetSupport() {}
}
