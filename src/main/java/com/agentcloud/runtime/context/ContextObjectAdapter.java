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
        if (message.metadata() != null && message.metadata().get("continuity_scope") != null) {
            metadata.put("continuity_scope", message.metadata().get("continuity_scope"));
        }
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
        List<String> reopenCandidatePaths = stringList(decisionMetadata, "reopen_candidate_paths");
        boolean needsContextReopen = metadataBoolean(decisionMetadata, "needs_context_reopen");
        boolean evidenceGapDetected = metadataBoolean(decisionMetadata, "evidence_gap_detected");
        boolean needsArchiveRetrieval = metadataBoolean(decisionMetadata, "needs_archive_retrieval");
        boolean needsExternalFactRefresh = metadataBoolean(decisionMetadata, "needs_external_fact_refresh");
        List<ContextReference> refs = new ArrayList<>();
        for (String toolInvocationId : toolInvocationIds) {
            refs.add(new ContextReference(
                "tool_invocation",
                taskPath + "/tool_invocations/" + toolInvocationId,
                toolInvocationId
            ));
        }
        for (String reopenCandidatePath : reopenCandidatePaths) {
            refs.add(new ContextReference(
                "reopen_candidate",
                reopenCandidatePath,
                reopenCandidateLabel(reopenCandidatePath)
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
        if (needsContextReopen) {
            metadata.put("needs_context_reopen", true);
        }
        if (evidenceGapDetected) {
            metadata.put("evidence_gap_detected", true);
        }
        if (needsArchiveRetrieval) {
            metadata.put("needs_archive_retrieval", true);
        }
        if (needsExternalFactRefresh) {
            metadata.put("needs_external_fact_refresh", true);
        }
        if (!reopenCandidatePaths.isEmpty()) {
            metadata.put("reopen_candidate_paths", reopenCandidatePaths);
            metadata.put("reopen_candidate_count", reopenCandidatePaths.size());
        }
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
                    labeledValue("suggested_next_action", metadataString(decisionMetadata, "suggested_next_action")),
                    labeledValue("needs_context_reopen", needsContextReopen ? "true" : null),
                    labeledValue("evidence_gap_detected", evidenceGapDetected ? "true" : null),
                    labeledValue("needs_archive_retrieval", needsArchiveRetrieval ? "true" : null),
                    labeledValue("needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null),
                    labeledValue("reopen_candidate_paths", joinList(reopenCandidatePaths))
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
                "needs_context_reopen", needsContextReopen ? "true" : null,
                "evidence_gap_detected", evidenceGapDetected ? "true" : null,
                "needs_archive_retrieval", needsArchiveRetrieval ? "true" : null,
                "needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null,
                "reopen_candidate_paths", joinList(reopenCandidatePaths),
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
        Map<String, Object> refinedPacket = checkpoint.refinedPacket() == null ? Map.of() : checkpoint.refinedPacket();
        Map<String, Object> runtimeCognitionSurface = objectMap(refinedPacket.get("runtime_cognition_surface"));
        Map<String, Object> routeSurface = objectMap(runtimeCognitionSurface.get("route"));
        Map<String, Object> executionSurface = objectMap(runtimeCognitionSurface.get("execution"));
        Map<String, Object> judgmentSurface = objectMap(runtimeCognitionSurface.get("execution_judgment"));
        List<String> candidateWorkers = firstNonEmpty(
            stringList(routeSurface, "candidate_workers"),
            stringList(refinedPacket, "candidate_workers")
        );
        List<String> toolInvocationIds = firstNonEmpty(
            stringList(executionSurface, "tool_invocation_ids"),
            stringList(refinedPacket, "tool_invocation_ids")
        );
        List<String> evidenceRefs = firstNonEmpty(
            stringList(executionSurface, "evidence_refs"),
            stringList(refinedPacket, "evidence_refs")
        );
        List<String> unfinishedItems = firstNonEmpty(
            stringList(executionSurface, "unfinished_items"),
            stringList(refinedPacket, "open_questions")
        );
        boolean needsContextReopen = metadataBoolean(judgmentSurface, "needs_context_reopen");
        boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected");
        boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval");
        boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh");
        List<String> reopenCandidatePaths = stringList(judgmentSurface, "reopen_candidate_paths");
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            joinList(reopenCandidatePaths)
        );
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "checkpoint_type", checkpoint.checkpointType());
        putIfNotBlank(metadata, "prompt_mode", firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(refinedPacket, "prompt_mode"),
            metadataString(refinedPacket, "mounted_context_mode"),
            metadataString(refinedPacket, "prompt_rendering_mode")
        ));
        putIfNotBlank(metadata, "route_source", metadataString(routeSurface, "route_source"));
        if (!candidateWorkers.isEmpty()) {
            metadata.put("candidate_workers", candidateWorkers);
        }
        if (!toolInvocationIds.isEmpty()) {
            metadata.put("tool_invocation_ids", toolInvocationIds);
            metadata.put("tool_invocation_count", toolInvocationIds.size());
        }
        if (!evidenceRefs.isEmpty()) {
            metadata.put("evidence_refs", evidenceRefs);
            metadata.put("evidence_ref_count", evidenceRefs.size());
        }
        if (!unfinishedItems.isEmpty()) {
            metadata.put("unfinished_items", unfinishedItems);
        }
        if (needsContextReopen) {
            metadata.put("needs_context_reopen", true);
        }
        if (evidenceGapDetected) {
            metadata.put("evidence_gap_detected", true);
        }
        if (needsArchiveRetrieval) {
            metadata.put("needs_archive_retrieval", true);
        }
        if (needsExternalFactRefresh) {
            metadata.put("needs_external_fact_refresh", true);
        }
        if (!reopenCandidatePaths.isEmpty()) {
            metadata.put("reopen_candidate_paths", reopenCandidatePaths);
            metadata.put("reopen_candidate_count", reopenCandidatePaths.size());
        }
        putIfNotBlank(metadata, "reopen_summary", reopenSummary);
        metadata.put("refined_packet_keys", refinedPacket.keySet().stream().sorted().toList());
        return new ContextObject(
            checkpoint.id(),
            taskPath + "/checkpoints/" + checkpoint.id(),
            ContextObjectType.CHECKPOINT,
            taskPath,
            firstNonBlank(checkpoint.checkpointType(), "checkpoint"),
            preview(checkpoint.consolidationSummary()),
            preview(multiline(
                "summary", checkpoint.consolidationSummary(),
                "prompt_mode", firstNonBlank(
                    metadataString(executionSurface, "prompt_mode"),
                    metadataString(refinedPacket, "prompt_mode"),
                    metadataString(refinedPacket, "mounted_context_mode"),
                    metadataString(refinedPacket, "prompt_rendering_mode")
                ),
                "route_source", metadataString(routeSurface, "route_source"),
                "key_decisions", joinList(stringList(checkpoint.refinedPacket(), "key_decisions")),
                "key_artifacts", joinList(stringList(checkpoint.refinedPacket(), "key_artifacts")),
                "open_questions", joinList(stringList(checkpoint.refinedPacket(), "open_questions")),
                "tool_invocation_ids", joinList(toolInvocationIds),
                "evidence_refs", joinList(evidenceRefs),
                "unfinished_items", joinList(unfinishedItems),
                "needs_context_reopen", needsContextReopen ? "true" : null,
                "evidence_gap_detected", evidenceGapDetected ? "true" : null,
                "needs_archive_retrieval", needsArchiveRetrieval ? "true" : null,
                "needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null,
                "reopen_candidate_paths", joinList(reopenCandidatePaths),
                "reopen_summary", reopenSummary
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
        Map<String, Object> payload = packet.payload() == null ? Map.of() : packet.payload();
        Map<String, Object> runtimeCognitionSurface = objectMap(payload.get("runtime_cognition_surface"));
        Map<String, Object> routeSurface = objectMap(runtimeCognitionSurface.get("route"));
        Map<String, Object> executionSurface = objectMap(runtimeCognitionSurface.get("execution"));
        Map<String, Object> judgmentSurface = objectMap(runtimeCognitionSurface.get("execution_judgment"));
        List<String> candidateWorkers = firstNonEmpty(
            stringList(routeSurface, "candidate_workers"),
            stringList(payload, "candidate_workers")
        );
        List<String> toolInvocationIds = firstNonEmpty(
            stringList(executionSurface, "tool_invocation_ids"),
            stringList(payload, "tool_invocation_ids")
        );
        List<String> evidenceRefs = firstNonEmpty(
            stringList(executionSurface, "evidence_refs"),
            stringList(payload, "evidence_refs")
        );
        List<String> unfinishedItems = firstNonEmpty(
            stringList(executionSurface, "unfinished_items"),
            packet.openQuestions()
        );
        boolean needsContextReopen = metadataBoolean(judgmentSurface, "needs_context_reopen");
        boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected");
        boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval");
        boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh");
        List<String> reopenCandidatePaths = stringList(judgmentSurface, "reopen_candidate_paths");
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            joinList(reopenCandidatePaths)
        );
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "packet_version", packet.packetVersion());
        putIfNotBlank(metadata, "current_status", packet.currentStatus());
        putIfNotBlank(metadata, "current_node", packet.currentNode());
        putIfNotBlank(metadata, "assigned_worker", packet.assignedWorker());
        putIfNotBlank(metadata, "prompt_mode", firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        ));
        putIfNotBlank(metadata, "route_source", metadataString(routeSurface, "route_source"));
        if (!candidateWorkers.isEmpty()) {
            metadata.put("candidate_workers", candidateWorkers);
        }
        if (!toolInvocationIds.isEmpty()) {
            metadata.put("tool_invocation_ids", toolInvocationIds);
            metadata.put("tool_invocation_count", toolInvocationIds.size());
        }
        if (!evidenceRefs.isEmpty()) {
            metadata.put("evidence_refs", evidenceRefs);
            metadata.put("evidence_ref_count", evidenceRefs.size());
        }
        if (!unfinishedItems.isEmpty()) {
            metadata.put("unfinished_items", unfinishedItems);
        }
        if (needsContextReopen) {
            metadata.put("needs_context_reopen", true);
        }
        if (evidenceGapDetected) {
            metadata.put("evidence_gap_detected", true);
        }
        if (needsArchiveRetrieval) {
            metadata.put("needs_archive_retrieval", true);
        }
        if (needsExternalFactRefresh) {
            metadata.put("needs_external_fact_refresh", true);
        }
        if (!reopenCandidatePaths.isEmpty()) {
            metadata.put("reopen_candidate_paths", reopenCandidatePaths);
            metadata.put("reopen_candidate_count", reopenCandidatePaths.size());
        }
        putIfNotBlank(metadata, "reopen_summary", reopenSummary);
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
                "prompt_mode", firstNonBlank(
                    metadataString(executionSurface, "prompt_mode"),
                    metadataString(payload, "prompt_mode"),
                    metadataString(payload, "mounted_context_mode"),
                    metadataString(payload, "prompt_rendering_mode")
                ),
                "route_source", metadataString(routeSurface, "route_source"),
                "next_step", packet.nextStep(),
                "open_questions", joinList(packet.openQuestions()),
                "blockers", joinList(packet.blockers()),
                "tool_invocation_ids", joinList(toolInvocationIds),
                "evidence_refs", joinList(evidenceRefs),
                "unfinished_items", joinList(unfinishedItems),
                "needs_context_reopen", needsContextReopen ? "true" : null,
                "evidence_gap_detected", evidenceGapDetected ? "true" : null,
                "needs_archive_retrieval", needsArchiveRetrieval ? "true" : null,
                "needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null,
                "reopen_candidate_paths", joinList(reopenCandidatePaths),
                "reopen_summary", reopenSummary
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
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        if ("task_control_action".equals(event.eventType())) {
            return controlActionEvent(task, event, taskPath, payload);
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "event_type", event.eventType());
        putIfNotBlank(metadata, "actor_type", event.actorType());
        putIfNotBlank(metadata, "actor_id", event.actorId());
        if (!payload.isEmpty()) {
            metadata.put("payload_keys", payload.keySet().stream().sorted().toList());
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

    private ContextObject controlActionEvent(Task task,
                                             Event event,
                                             String taskPath,
                                             Map<String, Object> payload) {
        Map<String, Object> runtimeFacts = objectMap(payload.get("runtime_facts"));
        Map<String, Object> runtimeCognitionSurface = objectMap(payload.get("runtime_cognition_surface"));
        Map<String, Object> routeSurface = objectMap(runtimeCognitionSurface.get("route"));
        Map<String, Object> executionSurface = objectMap(runtimeCognitionSurface.get("execution"));
        Map<String, Object> judgmentSurface = objectMap(runtimeCognitionSurface.get("execution_judgment"));
        List<String> candidateWorkers = firstNonEmpty(
            stringList(routeSurface, "candidate_workers"),
            stringList(payload, "candidate_workers")
        );
        List<String> toolInvocationIds = firstNonEmpty(
            stringList(executionSurface, "tool_invocation_ids"),
            stringList(payload, "tool_invocation_ids")
        );
        List<String> evidenceRefs = firstNonEmpty(
            stringList(executionSurface, "evidence_refs"),
            stringList(payload, "evidence_refs")
        );
        List<String> unfinishedItems = firstNonEmpty(
            stringList(executionSurface, "unfinished_items"),
            stringList(payload, "unfinished_items")
        );
        String action = metadataString(payload, "action");
        String targetWorker = metadataString(payload, "target_worker");
        String previousWorker = metadataString(payload, "previous_worker");
        String currentWorker = firstNonBlank(
            metadataString(executionSurface, "worker_id"),
            metadataString(routeSurface, "selected_worker"),
            metadataString(payload, "assigned_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        );
        String routeSource = metadataString(routeSurface, "route_source");
        String reason = metadataString(payload, "reason");
        String recommendedNextStep = firstNonBlank(
            metadataString(runtimeFacts, "recommended_next_step"),
            metadataString(payload, "resume_hint")
        );
        String executionStatus = metadataString(executionSurface, "execution_status");
        boolean needsContextReopen = metadataBoolean(judgmentSurface, "needs_context_reopen");
        boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected");
        boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval");
        boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh");
        List<String> reopenCandidatePaths = firstNonEmpty(
            stringList(judgmentSurface, "reopen_candidate_paths"),
            stringList(payload, "reopen_candidate_paths")
        );
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            joinList(reopenCandidatePaths)
        );
        String proofSummary = firstNonBlank(
            metadataString(executionSurface, "proof_summary"),
            metadataString(payload, "proof_summary")
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "event_type", event.eventType());
        putIfNotBlank(metadata, "actor_type", event.actorType());
        putIfNotBlank(metadata, "actor_id", event.actorId());
        putIfNotBlank(metadata, "action", action);
        putIfNotBlank(metadata, "previous_worker", previousWorker);
        putIfNotBlank(metadata, "assigned_worker", currentWorker);
        putIfNotBlank(metadata, "target_worker", targetWorker);
        putIfNotBlank(metadata, "prompt_mode", promptMode);
        putIfNotBlank(metadata, "route_source", routeSource);
        putIfNotBlank(metadata, "execution_status", executionStatus);
        putIfNotBlank(metadata, "recommended_next_step", recommendedNextStep);
        if (needsContextReopen) {
            metadata.put("needs_context_reopen", true);
        }
        if (evidenceGapDetected) {
            metadata.put("evidence_gap_detected", true);
        }
        if (needsArchiveRetrieval) {
            metadata.put("needs_archive_retrieval", true);
        }
        if (needsExternalFactRefresh) {
            metadata.put("needs_external_fact_refresh", true);
        }
        if (!reopenCandidatePaths.isEmpty()) {
            metadata.put("reopen_candidate_paths", reopenCandidatePaths);
            metadata.put("reopen_candidate_count", reopenCandidatePaths.size());
        }
        putIfNotBlank(metadata, "reopen_summary", reopenSummary);
        if (!candidateWorkers.isEmpty()) {
            metadata.put("candidate_workers", candidateWorkers);
        }
        if (!toolInvocationIds.isEmpty()) {
            metadata.put("tool_invocation_ids", toolInvocationIds);
            metadata.put("tool_invocation_count", toolInvocationIds.size());
        }
        if (!evidenceRefs.isEmpty()) {
            metadata.put("evidence_refs", evidenceRefs);
            metadata.put("evidence_ref_count", evidenceRefs.size());
        }
        if (!unfinishedItems.isEmpty()) {
            metadata.put("unfinished_items", unfinishedItems);
        }
        if (!payload.isEmpty()) {
            metadata.put("payload_keys", payload.keySet().stream().sorted().toList());
        }
        return new ContextObject(
            event.id(),
            taskPath + "/events/" + event.id(),
            ContextObjectType.EVENT,
            taskPath,
            firstNonBlank(action, event.eventType(), "event"),
            preview(firstNonBlank(
                joinSummary(
                    action,
                    workerTransition(previousWorker, targetWorker),
                    promptMode,
                    recommendedNextStep
                ),
                event.summary()
            )),
            preview(multiline(
                "summary", event.summary(),
                "action", action,
                "previous_worker", previousWorker,
                "assigned_worker", currentWorker,
                "target_worker", targetWorker,
                "prompt_mode", promptMode,
                "route_source", routeSource,
                "execution_status", executionStatus,
                "reason", reason,
                "recommended_next_step", recommendedNextStep,
                "needs_context_reopen", needsContextReopen ? "true" : null,
                "evidence_gap_detected", evidenceGapDetected ? "true" : null,
                "needs_archive_retrieval", needsArchiveRetrieval ? "true" : null,
                "needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null,
                "reopen_candidate_paths", joinList(reopenCandidatePaths),
                "reopen_summary", reopenSummary,
                "tool_invocation_ids", joinList(toolInvocationIds),
                "evidence_refs", joinList(evidenceRefs),
                "unfinished_items", joinList(unfinishedItems),
                "proof_summary", proofSummary
            )),
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

    public ContextObject reopenCapsule(Task task,
                                       String capsuleId,
                                       String scopePath,
                                       String outcome,
                                       List<String> keyDecisions,
                                       List<String> reusableFindings,
                                       List<String> unresolvedRisks,
                                       List<String> reopenCandidatePaths,
                                       List<String> nextFollowups) {
        if (task == null || capsuleId == null || capsuleId.isBlank()) {
            return null;
        }
        String taskPath = taskPath(task);
        ContextCapsule capsule = new ContextCapsule(
            capsuleId,
            firstNonBlank(scopePath, taskPath + "/archive/reopen"),
            outcome,
            copyList(keyDecisions),
            copyList(reusableFindings),
            copyList(unresolvedRisks),
            reopenCandidateRefs(reopenCandidatePaths),
            copyList(nextFollowups),
            ContextRetentionState.COLD_CAPSULE
        );
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capsule_scope_path", capsule.scopePath());
        metadata.put("retention_state", capsule.retentionState().wireName());
        if (!capsule.keyDecisions().isEmpty()) {
            metadata.put("key_decisions", capsule.keyDecisions());
        }
        if (!capsule.reusableFindings().isEmpty()) {
            metadata.put("reusable_findings", capsule.reusableFindings());
        }
        if (!capsule.unresolvedRisks().isEmpty()) {
            metadata.put("unresolved_risks", capsule.unresolvedRisks());
        }
        if (!capsule.evidenceRefs().isEmpty()) {
            metadata.put("reopen_candidate_paths", capsule.evidenceRefs().stream()
                .map(ContextReference::targetPath)
                .toList());
            metadata.put("reopen_candidate_count", capsule.evidenceRefs().size());
            metadata.put("target_path", capsule.evidenceRefs().getFirst().targetPath());
        }
        if (!capsule.nextFollowups().isEmpty()) {
            metadata.put("next_followups", capsule.nextFollowups());
        }
        return new ContextObject(
            task.id() + ":" + capsule.id(),
            capsule.scopePath(),
            ContextObjectType.CAPSULE,
            taskPath,
            "Reopen Capsule",
            preview(firstNonBlank(capsule.outcome(), joinSummary(capsule.nextFollowups().toArray(String[]::new)))),
            preview(multiline(
                "outcome", capsule.outcome(),
                "key_decisions", joinList(capsule.keyDecisions()),
                "reusable_findings", joinList(capsule.reusableFindings()),
                "unresolved_risks", joinList(capsule.unresolvedRisks()),
                "reopen_candidate_paths", joinList(capsule.evidenceRefs().stream()
                    .map(ContextReference::targetPath)
                    .toList()),
                "next_followups", joinList(capsule.nextFollowups())
            )),
            task.updatedAt(),
            capsule.retentionState(),
            List.copyOf(capsule.evidenceRefs()),
            List.of(),
            metadata
        );
    }

    public ContextObject retrievalPolicyCapsule(Task task,
                                                String capsuleId,
                                                String scopePath,
                                                String outcome,
                                                List<String> keyDecisions,
                                                List<String> reusableFindings,
                                                List<String> unresolvedRisks,
                                                List<String> candidatePaths,
                                                List<String> nextFollowups,
                                                boolean needsArchiveRetrieval,
                                                boolean needsExternalFactRefresh) {
        if (task == null || capsuleId == null || capsuleId.isBlank()) {
            return null;
        }
        String taskPath = taskPath(task);
        ContextCapsule capsule = new ContextCapsule(
            capsuleId,
            firstNonBlank(scopePath, taskPath + "/archive/retrieval_policy"),
            outcome,
            copyList(keyDecisions),
            copyList(reusableFindings),
            copyList(unresolvedRisks),
            reopenCandidateRefs(candidatePaths),
            copyList(nextFollowups),
            ContextRetentionState.COLD_CAPSULE
        );
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capsule_scope_path", capsule.scopePath());
        metadata.put("retention_state", capsule.retentionState().wireName());
        if (needsArchiveRetrieval) {
            metadata.put("needs_archive_retrieval", true);
        }
        if (needsExternalFactRefresh) {
            metadata.put("needs_external_fact_refresh", true);
        }
        if (!capsule.keyDecisions().isEmpty()) {
            metadata.put("key_decisions", capsule.keyDecisions());
        }
        if (!capsule.reusableFindings().isEmpty()) {
            metadata.put("reusable_findings", capsule.reusableFindings());
        }
        if (!capsule.unresolvedRisks().isEmpty()) {
            metadata.put("unresolved_risks", capsule.unresolvedRisks());
        }
        if (!capsule.evidenceRefs().isEmpty()) {
            List<String> paths = capsule.evidenceRefs().stream()
                .map(ContextReference::targetPath)
                .toList();
            metadata.put("reopen_candidate_paths", paths);
            metadata.put("retrieval_candidate_paths", paths);
            metadata.put("reopen_candidate_count", capsule.evidenceRefs().size());
            metadata.put("retrieval_candidate_count", capsule.evidenceRefs().size());
            metadata.put("target_path", capsule.evidenceRefs().getFirst().targetPath());
        }
        if (!capsule.nextFollowups().isEmpty()) {
            metadata.put("next_followups", capsule.nextFollowups());
        }
        return new ContextObject(
            task.id() + ":" + capsule.id(),
            capsule.scopePath(),
            ContextObjectType.CAPSULE,
            taskPath,
            "Retrieval Policy Capsule",
            preview(firstNonBlank(capsule.outcome(), joinSummary(capsule.nextFollowups().toArray(String[]::new)))),
            preview(multiline(
                "outcome", capsule.outcome(),
                "needs_archive_retrieval", needsArchiveRetrieval ? "true" : null,
                "needs_external_fact_refresh", needsExternalFactRefresh ? "true" : null,
                "key_decisions", joinList(capsule.keyDecisions()),
                "reusable_findings", joinList(capsule.reusableFindings()),
                "unresolved_risks", joinList(capsule.unresolvedRisks()),
                "retrieval_candidate_paths", joinList(capsule.evidenceRefs().stream()
                    .map(ContextReference::targetPath)
                    .toList()),
                "next_followups", joinList(capsule.nextFollowups())
            )),
            task.updatedAt(),
            capsule.retentionState(),
            List.copyOf(capsule.evidenceRefs()),
            List.of(),
            metadata
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                typed.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return typed;
    }

    private List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        return fallback != null ? fallback : List.of();
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

    private String workerTransition(String previousWorker, String targetWorker) {
        String from = firstNonBlank(previousWorker);
        String to = firstNonBlank(targetWorker);
        if (from == null && to == null) {
            return null;
        }
        if (from == null) {
            return to;
        }
        if (to == null) {
            return from;
        }
        if (from.equals(to)) {
            return from;
        }
        return from + " -> " + to;
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

    private boolean metadataBoolean(Map<String, Object> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return false;
        }
        Object raw = payload.get(key);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return raw != null && Boolean.parseBoolean(raw.toString());
    }

    private String reopenCandidateLabel(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return null;
        }
        String[] tokens = targetPath.split("/");
        if (tokens.length == 0) {
            return targetPath;
        }
        String tail = tokens[tokens.length - 1];
        if (tail == null || tail.isBlank()) {
            return targetPath;
        }
        if ("messages".equals(tail) || "artifacts".equals(tail) || "tool_invocations".equals(tail) || "decisions".equals(tail)) {
            return tail;
        }
        if (tokens.length >= 2) {
            String parent = tokens[tokens.length - 2];
            if ("checkpoints".equals(parent) || "packets".equals(parent)) {
                return parent + ":" + tail;
            }
        }
        return tail;
    }

    private List<ContextReference> reopenCandidateRefs(List<String> reopenCandidatePaths) {
        if (reopenCandidatePaths == null || reopenCandidatePaths.isEmpty()) {
            return List.of();
        }
        List<ContextReference> refs = new ArrayList<>();
        for (String reopenCandidatePath : reopenCandidatePaths) {
            if (reopenCandidatePath == null || reopenCandidatePath.isBlank()) {
                continue;
            }
            refs.add(new ContextReference(
                "reopen_candidate",
                reopenCandidatePath,
                reopenCandidateLabel(reopenCandidatePath)
            ));
        }
        return List.copyOf(refs);
    }

    private List<String> copyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
