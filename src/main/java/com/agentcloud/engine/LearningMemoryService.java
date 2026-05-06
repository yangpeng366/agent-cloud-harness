package com.agentcloud.engine;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.LearningMemory;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.worker.WorkerExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小 operational learning memory 服务。
 * 当前只沉淀候选经验，并支持按 hint_key 进行强化。
 */
public class LearningMemoryService {
    private static final Logger log = LoggerFactory.getLogger(LearningMemoryService.class);
    private static final double MIN_CANDIDATE_CONFIDENCE = 0.6d;
    private final LearningMemoryDao learningMemoryDao;

    public LearningMemoryService(LearningMemoryDao learningMemoryDao) {
        this.learningMemoryDao = learningMemoryDao;
    }

    public void captureFromExecution(Task task, TaskRuntimeContext runtimeContext, WorkerExecutionResult executionResult,
                                     ExecutionDecision executionDecision, CompletionDecision completionDecision) {
        if (task == null || executionDecision == null || completionDecision == null) {
            return;
        }

        captureCompletionPattern(task, completionDecision, executionResult);
        captureRoutingPreference(task, executionDecision, completionDecision);
        captureWorkerHeuristic(task, executionResult, completionDecision);
        captureContextRetentionHint(task, runtimeContext, executionDecision, completionDecision);
    }

    public List<LearningMemory> listByTask(String taskId, int limit) {
        return learningMemoryDao.listByTask(taskId, limit);
    }

    public List<LearningMemory> listByType(String memoryType, int limit) {
        return learningMemoryDao.listByType(memoryType, limit);
    }

