package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import com.agentcloud.tool.ListFilesTool;
import com.agentcloud.tool.ReadFileTool;
import com.agentcloud.tool.SearchTextTool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.agentcloud.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAwareWorkerExecutorMultiStepTest {

    @TempDir
    Path tempDir;

    @Test
    void completesThreeStepSearchReadWriteChain() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "TODO: refine intro\nReference: keep it short.\n");
        Path outputFile = workspace.resolve("result.md");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-chain.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-codex",
                "codex",
                List.of("coding"),
                List.of("search_text", "read_file", "write_file"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new ListFilesTool(workerRegistry, toolPolicy))
                .register(new ReadFileTool(workerRegistry, toolPolicy))
                .register(new SearchTextTool(workerRegistry, toolPolicy))
                .register(new WriteFileTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                "{\"needs_tool\":true,\"tool_name\":\"search_text\",\"tool_arguments\":{\"path\":\".\",\"query\":\"TODO\"},\"reason\":\"find the relevant reference file first\"}",
                "{\"needs_tool\":true,\"tool_name\":\"read_file\",\"tool_arguments\":{\"path\":\"notes.txt\"},\"reason\":\"read the matched note before writing\"}",
                "{\"needs_tool\":true,\"tool_name\":\"write_file\",\"tool_arguments\":{\"path\":\"result.md\",\"content\":\"Final grounded answer\\n\"},\"reason\":\"write the grounded answer to the output file\"}",
                "{\"summary\":\"completed grounded write\",\"output_text\":\"final answer prepared\",\"produced_artifact\":true,\"artifact_title\":\"result.md\",\"artifact_content\":\"\",\"suggested_next_step\":\"\",\"confidence\":\"high\"}"
            ));

            ToolAwareWorkerExecutor executor = new ToolAwareWorkerExecutor(
                workerRegistry,
                toolRegistry,
                toolPolicy,
                toolInvocationDao,
                llmClient,
                (context, workerId) -> new WorkerExecutionResult(
                    "fallback",
                    "fallback",
                    false,
                    "",
                    "",
                    "",
                    "low",
                    0,
                    0L,
                    Map.of("executor", "fallback")
                )
            );

            Task task = new Task(
                "task_multi_1",
                "session_multi_1",
                null,
                "grounded article",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Write a grounded result file",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Search the workspace, inspect the reference, then write the final result.",
                    "output_file", outputFile.toString()
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool chain", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals(3, ((Number) result.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("max_tool_rounds_reached", result.metadata().get("tool_chain_termination_reason"));
            assertTrue(Files.exists(outputFile));
            assertEquals("Final grounded answer\n", Files.readString(outputFile));

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(3, trace.size());
            assertEquals("search_text", trace.get(0).get("selected_tool"));
            assertEquals("read_file", trace.get(1).get("selected_tool"));
            assertEquals("write_file", trace.get(2).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(3, invocations.size());
            Set<Integer> stepIndexes = invocations.stream()
                .map(record -> ((Number) record.metadata().get("tool_chain_step_index")).intValue())
                .collect(Collectors.toSet());
            assertEquals(Set.of(1, 2, 3), stepIndexes);
            assertTrue(invocations.stream().allMatch(record ->
                "multi_tool_round".equals(record.metadata().get("tool_execution_mode"))));
        }
    }

    @Test
    void stopsBeforeRepeatingSameToolAndArgs() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repeat-workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "TODO: one note only.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-repeat.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-guard",
                "codex",
                List.of("coding"),
                List.of("search_text", "read_file"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy))
                .register(new ReadFileTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                "{\"needs_tool\":true,\"tool_name\":\"search_text\",\"tool_arguments\":{\"path\":\".\",\"query\":\"TODO\"},\"reason\":\"scan the workspace first\"}",
                "{\"needs_tool\":true,\"tool_name\":\"search_text\",\"tool_arguments\":{\"path\":\".\",\"query\":\"TODO\"},\"reason\":\"run the same search again\"}",
                "{\"summary\":\"stopped on repeated search\",\"output_text\":\"search trace kept for inspection\",\"produced_artifact\":false,\"artifact_title\":\"\",\"artifact_content\":\"\",\"suggested_next_step\":\"adjust the query before trying again\",\"confidence\":\"medium\"}"
            ));

            ToolAwareWorkerExecutor executor = new ToolAwareWorkerExecutor(
                workerRegistry,
                toolRegistry,
                toolPolicy,
                toolInvocationDao,
                llmClient,
                (context, workerId) -> new WorkerExecutionResult(
                    "fallback",
                    "fallback",
                    false,
                    "",
                    "",
                    "",
                    "low",
                    0,
                    0L,
                    Map.of("executor", "fallback")
                )
            );

            Task task = new Task(
                "task_multi_2",
                "session_multi_2",
                null,
                "repeat guard",
                "active",
                "medium",
                null,
                null,
                null,
                null,
                null,
                null,
                "Avoid repeating the same search forever",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of("intent", "Inspect TODO notes without looping.")
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool repeat", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals("repeated_tool_guard", result.metadata().get("tool_chain_termination_reason"));
            assertFalse(result.producedArtifact());

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(1, trace.size());
            assertTrue(trace.get(0).get("why_next_step").toString().contains("repeated_tool_guard"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(1, invocations.size());
            assertEquals("search_text", invocations.get(0).toolName());
        }
    }

    private static final class SequencedLlmClient implements LlmClient {
        private final Queue<String> responses;

        private SequencedLlmClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("No LLM response left for prompt: " + systemPrompt);
            }
            return responses.remove();
        }
    }
}
