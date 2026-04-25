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
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceAutoStartTest {

    @TempDir
    Path tempDir;

    @Test
    void missingAutoStartDefaultsToTrue() throws Exception {
        assertTrue(invokeShouldAutoStart(new TaskCreateRequest(
            "demo", "local_doc", "user", "high",
            "intent", "goal", null, null, Map.of(), null
        )));
    }

    @Test
    void explicitFalseDisablesAutoStart() throws Exception {
        assertFalse(invokeShouldAutoStart(new TaskCreateRequest(
            "demo", "local_doc", "user", "high",
            "intent", "goal", null, null, Map.of(), false
        )));
    }

    @Test
    void explicitTrueKeepsAutoStartEnabled() throws Exception {
        assertTrue(invokeShouldAutoStart(new TaskCreateRequest(
            "demo", "local_doc", "user", "high",
            "intent", "goal", null, null, Map.of(), true
        )));
    }

    @Test
    void explicitFalsePersistsManualStartMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("autostart-false.db"))) {
            TaskService service = service(db, null);

            Task task = service.createTask(new TaskCreateRequest(
                "demo", "local_doc", "user", "high",
                "intent", "goal", null, null, Map.of(), false
            ));

            assertEquals(Boolean.FALSE, task.metadata().get("auto_start"));
            assertEquals("manual", task.metadata().get("start_mode"));
        }
    }

    @Test
    void explicitTruePersistsAutoStartMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("autostart-true.db"))) {
            ControlNodeGraph graph = new ControlNodeGraph(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    return task;
                }
            };
            TaskService service = service(db, graph);

            Task task = service.createTask(new TaskCreateRequest(
                "demo", "local_doc", "user", "high",
                "intent", "goal", null, null, Map.of(), true
            ));

            assertEquals(Boolean.TRUE, task.metadata().get("auto_start"));
            assertEquals("auto", task.metadata().get("start_mode"));
        }
    }

    private boolean invokeShouldAutoStart(TaskCreateRequest request) throws Exception {
        TaskService service = new TaskService(
            null, null, null, null, null, null, null,
            null, null, null, null, null
        );
        Method method = TaskService.class.getDeclaredMethod("shouldAutoStart", TaskCreateRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, request);
    }

    private TaskService service(DatabaseManager db, ControlNodeGraph graph) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        return new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, graph,
            null, null, null, null, null
        );
    }
}
