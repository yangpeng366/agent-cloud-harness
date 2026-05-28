package com.agentcloud.engine;

import com.agentcloud.engine.memory.AgentActionContinuityProjection;
import com.agentcloud.model.*;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.RuntimeFactSurfaceExporter;
import com.agentcloud.runtime.context.PromptRenderingMode;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidation Layer - 离线巩固层
 * 负责：Reactivation / Selection / Compression / Abstraction / Integration
 */
public class ConsolidationService {
    private static final Logger log = LoggerFactory.getLogger(ConsolidationService.class);
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final EventDao eventDao;
    private final CheckpointDao checkpointDao;
    private final TaskDao taskDao;
    private final ResumePacketDao resumePacketDao;
    private final RuntimeFactSetAssembler runtimeFactSetAssembler;
    private final RuntimeFactSurfaceExporter runtimeFactSurfaceExporter;
    private final AgentActionDao agentActionDao;

    public ConsolidationService(DecisionDao decisionDao, ArtifactDao artifactDao, EventDao eventDao,
                                CheckpointDao checkpointDao, TaskDao taskDao) {
        this(decisionDao, artifactDao, eventDao, checkpointDao, taskDao, null, null, null, null);
    }

    public ConsolidationService(DecisionDao decisionDao, ArtifactDao artifactDao, EventDao eventDao,
                                CheckpointDao checkpointDao, TaskDao taskDao, ResumePacketDao resumePacketDao) {
        this(decisionDao, artifactDao, eventDao, checkpointDao, taskDao, resumePacketDao, null, null, null);
    }

    public ConsolidationService(DecisionDao decisionDao, ArtifactDao artifactDao, EventDao eventDao,
                                CheckpointDao checkpointDao, TaskDao taskDao, ResumePacketDao resumePacketDao,
                                RuntimeFactSetAssembler runtimeFactSetAssembler,
                                RuntimeFactSurfaceExporter runtimeFactSurfaceExporter) {
        this(decisionDao, artifactDao, eventDao, checkpointDao, taskDao, resumePacketDao,
            runtimeFactSetAssembler, runtimeFactSurfaceExporter, null);
    }

    public ConsolidationService(DecisionDao decisionDao, ArtifactDao artifactDao, EventDao eventDao,
                                CheckpointDao checkpointDao, TaskDao taskDao, ResumePacketDao resumePacketDao,
                                RuntimeFactSetAssembler runtimeFactSetAssembler,
                                RuntimeFactSurfaceExporter runtimeFactSurfaceExporter,
                                AgentActionDao agentActionDao) {
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.eventDao = eventDao;
        this.checkpointDao = checkpointDao;
        this.taskDao = taskDao;
        this.resumePacketDao = resumePacketDao;
        this.runtimeFactSetAssembler = runtimeFactSetAssembler != null ? runtimeFactSetAssembler : new RuntimeFactSetAssembler();
        this.runtimeFactSurfaceExporter = runtimeFactSurfaceExporter != null
            ? runtimeFactSurfaceExporter
            : new RuntimeFactSurfaceExporter();
        this.agentActionDao = agentActionDao;
    }

