package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 单轮运行时挂载的 panel-based 上下文视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MountedContextView(
    String viewId,
    String taskId,
    List<MountedContextPanel> panels,
    List<String> selectionTrace
) {
    public MountedContextView {
        if (taskId == null) taskId = "";
        if (viewId == null || viewId.isBlank()) {
            viewId = taskId.isBlank() ? "mounted-context-view" : "mounted-context-view:" + taskId;
        }
        if (panels == null || panels.isEmpty()) {
            panels = defaultPanels();
        } else {
            panels = normalizePanels(panels);
        }
        if (selectionTrace == null) selectionTrace = List.of();
    }

    public static MountedContextView empty(String taskId) {
        return new MountedContextView(null, taskId, defaultPanels(), List.of());
    }

    public MountedContextPanel panel(MountedContextPanelName name) {
        if (name == null) {
            return new MountedContextPanel(MountedContextPanelName.ACTIVE, MountedContextPanelName.ACTIVE.title(), List.of());
        }
        return panels.stream()
            .filter(panel -> panel != null && name == panel.name())
            .findFirst()
            .orElse(new MountedContextPanel(name, name.title(), List.of()));
    }

    public List<ContextObject> objects(MountedContextPanelName name) {
        return panel(name).objects();
    }

    private static List<MountedContextPanel> defaultPanels() {
        return Arrays.stream(MountedContextPanelName.values())
            .map(name -> new MountedContextPanel(name, name.title(), List.of()))
            .toList();
    }

    private static List<MountedContextPanel> normalizePanels(List<MountedContextPanel> panels) {
        LinkedHashMap<MountedContextPanelName, MountedContextPanel> ordered = new LinkedHashMap<>();
        for (MountedContextPanelName name : MountedContextPanelName.values()) {
            ordered.put(name, new MountedContextPanel(name, name.title(), List.of()));
        }
        for (MountedContextPanel panel : panels) {
            if (panel == null || panel.name() == null) {
                continue;
            }
            ordered.put(panel.name(), new MountedContextPanel(panel.name(), panel.title(), panel.objects()));
        }
        return List.copyOf(ordered.values());
    }
}
