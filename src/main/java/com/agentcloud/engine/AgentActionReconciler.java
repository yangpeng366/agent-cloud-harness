package com.agentcloud.engine;

import com.agentcloud.model.AgentAction;
import com.agentcloud.model.AgentActionDecision;
import com.agentcloud.model.AgentActionDraft;
import com.agentcloud.model.AgentActionReconciliationResult;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Event;
import com.agentcloud.model.Relation;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.AgentActionDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.RelationDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.worker.WorkerExecutionResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第一版 bounded autonomy 协调器。
 * 将 worker 提出的 action proposal 校验、落库，并执行低风险副作用。
 */
public class AgentActionReconciler {
    private final EventDao eventDao;
    private final ArtifactDao artifactDao;
    private final AgentActionDao agentActionDao;
    private final CheckpointDao checkpointDao;
    private final TaskDao taskDao;
    private final RelationDao relationDao;

    public AgentActionReconciler(EventDao eventDao, ArtifactDao artifactDao) {
        this(eventDao, artifactDao, null, null, null, null);
    }

    public AgentActionReconciler(EventDao eventDao, ArtifactDao artifactDao,
                                 CheckpointDao checkpointDao, TaskDao taskDao,
                                 RelationDao relationDao) {
        this(eventDao, artifactDao, null, checkpointDao, taskDao, relationDao);
    }

    public AgentActionReconciler(EventDao eventDao, ArtifactDao artifactDao,
                                 AgentActionDao agentActionDao, CheckpointDao checkpointDao,
                                 TaskDao taskDao, RelationDao relationDao) {
        this.eventDao = eventDao;
        this.artifactDao = artifactDao;
        this.agentActionDao = agentActionDao;
        this.checkpointDao = checkpointDao;
        this.taskDao = taskDao;
        this.relationDao = relationDao;
    }

    public AgentActionReconciliationResult reconcile(Task task, WorkerExecutionResult result) {
        if (task == null || result == null) {
            return AgentActionReconciliationResult.empty();
        }
        List<AgentActionDraft> drafts = extractDrafts(result);
        if (drafts.isEmpty()) {
            return AgentActionReconciliationResult.empty();
        }

        ArrayList<AgentActionDecision> decisions = new ArrayList<>();
        ArrayList<AgentAction> accepted = new ArrayList<>();
        ArrayList<AgentAction> rejected = new ArrayList<>();
        ArrayList<AgentAction> approvalNeeded = new ArrayList<>();

        for (AgentActionDraft draft : drafts) {
            AgentActionDecision decision = reconcileOne(task, result, draft);
            decisions.add(decision);
            AgentAction action = decision.action();
            if (action == null) {
                continue;
            }
            persistAction(action);
            switch (action.status()) {
                case "accepted" -> {
                    accepted.add(action);
                    applyAcceptedSideEffect(task, action);
                }
                case "needs_approval" -> approvalNeeded.add(action);
                default -> rejected.add(action);
            }
            emitActionEvent(task, action, decision);
        }

        return new AgentActionReconciliationResult(
            List.copyOf(decisions),
            List.copyOf(accepted),
            List.copyOf(rejected),
            List.copyOf(approvalNeeded)
        );
    }

    private void persistAction(AgentAction action) {
        if (agentActionDao != null) {
            agentActionDao.insert(action);
        }
    }

    private List<AgentActionDraft> extractDrafts(WorkerExecutionResult result) {
        ArrayList<AgentActionDraft> drafts = new ArrayList<>();
        if (result.proposedActions() != null) {
            drafts.addAll(result.proposedActions());
        }
        if (result.contextRequests() != null) {
            for (String request : result.contextRequests()) {
                if (request != null && !request.isBlank()) {
                    drafts.add(new AgentActionDraft(
                        "REQUEST_CONTEXT",
                        "Context requested",
                        Map.of("needed_context", request),
                        "low",
                        false,
                        request,
                        result.confidence()
                    ));
                }
            }
        }
        if (result.completionClaim() != null && !result.completionClaim().isBlank()) {
            drafts.add(new AgentActionDraft(
                "MARK_COMPLETE",
                "Worker claims completion",
                Map.of("completion_claim", result.completionClaim()),
                "medium",
                false,
                result.completionClaim(),
                result.confidence()
            ));
        }
        if (result.handoffTarget() != null && !result.handoffTarget().isBlank()) {
            drafts.add(new AgentActionDraft(
                "HANDOFF",
                "Worker requests handoff",
                Map.of("to_worker", result.handoffTarget()),
                "medium",
                false,
                "Worker requested handoff to " + result.handoffTarget(),
                result.confidence()
            ));
        }
        return drafts;
    }