    public Checkpoint consolidate(Task task, String triggerType) {
        log.info("[Consolidation] task={} trigger={}", task.id(), triggerType);

        // Step 1: Reactivation - 收集最近轨迹
        List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 20);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 20);
        List<Event> events = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 50);
        List<AgentAction> actions = loadRecentActions(task, 20);

        // Step 2: Selection - 筛出高价值项
        List<String> keyDecisions = decisions.stream()
            .filter(d -> d.impactLevel() != null && List.of("high", "critical").contains(d.impactLevel()))
            .map(Decision::summary)
            .filter(summary -> summary != null && !summary.isBlank())
            .limit(5)
            .toList();

        List<String> keyArtifacts = artifacts.stream()
            .map(this::artifactLine)
            .filter(line -> line != null && !line.isBlank())
            .limit(5)
            .toList();

        List<String> openQuestions = collectOpenQuestions(task, decisions);

        List<String> keyConstraints = collectKeyConstraints(task);

        List<String> nextCandidates = new ArrayList<>();
        addIfPresent(nextCandidates, task.nextStep());
        decisions.stream()
            .map(this::extractNextCandidate)
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .distinct()
            .limit(3)
            .forEach(nextCandidates::add);
        nextCandidates = nextCandidates.stream().distinct().limit(3).toList();

        List<String> repeatedFailureHints = decisions.stream()
            .map(Decision::rationale)
            .filter(this::looksFailureHint)
            .distinct()
            .limit(3)
            .toList();

        // Step 3: Compression - 合并重复/低价值噪声
        String summary = buildConsolidationSummary(task, decisions.size(), artifacts.size(), events.size(),
            openQuestions, nextCandidates, repeatedFailureHints);

        // Step 4: Abstraction - 提取结构化关系
        List<Map<String, String>> newRelations = new ArrayList<>();
        for (Decision d : decisions) {
            Map<String, String> rel = new HashMap<>();
            rel.put("type", "decision");
            rel.put("summary", d.summary());
            rel.put("impact", d.impactLevel());
            newRelations.add(rel);
        }

        // Step 5: Integration - 输出 refined packet + world model delta
        Map<String, Object> refinedPacket = new LinkedHashMap<>();
        refinedPacket.put("packet_type", "checkpoint_refined_packet");
        refinedPacket.put("packet_version", "1.0");
        refinedPacket.put("machine_readable_first", true);
        refinedPacket.put("task_identity", buildTaskIdentity(task));
        refinedPacket.put("current_objective", firstNonBlank(task.goal(), task.nextStep(), task.title()));
        refinedPacket.put("current_status", task.status());
        refinedPacket.put("current_node", task.controlNode());
        refinedPacket.put("assigned_worker", task.assignedWorker());
        refinedPacket.put("latest_summary", resolveLatestSummary(task, artifacts, decisions));
        refinedPacket.put("next_step", task.nextStep());
        refinedPacket.put("blockers", resolveBlockers(task, decisions));
        refinedPacket.put("recent_artifacts", artifacts.stream().limit(5).map(this::toArtifactRef).toList());
        refinedPacket.put("recent_decisions", decisions.stream().limit(5).map(this::toDecisionRef).toList());
        refinedPacket.put("task_id", task.id());
        refinedPacket.put("trigger", triggerType);
        refinedPacket.put("key_decisions", keyDecisions);
        refinedPacket.put("key_artifacts", keyArtifacts);
        refinedPacket.put("open_questions", openQuestions);
        refinedPacket.put("key_constraints", keyConstraints);
        refinedPacket.put("next_candidates", nextCandidates);
        refinedPacket.put("repeated_failure_hints", repeatedFailureHints);
        refinedPacket.put("task_summary", firstNonBlank(task.summary(), task.title()));
        refinedPacket.put("assigned_worker", task.assignedWorker());
        putPromptModeContinuityFields(refinedPacket, task);
        attachRuntimeFactSurface(refinedPacket, task);
        refinedPacket.putAll(AgentActionContinuityProjection.from(actions));
        refinedPacket.put("consolidated_at", Instant.now().toString());

        Map<String, Object> worldModelDelta = new HashMap<>();
        worldModelDelta.put("new_relations", newRelations);
        worldModelDelta.put("stale_items", List.of());
        worldModelDelta.put("open_questions", openQuestions);
        worldModelDelta.put("next_candidates", nextCandidates);

        Checkpoint cp = new Checkpoint(
            IdGenerator.newId("cp"), task.sessionId(), task.id(), Instant.now(),
            triggerType, summary, refinedPacket, worldModelDelta,
            Map.of(
                "decision_count", decisions.size(),
                "artifact_count", artifacts.size(),
                "event_count", events.size(),
                "action_count", actions.size()
            )
        );
        checkpointDao.insert(cp);

        log.info("[Consolidation] checkpoint={} created for task={}", cp.id(), task.id());
        return cp;
    }

    public java.util.List<com.agentcloud.model.Checkpoint> listByTask(String taskId, int limit) {
        return checkpointDao.listByTask(taskId, limit);
    }

    private List<String> collectKeyConstraints(Task task) {
        List<String> constraints = new ArrayList<>();
        addIfPresent(constraints, task.priority() != null ? "priority=" + task.priority() : null);
        addIfPresent(constraints, task.assignedWorker() != null ? "assigned_worker=" + task.assignedWorker() : null);
        addIfPresent(constraints, task.waitingReason() != null ? "waiting_reason=" + task.waitingReason() : null);
        if (task.metadata() != null) {
            addIfPresent(constraints, valueLine("task_type", task.metadata().get("task_type")));
            addIfPresent(constraints, valueLine("intent", task.metadata().get("intent")));
            addIfPresent(constraints, valueLine("source", task.metadata().get("source")));
        }
        return constraints.stream().distinct().limit(5).toList();
    }

    private List<String> collectOpenQuestions(Task task, List<Decision> decisions) {
        List<String> items = new ArrayList<>(metadataStringList(task.metadata(), "open_questions"));
        decisions.stream()
            .map(decision -> metadataString(decision.metadata(), "open_question"))
            .filter(value -> value != null && !value.isBlank())
            .forEach(items::add);
        decisions.stream()
            .map(Decision::rationale)
            .filter(this::looksOpenQuestion)
            .forEach(items::add);
        return items.stream().distinct().limit(3).toList();
    }

    private List<String> resolveBlockers(Task task, List<Decision> decisions) {
        List<String> items = new ArrayList<>(metadataStringList(task.metadata(), "blockers"));
        addIfPresent(items, task.waitingReason());
        if ("paused".equalsIgnoreCase(task.status())) {
            items.add("task_paused");
        }
        if ("waiting_human".equalsIgnoreCase(task.status())) {
            items.add("awaiting_human_confirmation");
        }
        decisions.stream()
            .map(decision -> metadataString(decision.metadata(), "blocker"))
            .filter(value -> value != null && !value.isBlank())
            .forEach(items::add);
        return items.stream().distinct().limit(5).toList();
    }

    private PacketTaskIdentity buildTaskIdentity(Task task) {
        return new PacketTaskIdentity(
            task.id(),
            task.sessionId(),
            task.parentTaskId(),
            task.title(),
            metadataString(task.metadata(), "task_type")
        );
    }

    private PacketArtifactRef toArtifactRef(Artifact artifact) {
        return new PacketArtifactRef(
            artifact.artifactType(),
            artifact.title(),
            artifact.summary(),
            artifact.createdAt() != null ? artifact.createdAt().toString() : null
        );
    }

    private PacketDecisionRef toDecisionRef(Decision decision) {
        return new PacketDecisionRef(
            decision.decisionType(),
            decision.summary(),
            decision.rationale(),
            decision.createdAt() != null ? decision.createdAt().toString() : null
        );
    }

    private String resolveLatestSummary(Task task, List<Artifact> artifacts, List<Decision> decisions) {
        return firstNonBlank(
            task.summary(),
            artifacts.stream()
                .map(artifact -> firstNonBlank(artifact.summary(), artifact.title()))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null),
            decisions.stream()
                .map(Decision::summary)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null),
            task.goal(),
            task.title()
        );
    }

    private String buildConsolidationSummary(Task task, int decisionCount, int artifactCount, int eventCount,
                                             List<String> openQuestions, List<String> nextCandidates,
                                             List<String> repeatedFailureHints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task [").append(task.title()).append("] consolidated. ")
            .append(decisionCount).append(" decisions, ")
            .append(artifactCount).append(" artifacts, ")
            .append(eventCount).append(" events reviewed.");
        if (!openQuestions.isEmpty()) {
            sb.append(" Open questions: ").append(String.join(" | ", openQuestions)).append(".");
        }
        if (!nextCandidates.isEmpty()) {
            sb.append(" Next candidates: ").append(String.join(" | ", nextCandidates)).append(".");
        }
        if (!repeatedFailureHints.isEmpty()) {
            sb.append(" Failure hints: ").append(String.join(" | ", repeatedFailureHints)).append(".");
        }
        return sb.toString();
    }

    private String artifactLine(Artifact artifact) {
        if (artifact == null) {
            return null;
        }
        String title = firstNonBlank(artifact.title(), artifact.artifactType());
        String summary = firstNonBlank(artifact.summary());
        if (title == null) {
            return summary;
        }
        return summary == null ? title : title + ": " + summary;
    }

    private String extractNextCandidate(Decision decision) {
        if (decision == null) {
            return null;
        }
        if (decision.metadata() != null) {
            Object nextStep = decision.metadata().get("next_step");
            if (nextStep != null && !nextStep.toString().isBlank()) {
                return nextStep.toString();
            }
            Object suggested = decision.metadata().get("suggested_next_action");
            if (suggested != null && !suggested.toString().isBlank()) {
                return suggested.toString();
            }
        }
        return null;
    }

    private boolean looksOpenQuestion(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return value.contains("?") || normalized.contains("need") || normalized.contains("clarif")
            || normalized.contains("unclear");
    }

    private boolean looksFailureHint(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("fail") || normalized.contains("error")
            || normalized.contains("misalign") || normalized.contains("blocked");
    }

    private String valueLine(String key, Object value) {
        return value == null || value.toString().isBlank() ? null : key + "=" + value;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object raw = metadata.get(key);
        if (raw instanceof List<?> values) {
            List<String> items = new ArrayList<>();
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    items.add(value.toString());
                }
            }
            return items;
        }
        if (raw != null && !raw.toString().isBlank()) {
            return List.of(raw.toString());
        }
        return List.of();
    }

    private void addIfPresent(List<String> items, String value) {
        if (value != null && !value.isBlank()) {
            items.add(value);
        }
    }

    private void putPromptModeContinuityFields(Map<String, Object> target, Task task) {
        if (target == null) {
            return;
        }
        String wireName = PromptRenderingMode.resolve(resolvePromptModeSource(task)).wireName();
        target.put("prompt_rendering_mode", wireName);
        target.put("mounted_context_mode", wireName);
        target.put("prompt_mode", wireName);
    }

    private void attachRuntimeFactSurface(Map<String, Object> target, Task task) {
        if (target == null || task == null) {
            return;
        }
        RuntimeFactSet factSet = runtimeFactSetAssembler.assemble(task, 10, currentContinuityMetadata(task));
        Map<String, Object> runtimeFacts = runtimeFactSurfaceExporter.exportRuntimeFacts(factSet);
        Map<String, Object> runtimeCognitionSurface =
            runtimeFactSurfaceExporter.exportRuntimeCognitionSurface(factSet);
        if (!runtimeFacts.isEmpty()) {
            target.put("runtime_facts", runtimeFacts);
        }
        if (!runtimeCognitionSurface.isEmpty()) {
            target.put("runtime_cognition_surface", runtimeCognitionSurface);
        }
    }

    private List<AgentAction> loadRecentActions(Task task, int limit) {
        if (agentActionDao == null || task == null) {
            return List.of();
        }
        return agentActionDao.listByTask(task.id(), limit);
    }

    private Map<String, Object> currentContinuityMetadata(Task task) {
        if (task == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (task.assignedWorker() != null && !task.assignedWorker().isBlank()) {
            metadata.put("selected_worker", task.assignedWorker());
        }
        if (task.metadata() != null) {
            copyMetadata(task.metadata(), metadata, "prompt_rendering_mode");
            copyMetadata(task.metadata(), metadata, "mounted_context_mode");
            copyMetadata(task.metadata(), metadata, "prompt_mode");
            copyMetadata(task.metadata(), metadata, "selected_model_tier");
            copyMetadata(task.metadata(), metadata, "execution_role");
            copyMetadata(task.metadata(), metadata, "selection_scope");
            copyMetadata(task.metadata(), metadata, "candidate_workers");
            copyMetadata(task.metadata(), metadata, "preferred_worker_hint");
            copyMetadata(task.metadata(), metadata, "learning_hint_applied");
            copyMetadata(task.metadata(), metadata, "fallback_reason");
            copyMetadata(task.metadata(), metadata, "route_source");
        }
        return metadata;
    }

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Task resolvePromptModeSource(Task task) {
        if (task == null || resumePacketDao == null) {
            return task;
        }
        if (PromptRenderingMode.resolve(task) != PromptRenderingMode.ACTIVE_CONTEXT_ONLY) {
            return task;
        }
        ResumePacket latestPacket = resumePacketDao.getLatestByTask(task.sessionId(), task.id()).orElse(null);
        if (latestPacket == null || latestPacket.payload() == null || latestPacket.payload().isEmpty()) {
            return task;
        }
        String packetMode = firstNonBlank(
            metadataString(latestPacket.payload(), "prompt_rendering_mode"),
            metadataString(latestPacket.payload(), "mounted_context_mode"),
            metadataString(latestPacket.payload(), "prompt_mode")
        );
        if (packetMode == null) {
            return task;
        }
        Map<String, Object> metadata = task.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(task.metadata());
        metadata.put("prompt_rendering_mode", packetMode);
        metadata.put("mounted_context_mode", packetMode);
        metadata.put("prompt_mode", packetMode);
        return task.withMetadata(metadata);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
