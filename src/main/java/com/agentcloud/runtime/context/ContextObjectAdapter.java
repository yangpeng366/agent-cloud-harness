package com.agentcloud.runtime.context;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将当前 runtime surface 包装为 mounted context objects。
 */
public class ContextObjectAdapter {
    private static final int PREVIEW_LIMIT = 240;

    public ContextObject taskGoal(Task task) {
        if (task == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "status", task.status());
        putIfNotBlank(metadata, "priority", task.priority());
        putIfNotBlank(metadata, "control_node", task.controlNode());
        putIfNotBlank(metadata, "assigned_worker", task.assignedWorker());
        putIfNotBlank(metadata, "parent_task_id", task.parentTaskId());
        return new ContextObject(
            task.id() + ":goal",
            taskPath + "/goal",
            ContextObjectType.TASK,
            taskPath,
            firstNonBlank(task.title(), task.id()),
            firstNonBlank(task.goal(), task.summary(), task.title()),
            preview(multiline(
                "goal", task.goal(),
                "summary", task.summary(),
                "next_step", task.nextStep()
            )),
            task.updatedAt(),
            ContextRetentionState.PINNED,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject constraints(Task task, ActiveContext activeContext) {
        if (task == null || activeContext == null || activeContext.constraints().isEmpty()) {
            return null;
        }
        String taskPath = taskPath(task);
        return new ContextObject(
            task.id() + ":constraints",
            taskPath + "/constraints",
            ContextObjectType.CONSTRAINT,
            taskPath,
            "Constraints",
            preview(String.join(" | ", activeContext.constraints())),
            preview(String.join("\n", activeContext.constraints())),
            task.updatedAt(),
            ContextRetentionState.PINNED,
            List.of(),
            List.of(),
            Map.of("constraint_count", activeContext.constraints().size())
        );
    }

    public ContextObject activeContext(Task task, ActiveContext activeContext, ResumePacket packet, Checkpoint checkpoint) {
        if (task == null || activeContext == null) {
            return null;
        }
        String taskPath = taskPath(task);
        List<ContextReference> sourceRefs = new ArrayList<>();
        if (packet != null) {
            sourceRefs.add(new ContextReference("source", taskPath + "/packets/" + packet.id(), "latest resume packet"));
        }
        if (checkpoint != null) {
            sourceRefs.add(new ContextReference("source", taskPath + "/checkpoints/" + checkpoint.id(), "latest checkpoint"));
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("item_budget", activeContext.itemBudget());
        metadata.put("open_question_count", activeContext.openQuestions().size());
        metadata.put("next_candidate_count", activeContext.nextCandidates().size());
        metadata.put("risk_hint_count", activeContext.riskHints().size());
        metadata.put("selection_trace_count", activeContext.selectionTrace().size());
        return new ContextObject(
            task.id() + ":active-context",
            taskPath + "/runtime/active_context",
            ContextObjectType.ACTIVE_CONTEXT,
            taskPath,
            firstNonBlank(activeContext.taskFocus(), "Active Context"),
            firstNonBlank(activeContext.continuitySummary(), activeContext.taskFocus()),
            preview(activeContext.synthesizedContext()),
            task.updatedAt(),
            ContextRetentionState.WARM_SUMMARY,
            List.of(),
            List.copyOf(sourceRefs),
            metadata
        );
    }

    public ContextObject sessionMessage(Task task, SessionMessage message) {
        if (task == null || message == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "role", message.role());
        putIfNotBlank(metadata, "message_type", message.messageType());
        if (message.metadata() != null && !message.metadata().isEmpty()) {
            metadata.put("source_surface", message.metadata().get("source_surface"));
        }
        return new ContextObject(
            message.id(),
            taskPath + "/messages/" + message.id(),
            ContextObjectType.SESSION_MESSAGE,
            taskPath,
            firstNonBlank(message.messageType(), message.role(), "message"),
            preview(message.content()),
            preview(message.content()),
            message.createdAt(),
            ContextRetentionState.HOT_RAW,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject decision(Task task, Decision decision) {
        if (task == null || decision == null) {
            return null;
        }
        String taskPath = taskPath(task);
        Map<String, Object> decisionMetadata = decision.metadata() == null ? Map.of() : decision.metadata();
        List<String> toolInvocationIds = stringList(decisionMetadata, "tool_invocation_ids");
        List<String> evidenceRefs = stringList(decisionMetadata, "evidence_refs");
        List<ContextReference> refs = new ArrayList<>();
        for (String toolInvocationId : toolInvocationIds) {
            refs.add(new ContextReference(
                "tool_invocation",
                taskPath + "/tool_invocations/" + toolInvocationId,
                toolInvocationId
            ));
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "decision_type", decision.decisionType());
        putIfNotBlank(metadata, "impact_level", decision.impactLevel());
        putIfNotBlank(metadata, "supersedes_decision_id", decision.supersedesDecisionId());
        putIfNotBlank(metadata, "judgment_stage", metadataString(decisionMetadata, "judgment_stage"));
        putIfNotBlank(metadata, "selected_worker", metadataString(decisionMetadata, "selected_worker"));
        putIfNotBlank(metadata, "selected_model_tier", metadataString(decisionMetadata, "selected_model_tier"));
        putIfNotBlank(metadata, "action", metadataString(decisionMetadata, "action"));
        putIfNotBlank(metadata, "status", metadataString(decisionMetadata, "status"));
        putIfNotBlank(metadata, "alignment_level", metadataString(decisionMetadata, "alignment_level"));
        putIfNotBlank(metadata, "next_step", metadataString(decisionMetadata, "next_step"));
        putIfNotBlank(metadata, "suggested_next_action", metadataString(decisionMetadata, "suggested_next_action"));
        if (!toolInvocationIds.isEmpty()) {
            metadata.put("tool_invocation_ids", toolInvocationIds);
            metadata.put("tool_invocation_count", toolInvocationIds.size());
        }
        if (!evidenceRefs.isEmpty()) {
            metadata.put("evidence_refs", evidenceRefs);
            metadata.put("evidence_ref_count", evidenceRefs.size());
        }
        return new ContextObject(
            decision.id(),
            taskPath + "/decisions/" + decision.id(),
            ContextObjectType.DECISION,
            taskPath,
            firstNonBlank(decision.summary(), decision.decisionType(), "decision"),
            preview(firstNonBlank(
                joinSummary(
                    labeledValue("judgment_stage", metadataString(decisionMetadata, "judgment_stage")),
                    labeledValue("selected_worker", metadataString(decisionMetadata, "selected_worker")),
                    labeledValue("action", metadataString(decisionMetadata, "action")),
                    labeledValue("status", metadataString(decisionMetadata, "status")),
                    labeledValue("next_step", metadataString(decisionMetadata, "next_step")),
                    labeledValue("suggested_next_action", metadataString(decisionMetadata, "suggested_next_action"))
                ),
                decision.summary(),
                decision.rationale()
            )),
            preview(multiline(
                "summary", decision.summary(),
                "rationale", decision.rationale(),
                "judgment_stage", metadataString(decisionMetadata, "judgment_stage"),
                "selected_worker", metadataString(decisionMetadata, "selected_worker"),
                "action", metadataString(decisionMetadata, "action"),
                "status", metadataString(decisionMetadata, "status"),
                "alignment_level", metadataString(decisionMetadata, "alignment_level"),
                "next_step", metadataString(decisionMetadata, "next_step"),
                "suggested_next_action", metadataString(decisionMetadata, "suggested_next_action"),
                "tool_invocation_ids", joinList(toolInvocationIds),
                "evidence_refs", joinList(evidenceRefs)
            )),
            decision.createdAt(),
            ContextRetentionState.WARM_SUMMARY,
            List.copyOf(refs),
            List.of(),
            metadata
        );
    }

    public ContextObject artifact(Task task, Artifact artifact) {
        if (task == null || artifact == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "artifact_type", artifact.artifactType());
        putIfNotBlank(metadata, "uri", artifact.uri());
        putIfNotBlank(metadata, "content_hash", artifact.contentHash());
        return new ContextObject(
            artifact.id(),
            taskPath + "/artifacts/" + artifact.id(),
            ContextObjectType.ARTIFACT,
            taskPath,
            firstNonBlank(artifact.title(), artifact.artifactType(), "artifact"),
            preview(firstNonBlank(artifact.summary(), artifact.uri(), artifact.title())),
            preview(multiline(
                "title", artifact.title(),
                "summary", artifact.summary(),
                "uri", artifact.uri()
            )),
            artifact.createdAt(),
            ContextRetentionState.WARM_SUMMARY,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject toolInvocation(Task task, ToolInvocationRecord invocation) {
        if (task == null || invocation == null) {
            return null;
        }
        String taskPath = taskPath(task);
        List<ContextReference> refs = new ArrayList<>();
        if (!invocation.touchedPaths().isEmpty()) {
            for (String touchedPath : invocation.touchedPaths()) {
                if (touchedPath == null || touchedPath.isBlank()) {
                    continue;
                }
                refs.add(new ContextReference("path", touchedPath, touchedPath));
            }
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "tool_name", invocation.toolName());
        putIfNotBlank(metadata, "worker_id", invocation.workerId());
        putIfNotBlank(metadata, "execution_id", invocation.executionId());
        putIfNotBlank(metadata, "status", invocation.status());
        metadata.put("success", invocation.success());
        if (invocation.elapsedMs() != null) {
            metadata.put("elapsed_ms", invocation.elapsedMs());
        }
        if (!invocation.arguments().isEmpty()) {
            metadata.put("argument_keys", invocation.arguments().keySet().stream().sorted().toList());
        }
        if (!invocation.touchedPaths().isEmpty()) {
            metadata.put("touched_path_count", invocation.touchedPaths().size());
        }
        if (!invocation.metadata().isEmpty()) {
            metadata.put("trace_metadata_keys", invocation.metadata().keySet().stream().sorted().toList());
        }
        return new ContextObject(
            invocation.id(),
            taskPath + "/tool_invocations/" + invocation.id(),
            ContextObjectType.TOOL_INVOCATION,
            taskPath,
            firstNonBlank(invocation.toolName(), "tool invocation"),
            preview(firstNonBlank(invocation.resultSummary(), invocation.status(), invocation.toolName())),
            preview(multiline(
                "tool", invocation.toolName(),
                "status", invocation.status(),
                "result", invocation.resultSummary(),
                "touched_paths", joinList(invocation.touchedPaths())
            )),
            invocation.createdAt(),
            ContextRetentionState.WARM_SUMMARY,
            List.copyOf(refs),
            List.of(),
            metadata
        );
    }

    public ContextObject checkpoint(Task task, Checkpoint checkpoint) {
        if (task == null || checkpoint == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "checkpoint_type", checkpoint.checkpointType());
        metadata.put("refined_packet_keys",
            checkpoint.refinedPacket() == null ? List.of() : checkpoint.refinedPacket().keySet().stream().sorted().toList());
        return new ContextObject(
            checkpoint.id(),
            taskPath + "/checkpoints/" + checkpoint.id(),
            ContextObjectType.CHECKPOINT,
            taskPath,
            firstNonBlank(checkpoint.checkpointType(), "checkpoint"),
            preview(checkpoint.consolidationSummary()),
            preview(multiline(
                "summary", checkpoint.consolidationSummary(),
                "key_decisions", joinList(stringList(checkpoint.refinedPacket(), "key_decisions")),
                "key_artifacts", joinList(stringList(checkpoint.refinedPacket(), "key_artifacts")),
                "open_questions", joinList(stringList(checkpoint.refinedPacket(), "open_questions"))
            )),
            checkpoint.createdAt(),
            ContextRetentionState.HOT_RAW,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject resumePacket(Task task, ResumePacket packet) {
        if (task == null || packet == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "packet_version", packet.packetVersion());
        putIfNotBlank(metadata, "current_status", packet.currentStatus());
        putIfNotBlank(metadata, "current_node", packet.currentNode());
        putIfNotBlank(metadata, "assigned_worker", packet.assignedWorker());
        metadata.put("open_question_count", packet.openQuestions().size());
        metadata.put("blocker_count", packet.blockers().size());
        return new ContextObject(
            packet.id(),
            taskPath + "/packets/" + packet.id(),
            ContextObjectType.RESUME_PACKET,
            taskPath,
            firstNonBlank(packet.currentObjective(), "resume packet"),
            preview(firstNonBlank(packet.latestSummary(), packet.activeTaskSummary(), packet.decisionSummary())),
            preview(multiline(
                "active_task_summary", packet.activeTaskSummary(),
                "decision_summary", packet.decisionSummary(),
                "artifact_summary", packet.artifactSummary(),
                "next_step", packet.nextStep(),
                "open_questions", joinList(packet.openQuestions()),
                "blockers", joinList(packet.blockers())
            )),
            packet.createdAt(),
            ContextRetentionState.HOT_RAW,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject event(Task task, Event event) {
        if (task == null || event == null) {
            return null;
        }
        String taskPath = taskPath(task);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "event_type", event.eventType());
        putIfNotBlank(metadata, "actor_type", event.actorType());
        putIfNotBlank(metadata, "actor_id", event.actorId());
        if (event.payload() != null && !event.payload().isEmpty()) {
            metadata.put("payload_keys", event.payload().keySet().stream().sorted().toList());
        }
        return new ContextObject(
            event.id(),
            taskPath + "/events/" + event.id(),
            ContextObjectType.EVENT,
            taskPath,
            firstNonBlank(event.eventType(), "event"),
            preview(event.summary()),
            preview(event.summary()),
            event.createdAt(),
            ContextRetentionState.WARM_SUMMARY,
            List.of(),
            List.of(),
            metadata
        );
    }

    public ContextObject index(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return null;
        }
        Task task = context.task();
        String taskPath = taskPath(task);
        List<ContextReference> refs = List.of(
            new ContextReference("collection", taskPath + "/messages", "recent messages"),
            new ContextReference("collection", taskPath + "/decisions", "recent decisions"),
            new ContextReference("collection", taskPath + "/artifacts", "recent artifacts"),
            new ContextReference("collection", taskPath + "/tool_invocations", "recent tool invocations"),
            new ContextReference("collection", taskPath + "/events", "recent events"),
            new ContextReference("collection", taskPath + "/checkpoints", "checkpoints"),
            new ContextReference("collection", taskPath + "/packets", "resume packets")
        );
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("message_count", context.recentMessages().size());
        metadata.put("decision_count", context.recentDecisions().size());
        metadata.put("artifact_count", context.recentArtifacts().size());
        metadata.put("tool_invocation_count", context.recentToolInvocations().size());
        metadata.put("event_count", context.recentEvents().size());
        metadata.put("has_latest_packet", context.latestPacket() != null);
        metadata.put("has_latest_checkpoint", context.latestCheckpoint() != null);
        return new ContextObject(
            task.id() + ":index",
            taskPath + "/context/index",
            ContextObjectType.INDEX,
            taskPath,
            "Mounted Context Index",
            "messages=%d, decisions=%d, artifacts=%d, tool_invocations=%d, events=%d".formatted(
                context.recentMessages().size(),
                context.recentDecisions().size(),
                context.recentArtifacts().size(),
                context.recentToolInvocations().size(),
                context.recentEvents().size()
            ),
            "Use collection refs for targeted reload and retrieval.",
            task.updatedAt(),
            ContextRetentionState.WARM_SUMMARY,
            refs,
            List.of(),
            metadata
        );
    }

    public ContextObject handle(Task task, String handleId, String title, String summary, String targetPath) {
        if (task == null || handleId == null || handleId.isBlank() || targetPath == null || targetPath.isBlank()) {
            return null;
        }
        String taskPath = taskPath(task);
        return new ContextObject(
            task.id() + ":" + handleId,
            taskPath + "/archive/" + handleId,
            ContextObjectType.HANDLE,
            taskPath,
            title,
            preview(summary),
            preview(summary),
            task.updatedAt(),
            ContextRetentionState.ARCHIVED_HANDLE,
            List.of(new ContextReference("handle", targetPath, title)),
            List.of(),
            Map.of("target_path", targetPath)
        );
    }

    public ContextObject parentTaskHandle(Task task) {
        if (task == null || task.parentTaskId() == null || task.parentTaskId().isBlank()) {
            return null;
        }
        return handle(
            task,
            "parent-task",
            "Parent Task",
            "Parent task handle: " + task.parentTaskId(),
            sessionPath(task) + "/tasks/" + task.parentTaskId()
        );
    }

    private String taskPath(Task task) {
        return sessionPath(task) + "/tasks/" + task.id();
    }

    private String sessionPath(Task task) {
        return "/sessions/" + task.sessionId();
    }

    private List<String> stringList(Map<String, Object> payload, String key) {
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

    private String joinSummary(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                present.add(value);
            }
        }
        return present.isEmpty() ? null : String.join(" | ", present);
    }

    private String labeledValue(String label, String value) {
        if (label == null || label.isBlank() || value == null || value.isBlank()) {
            return null;
        }
        return label + "=" + value;
    }

    private String multiline(String... lines) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i + 1 < lines.length; i += 2) {
            String label = lines[i];
            String value = lines[i + 1];
            if (value != null && !value.isBlank()) {
                values.add(label + ": " + value);
            }
        }
        return String.join("\n", values);
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT).trim() + "...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String metadataString(Map<String, Object> payload, String key) {
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

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