    private AgentActionDecision reconcileOne(Task task, WorkerExecutionResult result, AgentActionDraft draft) {
        String type = normalizeActionType(draft.actionType());
        String validationError = validationError(task, type, draft);
        String status;
        String reason;
        if (validationError != null) {
            status = "rejected";
            reason = validationError;
        } else if (requiresApproval(draft)) {
            status = "needs_approval";
            reason = "approval_required";
        } else {
            status = "accepted";
            reason = "accepted_by_runtime_policy";
        }
        AgentAction action = new AgentAction(
            IdGenerator.newId("act"),
            task.sessionId(),
            task.id(),
            stringValue(result.metadata().get("execution_id")),
            type,
            status,
            firstNonBlank(draft.summary(), type.toLowerCase(Locale.ROOT)),
            draft.payload(),
            normalizeRisk(draft.riskLevel()),
            requiresApproval(draft),
            "accepted".equals(status) ? "runtime_policy" : "",
            "rejected".equals(status) ? reason : "",
            Instant.now(),
            Instant.now(),
            metadataOf(
                "decision_reason", reason,
                "source", "worker_execution_result",
                "confidence", draft.confidence(),
                "risk_flags", result.riskFlags()
            )
        );
        return new AgentActionDecision(draft, statusDecision(status), reason, action);
    }

    private String validationError(Task task, String type, AgentActionDraft draft) {
        if (type.isBlank()) {
            return "missing_action_type";
        }
        if (List.of("done", "failed").contains(task.status())) {
            return "invalid_task_state";
        }
        Map<String, Object> payload = draft.payload();
        return switch (type) {
            case "WRITE_ARTIFACT" -> payloadValue(payload, "title") == null && payloadValue(payload, "content") == null
                ? "missing_artifact_payload" : null;
            case "HANDOFF" -> payloadValue(payload, "to_worker") == null
                ? "missing_handoff_target" : null;
            case "REQUEST_CONTEXT" -> payloadValue(payload, "needed_context") == null && draft.reason().isBlank()
                ? "missing_context_request" : null;
            case "SPAWN_SUBTASK" -> payloadValue(payload, "title") == null || payloadValue(payload, "goal") == null
                ? "missing_subtask_payload" : null;
            case "ASK_HUMAN", "MARK_BLOCKED" -> draft.reason().isBlank() && payload.isEmpty()
                ? "missing_reason" : null;
            case "CHECKPOINT", "MARK_COMPLETE" -> null;
            default -> "unsupported_action";
        };
    }

    private boolean requiresApproval(AgentActionDraft draft) {
        String risk = normalizeRisk(draft.riskLevel());
        return Boolean.TRUE.equals(draft.requiresApproval())
            || "high".equals(risk)
            || "critical".equals(risk);
    }

    private void applyAcceptedSideEffect(Task task, AgentAction action) {
        switch (action.actionType()) {
            case "WRITE_ARTIFACT" -> applyWriteArtifact(task, action);
            case "CHECKPOINT" -> applyCheckpoint(task, action);
            case "SPAWN_SUBTASK" -> applySpawnSubtask(task, action);
            default -> {
                // 主 task 状态迁移由 ControlNodeGraph 在同一调度流内处理，避免被旧状态覆盖。
            }
        }
    }

    private void applyWriteArtifact(Task task, AgentAction action) {
        if (artifactDao == null) {
            return;
        }
        String title = firstNonBlank(payloadValue(action.payload(), "title"), action.summary(), "Agent Action Artifact");
        String content = payloadValue(action.payload(), "content");
        String artifactType = firstNonBlank(payloadValue(action.payload(), "artifact_type"), "agent_action_artifact");
        artifactDao.insert(new Artifact(
            IdGenerator.newId("art"),
            task.sessionId(),
            task.id(),
            Instant.now(),
            artifactType,
            title,
            null,
            null,
            firstNonBlank(content, title),
            metadataOf(
                "source_action_id", action.id(),
                "source_action_type", action.actionType(),
                "agent_action_status", action.status()
            )
        ));
    }

    private void applyCheckpoint(Task task, AgentAction action) {
        if (checkpointDao == null) {
            return;
        }
        String checkpointType = firstNonBlank(payloadValue(action.payload(), "checkpoint_type"), "agent_action");
        checkpointDao.insert(new Checkpoint(
            IdGenerator.newId("chk"),
            task.sessionId(),
            task.id(),
            Instant.now(),
            checkpointType,
            firstNonBlank(action.summary(), "Agent action checkpoint"),
            metadataOf(
                "agent_action", actionToMap(action),
                "task_id", task.id(),
                "checkpoint_source", "agent_action"
            ),
            metadataOf(
                "source_action_id", action.id(),
                "source_action_type", action.actionType()
            ),
            metadataOf(
                "source_action_id", action.id(),
                "source_action_type", action.actionType(),
                "agent_action_status", action.status()
            )
        ));
    }

