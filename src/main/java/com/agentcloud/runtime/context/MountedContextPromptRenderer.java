package com.agentcloud.runtime.context;

import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 mounted context 视图压缩成 prompt 可消费的文本摘要。
 */
public class MountedContextPromptRenderer {
    private static final int DEFAULT_OBJECT_LIMIT = 3;
    private static final int DEFAULT_PREVIEW_LIMIT = 180;

    public String render(TaskRuntimeContext context) {
        if (context == null || context.mountedContextView() == null) {
            return "";
        }
        return render(context.mountedContextView());
    }

    public String render(MountedContextView view) {
        if (view == null) {
            return "";
        }

        List<String> panelLines = new ArrayList<>();
        for (MountedContextPanelName name : MountedContextPanelName.values()) {
            MountedContextPanel panel = view.panel(name);
            List<ContextObject> objects = panel.objects();
            if (objects == null || objects.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("- ").append(panel.title())
                .append(" (").append(objects.size()).append(")");
            line.append(": ").append(renderObjects(objects));
            panelLines.add(line.toString());
        }

        List<String> traceLines = new ArrayList<>();
        for (String item : view.selectionTrace()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            traceLines.add("- " + truncate(item, DEFAULT_PREVIEW_LIMIT));
        }

        if (panelLines.isEmpty() && traceLines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Mounted Context:\n");
        for (String line : panelLines) {
            sb.append(line).append("\n");
        }
        if (!traceLines.isEmpty()) {
            sb.append("Mounted Context Selection Trace:\n");
            for (String line : traceLines) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String renderObjects(List<ContextObject> objects) {
        List<String> lines = new ArrayList<>();
        int limit = Math.min(DEFAULT_OBJECT_LIMIT, objects.size());
        for (int index = 0; index < limit; index++) {
            ContextObject object = objects.get(index);
            if (object == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(object.type()).append("/");
            line.append(object.retentionState()).append("/");
            line.append(firstNonBlank(object.title(), object.id(), object.path(), "(untitled)"));
            String detail = firstNonBlank(object.summary(), object.contentPreview());
            if (!detail.isBlank()) {
                line.append(" -> ").append(truncate(detail, DEFAULT_PREVIEW_LIMIT));
            }
            lines.add(line.toString());
        }
        if (objects.size() > limit) {
            lines.add("... +" + (objects.size() - limit) + " more");
        }
        return String.join(" | ", lines);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
