package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TaskServiceGoalContractTest {

    @TempDir
    Path tempDir;

    @Test
    void createTaskInitializesGoalContractDefaultsFromGoal() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-contract-defaults.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "goal contract defaults",
                "coding",
                "user",
                "high",
                "按目标推进任务",
                "把 goal 进度写进控制图",
                null,
                null,
                Map.of(),
                false
            ));

            assertEquals("把 goal 进度写进控制图", task.metadata().get("goal"));
            assertEquals(List.of("把 goal 进度写进控制图"), task.metadata().get("subgoals"));
            List<?> subgoalStatus = assertInstanceOf(List.class, task.metadata().get("subgoal_status"));
            assertEquals(1, subgoalStatus.size());
            Map<?, ?> statusEntry = assertInstanceOf(Map.class, subgoalStatus.get(0));
            assertEquals("把 goal 进度写进控制图", statusEntry.get("title"));
            assertEquals("pending", statusEntry.get("status"));
            assertEquals("0/1 subgoals done", task.metadata().get("progress_summary"));
        }
    }

    @Test
    void createTaskFallsBackToIntentWhenGoalMissingForGoalContract() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-contract-intent-fallback.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "goal contract intent fallback",
                "coding",
                "user",
                "high",
                "把 intent 当成最小 goal contract",
                null,
                null,
                null,
                Map.of(),
                false
            ));

            assertEquals("把 intent 当成最小 goal contract", task.metadata().get("goal"));
            assertEquals(List.of("把 intent 当成最小 goal contract"), task.metadata().get("subgoals"));
            assertEquals("0/1 subgoals done", task.metadata().get("progress_summary"));
        }
    }

    @Test
    void createTaskPreservesExplicitGoalContractMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("goal-contract-preserve-explicit.db"))) {
            TaskService service = service(db);
            List<Map<String, Object>> explicitStatus = List.of(
                Map.of("title", "先补文档", "status", "done"),
                Map.of("title", "再补测试", "status", "blocked")
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("subgoals", List.of("先补文档", "再补测试"));
            metadata.put("subgoal_status", explicitStatus);
            metadata.put("progress_summary", "1/2 subgoals done; 1 blocked");
            metadata.put("acceptance_criteria", List.of("文档和测试都能看到 goal contract"));

            Task task = service.createTask(new TaskCreateRequest(
                "goal contract preserve explicit",
                "coding",
                "user",
                "high",
                "保留显式 goal contract",
                "保留显式 goal contract",
                null,
                null,
                metadata,
                false
            ));

            assertEquals("保留显式 goal contract", task.metadata().get("goal"));
            assertEquals(List.of("先补文档", "再补测试"), task.metadata().get("subgoals"));
            assertEquals(explicitStatus, task.metadata().get("subgoal_status"));
            assertEquals("1/2 subgoals done; 1 blocked", task.metadata().get("progress_summary"));
            assertEquals(List.of("文档和测试都能看到 goal contract"), task.metadata().get("acceptance_criteria"));
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        return new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, null,
            null, null, null, null, null
        );
    }
}