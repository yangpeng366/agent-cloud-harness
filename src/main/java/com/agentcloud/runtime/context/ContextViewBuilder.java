package com.agentcloud.runtime.context;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于现有 TaskRuntimeContext 构建 panel-based mounted view。
 */
public class ContextViewBuilder {
    private static final int ACTIVE_MESSAGE_LIMIT = 4;
    private static final int ACTIVE_DECISION_LIMIT = 3;
    private static final int EVIDENCE_ARTIFACT_LIMIT = 4;
    private static final int EVIDENCE_DECISION_LIMIT = 3;
    private static final int EVIDENCE_TOOL_INVOCATION_LIMIT = 3;
    private static final int EVIDENCE_EVENT_LIMIT = 3;
    private static final int REOPEN_REHYDRATE_LIMIT = 4;

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
        List<Decision> durableEvidenceDecisions = durableEvidenceDecisions(context.recentDecisions());

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
            "retention_states=pinned,hot_raw,warm_summary,cold_capsule,archived_handle",
            "message_window=" + Math.min(context.recentMessages().size(), ACTIVE_MESSAGE_LIMIT) + "/" + context.recentMessages().size(),
            "decision_window=" + Math.min(context.recentDecisions().size(), ACTIVE_DECISION_LIMIT) + "/" + context.recentDecisions().size(),
            "evidence_decision_window=" + Math.min(durableEvidenceDecisions.size(), EVIDENCE_DECISION_LIMIT) + "/" + durableEvidenceDecisions.size(),
            "tool_window=" + Math.min(context.recentToolInvocations().size(), EVIDENCE_TOOL_INVOCATION_LIMIT) + "/" + context.recentToolInvocations().size()
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
        for (ToolInvocationRecord invocation : limit(context.recentToolInvocations(), EVIDENCE_TOOL_INVOCATION_LIMIT)) {
            addIfPresent(objects, adapter.toolInvocation(context.task(), invocation));
        }
        for (Decision decision : limit(durableEvidenceDecisions(context.recentDecisions()), EVIDENCE_DECISION_LIMIT)) {
            addIfPresent(objects, adapter.decision(context.task(), decision));
        }
        for (Event event : limit(context.recentEvents(), EVIDENCE_EVENT_LIMIT)) {
            addIfPresent(objects, adapter.event(context.task(), event));
        }
        addRehydratedEvidence(objects, context);
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
        addIfPresent(objects, reopenCapsule(context, task, taskPath));
        addIfPresent(objects, retrievalPolicyCapsule(context, task, taskPath));
        if (!context.recentMessages().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "message-history", "Message History", "Reload recent message windows on demand", taskPath + "/messages"));
        }
        if (!context.recentArtifacts().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "artifact-history", "Artifact History", "Reload artifact summaries or raw outputs on demand", taskPath + "/artifacts"));
        }
        if (!context.recentToolInvocations().isEmpty()) {
            addIfPresent(objects, adapter.handle(task, "tool-invocation-history", "Tool Invocation History", "Reload tool invocation evidence on demand", taskPath + "/tool_invocations"));
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

    private ContextObject reopenCapsule(TaskRuntimeContext context, Task task, String taskPath) {
        ReopenSignal signal = latestReopenSignal(context);
        if (signal == null || !signal.needsContextReopen() || signal.reopenCandidatePaths().isEmpty()) {
            return null;
        }
        return adapter.reopenCapsule(
            task,
            "reopen-capsule",
            taskPath + "/archive/reopen_capsule",
            firstNonBlank(
                signal.reopenSummary(),
                "Targeted archive reopen is recommended before the next round."
            ),
            signal.keyDecisions(),
            signal.reusableFindings(),
            signal.unresolvedRisks(),
            signal.reopenCandidatePaths(),
            signal.nextFollowups()
        );
    }

    private ContextObject retrievalPolicyCapsule(TaskRuntimeContext context, Task task, String taskPath) {
        RetrievalSignal signal = latestRetrievalSignal(context);
        if (signal == null || (!signal.needsArchiveRetrieval() && !signal.needsExternalFactRefresh())) {
            return null;
        }
        return adapter.retrievalPolicyCapsule(
            task,
            "retrieval-policy-capsule",
            taskPath + "/archive/retrieval_policy_capsule",
            firstNonBlank(
                signal.retrievalSummary(),
                signal.needsArchiveRetrieval() && signal.needsExternalFactRefresh()
                    ? "Archive retrieval and external fact refresh are recommended before the next round."
                    : signal.needsArchiveRetrieval()
                        ? "Archive retrieval is recommended before the next round."
                        : "External fact refresh is recommended before the next round."
            ),
            signal.keyDecisions(),
            signal.reusableFindings(),
            signal.unresolvedRisks(),
            signal.reopenCandidatePaths(),
            signal.nextFollowups(),
            signal.needsArchiveRetrieval(),
            signal.needsExternalFactRefresh()
        );
    }

    private ReopenSignal latestReopenSignal(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return null;
        }
        Event latestControlAction = latestControlActionWithReopen(context.recentEvents());
        if (latestControlAction != null) {
            return reopenSignalFromControlAction(latestControlAction);
        }
        if (context.latestPacket() != null) {
            ReopenSignal packetSignal = reopenSignalFromPacket(context.latestPacket());
            if (packetSignal != null) {
                return packetSignal;
            }
        }
        if (context.latestCheckpoint() != null) {
            ReopenSignal checkpointSignal = reopenSignalFromCheckpoint(context.latestCheckpoint());
            if (checkpointSignal != null) {
                return checkpointSignal;
            }
        }
        for (Decision decision : context.recentDecisions()) {
            ReopenSignal decisionSignal = reopenSignalFromDecision(decision);
            if (decisionSignal != null) {
                return decisionSignal;
            }
        }
        return null;
    }

    private RetrievalSignal latestRetrievalSignal(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return null;
        }
        Event latestControlAction = latestControlActionWithRetrieval(context.recentEvents());
        if (latestControlAction != null) {
            return retrievalSignalFromControlAction(latestControlAction);
        }
        if (context.latestPacket() != null) {
            RetrievalSignal packetSignal = retrievalSignalFromPacket(context.latestPacket());
            if (packetSignal != null) {
                return packetSignal;
            }
        }
        if (context.latestCheckpoint() != null) {
            RetrievalSignal checkpointSignal = retrievalSignalFromCheckpoint(context.latestCheckpoint());
            if (checkpointSignal != null) {
                return checkpointSignal;
            }
        }
        for (Decision decision : context.recentDecisions()) {
            RetrievalSignal decisionSignal = retrievalSignalFromDecision(decision);
            if (decisionSignal != null) {
                return decisionSignal;
            }
        }
        return null;
    }

    private Event latestControlActionWithReopen(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.stream()
            .filter(event -> event != null && "task_control_action".equals(event.eventType()))
            .filter(event -> reopenSignalFromControlAction(event) != null)
            .findFirst()
            .orElse(null);
    }

    private Event latestControlActionWithRetrieval(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.stream()
            .filter(event -> event != null && "task_control_action".equals(event.eventType()))
            .filter(event -> retrievalSignalFromControlAction(event) != null)
            .findFirst()
            .orElse(null);
    }

    private ReopenSignal reopenSignalFromDecision(Decision decision) {
        if (decision == null) {
            return null;
        }
        ContextObject object = adapter.decision(contextTask(decision), decision);
        return reopenSignalFromObject(object, summaryList(decision.summary(), decision.rationale()));
    }

    private RetrievalSignal retrievalSignalFromDecision(Decision decision) {
        if (decision == null) {
            return null;
        }
        ContextObject object = adapter.decision(contextTask(decision), decision);
        return retrievalSignalFromObject(object, summaryList(decision.summary(), decision.rationale()));
    }

    private ReopenSignal reopenSignalFromPacket(com.agentcloud.model.ResumePacket packet) {
        if (packet == null) {
            return null;
        }
        ContextObject object = adapter.resumePacket(contextTask(packet), packet);
        List<String> nextFollowups = new ArrayList<>();
        if (packet.nextStep() != null && !packet.nextStep().isBlank()) {
            nextFollowups.add(packet.nextStep());
        }
        nextFollowups.addAll(packet.openQuestions());
        return reopenSignalFromObject(object, nextFollowups);
    }

    private RetrievalSignal retrievalSignalFromPacket(com.agentcloud.model.ResumePacket packet) {
        if (packet == null) {
            return null;
        }
        ContextObject object = adapter.resumePacket(contextTask(packet), packet);
        List<String> nextFollowups = new ArrayList<>();
        if (packet.nextStep() != null && !packet.nextStep().isBlank()) {
            nextFollowups.add(packet.nextStep());
        }
        nextFollowups.addAll(packet.openQuestions());
        return retrievalSignalFromObject(object, nextFollowups);
    }

    private ReopenSignal reopenSignalFromCheckpoint(com.agentcloud.model.Checkpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        ContextObject object = adapter.checkpoint(contextTask(checkpoint), checkpoint);
        List<String> openQuestions = metadataStringList(checkpoint.refinedPacket(), "open_questions");
        return reopenSignalFromObject(object, summaryList(checkpoint.consolidationSummary(), joinList(openQuestions)));
    }

    private RetrievalSignal retrievalSignalFromCheckpoint(com.agentcloud.model.Checkpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        ContextObject object = adapter.checkpoint(contextTask(checkpoint), checkpoint);
        List<String> openQuestions = metadataStringList(checkpoint.refinedPacket(), "open_questions");
        return retrievalSignalFromObject(object, summaryList(checkpoint.consolidationSummary(), joinList(openQuestions)));
    }

    private ReopenSignal reopenSignalFromControlAction(Event event) {
        if (event == null) {
            return null;
        }
        ContextObject object = adapter.event(contextTask(event), event);
        List<String> followups = new ArrayList<>();
        if (event.payload() != null) {
            followups.add(metadataString(event.payload(), "resume_hint"));
            followups.add(metadataString(object.metadata(), "recommended_next_step"));
        }
        return reopenSignalFromObject(object, followups);
    }

    private RetrievalSignal retrievalSignalFromControlAction(Event event) {
        if (event == null) {
            return null;
        }
        ContextObject object = adapter.event(contextTask(event), event);
        List<String> followups = new ArrayList<>();
        if (event.payload() != null) {
            followups.add(metadataString(event.payload(), "resume_hint"));
            followups.add(metadataString(object.metadata(), "recommended_next_step"));
        }
        return retrievalSignalFromObject(object, followups);
    }

    private ReopenSignal reopenSignalFromObject(ContextObject object, List<String> nextFollowups) {
        if (object == null || !Boolean.TRUE.equals(object.metadata().get("needs_context_reopen"))) {
            return null;
        }
        List<String> reopenCandidatePaths = metadataStringList(object.metadata(), "reopen_candidate_paths");
        if (reopenCandidatePaths.isEmpty() && object.refs() != null) {
            reopenCandidatePaths = object.refs().stream()
                .filter(ref -> ref != null && "reopen_candidate".equals(ref.refType()))
                .map(ContextReference::targetPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        }
        if (reopenCandidatePaths.isEmpty()) {
            return null;
        }
        List<String> keyDecisions = summaryList(
            object.title(),
            metadataString(object.metadata(), "action"),
            metadataString(object.metadata(), "status"),
            metadataString(object.metadata(), "next_step"),
            metadataString(object.metadata(), "suggested_next_action")
        );
        List<String> reusableFindings = summaryList(
            object.summary(),
            metadataString(object.metadata(), "route_source"),
            metadataString(object.metadata(), "prompt_mode")
        );
        List<String> unresolvedRisks = summaryList(
            metadataString(object.metadata(), "reopen_summary"),
            joinList(metadataStringList(object.metadata(), "unfinished_items"))
        );
        return new ReopenSignal(
            true,
            reopenCandidatePaths,
            metadataString(object.metadata(), "reopen_summary"),
            keyDecisions,
            reusableFindings,
            unresolvedRisks,
            summaryList(nextFollowups)
        );
    }

    private RetrievalSignal retrievalSignalFromObject(ContextObject object, List<String> nextFollowups) {
        if (object == null || object.metadata() == null) {
            return null;
        }
        boolean needsArchiveRetrieval = Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"));
        boolean needsExternalFactRefresh = Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"));
        if (!needsArchiveRetrieval && !needsExternalFactRefresh) {
            return null;
        }
        List<String> reopenCandidatePaths = metadataStringList(object.metadata(), "reopen_candidate_paths");
        if (reopenCandidatePaths.isEmpty() && object.refs() != null) {
            reopenCandidatePaths = object.refs().stream()
                .filter(ref -> ref != null && "reopen_candidate".equals(ref.refType()))
                .map(ContextReference::targetPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        }
        String retrievalSummary = firstNonBlank(
            metadataString(object.metadata(), "reopen_summary"),
            joinList(reopenCandidatePaths)
        );
        List<String> keyDecisions = summaryList(
            object.title(),
            metadataString(object.metadata(), "action"),
            metadataString(object.metadata(), "status"),
            metadataString(object.metadata(), "next_step"),
            metadataString(object.metadata(), "suggested_next_action")
        );
        List<String> reusableFindings = summaryList(
            object.summary(),
            metadataString(object.metadata(), "route_source"),
            metadataString(object.metadata(), "prompt_mode"),
            needsArchiveRetrieval ? "needs_archive_retrieval=true" : null,
            needsExternalFactRefresh ? "needs_external_fact_refresh=true" : null
        );
        List<String> unresolvedRisks = summaryList(
            retrievalSummary,
            joinList(metadataStringList(object.metadata(), "unfinished_items"))
        );
        return new RetrievalSignal(
            needsArchiveRetrieval,
            needsExternalFactRefresh,
            reopenCandidatePaths,
            retrievalSummary,
            keyDecisions,
            reusableFindings,
            unresolvedRisks,
            summaryList(nextFollowups)
        );
    }

    private void addRehydratedEvidence(List<ContextObject> target, TaskRuntimeContext context) {
        if (target == null || context == null || context.task() == null) {
            return;
        }
        ReopenSignal signal = latestReopenSignal(context);
        if (signal == null || signal.reopenCandidatePaths() == null || signal.reopenCandidatePaths().isEmpty()) {
            return;
        }
        String capsulePath = reopenCapsulePath(context.task());
        List<String> reopenCandidatePaths = limit(signal.reopenCandidatePaths(), REOPEN_REHYDRATE_LIMIT);
        for (String targetPath : reopenCandidatePaths) {
            ContextObject rehydrated = rehydrateEvidenceObject(context, targetPath, capsulePath);
            if (rehydrated != null) {
                upsertRehydratedEvidence(target, rehydrated);
            }
        }
    }

    private ContextObject rehydrateEvidenceObject(TaskRuntimeContext context, String targetPath, String capsulePath) {
        if (context == null || context.task() == null || targetPath == null || targetPath.isBlank()) {
            return null;
        }
        Task task = context.task();
        if (matchesCollectionPath(targetPath, "tool_invocations")) {
            ToolInvocationRecord invocation = firstItem(context.recentToolInvocations());
            return invocation == null ? null : asRehydrated(adapter.toolInvocation(task, invocation), targetPath, capsulePath);
        }
        if (matchesCollectionPath(targetPath, "artifacts")) {
            Artifact artifact = firstItem(context.recentArtifacts());
            return artifact == null ? null : asRehydrated(adapter.artifact(task, artifact), targetPath, capsulePath);
        }
        if (matchesCollectionPath(targetPath, "decisions")) {
            Decision decision = firstItem(durableEvidenceDecisions(context.recentDecisions()));
            return decision == null ? null : asRehydrated(adapter.decision(task, decision), targetPath, capsulePath);
        }
        if (matchesCollectionPath(targetPath, "events")) {
            Event event = firstItem(context.recentEvents());
            return event == null ? null : asRehydrated(adapter.event(task, event), targetPath, capsulePath);
        }
        if (matchesCollectionPath(targetPath, "packets")) {
            ResumePacket packet = context.latestPacket();
            return packet == null ? null : asRehydrated(adapter.resumePacket(task, packet), targetPath, capsulePath);
        }
        if (matchesCollectionPath(targetPath, "checkpoints")) {
            Checkpoint checkpoint = context.latestCheckpoint();
            return checkpoint == null ? null : asRehydrated(adapter.checkpoint(task, checkpoint), targetPath, capsulePath);
        }
        if (matchesEntityPath(targetPath, "packets")) {
            ResumePacket packet = context.latestPacket();
            return packet == null || !targetPath.endsWith("/" + packet.id())
                ? null
                : asRehydrated(adapter.resumePacket(task, packet), targetPath, capsulePath);
        }
        if (matchesEntityPath(targetPath, "checkpoints")) {
            Checkpoint checkpoint = context.latestCheckpoint();
            return checkpoint == null || !targetPath.endsWith("/" + checkpoint.id())
                ? null
                : asRehydrated(adapter.checkpoint(task, checkpoint), targetPath, capsulePath);
        }
        return null;
    }

    private ContextObject asRehydrated(ContextObject original, String targetPath, String capsulePath) {
        if (original == null || targetPath == null || targetPath.isBlank()) {
            return original;
        }
        Map<String, Object> originalMetadata = original.metadata() == null ? Map.of() : original.metadata();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(originalMetadata);
        metadata.put("rehydrated_from_archive", true);
        metadata.put("rehydrated_target_path", targetPath);
        List<ContextReference> sourceRefs = new ArrayList<>();
        if (original.sourceRefs() != null && !original.sourceRefs().isEmpty()) {
            sourceRefs.addAll(original.sourceRefs());
        }
        if (capsulePath != null && !capsulePath.isBlank()) {
            sourceRefs.add(new ContextReference("reopen_capsule", capsulePath, "Reopen Capsule"));
        }
        return new ContextObject(
            original.id(),
            original.path(),
            original.type(),
            original.parentPath(),
            original.title(),
            original.summary(),
            original.contentPreview(),
            original.createdAt(),
            ContextRetentionState.HOT_RAW,
            original.refs(),
            List.copyOf(sourceRefs),
            metadata
        );
    }

    private String reopenCapsulePath(Task task) {
        if (task == null) {
            return null;
        }
        return "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/archive/reopen_capsule";
    }

    private boolean containsPath(List<ContextObject> objects, String path) {
        if (objects == null || objects.isEmpty() || path == null || path.isBlank()) {
            return false;
        }
        return objects.stream()
            .filter(object -> object != null && object.path() != null)
            .anyMatch(object -> path.equals(object.path()));
    }

    private void upsertRehydratedEvidence(List<ContextObject> objects, ContextObject rehydrated) {
        if (objects == null || rehydrated == null || rehydrated.path() == null || rehydrated.path().isBlank()) {
            return;
        }
        for (int i = 0; i < objects.size(); i++) {
            ContextObject existing = objects.get(i);
            if (existing != null && rehydrated.path().equals(existing.path())) {
                objects.set(i, rehydrated);
                return;
            }
        }
        objects.add(rehydrated);
    }

    private boolean matchesCollectionPath(String targetPath, String collectionName) {
        return targetPath != null
            && collectionName != null
            && !collectionName.isBlank()
            && targetPath.endsWith("/" + collectionName);
    }

    private boolean matchesEntityPath(String targetPath, String collectionName) {
        if (targetPath == null || collectionName == null || collectionName.isBlank()) {
            return false;
        }
        String token = "/" + collectionName + "/";
        return targetPath.contains(token);
    }

    private <T> T firstItem(List<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private Task contextTask(Decision decision) {
        return decision == null ? null : new Task(
            decision.taskId(),
            decision.sessionId(),
            null,
            decision.taskId(),
            "active",
            "medium",
            decision.createdAt(),
            decision.createdAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.Map.of()
        );
    }

    private Task contextTask(com.agentcloud.model.ResumePacket packet) {
        return packet == null ? null : new Task(
            packet.taskId(),
            packet.sessionId(),
            null,
            packet.taskId(),
            "active",
            "medium",
            packet.createdAt(),
            packet.createdAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.Map.of()
        );
    }

    private Task contextTask(com.agentcloud.model.Checkpoint checkpoint) {
        return checkpoint == null ? null : new Task(
            checkpoint.taskId(),
            checkpoint.sessionId(),
            null,
            checkpoint.taskId(),
            "active",
            "medium",
            checkpoint.createdAt(),
            checkpoint.createdAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.Map.of()
        );
    }

    private Task contextTask(Event event) {
        return event == null ? null : new Task(
            event.taskId(),
            event.sessionId(),
            null,
            event.taskId(),
            "active",
            "medium",
            event.createdAt(),
            event.createdAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.Map.of()
        );
    }

    private List<String> summaryList(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String value : values) {
            String token = summaryToken(value);
            if (token != null) {
                items.add(token);
            }
        }
        return List.copyOf(items);
    }

    private List<String> summaryList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return summaryList(values.toArray(String[]::new));
    }

    private String summaryToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String metadataString(java.util.Map<String, Object> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return null;
        }
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private List<String> metadataStringList(java.util.Map<String, Object> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object raw = payload.get(key);
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.toString().isBlank())
            .map(Object::toString)
            .toList();
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(" | ", values);
    }

    private record ReopenSignal(boolean needsContextReopen,
                                List<String> reopenCandidatePaths,
                                String reopenSummary,
                                List<String> keyDecisions,
                                List<String> reusableFindings,
                                List<String> unresolvedRisks,
                                List<String> nextFollowups) {
    }

    private record RetrievalSignal(boolean needsArchiveRetrieval,
                                   boolean needsExternalFactRefresh,
                                   List<String> reopenCandidatePaths,
                                   String retrievalSummary,
                                   List<String> keyDecisions,
                                   List<String> reusableFindings,
                                   List<String> unresolvedRisks,
                                   List<String> nextFollowups) {
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

    private List<Decision> durableEvidenceDecisions(List<Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream()
            .filter(this::isDurableEvidenceDecision)
            .toList();
    }

    private boolean isDurableEvidenceDecision(Decision decision) {
        if (decision == null || decision.decisionType() == null || decision.decisionType().isBlank()) {
            return false;
        }
        return "execution_judgment".equalsIgnoreCase(decision.decisionType())
            || "completion_judgment".equalsIgnoreCase(decision.decisionType());
    }

    private int panelCount(List<MountedContextPanel> panels, MountedContextPanelName target) {
        return panels.stream()
            .filter(panel -> panel != null && panel.name() == target)
            .findFirst()
            .map(panel -> panel.objects() == null ? 0 : panel.objects().size())
            .orElse(0);
    }
}
