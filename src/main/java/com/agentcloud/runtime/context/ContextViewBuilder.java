package com.agentcloud.runtime.context;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于现有 TaskRuntimeContext 构建 panel-based mounted view。
 */
public class ContextViewBuilder {
    private static final int ACTIVE_MESSAGE_LIMIT = 4;
    private static final int ACTIVE_DECISION_LIMIT = 3;
    private static final int EVIDENCE_ARTIFACT_LIMIT = 4;
    private static final int EVIDENCE_EVENT_LIMIT = 3;

    private final ContextObjectAdapter adapter;

    public ContextViewBuilder() {
        this(new ContextObjectAdapter());
    }

    public ContextViewBuilder(ContextObjectAdapter adapter) {
        this.adapter = adapter == null ? new ContextObjectAdapter() : adapter;
    }

    public MountedContextView build(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return MountedContextView.empty(null);
        }
        Task task = context.task();

        List<MountedContextPanel> panels = List.of(
            panel(MountedContextPanelName.PINNED, pinned(context)),
            panel(MountedContextPanelName.ACTIVE, active(context)),
            panel(MountedContextPanelName.ANCESTOR, ancestor(context)),
            panel(MountedContextPanelName.SIBLING, sibling(context)),
            panel(MountedContextPanelName.EVIDENCE, evidence(context)),
            panel(MountedContextPanelName.INDEX, index(context)),
            panel(MountedContextPanelName.ARCHIVE_HANDLES, archiveHandles(context))
        );

        List<String> selectionTrace = List.of(
            "compat_mode=task_runtime_context_preserved",
            "panels=7",
            "pinned=" + panelCount(panels, MountedContextPanelName.PINNED),
            "active=" + panelCount(panels, MountedContextPanelName.ACTIVE),
            "evidence=" + panelCount(panels, MountedContextPanelName.EVIDENCE),
            "index=" + panelCount(panels, MountedContextPanelName.INDEX),
            "archive_handles=" + panelCount(panels, MountedContextPanelName.ARCHIVE_HANDLES),
            "retention_states=pinned,hot_raw,warm_summary,archived_handle",
            "message_window=" + Math.min(context.recentMessages().size(), ACTIVE_MESSAGE_LIMIT) + "/" + context.recentMessages().size(),
            "decision_window=" + Math.min(context.recentDecisions().size(), ACTIVE_DECISION_LIMIT) + "/" + context.recentDecisions().size()
        );
        return new MountedContextView(null, task.id(), panels, selectionTrace);
    }

    private MountedContextPanel panel(MountedContextPanelName name, List<ContextObject> objects) {
        return new MountedContextPanel(name, name.title(), objects);
    }

    private List<ContextObject> pinned(TaskRuntimeContext context) {
        List<ContextObject> objects = new ArrayList<>();
        addIfPresent(objects, adapter.taskGoal(context.task()));
        addIfPresent(objects, adapter.constraints(context.task(), context.activeContext()));
        return List.copyOf(objects);
    }

    private List<ContextObject> active(TaskRuntimeContext context) {
        List<ContextObject> objects = new ArrayList<>();
        addIfPresent(objects, adapter.activeContext(context.task(), context.activeContext(), context.latestPacket(), context.latestCheckpoint()));
        addIfPresent(objects, adapter.resumePacket(context.task(), context.latestPacket()));
        addIfPresent(objects, adapter.checkpoint(context.task(), context.latestCheckpoint()));
        for (Decision decision : limit(context.recentDecisions(), ACTIVE_DECISION_LIMIT)) {
            addIfPresent(objects, adapter.decision(context.task(), decision));
        }
        for (SessionMessage message : tail(context.recentMessages(), ACTIVE_MESSAGE_LIMIT)) {
            addIfPresent(objects, adapter.sessionMessage(context.task(), message));
        }
        return List.copyOf(objects);
    }

    private List<ContextObject> ancestor(TaskRuntimeContext context) {
        List<ContextObject> objects = new ArrayList<>();
        addIfPresent(objects, adapter.parentTaskHandle(context.task()));
        return List.copyOf(objects);
    }

    private List<ContextObject> sibling(TaskRuntimeContext context) {
        Object siblingIds = context.task() != null && context.task().metadata() != null
            ? context.task().metadata().get("sibling_task_ids")
            : null;
        if (!(siblingIds instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<ContextObject> objects = new ArrayList<>();
        for (Object value : values) {
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            addIfPresent(objects, adapter.handle(
                context.task(),
                "sibling-" + value,
                "Sibling Task",
                "Sibling task handle: " + value,
                "/sessions/" + context.task().sessionId() + "/tasks/" + value
            ));
        }
        return List.copyOf(objects);
    }

    private List<ContextObject> evidence(TaskRuntimeContext context) {
        List<ContextObject> objects = new ArrayList<>();
        for (Artifact artifact : limit(context.recentArtifacts(), EVIDENCE_ARTIFACT_LIMIT)) {
            addIfPresent(objects, adapter.artifact(context.task(), artifact));
        }
        for (Event event : limit(context.recentEvents(), EVIDENCE_EVENT_LIMIT)) {
            addIfPresent(objects, adapter.event(context.task(), event));
        }
        return List.copyOf(objects);
    }

    private List<ContextObject> index(TaskRuntimeContext context) {
        List<ContextObject> objects = new ArrayList<>();
        addIfPresent(objects, adapter.index(context));
        return List.copyOf(objects);
    }

    private List<ContextObject> archiveHandles(TaskRuntimeContext context) {
        Task task = context.task();
        if (task == null) {
            return List.of();
        }
        String taskPath = "/sessions/" + task.sessionId() + "/tasks/" + task.id();
        List<ContextObject> objects = new ArrayList<>();
        if (!context.recentMessages().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "message-history", "Message History", "Reload recent message windows on demand", taskPath + "/messages"));
        }
        if (!context.recentArtifacts().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "artifact-history", "Artifact History", "Reload artifact summaries or raw outputs on demand", taskPath + "/artifacts"));
        }
        if (!context.recentDecisions().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "decision-history", "Decision History", "Reload decision trace on demand", taskPath + "/decisions"));
        }
        if (context.latestCheckpoint() != null) {
            addIfPresent(objects, adapter.handle(task, "latest-checkpoint", "Latest Checkpoint Handle", "Latest checkpoint can be re-opened for deeper detail", taskPath + "/checkpoints/" + context.latestCheckpoint().id()));
        }
        if (context.latestPacket() != null) {
            addIfPresent(objects, adapter.handle(task, "latest-packet", "Latest Packet Handle", "Latest resume packet can be re-opened for deeper detail", taskPath + "/packets/" + context.latestPacket().id()));
        }
        return List.copyOf(objects);
    }

    private <T> List<T> limit(List<T> values, int max) {
        if (values == null || values.isEmpty() || max <= 0) {
            return List.of();
        }
        return values.stream().limit(max).toList();
    }

    private <T> List<T> tail(List<T> values, int max) {
        if (values == null || values.isEmpty() || max <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, values.size() - max);
        return List.copyOf(values.subList(fromIndex, values.size()));
    }

    private void addIfPresent(List<ContextObject> target, ContextObject value) {
        if (target != null && value != null) {
            target.add(value);
        }
    }

    private int panelCount(List<MountedContextPanel> panels, MountedContextPanelName target) {
        return panels.stream()
            .filter(panel -> panel != null && panel.name() == target)
            .findFirst()
            .map(panel -> panel.objects() == null ? 0 : panel.objects().size())
            .orElse(0);
    }
}
