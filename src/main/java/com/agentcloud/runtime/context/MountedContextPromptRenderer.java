package com.agentcloud.runtime.context;

import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 mounted context 视图压缩成 prompt 可消费的文本摘要。
 */
public class MountedContextPromptRenderer {
    private static final int DEFAULT_OBJECT_LIMIT = 3;
    private static final int DEFAULT_PREVIEW_LIMIT = 180;
    private static final int DEFAULT_SELECTION_TRACE_LIMIT = 4;
    private static final int DEFAULT_LABEL_LIMIT = 72;
    private static final int DEFAULT_PROOF_EDGE_LIMIT = 2;
    private static final Map<MountedContextPanelName, Integer> PANEL_OBJECT_LIMITS = Map.of(
        MountedContextPanelName.PINNED, 3,
        MountedContextPanelName.ACTIVE, 5,
        MountedContextPanelName.ANCESTOR, 2,
        MountedContextPanelName.SIBLING, 3,
        MountedContextPanelName.EVIDENCE, 3,
        MountedContextPanelName.INDEX, 2,
        MountedContextPanelName.ARCHIVE_HANDLES, 2
    );

    public String render(TaskRuntimeContext context) {
        return renderResult(context).prompt();
    }

    public MountedContextPromptRenderResult renderResult(TaskRuntimeContext context) {
        if (context == null || context.mountedContextView() == null) {
            return MountedContextPromptRenderResult.empty();
        }
        return renderResult(context.mountedContextView());
    }

    public String render(MountedContextView view) {
        return renderResult(view).prompt();
    }

    public MountedContextPromptRenderResult renderResult(MountedContextView view) {
        if (view == null) {
            return MountedContextPromptRenderResult.empty();
        }

        List<String> panelLines = new ArrayList<>();
        int renderedPanelCount = 0;
        int renderedObjectCount = 0;
        int hiddenObjectCount = 0;
        for (MountedContextPanelName name : MountedContextPanelName.values()) {
            MountedContextPanel panel = view.panel(name);
            List<ContextObject> objects = nonNullObjects(panel.objects());
            if (objects.isEmpty()) {
                continue;
            }
            renderedPanelCount++;
            StringBuilder line = new StringBuilder();
            line.append("- ").append(panel.title())
                .append(" (").append(objects.size()).append(")");
            RenderedObjectSection renderedSection = renderObjects(name, objects);
            renderedObjectCount += renderedSection.renderedObjectCount();
            hiddenObjectCount += renderedSection.hiddenObjectCount();
            line.append(": ").append(renderedSection.text());
            panelLines.add(line.toString());
        }

        List<String> traceItems = new ArrayList<>();
        for (String item : view.selectionTrace()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            traceItems.add(item);
        }

        List<String> traceLines = new ArrayList<>();
        int traceLimit = Math.min(DEFAULT_SELECTION_TRACE_LIMIT, traceItems.size());
        for (int index = 0; index < traceLimit; index++) {
            traceLines.add("- " + truncate(traceItems.get(index), DEFAULT_PREVIEW_LIMIT));
        }
        int hiddenSelectionTraceCount = Math.max(0, traceItems.size() - traceLimit);
        if (hiddenSelectionTraceCount > 0) {
            traceLines.add("- ... +" + hiddenSelectionTraceCount + " more");
        }

        if (panelLines.isEmpty() && traceLines.isEmpty()) {
            return MountedContextPromptRenderResult.empty();
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
        return new MountedContextPromptRenderResult(
            sb.toString(),
            renderedPanelCount,
            0,
            renderedObjectCount,
            hiddenObjectCount,
            traceLimit,
            hiddenSelectionTraceCount
        );
    }

    private RenderedObjectSection renderObjects(MountedContextPanelName panelName, List<ContextObject> objects) {
        List<String> lines = new ArrayList<>();
        int limit = Math.min(objectLimit(panelName), objects.size());
        for (int index = 0; index < limit; index++) {
            ContextObject object = objects.get(index);
            if (object == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(object.type()).append("/");
            line.append(object.retentionState()).append("/");
            line.append(truncate(firstNonBlank(object.title(), object.id(), object.path(), "(untitled)"), DEFAULT_LABEL_LIMIT));
            String detail = firstNonBlank(object.summary(), object.contentPreview());
            String proofEdge = formatProofEdge(object);
            if (!detail.isBlank() && !isHandleOnlyPanel(panelName)) {
                line.append(" -> ").append(truncate(detail, DEFAULT_PREVIEW_LIMIT));
            }
            if (!proofEdge.isBlank() && !isHandleOnlyPanel(panelName)) {
                line.append(" [").append(proofEdge).append("]");
            }
            lines.add(line.toString());
        }
        if (objects.size() > limit) {
            lines.add("... +" + (objects.size() - limit) + " more");
        }
        return new RenderedObjectSection(
            String.join(" | ", lines),
            limit,
            Math.max(0, objects.size() - limit)
        );
    }

    private List<ContextObject> nonNullObjects(List<ContextObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return List.of();
        }
        List<ContextObject> filtered = new ArrayList<>();
        for (ContextObject object : objects) {
            if (object != null) {
                filtered.add(object);
            }
        }
        return filtered;
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

    private int objectLimit(MountedContextPanelName panelName) {
        if (panelName == null) {
            return DEFAULT_OBJECT_LIMIT;
        }
        return PANEL_OBJECT_LIMITS.getOrDefault(panelName, DEFAULT_OBJECT_LIMIT);
    }

    private boolean isHandleOnlyPanel(MountedContextPanelName panelName) {
        return panelName == MountedContextPanelName.INDEX
            || panelName == MountedContextPanelName.ARCHIVE_HANDLES;
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

    private String formatProofEdge(ContextObject object) {
        if (object == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        appendProofEdges(parts, prefixedValues("tool", metadataStringList(object.metadata(), "tool_invocation_ids")));
        appendProofEdges(parts, prefixedValues("evidence", metadataStringList(object.metadata(), "evidence_refs")));
        appendProofEdges(parts, contextReferenceLabels(object.refs()));
        if (parts.isEmpty()) {
            return "";
        }
        return "proof=" + String.join(", ", parts);
    }

    private void appendProofEdges(List<String> target, List<String> values) {
        if (values == null || values.isEmpty() || target.size() >= DEFAULT_PROOF_EDGE_LIMIT) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            target.add(truncate(value, DEFAULT_LABEL_LIMIT));
            if (target.size() >= DEFAULT_PROOF_EDGE_LIMIT) {
                return;
            }
        }
    }

    private List<String> prefixedValues(String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(prefix + ":" + value.trim());
        }
        return result;
    }

    private List<String> contextReferenceLabels(List<ContextReference> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ContextReference ref : refs) {
            if (ref == null) {
                continue;
            }
            String label = firstNonBlank(ref.label(), ref.refType(), tailPath(ref.targetPath()));
            if (!label.isBlank()) {
                result.add("ref:" + label);
            }
        }
        return result;
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String stringValue = item.toString().trim();
            if (!stringValue.isBlank()) {
                result.add(stringValue);
            }
        }
        return result;
    }

    private String tailPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash < normalized.length() - 1) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }

    private record RenderedObjectSection(String text, int renderedObjectCount, int hiddenObjectCount) {}
}
