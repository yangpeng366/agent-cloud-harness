package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.ToolInvocationDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFactSetAssemblerTest {

    @Test
    void assembleBuildsFactSetFromRuntimeContextAndToolTrace() {
        Instant now = Instant.parse("2026-05-05T06:00:00Z");
        Task task = new Task(
            "task_1",
            "session_1",
            null,
            "runtime facts task",
            "active",
            "high",
            now,
            now,
            now,
            null,
            null,
            null,
            "unify facts",
            "review packet",
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding", "model_mode", "strong_only")
        );

        Decision executionDecision = new Decision(
            "dec_exec",
            task.sessionId(),
            task.id(),
            now.plusSeconds(1),
            "execution_judgment",
            "Need review",
            "Execution suggests pausing for human review",
            "medium",
            null,
            Map.of("action", "pause", "next_step", "review generated draft")
        );
        Decision completionDecision = new Decision(
            "dec_done",
            task.sessionId(),
            task.id(),
            now.plusSeconds(2),
            "completion_judgment",
            "Not done yet",
            "More work remains",
            "low",
            null,
            Map.of("status", "incomplete", "suggested_next_action", "finish edits")
        );
        Artifact latestArtifact = new Artifact(
            "art_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "worker_artifact",
            "Draft summary",
            null,
            null,
            "Draft body",
            Map.of("suggested_next_step", "apply polish")
        );
        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(executionDecision, completionDecision),
            List.of(latestArtifact),
            List.of(),
            new ActiveContext(
                "finish draft",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("ship patch"),
                List.of(),
                List.of(),
                List.of(),
                "continuity says review draft",
                "ctx",
                12
            )
        );

        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(null, null, null, null, null, null, null) {
            @Override
            public TaskRuntimeContext build(Task ignored) {
                return runtimeContext;
            }
        };

        ToolInvocationRecord record = new ToolInvocationRecord(
            "tool_1",
            task.sessionId(),
            task.id(),
            "codex",
            "exec_1",
            "write_file",
            Map.of("path", "draft.txt"),
            "draft updated",
            "succeeded",
            true,
            21,
            List.of("draft.txt"),
            now.plusSeconds(4),
            Map.of("tool_execution_mode", "multi_tool_round")
        );

        ToolInvocationDao toolInvocationDao = new InMemoryToolInvocationDao(record);
        WorkerRouter router = routerWithCodex();
        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(runtimeContextBuilder, toolInvocationDao, router);

        RuntimeFactSet facts = assembler.assemble(task, 10);

        assertEquals(task.id(), facts.taskId());
        assertEquals("Draft body", facts.latestOutput());
        assertEquals("pause", facts.recommendedAction());
        assertEquals("review generated draft", facts.recommendedNextStep());
        assertEquals(1, facts.toolInvocations().size());
        assertEquals("write_file", facts.toolInvocations().get(0).toolName());
        assertNotNull(facts.executionBoundary());
        assertEquals("exec_1", facts.executionBoundary().executionId());
        assertEquals("codex", facts.executionBoundary().workerId());
        assertEquals("succeeded", facts.executionBoundary().executionStatus());
        assertEquals(1, facts.executionBoundary().toolInvocationCount());
        assertEquals(List.of("tool_1"), facts.executionBoundary().toolInvocationIds());
        assertEquals("1 tool call · succeeded · write_file", facts.executionBoundary().traceSummary());
        assertNotNull(facts.routePreview());
        assertEquals("codex", facts.routePreview().selectedWorker());
        assertTrue((Boolean) facts.metadata().get("has_runtime_context"));
        assertTrue((Boolean) facts.metadata().get("has_execution_judgment"));
        assertTrue((Boolean) facts.metadata().get("has_completion_judgment"));
        assertTrue((Boolean) facts.metadata().get("has_execution_boundary"));
        assertEquals("exec_1", facts.metadata().get("execution_id"));
        assertEquals("succeeded", facts.metadata().get("execution_status"));
        assertEquals(21L, facts.metadata().get("execution_duration_ms"));
        assertEquals(1, facts.metadata().get("execution_tool_invocation_count"));
        assertEquals("1 tool call · succeeded · write_file", facts.metadata().get("execution_trace_summary"));
        assertFalse((Boolean) facts.metadata().get("has_latest_packet"));
    }

    @Test
    void assembleReturnsEmptyFactSetWhenTaskIsNull() {
        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(null, null, null);

        RuntimeFactSet facts = assembler.assemble(null, 10);

        assertNotNull(facts);
        assertEquals("", facts.taskId());
        assertEquals("", facts.latestOutput());
        assertEquals(List.of(), facts.toolInvocations());
        assertEquals(Map.of(), facts.metadata());
        assertEquals(null, facts.executionBoundary());
    }

    private WorkerRouter routerWithCodex() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "codex",
            "codex",
            List.of("coding", "continuation"),
            List.of("read_file", "write_file"),
            List.of("workspace"),
            Map.of(),
            Map.of("model_tier", "strong", "primary_role", "executor"),
            false,
            true
        ));
        return new WorkerRouter(registry);
    }

    private static final class InMemoryToolInvocationDao implements ToolInvocationDao {
        private final List<ToolInvocationRecord> records;

        private InMemoryToolInvocationDao(ToolInvocationRecord... records) {
            this.records = List.of(records);
        }

        @Override
        public void insertRaw(String id, String sessionId, String taskId, String workerId, String executionId,
                              String toolName, String arguments, String resultSummary, String status,
                              boolean success, Integer elapsedMs, String touchedPaths, Instant createdAt,
                              String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolInvocationRecord> listByTask(String taskId, int limit) {
            return records.stream().filter(record -> taskId.equals(record.taskId())).limit(limit).toList();
        }

        @Override
        public List<ToolInvocationRecord> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return records.stream()
                .filter(record -> sessionId.equals(record.sessionId()) && taskId.equals(record.taskId()))
                .limit(limit)
                .toList();
        }

        @Override
        public org.jdbi.v3.core.Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(org.jdbi.v3.core.HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }
}
