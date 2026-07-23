package com.agentcloud.engine.router;

import com.agentcloud.model.Task;

import java.time.Instant;
import java.util.Map;

final class TestTasks {

    private TestTasks() {
    }

    static Task task(String taskType) {
        return task(taskType, Map.of("task_type", taskType));
    }

    static Task task(String taskType, Map<String, Object> metadata) {
        return new Task(
            "task_1",
            "session_1",
            null,
            "trace test",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "verify route trace",
            null,
            null,
            "intake",
            null,
            metadata
        );
    }
}
