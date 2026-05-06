package com.agentcloud.runtime.context;

import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一收口 mounted context prompt seam 的轻量诊断信息。
 */
public record MountedContextPromptMetrics(
    String promptMode,
    boolean mountedRendered,
    boolean mountedRenderUsed,
    boolean mountedInjected,
    int panelCount,
    int nonEmptyPanelCount,
    int selectionTraceCount,
    int pinnedCount,
    int activeCount,
    int ancestorCount,
    int siblingCount,
    int evidenceCount,
    int indexCount,
    int archiveCount
) {
    public static MountedContextPromptMetrics from(TaskRuntimeContext context,
                                                   PromptRenderingMode renderingMode,
                                                   String mountedPrompt) {
        MountedContextView view = context == null ? null : context.mountedContextView();
        PromptRenderingMode safeMode = renderingMode == null
            ? PromptRenderingMode.ACTIVE_CONTEXT_ONLY
            : renderingMode;
        boolean mountedRendered = safeMode.shouldRenderMountedPrompt();
        boolean mountedRenderUsed = mountedRendered && mountedPrompt != null && !mountedPrompt.isBlank();
        return new MountedContextPromptMetrics(
            safeMode.wireName(),
            mountedRendered,
            mountedRenderUsed,
            safeMode.shouldInjectMountedPrompt() && mountedRenderUsed,
            view == null ? 0 : view.panels().size(),
            nonEmptyPanelCount(view),
            selectionTraceCount(view),
            objectCount(view, MountedContextPanelName.PINNED),
            objectCount(view, MountedContextPanelName.ACTIVE),
            objectCount(view, MountedContextPanelName.ANCESTOR),
            objectCount(view, MountedContextPanelName.SIBLING),
            objectCount(view, MountedContextPanelName.EVIDENCE),
            objectCount(view, MountedContextPanelName.INDEX),
            objectCount(view, MountedContextPanelName.ARCHIVE_HANDLES)
        );
    }

    public Map<String, Object> toMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("prompt_rendering_mode", promptMode);
        metadata.put("prompt_mode", promptMode);
        metadata.put("mounted_context_rendered", mountedRendered);
        metadata.put("mounted_render_used", mountedRenderUsed);
        metadata.put("mounted_context_injected", mountedInjected);
        metadata.put("mounted_context_panel_count", panelCount);
        metadata.put("mounted_panel_count", panelCount);
        metadata.put("mounted_context_non_empty_panel_count", nonEmptyPanelCount);
        metadata.put("mounted_non_empty_panel_count", nonEmptyPanelCount);
        metadata.put("mounted_context_selection_trace_count", selectionTraceCount);
        metadata.put("mounted_pinned_count", pinnedCount);
        metadata.put("mounted_active_count", activeCount);
        metadata.put("mounted_ancestor_count", ancestorCount);
        metadata.put("mounted_sibling_count", siblingCount);
        metadata.put("mounted_evidence_count", evidenceCount);
        metadata.put("mounted_index_count", indexCount);
        metadata.put("mounted_archive_count", archiveCount);
        return metadata;
    }

    private static int nonEmptyPanelCount(MountedContextView view) {
        if (view == null || view.panels() == null || view.panels().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (MountedContextPanel panel : view.panels()) {
            if (panel != null && panel.objects() != null && !panel.objects().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int selectionTraceCount(MountedContextView view) {
        if (view == null || view.selectionTrace() == null || view.selectionTrace().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String item : view.selectionTrace()) {
            if (item != null && !item.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int objectCount(MountedContextView view, MountedContextPanelName panelName) {
        if (view == null || panelName == null) {
            return 0;
        }
        MountedContextPanel panel = view.panel(panelName);
        return panel == null || panel.objects() == null ? 0 : panel.objects().size();
    }
}