    private void applySpawnSubtask(Task task, AgentAction action) {
        if (taskDao == null) {
            return;
        }
        String title = firstNonBlank(payloadValue(action.payload(), "title"), "Agent Subtask");
        String goal = payloadValue(action.payload(), "goal");
        String priority = firstNonBlank(payloadValue(action.payload(), "priority"), task.priority(), "medium");
        String taskType = payloadValue(action.payload(), "task_type");
        String assignedWorker = payloadValue(action.payload(), "assigned_worker");
        String nextStep = payloadValue(action.payload(), "next_step");
        String childId = IdGenerator.newId("task");
        Task child = new Task(
            childId,
            task.sessionId(),
            task.id(),
            title,
            "pending",
            priority,
            Instant.now(),
            Instant.now(),
            null,
            null,
            "agent_action",
            action.summary(),
            goal,
            nextStep,
            assignedWorker,
            "intake",
            null,
            metadataOf(
                "task_type", taskType,
                "source_action_id", action.id(),
                "source_action_type", action.actionType(),
                "parent_task_id", task.id()
            )
        );
        taskDao.insert(child);
        if (relationDao != null) {
            relationDao.insert(new Relation(
                IdGenerator.newId("rel"),
                "task",
                task.id(),
                "spawns",
                "task",
                childId,
                Instant.now(),
                metadataOf(
                    "source_action_id", action.id(),
                    "source_action_type", action.actionType()
                )
            ));
        }
    }

    private void emitActionEvent(Task task, AgentAction action, AgentActionDecision decision) {
        if (eventDao == null) {
            return;
        }
        eventDao.insert(new Event(
            IdGenerator.newId("evt"),
            task.sessionId(),
            task.id(),
            Instant.now(),
            "agent_action_" + action.status(),
            "runtime_policy",
            null,
            action.actionType() + " " + action.status() + ": " + action.summary(),
            metadataOf(
                "action", actionToMap(action),
                "decision", decision.decision(),
                "reason", decision.reason()
            )
        ));
    }

    public static List<Map<String, Object>> actionMaps(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream().map(AgentActionReconciler::actionToMap).toList();
    }

    public static List<Map<String, Object>> draftMaps(List<AgentActionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream().map(AgentActionReconciler::draftToMap).toList();
    }

    private static Map<String, Object> actionToMap(AgentAction action) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "id", action.id());
        putIfPresent(map, "action_type", action.actionType());
        putIfPresent(map, "status", action.status());
        putIfPresent(map, "summary", action.summary());
        putIfPresent(map, "payload", action.payload());
        putIfPresent(map, "risk_level", action.riskLevel());
        putIfPresent(map, "requires_approval", action.requiresApproval());
        putIfPresent(map, "accepted_by", action.acceptedBy());
        putIfPresent(map, "rejection_reason", action.rejectionReason());
        putIfPresent(map, "created_at", action.createdAt() != null ? action.createdAt().toString() : null);
        putIfPresent(map, "metadata", action.metadata());
        return map;
    }

    private static Map<String, Object> draftToMap(AgentActionDraft draft) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "action_type", normalizeActionType(draft.actionType()));
        putIfPresent(map, "summary", draft.summary());
        putIfPresent(map, "payload", draft.payload());
        putIfPresent(map, "risk_level", normalizeRisk(draft.riskLevel()));
        putIfPresent(map, "requires_approval", draft.requiresApproval());
        putIfPresent(map, "reason", draft.reason());
        putIfPresent(map, "confidence", draft.confidence());
        return map;
    }

    private static String normalizeActionType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String normalizeRisk(String value) {
        String normalized = value == null || value.isBlank() ? "low" : value.trim().toLowerCase(Locale.ROOT);
        return List.of("low", "medium", "high", "critical").contains(normalized) ? normalized : "medium";
    }

    private static String statusDecision(String status) {
        return switch (status) {
            case "accepted" -> "accept";
            case "needs_approval" -> "needs_approval";
            default -> "reject";
        };
    }

    private static String payloadValue(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Object value = payload.get(key);
        String text = value == null ? null : value.toString().trim();
        return text == null || text.isBlank() ? null : text;
    }

    private static Map<String, Object> metadataOf(Object... entries) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (entries == null) {
            return metadata;
        }
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object key = entries[i];
            Object value = entries[i + 1];
            if (key != null && value != null) {
                metadata.put(key.toString(), value);
            }
        }
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
