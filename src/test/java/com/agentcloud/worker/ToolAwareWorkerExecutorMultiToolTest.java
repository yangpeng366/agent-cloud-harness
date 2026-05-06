package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import com.agentcloud.tool.ListFilesTool;
import com.agentcloud.tool.PatchFileTool;
import com.agentcloud.tool.ReadFileTool;
import com.agentcloud.tool.SearchTextTool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.agentcloud.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAwareWorkerExecutorMultiToolTest {

    @TempDir
    Path tempDir;

    @Test
    void executeOneRoundRecordsMultiToolChainTraceAndToolInvocations() throws Exception {
        Path noteFile = tempDir.resolve("notes.txt");
        Path outputFile = tempDir.resolve("draft.txt");
        Files.writeString(noteFile, "Reference note for the grounded draft.");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-trace.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(outputFile, "Read the note and produce a grounded draft.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"read_file","tool_arguments":{"path":"notes.txt"},"reason":"Read the reference note before drafting."}
                """,
                """
                {"needs_tool":true,"tool_name":"write_file","tool_arguments":{"path":"draft.txt","content":"Grounded summary from notes."},"reason":"Write the grounded draft now."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Grounded draft written. Finalize the round."}
                """,
                """
                {"summary":"Grounded draft completed.","output_text":"Used the reference note and wrote the draft.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"","confidence":"high"}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertTrue(Files.exists(outputFile));
            assertEquals("Grounded summary from notes.", Files.readString(outputFile));
            assertTrue(result.producedArtifact());
            assertEquals("Grounded summary from notes.", result.artifactContent());
            assertEquals("high", result.confidence());

            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals(2, numberValue(result.metadata().get("tool_chain_step_count")));
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));
            assertEquals(Boolean.TRUE, result.metadata().get("file_backed_artifact"));

            List<Map<String, Object>> trace = trace(result.metadata());
            assertEquals(2, trace.size());
            assertEquals("read_file", trace.get(0).get("selected_tool"));
            assertEquals("Read the reference note before drafting.", trace.get(0).get("why_selected"));
            assertEquals("Write the grounded draft now.", trace.get(0).get("why_next_step"));
            assertEquals("write_file", trace.get(1).get("selected_tool"));
            assertEquals("Grounded draft written. Finalize the round.", trace.get(1).get("why_next_step"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask("task-multi-tool", 10);
            assertEquals(2, invocations.size());
            ToolInvocationRecord writeInvocation = findInvocation(invocations, "write_file");
            ToolInvocationRecord readInvocation = findInvocation(invocations, "read_file");

            assertEquals("multi_tool_round", writeInvocation.metadata().get("tool_execution_mode"));
            assertEquals(2, numberValue(writeInvocation.metadata().get("tool_chain_step_index")));
            assertEquals("write_file", writeInvocation.metadata().get("selected_tool"));
            assertEquals("Write the grounded draft now.", writeInvocation.metadata().get("why_selected"));

            assertEquals("multi_tool_round", readInvocation.metadata().get("tool_execution_mode"));
            assertEquals(1, numberValue(readInvocation.metadata().get("tool_chain_step_index")));
            assertEquals("read_file", readInvocation.metadata().get("selected_tool"));
            assertTrue(String.valueOf(readInvocation.metadata().get("result_summary")).contains("read"));
        }
    }

    @Test
    void executeOneRoundResultPreservesHarnessEvolutionSchemaFields() throws Exception {
        Path noteFile = tempDir.resolve("schema-note.txt");
        Path outputFile = tempDir.resolve("schema-draft.txt");
        Files.writeString(noteFile, "Schema guard reference note.");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-result-schema-guard.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(outputFile, "Read the schema note and write a guarded draft.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"read_file","tool_arguments":{"path":"schema-note.txt"},"reason":"Read evidence before writing."}
                """,
                """
                {"needs_tool":true,"tool_name":"write_file","tool_arguments":{"path":"schema-draft.txt","content":"Schema guarded draft."},"reason":"Write the grounded schema guard draft."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Draft is written; finalize."}
                """,
                """
                {"summary":"Schema guard completed.","output_text":"The guarded draft was written.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"Review the generated draft.","confidence":"high","execution_status":"blocked","evidence_refs":["tool:read_file:schema-note.txt","tool:write_file:schema-draft.txt"],"unfinished_items":["manual_review"]}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertEquals("blocked", result.executionStatus());
            assertNotNull(result.evidenceRefs());
            assertEquals(List.of("tool:read_file:schema-note.txt", "tool:write_file:schema-draft.txt"), result.evidenceRefs());
            assertNotNull(result.unfinishedItems());
            assertEquals(List.of("manual_review"), result.unfinishedItems());
            assertEquals(0, result.tokenUsage());
            assertTrue(result.durationMs() >= 0L);
            assertNotNull(result.metadata());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals(2, numberValue(result.metadata().get("tool_chain_step_count")));
            assertTrue(result.producedArtifact());
        }
    }

    @Test
    void executeOneRoundStopsOnRepeatedToolGuard() throws Exception {
        Path noteFile = tempDir.resolve("repeat-note.txt");
        Files.writeString(noteFile, "Repeated note content.");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-repeat.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(null, "Inspect the note before deciding the next action.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"read_file","tool_arguments":{"path":"repeat-note.txt"},"reason":"Read the note once."}
                """,
                """
                {"needs_tool":true,"tool_name":"read_file","tool_arguments":{"path":"repeat-note.txt"},"reason":"Read the same note again."}
                """,
                """
                {"summary":"Stopped after repeated tool planning.","output_text":"The worker stopped because the same read was planned twice.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"Choose a different grounded step.","confidence":"high"}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertFalse(result.producedArtifact());
            assertEquals("low", result.confidence());
            assertEquals(1, numberValue(result.metadata().get("tool_chain_step_count")));
            assertEquals("repeated_tool_guard", result.metadata().get("tool_chain_termination_reason"));

            List<Map<String, Object>> trace = trace(result.metadata());
            assertEquals(1, trace.size());
            assertTrue(String.valueOf(trace.get(0).get("why_next_step")).contains("repeated_tool_guard"));
            assertEquals(1, toolInvocationDao.listByTask("task-multi-tool", 10).size());
        }
    }

    @Test
    void executeOneRoundAllowsSameSizeOverwriteWithoutFalseNoProgressGuard() throws Exception {
        Path outputFile = tempDir.resolve("same-size-draft.txt");
        Files.writeString(outputFile, "AAAA");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-overwrite.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(outputFile, "Refresh the existing grounded draft in place.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"write_file","tool_arguments":{"path":"same-size-draft.txt","content":"BBBB"},"reason":"Overwrite the draft with the revised version."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"The revised draft is already written."}
                """,
                """
                {"summary":"Draft updated.","output_text":"The revised draft was written to disk.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"","confidence":"medium"}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertEquals("BBBB", Files.readString(outputFile));
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));
            assertEquals(1, numberValue(result.metadata().get("tool_chain_step_count")));
            assertEquals("medium", result.confidence());

            List<Map<String, Object>> trace = trace(result.metadata());
            assertEquals(1, trace.size());
            assertEquals("The revised draft is already written.", trace.get(0).get("why_next_step"));
        }
    }

    @Test
    void executeOneRoundStopsOnNoProgressGuardWhenWriteDoesNotChangeOutput() throws Exception {
        Path outputFile = tempDir.resolve("unchanged-draft.txt");
        Files.writeString(outputFile, "SAME");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-no-progress.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(outputFile, "Only perform a grounded write if it changes the draft.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"write_file","tool_arguments":{"path":"unchanged-draft.txt","content":"SAME"},"reason":"Attempt the grounded write for the current draft."}
                """,
                """
                {"summary":"Stopped after a no-progress write.","output_text":"The worker detected that the write left the output unchanged.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"Change the content before writing again.","confidence":"high"}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertEquals("SAME", Files.readString(outputFile));
            assertFalse(result.producedArtifact());
            assertEquals("low", result.confidence());
            assertEquals(1, numberValue(result.metadata().get("tool_chain_step_count")));
            assertEquals("no_progress_guard", result.metadata().get("tool_chain_termination_reason"));

            List<Map<String, Object>> trace = trace(result.metadata());
            assertEquals(1, trace.size());
            assertEquals("write_file", trace.get(0).get("selected_tool"));
            assertTrue(String.valueOf(trace.get(0).get("why_next_step")).contains("no_progress_guard"));
            assertTrue(String.valueOf(trace.get(0).get("why_next_step")).contains("write_file did not change the grounded output"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask("task-multi-tool", 10);
            assertEquals(1, invocations.size());
            assertEquals("write_file", invocations.get(0).toolName());
        }
    }

    @Test
    void executeOneRoundTreatsPatchFileAsGroundedWriteArtifact() throws Exception {
        Path outputFile = tempDir.resolve("patched-draft.txt");
        Files.writeString(outputFile, "Grounded TODO draft.");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-patch.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            WorkerRegistry workerRegistry = createWorkerRegistry(tempDir);
            Task task = createTask(outputFile, "Patch the existing grounded draft in place.");
            persistTask(sessionDao, taskDao, task);
            QueuedLlmClient llmClient = new QueuedLlmClient(
                """
                {"needs_tool":true,"tool_name":"patch_file","tool_arguments":{"path":"patched-draft.txt","old_text":"TODO","new_text":"final"},"reason":"Apply the targeted wording fix."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"The grounded patch is already applied."}
                """,
                """
                {"summary":"Draft patched.","output_text":"The targeted wording fix was applied.","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"","confidence":"high"}
                """
            );

            ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
            WorkerExecutionResult result = executor.executeOneRound(
                createRuntimeContext(task),
                "tool-worker"
            );

            llmClient.assertExhausted();
            assertEquals("Grounded final draft.", Files.readString(outputFile));
            assertTrue(result.producedArtifact());
            assertEquals("Grounded final draft.", result.artifactContent());
            assertEquals(Boolean.TRUE, result.metadata().get("file_backed_artifact"));
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));

            List<Map<String, Object>> trace = trace(result.metadata());
            assertEquals(1, trace.size());
            assertEquals("patch_file", trace.get(0).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask("task-multi-tool", 10);
            assertEquals(1, invocations.size());
            assertEquals("patch_file", invocations.get(0).toolName());
        }
    }

    private ToolAwareWorkerExecutor createExecutor(WorkerRegistry workerRegistry,
                                                   ToolInvocationDao toolInvocationDao,
                                                   LlmClient llmClient) {
        ToolPolicy toolPolicy = new ToolPolicy();
        ToolRegistry toolRegistry = new ToolRegistry()
            .register(new ListFilesTool(workerRegistry, toolPolicy))
            .register(new ReadFileTool(workerRegistry, toolPolicy))
            .register(new SearchTextTool(workerRegistry, toolPolicy))
            .register(new WriteFileTool(workerRegistry, toolPolicy))
            .register(new PatchFileTool(workerRegistry, toolPolicy));
        WorkerExecutor fallbackExecutor = (context, workerId) -> {
            throw new AssertionError("fallback executor should not be used in this test");
        };
        return new ToolAwareWorkerExecutor(
            workerRegistry,
            toolRegistry,
            toolPolicy,
            toolInvocationDao,
            llmClient,
            fallbackExecutor
        );
    }

    private WorkerRegistry createWorkerRegistry(Path scopeRoot) {
        WorkerRegistry workerRegistry = new WorkerRegistry();
        workerRegistry.register(new Worker(
            "tool-worker",
            "native-tool",
            List.of("coding"),
            List.of("list_files", "read_file", "search_text", "write_file", "patch_file"),
            List.of(scopeRoot.toString()),
            Map.of("config_present", true),
            Map.of("model_tier", "small"),
            false,
            true
        ));
        return workerRegistry;
    }

    private TaskRuntimeContext createRuntimeContext(Task task) {
        return new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            new ActiveContext("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 12)
        );
    }

    private void persistTask(SessionDao sessionDao, TaskDao taskDao, Task task) {
        sessionDao.insert(Session.create(task.sessionId(), "tool session", "active"));
        taskDao.insert(task);
    }

    private Task createTask(Path outputFile, String intent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent", intent);
        if (outputFile != null) {
            metadata.put("output_file", outputFile.toString());
        }
        return new Task(
            "task-multi-tool",
            "session-multi-tool",
            null,
            "Grounded tool task",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            "Produce a grounded result.",
            "Produce a grounded result.",
            "",
            "tool-worker",
            "continue",
            null,
            metadata
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> trace(Map<String, Object> metadata) {
        Object value = metadata.get("tool_chain_trace");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private ToolInvocationRecord findInvocation(List<ToolInvocationRecord> invocations, String toolName) {
        return invocations.stream()
            .filter(record -> toolName.equals(record.toolName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing invocation for " + toolName));
    }

    private static final class QueuedLlmClient implements LlmClient {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        private QueuedLlmClient(String... responses) {
            for (String response : responses) {
                this.responses.addLast(response.strip());
            }
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            prompts.add(systemPrompt + "\n---\n" + userPrompt);
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected llm call: " + prompts.size());
            }
            return responses.removeFirst();
        }

        private void assertExhausted() {
            assertTrue(responses.isEmpty(), "unconsumed llm responses: " + responses.size());
        }
    }
}
