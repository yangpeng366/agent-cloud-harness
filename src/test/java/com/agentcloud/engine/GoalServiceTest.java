package com.agentcloud.engine;

import com.agentcloud.model.Goal;
import com.agentcloud.model.GoalCreateRequest;
import com.agentcloud.model.GoalEvent;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.GoalDao;
import com.agentcloud.store.GoalEventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalServiceTest {

    @TempDir
    Path tempDir;

    private GoalService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        GoalDao goalDao = db.jdbi().onDemand(GoalDao.class);
        GoalEventDao goalEventDao = db.jdbi().onDemand(GoalEventDao.class);
        return new GoalService(taskDao, goalDao, goalEventDao);
    }

    private void seedSession(DatabaseManager db, String sessionId) {
        db.jdbi().onDemand(SessionDao.class).insert(Session.create(sessionId, "s", "active"));
    }

    @Test
    void createGoalPersistsGoalAndCreatedEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-create.db"))) {
            GoalService svc = service(db);
            seedSession(db, "sess_1");
            Goal g = svc.createGoal(new GoalCreateRequest(
                "sess_1", "落地 /goal 生命周期", "把 goal 变成一等对象", null, null, null, null, null, null));

            assertEquals("open", g.status());
            assertEquals("active", g.phase());
            assertEquals(1, g.revision());
            assertNotNull(g.id());

            List<GoalEvent> events = svc.listEvents(g.id(), 10);
            assertEquals(1, events.size());
            assertEquals("goal_created", events.get(0).eventType());
        }
    }

    @Test
    void createGoalRequiresTitle() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-create-validation.db"))) {
            GoalService svc = service(db);
            try {
                svc.createGoal(new GoalCreateRequest("sess_1", "  ", "obj", null, null, null, null, null, null));
                org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("title"));
            }
        }
    }

    @Test
    void pauseReopenCloseTransitionsRecordEvents() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-transitions.db"))) {
            GoalService svc = service(db);
            seedSession(db, "sess_1");
            Goal g = svc.createGoal(new GoalCreateRequest("sess_1", "goal A", "obj", null, null, null, null, null, null));

            Goal paused = svc.pause(g.id());
            assertEquals("paused", paused.status());

            Goal reopened = svc.reopen(g.id());
            assertEquals("open", reopened.status());

            Goal closed = svc.close(g.id(), "delivered");
            assertEquals("closed", closed.status());
            assertEquals("completed", closed.phase());
            assertEquals("delivered", closed.outcomeSummary());
            assertNotNull(closed.closedAt());

            assertEquals(4, svc.listEvents(g.id(), 100).size());
        }
    }

    @Test
    void attachTaskWritesGoalIdIntoTaskMetadataAndLinksGoal() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-attach.db"))) {
            GoalService svc = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            sessionDao.insert(Session.create("sess_1", "s", "active"));
            taskDao.insert(Task.create("task_1", "sess_1", "title", "active", "high"));

            Goal g = svc.createGoal(new GoalCreateRequest("sess_1", "goal A", "obj", null, null, null, null, null, null));
            svc.attachTask(g.id(), "task_1");

            Task reloaded = taskDao.findById("task_1").orElseThrow();
            assertEquals(g.id(), reloaded.metadata().get("goal_id"));
            Goal reloadedGoal = svc.getGoal(g.id()).orElseThrow();
            assertEquals("task_1", reloadedGoal.activeTaskId());
        }
    }

    @Test
    void noteTaskAttachedIsGuardedWhenGoalMissing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-note-missing.db"))) {
            GoalService svc = service(db);
            // must not throw even though the goal does not exist
            svc.noteTaskAttached("goal_nonexistent", "task_1");
        }
    }

    @Test
    void onTaskTransitionRecordsEventAndProgressWhenTaskLinkedToGoal() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-transition-hook.db"))) {
            GoalService svc = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            sessionDao.insert(Session.create("sess_1", "s", "active"));

            Goal g = svc.createGoal(new GoalCreateRequest("sess_1", "goal A", "obj", null, null, null, null, null, null));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("goal_id", g.id());
            meta.put("progress_summary", "1/2 subgoals done");
            Task prev = Task.create("task_1", "sess_1", "title", "active", "high").withMetadata(meta);
            taskDao.insert(prev);
            Task curr = prev.withStatus("done");
            taskDao.updateState(curr);

            svc.onTaskTransition(prev, curr, "completed");

            List<GoalEvent> events = svc.listEvents(g.id(), 100);
            assertTrue(events.stream().anyMatch(e -> "goal_task_transition".equals(e.eventType())));
            Goal reloaded = svc.getGoal(g.id()).orElseThrow();
            assertNotNull(reloaded.progress());
            assertEquals("done", reloaded.progress().get("last_task_status"));
            assertEquals("1/2 subgoals done", reloaded.progress().get("progress_summary"));
        }
    }

    @Test
    void onTaskTransitionIsNoopWhenTaskHasNoGoalId() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-noop.db"))) {
            GoalService svc = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            sessionDao.insert(Session.create("sess_1", "s", "active"));
            Task t = Task.create("task_1", "sess_1", "title", "active", "high");
            taskDao.insert(t);
            Task curr = t.withStatus("done");
            // must not throw, and creates no goal events
            svc.onTaskTransition(t, curr, "completed");
        }
    }

    @Test
    void buildLiveFlowAggregatesGoalEventsAndTasks() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-liveflow.db"))) {
            GoalService svc = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            sessionDao.insert(Session.create("sess_1", "s", "active"));
            taskDao.insert(Task.create("task_1", "sess_1", "title", "active", "high"));

            Goal g = svc.createGoal(new GoalCreateRequest("sess_1", "goal A", "obj", null, null, null, null, null, null));
            svc.attachTask(g.id(), "task_1");

            Map<String, Object> flow = svc.buildLiveFlow(g.id());
            assertEquals(g.id(), ((Goal) flow.get("goal")).id());
            assertEquals(2, flow.get("event_count"));
            assertEquals(1, flow.get("task_count"));
        }
    }

    @Test
    void listGoalsFiltersBySessionAndStatus() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-list.db"))) {
            GoalService svc = service(db);
            seedSession(db, "sess_1");
            seedSession(db, "sess_2");
            Goal open = svc.createGoal(new GoalCreateRequest("sess_1", "open goal", "obj", null, null, null, null, null, null));
            svc.createGoal(new GoalCreateRequest("sess_2", "other session goal", "obj", null, null, null, null, null, null));
            svc.pause(open.id());

            List<Goal> sess1All = svc.listGoals("sess_1", null);
            assertEquals(1, sess1All.size());
            List<Goal> paused = svc.listGoals(null, "paused");
            assertEquals(1, paused.size());
            assertEquals(open.id(), paused.get(0).id());
        }
    }
}