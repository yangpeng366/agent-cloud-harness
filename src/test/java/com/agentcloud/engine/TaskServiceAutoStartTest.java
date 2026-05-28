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
import java.util.List;
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

    @Test
    void directTaskCreationInfersWorkspaceContractFromLocalPathIntent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("direct-task-local-path-contract.db"))) {
            TaskService service = service(db, null);

            Task task = service.createTask(new TaskCreateRequest(
                "direct coding task",
                "coding",
                "user",
                "high",
                "修改 D:\\gitAll\\agent-cloud-harness\\src\\main\\java\\com\\agentcloud\\engine\\TaskService.java 并补测试。",
                "让 worker 能拿到本地路径合同。",
                null,
                null,
                Map.of(),
                false
            ));

            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("workspace_root"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("cwd"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("repo_path"));
            assertTrue(((List<?>) task.metadata().get("reference_paths"))
                .contains("D:\\gitAll\\agent-cloud-harness\\src\\main\\java\\com\\agentcloud\\engine\\TaskService.java"));
            assertTrue(((List<?>) task.metadata().get("target_paths"))
                .contains("D:\\gitAll\\agent-cloud-harness\\src\\main\\java\\com\\agentcloud\\engine\\TaskService.java"));
        }
    }

    @Test
    void directTaskCreationExpandsExplicitRepoPathAndPreservesProviderContract() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("direct-task-provider-contract.db"))) {
            TaskService service = service(db, null);

            Task task = service.createTask(new TaskCreateRequest(
                "direct provider contract",
                "coding",
                "user",
                "high",
                "按 provider 合同执行。",
                "验证直建任务也能给 worker 明确路径和验收。",
                null,
                null,
                Map.of(
                    "repo_path", "D:\\gitAll\\agent-cloud-harness",
                    "validation_commands", List.of("mvn -Dtest=TaskServiceAutoStartTest test"),
                    "write_scope", List.of("src/main/java/com/agentcloud/engine", "docs"),
                    "acceptance_criteria", List.of("worker reports exact validation result")
                ),
                false
            ));

            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("workspace_root"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("cwd"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("repo_path"));
            assertEquals(List.of("mvn -Dtest=TaskServiceAutoStartTest test"), task.metadata().get("validation_commands"));
            assertEquals(List.of("src/main/java/com/agentcloud/engine", "docs"), task.metadata().get("write_scope"));
            assertEquals(List.of("worker reports exact validation result"), task.metadata().get("acceptance_criteria"));
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
