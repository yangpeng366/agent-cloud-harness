package com.agentcloud.engine;

import com.agentcloud.model.Goal;
import com.agentcloud.model.GoalCreateRequest;
import com.agentcloud.model.GoalEvent;
import com.agentcloud.model.Task;
import com.agentcloud.store.GoalDao;
import com.agentcloud.store.GoalEventDao;
import com.agentcloud.store.JsonMapper;
import com.agentcloud.store.TaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Goal lifecycle service (P0 of GOAL_LOOP_LANDING_PLAN).
 *
 * <p>Goal is a first-class persisted object (goals + goal_events tables). A task is linked to a
 * goal by storing {@code goal_id} in {@code tasks.metadata_json} (payload-first linkage, see plan
 * §4.3). This service owns create/attach/pause/reopen/close, a minimal runtime hook
 * ({@link #onTaskTransition}) that mirrors task transitions into the goal event log, and a
 * goal-level live flow view. Continuation policy (MaybeContinueIfIdle) is deferred to P2.
 */
public class GoalService {
    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private final TaskDao taskDao;
    private final GoalDao goalDao;
    private final GoalEventDao goalEventDao;

    public GoalService(TaskDao taskDao, GoalDao goalDao, GoalEventDao goalEventDao) {
        this.taskDao = taskDao;
        this.goalDao = goalDao;
        this.goalEventDao = goalEventDao;
    }

    public Goal createGoal(GoalCreateRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String id = IdGenerator.newId("goal");
        Goal goal = new Goal(id, req.sessionId(), req.parentGoalId(), req.title(), "open", "active",
            req.sourceTaskId(), null, req.objective(),
            req.successCriteria(), req.constraints(), req.budget(), null, null, 1,
            Instant.now(), Instant.now(), null, req.metadata());
        goalDao.insert(goal);
        recordEvent(id, "goal_created", "Goal created: " + req.title(),
            mapOf("title", req.title(), "objective", req.objective(), "session_id", req.sessionId()));
        log.info("Goal created id={} title={}", id, req.title());
        return goal;
    }

    public Optional<Goal> getGoal(String id) {
        return goalDao.findById(id);
    }

    public List<Goal> listGoals(String sessionId, String status) {
        if (sessionId != null && !sessionId.isBlank() && status != null && !status.isBlank()) {
            return goalDao.listBySessionAndStatus(sessionId, status);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return goalDao.listBySession(sessionId);
        }
        if (status != null && !status.isBlank()) {
            return goalDao.listByStatus(status);
        }
        return goalDao.listRecent(100);
    }

    /** Full attach: write goal_id into task metadata + mark goal active task + event. */
    public Goal attachTask(String goalId, String taskId) {
        Goal goal = goalDao.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("goal not found: " + goalId));
        Task task = taskDao.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        Map<String, Object> meta = task.metadata() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(task.metadata());
        meta.put("goal_id", goalId);
        taskDao.updateMetadata(task.id(), JsonMapper.toJson(meta), Instant.now());
        if (goal.activeTaskId() == null || !goal.activeTaskId().equals(taskId)) {
            goalDao.updateActiveTask(goalId, taskId, Instant.now());
        }
        recordEvent(goalId, "goal_task_attached", "Task attached: " + task.title(),
            mapOf("task_id", taskId, "task_title", task.title()));
        return goalDao.findById(goalId).orElse(goal);
    }

    /**
     * Lightweight attach used by TaskService.createTask: goal_id is already in task metadata, so we
     * only update the goal's active task and emit an event. Failures are swallowed so they never
     * break task creation.
     */
    public void noteTaskAttached(String goalId, String taskId) {
        if (goalId == null || goalId.isBlank()) return;
        try {
            if (goalDao.findById(goalId).isEmpty()) {
                log.warn("noteTaskAttached: goal not found {}, skipping", goalId);
                return;
            }
            goalDao.updateActiveTask(goalId, taskId, Instant.now());
            recordEvent(goalId, "goal_task_attached", "Task attached at creation: " + taskId,
                mapOf("task_id", taskId));
        } catch (Exception e) {
            log.warn("noteTaskAttached failed goal={} task={}: {}", goalId, taskId, e.getMessage());
        }
    }

    public Goal pause(String id) {
        return transition(id, "paused", "active", "goal_paused", "Goal paused", null);
    }

    public Goal reopen(String id) {
        return transition(id, "open", "active", "goal_reopened", "Goal reopened", null);
    }

    /** P0 stub: continuation policy (idle/budget gates) is P2. Reopens if paused, records intent. */
    public Goal continueGoal(String id) {
        Goal goal = goalDao.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("goal not found: " + id));
        if ("paused".equals(goal.status())) {
            goalDao.updateStatus(id, "open", "active", Instant.now());
            recordEvent(id, "goal_continue", "Goal continuation requested (reopened)", mapOf());
        } else {
            recordEvent(id, "goal_continue", "Goal continuation requested",
                mapOf("status", goal.status(), "phase", goal.phase()));
        }
        return goalDao.findById(id).orElse(goal);
    }

    public Goal close(String id, String outcomeSummary) {
        Goal goal = goalDao.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("goal not found: " + id));
        Instant now = Instant.now();
        goalDao.close(id, "closed", "completed", outcomeSummary, now, now);
        recordEvent(id, "goal_closed", "Goal closed: " + firstNonBlank(outcomeSummary, goal.title()),
            mapOf("outcome_summary", outcomeSummary));
        return goalDao.findById(id).orElse(goal);
    }

    /**
     * Minimal runtime hook: called from TaskService.recordTaskStateProjection on task status change.
     * Mirrors the transition into the goal event log and refreshes the goal progress snapshot.
     * Fully guarded: never propagates exceptions into the task runtime.
     */
    public void onTaskTransition(Task previousTask, Task currentTask, String reason) {
        if (currentTask == null) return;
        Map<String, Object> meta = currentTask.metadata();
        String goalId = stringValue(meta == null ? null : meta.get("goal_id"));
        if (goalId == null) return;
        try {
            if (goalDao.findById(goalId).isEmpty()) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_id", currentTask.id());
            payload.put("previous_status", previousTask != null ? previousTask.status() : null);
            payload.put("current_status", currentTask.status());
            if (currentTask.controlNode() != null) payload.put("control_node", currentTask.controlNode());
            if (reason != null && !reason.isBlank()) payload.put("reason", reason);
            Object progressSummary = meta.get("progress_summary");
            if (progressSummary != null) payload.put("progress_summary", progressSummary);
            recordEvent(goalId, "goal_task_transition",
                "Task " + currentTask.id() + " -> " + currentTask.status(), payload);

            Map<String, Object> prog = new LinkedHashMap<>();
            prog.put("last_task_id", currentTask.id());
            prog.put("last_task_status", currentTask.status());
            if (progressSummary != null) prog.put("progress_summary", progressSummary);
            prog.put("updated_at", Instant.now().toString());
            goalDao.updateProgress(goalId, JsonMapper.toJson(prog), currentTask.id(), Instant.now());
        } catch (Exception e) {
            log.warn("onTaskTransition failed goal={} task={}: {}", goalId, currentTask.id(), e.getMessage());
        }
    }

    public Map<String, Object> buildLiveFlow(String goalId) {
        Goal goal = goalDao.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("goal not found: " + goalId));
        List<GoalEvent> events = goalEventDao.listByGoal(goalId, 50);
        List<Task> tasks = taskDao.listByGoalId(goalId);
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("goal", goal);
        flow.put("events", events);
        flow.put("tasks", tasks);
        flow.put("event_count", goalEventDao.countByGoal(goalId));
        flow.put("task_count", tasks.size());
        return flow;
    }

    public List<GoalEvent> listEvents(String goalId, int limit) {
        return goalEventDao.listByGoal(goalId, limit <= 0 ? 50 : limit);
    }

    private Goal transition(String id, String status, String phase, String eventType, String summary, Map<String, Object> extra) {
        Goal goal = goalDao.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("goal not found: " + id));
        goalDao.updateStatus(id, status, phase, Instant.now());
        Map<String, Object> payload = extra == null ? mapOf() : new LinkedHashMap<>(extra);
        recordEvent(id, eventType, summary, payload);
        return goalDao.findById(id).orElse(goal);
    }

    void recordEvent(String goalId, String eventType, String summary, Map<String, Object> payload) {
        goalEventDao.insert(GoalEvent.create(IdGenerator.newId("gevt"), goalId, eventType, summary, payload));
    }

    private static String stringValue(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}