package com.agentcloud.engine.memory;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PacketBuilder {
    private static final Logger log = LoggerFactory.getLogger(PacketBuilder.class);
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final TaskDao taskDao;
    private final ResumePacketDao resumePacketDao;
    private final RuntimeFactSetAssembler runtimeFactSetAssembler;
    private final RuntimeFactSurfaceExporter runtimeFactSurfaceExporter;

    public PacketBuilder(DecisionDao decisionDao, ArtifactDao artifactDao, TaskDao taskDao) {
        this(decisionDao, artifactDao, taskDao, null, null, null);
    }

    public PacketBuilder(DecisionDao decisionDao, ArtifactDao artifactDao, TaskDao taskDao,
                         ResumePacketDao resumePacketDao) {
        this(decisionDao, artifactDao, taskDao, resumePacketDao, null, null);
    }

    public PacketBuilder(DecisionDao decisionDao, ArtifactDao artifactDao, TaskDao taskDao,
                         ResumePacketDao resumePacketDao,
                         RuntimeFactSetAssembler runtimeFactSetAssembler,
                         RuntimeFactSurfaceExporter runtimeFactSurfaceExporter) {
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.taskDao = taskDao;
        this.resumePacketDao = resumePacketDao;
        this.runtimeFactSetAssembler = runtimeFactSetAssembler != null ? runtimeFactSetAssembler : new RuntimeFactSetAssembler();
        this.runtimeFactSurfaceExporter = runtimeFactSurfaceExporter != null
            ? runtimeFactSurfaceExporter
            : new RuntimeFactSurfaceExporter();
    }

    public ResumePacket buildResumePacket(Task task, Session session) {
        List<Decision> decisions = decisionDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(session.id(), task.id(), 10);
        PacketTaskIdentity taskIdentity = buildTaskIdentity(task);
        List<PacketDecisionRef> recentDecisions = decisions.stream()
            .limit(5)
            .map(this::toDecisionRef)
            .toList();
        List<PacketArtifactRef> recentArtifacts = artifacts.stream()
            .limit(5)
            .map(this::toArtifactRef)
            .toList();
        List<String> openQuestions = resolveOpenQuestions(task, decisions);
        List<String> blockers = resolveBlockers(task, decisions);
        String currentObjective = firstNonBlank(task.goal(), task.nextStep(), task.title());
        String latestSummary = resolveLatestSummary(task, artifacts, decisions);

        String decisionSummary = decisions.stream()
            .map(d -> "[" + d.createdAt() + "] " + d.summary())
            .collect(Collectors.joining("\n"));

        String artifactSummary = artifacts.stream()
            .map(a -> a.artifactType() + ": " + a.title())
            .collect(Collectors.joining("\n"));
        String nextStep = resolvePacketNextStep(task);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machine_readable_first", true);
        payload.put("task_identity", taskIdentity);
        payload.put("current_objective", currentObjective);
        payload.put("current_status", task.status());
        payload.put("current_node", task.controlNode());
        payload.put("assigned_worker", task.assignedWorker());
        payload.put("latest_summary", latestSummary);
        payload.put("next_step", nextStep);
        payload.put("blockers", blockers);
        payload.put("open_questions", openQuestions);
        payload.put("recent_artifacts", recentArtifacts);
        payload.put("recent_decisions", recentDecisions);
        payload.put("recent_decision_summaries", decisions.stream().map(Decision::summary).toList());
        payload.put("resume_hint", nextStep);
        payload.put("task_title", task.title());
        payload.put("task_type", metadataString(task.metadata(), "task_type"));
        payload.put("parent_task_id", task.parentTaskId());
        payload.put("session_id", session.id());
        payload.put("session_title", session.title());
        payload.put("active_goal", currentObjective);
        payload.put("task_status", task.status());
        payload.put("relevant_artifacts", artifacts.stream().map(Artifact::title).toList());
        payload.put("key_constraints", List.of());
        putPromptModeContinuityFields(payload, task);
        attachRuntimeFactSurface(payload, task);

        return new ResumePacket(
            java.util.UUID.randomUUID().toString(),
            session.id(), task.id(), Instant.now(), "1.1",
            task.summary(), decisionSummary, artifactSummary,
            openQuestions, nextStep, payload,
            taskIdentity,
            currentObjective,
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            latestSummary,
            blockers,
            recentArtifacts,
            recentDecisions,
            Boolean.TRUE
        );
    }

    public HandoffPacket buildHandoffPacket(Task task, Session session, String fromWorker, String toWorker) {
        List<Decision> decisions = decisionDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Task> subTasks = taskDao.listBySession(session.id()).stream()
            .filter(t -> task.id().equals(t.parentTaskId()))
            .toList();
        PacketTaskIdentity taskIdentity = buildTaskIdentity(task);
        String currentObjective = firstNonBlank(task.goal(), task.nextStep(), task.title());
        String latestSummary = resolveLatestSummary(task, artifacts, decisions);
        List<String> openQuestions = resolveOpenQuestions(task, decisions);
        List<String> cautions = mergeLines(resolveBlockers(task, decisions), openQuestions, 5);
        List<String> whatDone = resolveWhatDone(task, subTasks, artifacts, decisions);
        List<String> whatRemaining = resolveWhatRemaining(task, subTasks);
        String resumeHint = firstNonBlank(resolvePacketNextStep(task), whatRemaining.isEmpty() ? null : whatRemaining.get(0), currentObjective);
        String whyHandoff = deriveWhyHandoff(task, fromWorker, toWorker);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("session_id", session.id());
        metadata.put("priority", task.priority());
        metadata.put("assigned_worker", task.assignedWorker());
        metadata.put("recent_artifact_count", artifacts.size());
        metadata.put("recent_decision_count", decisions.size());
        metadata.put("open_questions", openQuestions);
        copyMetadata(task.metadata(), metadata, "task_type");
        copyMetadata(task.metadata(), metadata, "model_mode");
        copyMetadata(task.metadata(), metadata, "orchestration_stage");
        copyMetadata(task.metadata(), metadata, "planner_worker");
        copyMetadata(task.metadata(), metadata, "executor_worker");
        copyMetadata(task.metadata(), metadata, "selected_model_tier");
        copyMetadata(task.metadata(), metadata, "fallback_reason");
        putPromptModeContinuityFields(metadata, task);
        attachRuntimeFactSurface(metadata, task);

        log.info("Handoff packet built for task={} from={} to={}", task.id(), fromWorker, toWorker);
        return new HandoffPacket(
            "1.0",
            Boolean.TRUE,
            taskIdentity,
            fromWorker,
            toWorker,
            currentObjective,
            task.status(),
            task.controlNode(),
            whyHandoff,
            whatDone,
            whatRemaining,
            cautions,
            resumeHint,
            latestSummary,
            "handoff " + firstNonBlank(fromWorker, "unassigned") + " -> " + firstNonBlank(toWorker, "unassigned")
                + " for task " + firstNonBlank(task.title(), task.id()),
            metadata
        );
    }

    private String resolvePacketNextStep(Task task) {
        if (task == null) {
            return "continue from current task";
        }
        if (isTerminalStatus(task.status())) {
            return null;
        }
        if (task.nextStep() != null && !task.nextStep().isBlank()) {
            return task.nextStep();
        }
        return "continue from current task";
    }

    private boolean isTerminalStatus(String status) {
        if (status == null) {
            return false;
        }
        return List.of("done", "failed").contains(status.toLowerCase());
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

    private PacketDecisionRef toDecisionRef(Decision decision) {
        return new PacketDecisionRef(
            decision.decisionType(),
            decision.summary(),
            decision.rationale(),
            decision.createdAt() != null ? decision.createdAt().toString() : null
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

    private List<String> resolveOpenQuestions(Task task, List<Decision> decisions) {
        List<String> items = new ArrayList<>(metadataStringList(task.metadata(), "open_questions"));
        decisions.stream()
            .map(decision -> metadataString(decision.metadata(), "open_question"))
            .filter(value -> value != null && !value.isBlank())
            .limit(2)
            .forEach(items::add);
        decisions.stream()
            .map(Decision::rationale)
            .filter(this::looksOpenQuestion)
            .limit(2)
            .forEach(items::add);
        if (!isTerminalStatus(task.status()) && (task.nextStep() == null || task.nextStep().isBlank())) {
            items.add("next_step_not_yet_clear");
        }
        return dedupe(items, 5);
    }

    private List<String> resolveBlockers(Task task, List<Decision> decisions) {
        List<String> items = new ArrayList<>(metadataStringList(task.metadata(), "blockers"));
        if (task.waitingReason() != null && !task.waitingReason().isBlank()) {
            items.add(task.waitingReason());
        }
        if ("paused".equalsIgnoreCase(task.status())) {
            items.add("task_paused");
        }
        if ("waiting_human".equalsIgnoreCase(task.status())) {
            items.add("awaiting_human_confirmation");
        }
        decisions.stream()
            .map(decision -> metadataString(decision.metadata(), "blocker"))
            .filter(value -> value != null && !value.isBlank())
            .limit(2)
            .forEach(items::add);
        return dedupe(items, 5);
    }

    private List<String> resolveWhatDone(Task task, List<Task> subTasks, List<Artifact> artifacts, List<Decision> decisions) {
        List<String> items = new ArrayList<>();
        subTasks.stream()
            .filter(subTask -> "done".equalsIgnoreCase(subTask.status()))
            .map(subTask -> firstNonBlank(subTask.summary(), subTask.title()))
            .filter(value -> value != null && !value.isBlank())
            .forEach(items::add);
        artifacts.stream()
            .map(artifact -> firstNonBlank(artifact.summary(), artifact.title()))
            .filter(value -> value != null && !value.isBlank())
            .limit(3)
            .forEach(items::add);
        if (items.isEmpty()) {
            items.add(firstNonBlank(task.summary(), decisions.stream().map(Decision::summary).findFirst().orElse(null), task.title()));
        }
        return dedupe(items, 5);
    }

    private List<String> resolveWhatRemaining(Task task, List<Task> subTasks) {
        List<String> items = new ArrayList<>();
        if (!isTerminalStatus(task.status()) && task.nextStep() != null && !task.nextStep().isBlank()) {
            items.add(task.nextStep());
        }
        subTasks.stream()
            .filter(subTask -> !"done".equalsIgnoreCase(subTask.status()))
            .map(subTask -> firstNonBlank(subTask.nextStep(), subTask.title()))
            .filter(value -> value != null && !value.isBlank())
            .forEach(items::add);
        if (items.isEmpty() && !isTerminalStatus(task.status())) {
            items.add("continue toward objective: " + firstNonBlank(task.goal(), task.title(), task.id()));
        }
        return dedupe(items, 5);
    }

    private String deriveWhyHandoff(Task task, String fromWorker, String toWorker) {
        String explicit = firstNonBlank(
            metadataString(task.metadata(), "handoff_reason"),
            metadataString(task.metadata(), "fallback_reason")
        );
        if (explicit != null) {
            return explicit;
        }
        if ("orchestrated".equalsIgnoreCase(metadataString(task.metadata(), "model_mode"))
            && fromWorker != null && toWorker != null && !fromWorker.equalsIgnoreCase(toWorker)) {
            return "orchestrated runtime delegated execution from " + fromWorker + " to " + toWorker;
        }
        return "handoff to " + firstNonBlank(toWorker, "target worker") + " for continued execution";
    }

    private List<String> mergeLines(List<String> first, List<String> second, int limit) {
        List<String> items = new ArrayList<>();
        if (first != null) {
            items.addAll(first);
        }
        if (second != null) {
            items.addAll(second);
        }
        return dedupe(items, limit);
    }

    private List<String> dedupe(List<String> items, int limit) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (items == null) {
            return List.of();
        }
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            unique.add(item);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private boolean looksOpenQuestion(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("?")
            || normalized.contains("待确认")
            || normalized.contains("unclear")
            || normalized.contains("unknown");
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

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
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