    public String selectPreferredWorker(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return null;
        }
        return learningMemoryDao.listByType("routing_preference", 50).stream()
            .filter(memory -> memory.hintKey() != null)
            .filter(memory -> memory.hintKey().startsWith("routing:" + taskType + ":"))
            .filter(this::isUsableHint)
            .min(this::compareMemoryPriority)
            .map(memory -> memory.hintKey().substring(("routing:" + taskType + ":").length()))
            .filter(workerId -> !workerId.isBlank())
            .orElse(null);
    }

    public List<String> contextRetentionHints(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return List.of();
        }
        return learningMemoryDao.listByType("context_retention_hint", 50).stream()
            .filter(memory -> memory.hintKey() != null)
            .filter(memory -> memory.hintKey().startsWith("context:" + taskType + ":"))
            .filter(this::isUsableHint)
            .sorted(this::compareMemoryPriority)
            .limit(3)
            .map(LearningMemory::summary)
            .filter(summary -> summary != null && !summary.isBlank())
            .toList();
    }

    private void captureCompletionPattern(Task task, CompletionDecision completionDecision, WorkerExecutionResult executionResult) {
        String status = completionDecision.status();
        if (status == null || "done".equalsIgnoreCase(status)) {
            return;
        }

        String workerId = task.assignedWorker() != null ? task.assignedWorker() : "unassigned";
        String hintKey = "completion:" + workerId + ":" + status.toLowerCase();
        String summary = "Worker " + workerId + " ended with completion status " + status
            + " on task type " + taskType(task) + ".";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("task_title", task.title());
        evidence.put("worker_id", workerId);
        evidence.put("task_type", taskType(task));
        evidence.put("completion_status", status);
        evidence.put("alignment_level", completionDecision.alignmentLevel());
        evidence.put("reason", completionDecision.reason());
        evidence.put("output_summary", executionResult != null ? executionResult.summary() : null);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "judgment");
        metadata.put("category", "completion_pattern");

        reinforceOrInsert(task, "completion_pattern", hintKey, summary,
            confidenceFromAlignment(completionDecision.alignmentLevel()), evidence, metadata);
    }

    private void captureRoutingPreference(Task task, ExecutionDecision executionDecision, CompletionDecision completionDecision) {
        String action = executionDecision.action();
        if (!"handoff".equalsIgnoreCase(action) || executionDecision.targetWorker() == null
            || executionDecision.targetWorker().isBlank()) {
            return;
        }

        String taskType = taskType(task);
        String hintKey = "routing:" + taskType + ":" + executionDecision.targetWorker();
        String summary = "Task type " + taskType + " may prefer worker " + executionDecision.targetWorker()
            + " after runtime handoff judgment.";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("task_title", task.title());
        evidence.put("task_type", taskType);
        evidence.put("from_worker", task.assignedWorker());
        evidence.put("target_worker", executionDecision.targetWorker());
        evidence.put("execution_reason", executionDecision.reason());
        evidence.put("completion_status", completionDecision.status());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "execution_judgment");
        metadata.put("category", "routing_preference");

        reinforceOrInsert(task, "routing_preference", hintKey, summary, 0.6d, evidence, metadata);
    }

    private void captureWorkerHeuristic(Task task, WorkerExecutionResult executionResult, CompletionDecision completionDecision) {
        if (executionResult == null || executionResult.confidence() == null
            || !"low".equalsIgnoreCase(executionResult.confidence())) {
            return;
        }

        String workerId = task.assignedWorker() != null ? task.assignedWorker() : "unassigned";
        String hintKey = "worker_low_confidence:" + workerId + ":" + taskType(task);
        String summary = "Worker " + workerId + " produced low-confidence output for task type " + taskType(task) + ".";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("task_title", task.title());
        evidence.put("worker_id", workerId);
        evidence.put("task_type", taskType(task));
        evidence.put("confidence", executionResult.confidence());
        evidence.put("suggested_next_step", executionResult.suggestedNextStep());
        evidence.put("completion_status", completionDecision.status());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "worker_execution");
        metadata.put("category", "worker_heuristic");

        reinforceOrInsert(task, "worker_heuristic", hintKey, summary, 0.55d, evidence, metadata);
    }

    private void captureContextRetentionHint(Task task, TaskRuntimeContext runtimeContext,
                                             ExecutionDecision executionDecision, CompletionDecision completionDecision) {
        if (runtimeContext == null) {
            return;
        }
        if (!executionDecision.needsCheckpoint() && !isRetentionSignal(completionDecision.status())) {
            return;
        }

        ContextRetentionHintCandidate retainedItem = firstMeaningfulContextItem(runtimeContext);
        if (retainedItem == null || retainedItem.item() == null || retainedItem.item().isBlank()) {
            return;
        }

        String taskType = taskType(task);
        String normalized = normalizeHintKey(retainedItem.item());
        if (normalized.isBlank()) {
            return;
        }

        String hintKey = "context:" + taskType + ":" + normalized;
        String summary = "Task type " + taskType + " may need to retain context item: " + shorten(retainedItem.item(), 120);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("task_title", task.title());
        evidence.put("task_type", taskType);
        evidence.put("retained_item", retainedItem.item());
        evidence.put("retained_source", retainedItem.source());
        evidence.put("completion_status", completionDecision.status());
        evidence.put("alignment_level", completionDecision.alignmentLevel());
        evidence.put("execution_action", executionDecision.action());
        putIfNotBlank(evidence, "mounted_context_panel", retainedItem.panel());
        putIfNotBlank(evidence, "mounted_context_retention_state", retainedItem.retentionState());
        putIfNotBlank(evidence, "mounted_context_object_type", retainedItem.objectType());
        putIfNotBlank(evidence, "mounted_context_object_path", retainedItem.objectPath());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", retainedItem.source());
        metadata.put("category", "context_retention_hint");
        putIfNotBlank(metadata, "mounted_context_panel", retainedItem.panel());
        putIfNotBlank(metadata, "mounted_context_retention_state", retainedItem.retentionState());
        putIfNotBlank(metadata, "mounted_context_object_type", retainedItem.objectType());
        if ("mounted_context".equals(retainedItem.source()) && runtimeContext.mountedContextView() != null) {
            metadata.put("mounted_context_selection_trace_count", runtimeContext.mountedContextView().selectionTrace().size());
        }

        reinforceOrInsert(task, "context_retention_hint", hintKey, summary, 0.58d, evidence, metadata);
    }

    private void reinforceOrInsert(Task task, String memoryType, String hintKey, String summary,
                                   double confidenceScore, Map<String, Object> evidence, Map<String, Object> metadata) {
        var existing = learningMemoryDao.findLatestByTypeAndHintKey(memoryType, hintKey);
        if (existing.isPresent()) {
            LearningMemory current = existing.get();
            int newCount = current.reinforcementCount() + 1;
            double newConfidence = Math.min(0.95d, Math.max(current.confidenceScore(), confidenceScore) + 0.05d);
            LearningMemory updated = current.withReinforcement(newConfidence, newCount, evidence, metadata);
            learningMemoryDao.update(updated);
            log.info("[LearningMemory] reinforced id={} type={} hintKey={} count={}",
                updated.id(), updated.memoryType(), updated.hintKey(), updated.reinforcementCount());
            return;
        }

        LearningMemory candidate = new LearningMemory(
            IdGenerator.newId("lm"), task.sessionId(), task.id(), null,
            memoryType, "candidate", hintKey, summary, confidenceScore, 1, evidence, metadata
        );
        learningMemoryDao.insert(candidate);
        log.info("[LearningMemory] created id={} type={} hintKey={}", candidate.id(), candidate.memoryType(), candidate.hintKey());
    }

    private String taskType(Task task) {
        if (task.metadata() == null || task.metadata().get("task_type") == null) {
            return "general";
        }
        return task.metadata().get("task_type").toString();
    }

    private double confidenceFromAlignment(String alignmentLevel) {
        if (alignmentLevel == null) {
            return 0.55d;
        }
        return switch (alignmentLevel.toLowerCase()) {
            case "high" -> 0.75d;
            case "low" -> 0.5d;
            default -> 0.6d;
        };
    }

    private boolean isRetentionSignal(String completionStatus) {
        if (completionStatus == null) {
            return false;
        }
        return switch (completionStatus.toLowerCase()) {
            case "misaligned", "needs_clarification", "partially_done" -> true;
            default -> false;
        };
    }

    private ContextRetentionHintCandidate firstMeaningfulContextItem(TaskRuntimeContext runtimeContext) {
        ContextRetentionHintCandidate mountedCandidate = firstMeaningfulMountedContextItem(runtimeContext);
        if (mountedCandidate != null) {
            return mountedCandidate;
        }

        if (runtimeContext.activeContext() == null) {
            return null;
        }

        String item = firstNonBlank(runtimeContext.activeContext().openQuestions());
        if (item != null) {
            return new ContextRetentionHintCandidate(item, "active_context", null, null, null, null);
        }
        item = firstNonBlank(runtimeContext.activeContext().constraints());
        if (item != null) {
            return new ContextRetentionHintCandidate(item, "active_context", null, null, null, null);
        }
        item = firstNonBlank(runtimeContext.activeContext().keyDecisions());
        if (item != null) {
            return new ContextRetentionHintCandidate(item, "active_context", null, null, null, null);
        }
        item = firstNonBlank(runtimeContext.activeContext().keyArtifacts());
        if (item != null) {
            return new ContextRetentionHintCandidate(item, "active_context", null, null, null, null);
        }
        return null;
    }

    private ContextRetentionHintCandidate firstMeaningfulMountedContextItem(TaskRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.mountedContextView() == null) {
            return null;
        }

        ContextRetentionHintCandidate candidate = firstMeaningfulMountedContextItem(
            runtimeContext.mountedContextView().objects(MountedContextPanelName.PINNED),
            MountedContextPanelName.PINNED
        );
        if (candidate != null) {
            return candidate;
        }

        candidate = firstMeaningfulMountedContextItem(
            runtimeContext.mountedContextView().objects(MountedContextPanelName.ACTIVE),
            MountedContextPanelName.ACTIVE
        );
        if (candidate != null) {
            return candidate;
        }

        return firstMeaningfulMountedContextItem(
            runtimeContext.mountedContextView().objects(MountedContextPanelName.EVIDENCE),
            MountedContextPanelName.EVIDENCE
        );
    }

    private ContextRetentionHintCandidate firstMeaningfulMountedContextItem(List<ContextObject> objects,
                                                                            MountedContextPanelName panelName) {
        if (objects == null || objects.isEmpty()) {
            return null;
        }
        for (ContextObject object : objects) {
            if (object == null) {
                continue;
            }
            String item = firstNonBlankString(object.summary(), object.contentPreview());
            if (item == null || item.isBlank()) {
                continue;
            }
            return new ContextRetentionHintCandidate(
                item,
                "mounted_context",
                panelName != null ? panelName.wireName() : null,
                object.retentionState() != null ? object.retentionState().wireName() : null,
                object.type() != null ? object.type().wireName() : null,
                object.path()
            );
        }
        return null;
    }

    private String firstNonBlank(Iterable<String> values) {
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

    private String firstNonBlankString(String... values) {
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

    private String normalizeHintKey(String value) {
        return value == null ? "" : value.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private int compareMemoryPriority(LearningMemory left, LearningMemory right) {
        int stateCompare = Integer.compare(stateWeight(right.state()), stateWeight(left.state()));
        if (stateCompare != 0) {
            return stateCompare;
        }
        int countCompare = Integer.compare(
            right.reinforcementCount() != null ? right.reinforcementCount() : 0,
            left.reinforcementCount() != null ? left.reinforcementCount() : 0
        );
        if (countCompare != 0) {
            return countCompare;
        }
        return Double.compare(
            right.confidenceScore() != null ? right.confidenceScore() : 0d,
            left.confidenceScore() != null ? left.confidenceScore() : 0d
        );
    }

    private boolean isUsableHint(LearningMemory memory) {
        if (memory == null) {
            return false;
        }
        String state = memory.state();
        if (state == null || state.isBlank()) {
            return false;
        }
        if (!"candidate".equalsIgnoreCase(state)) {
            return true;
        }
        int reinforcementCount = memory.reinforcementCount() != null ? memory.reinforcementCount() : 0;
        double confidenceScore = memory.confidenceScore() != null ? memory.confidenceScore() : 0d;
        return reinforcementCount >= 2 || confidenceScore >= MIN_CANDIDATE_CONFIDENCE;
    }

    private int stateWeight(String state) {
        if (state == null) {
            return 0;
        }
        return switch (state.toLowerCase()) {
            case "stable_hint" -> 3;
            case "reinforced" -> 2;
            case "candidate" -> 1;
            default -> 0;
        };
    }

    private record ContextRetentionHintCandidate(
        String item,
        String source,
        String panel,
        String retentionState,
        String objectType,
        String objectPath
    ) {}
}
