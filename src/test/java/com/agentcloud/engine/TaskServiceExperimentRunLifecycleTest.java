package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskServiceExperimentRunLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void taskLifecycleRefreshesExperimentRunAutomatically() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-experiment-run.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "baseline strong only task",
                "coding",
                "user",
                "high",
                "Write a concise baseline output",
                "Produce the requested result",
                null,
                null,
                Map.of(
                    "experiment_name", "baseline-auto",
                    "task_case_key", "case-auto-1",
                    "task_length_bucket", "short",
                    "model_mode", "strong_only"
                ),
                false
            ));

            var initialRun = service.getExperimentRun(task.id());
            assertNotNull(initialRun);
            assertEquals("strong_only", initialRun.modelMode());
            assertEquals("active", initialRun.completionStatus());
            assertEquals("not_evaluated", initialRun.acceptanceResult());

            service.updateTaskState(task.id(), "done", "manual finish");

            var completedRun = service.getExperimentRun(task.id());
            assertNotNull(completedRun);
            assertEquals("done", completedRun.completionStatus());
            assertEquals("accepted", completedRun.acceptanceResult());
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

        ExperimentRunService experimentRunService = new ExperimentRunService(
            experimentRunDao,
            decisionDao,
            artifactDao,
            eventDao,
            toolInvocationDao
        );

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            null,
            null,
            null,
            new TaskRuntimeContextBuilder(null, null, null, null, null, null, null),
            null,
            null,
            toolInvocationDao,
            null,
            experimentRunService
        );
    }
}
