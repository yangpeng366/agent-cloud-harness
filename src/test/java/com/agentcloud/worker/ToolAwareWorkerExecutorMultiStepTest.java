package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
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
import com.agentcloud.tool.WriteFilesTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
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
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"the grounded file already exists, finalize now\"}",
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
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));
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
    void treatsDirectoryBackedWriteFilesOutputAsGroundedArtifact() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-dir"));
        Path outputDir = workspace.resolve("site-out");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-dir.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-dir",
                "codex",
                List.of("coding"),
                List.of("write_files"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new WriteFilesTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                """
                {"needs_tool":true,"tool_name":"write_files","tool_arguments":{"base_path":"site-out","files":[{"path":"index.html","content":"<h1>Demo</h1>"},{"path":"assets/app.js","content":"console.log('ok');"}],"overwrite":true},"reason":"create the directory-backed grounded artifact in one step"}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"the output directory now contains the required grounded files"}
                """,
                """
                {"summary":"completed directory grounded write","output_text":"site bundle prepared","produced_artifact":true,"artifact_title":"site-out","artifact_content":"","suggested_next_step":"","confidence":"high"}
                """
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
                "task_multi_dir_1",
                "session_multi_dir_1",
                null,
                "grounded directory artifact",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create a small site bundle in the output directory",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Create the grounded output directory with the required files.",
                    "output_dir", outputDir.toString()
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool directory", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals(1, ((Number) result.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));
            assertEquals(Boolean.TRUE, result.metadata().get("output_dir_required"));
            assertEquals(Boolean.TRUE, result.metadata().get("output_dir_exists"));
            assertEquals(Boolean.TRUE, result.metadata().get("grounded_output_present"));
            assertEquals(Boolean.TRUE, result.metadata().get("directory_backed_artifact"));
            assertTrue(Files.exists(outputDir.resolve("index.html")));
            assertTrue(Files.exists(outputDir.resolve("assets").resolve("app.js")));

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(1, trace.size());
            assertEquals("write_files", trace.get(0).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(1, invocations.size());
            assertEquals("write_files", invocations.get(0).toolName());
        }
    }

    @Test
    void initialNoToolPlanForDirectoryTaskTriggersLightweightProbeBeforeWrite() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-probe"));
        Path outputDir = workspace.resolve("demo-project");
        Files.writeString(workspace.resolve("brief.txt"), "Build a small grounded demo bundle.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-probe.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-probe",
                "codex",
                List.of("coding"),
                List.of("list_files", "write_files"),
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
                .register(new WriteFilesTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Need a quick workspace check before deciding the grounded bundle layout."}
                """,
                """
                {"needs_tool":true,"tool_name":"write_files","tool_arguments":{"base_path":"demo-project","files":[{"path":"README.md","content":"# Demo Project\\n"},{"path":"src/main.js","content":"console.log('demo');\\n"}],"overwrite":true},"reason":"Now write the grounded directory bundle."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"The grounded directory bundle is now present."}
                """,
                """
                {"summary":"completed directory bundle after probe","output_text":"probe then grounded write finished","produced_artifact":true,"artifact_title":"demo-project","artifact_content":"","suggested_next_step":"","confidence":"high"}
                """
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
                "task_multi_probe",
                "session_multi_probe",
                null,
                "directory probe",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create the grounded demo directory after a lightweight probe.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Create the grounded output directory with the required files.",
                    "output_dir", outputDir.toString()
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool probe", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) result.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool", result.metadata().get("tool_chain_termination_reason"));
            assertEquals(Boolean.TRUE, result.metadata().get("grounded_output_present"));
            assertTrue(Files.exists(outputDir.resolve("README.md")));
            assertTrue(Files.exists(outputDir.resolve("src").resolve("main.js")));

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(2, trace.size());
            assertEquals("list_files", trace.get(0).get("selected_tool"));
            assertEquals("write_files", trace.get(1).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(2, invocations.size());
            assertEquals("list_files", invocations.get(1).toolName());
            assertEquals("write_files", invocations.get(0).toolName());
        }
    }

    @Test
    void probeThenNoToolStillAutoWritesDirectoryBundle() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-probe-auto"));
        Path outputDir = workspace.resolve("demo-project");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-probe-auto.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-probe-auto",
                "codex",
                List.of("coding"),
                List.of("list_files", "write_files"),
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
                .register(new WriteFilesTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Run one quick directory probe first."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Enough evidence collected; produce the bundle now."}
                """,
                """
                {"summary":"auto generated directory demo bundle","base_path":"demo-project","files":[{"path":"index.html","content":"<!doctype html><html><body><h1>Auto Demo</h1><script src=\\"app.js\\"></script></body></html>"},{"path":"app.js","content":"console.log('auto bundle');\\n"}],"suggested_next_step":"","confidence":"high"}
                """
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
                "task_multi_probe_auto",
                "session_multi_probe_auto",
                null,
                "directory probe auto write",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create the grounded demo directory after a lightweight probe.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Create the grounded output directory with the required files.",
                    "output_dir", outputDir.toString(),
                    "image_inputs", List.of("D:\\gitAll\\open\\20260506-141916.jpg")
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool probe auto", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals("auto_grounded_directory_write", result.metadata().get("tool_chain_termination_reason"));
            assertEquals("generated", result.metadata().get("auto_write_generation_mode"));
            assertEquals(Boolean.TRUE, result.metadata().get("grounded_output_present"));
            assertEquals(Boolean.TRUE, result.metadata().get("directory_backed_artifact"));
            assertTrue(Files.exists(outputDir.resolve("index.html")));
            assertTrue(Files.exists(outputDir.resolve("index.html")) || Files.exists(outputDir.resolve("app.js")));

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(2, trace.size());
            assertEquals("list_files", trace.get(0).get("selected_tool"));
            assertEquals("write_files", trace.get(1).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(2, invocations.size());
            assertEquals("write_files", invocations.get(0).toolName());
        }
    }

    @Test
    void noToolAfterEvidenceAutoWritesDesiredOutputFile() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-auto-file"));
        Files.writeString(workspace.resolve("loop.md"), "# Loop\nGrounded source.\n");
        Path outputFile = workspace.resolve("notes.md");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-auto-file.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-auto-file",
                "codex",
                List.of("research"),
                List.of("read_file", "write_file"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new ReadFileTool(workerRegistry, toolPolicy))
                .register(new WriteFileTool(workerRegistry, toolPolicy));

            SequencedLlmClient llmClient = new SequencedLlmClient(List.of(
                """
                {"needs_tool":true,"tool_name":"read_file","tool_arguments":{"path":"loop.md"},"reason":"Read the source document first."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"Enough grounded evidence collected; produce the notes file now."}
                """,
                """
                {"summary":"Grounded notes prepared.","artifact_title":"notes.md","content":"# Notes\\nGrounded summary from the loop doc.\\n","suggested_next_step":"","confidence":"high"}
                """,
                """
                {"summary":"Grounded notes file created.","output_text":"The required notes file was written from grounded evidence.","produced_artifact":true,"artifact_title":"notes.md","artifact_content":"","suggested_next_step":"","confidence":"high"}
                """
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
                "task_multi_auto_file",
                "session_multi_auto_file",
                null,
                "desired output file auto write",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create grounded notes from the loop document.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Read the source loop doc and produce grounded notes.",
                    "desired_output_file", outputFile.toString()
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool auto file", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals("auto_grounded_required_write", result.metadata().get("tool_chain_termination_reason"));
            assertEquals("generated", result.metadata().get("auto_write_generation_mode"));
            assertEquals(Boolean.TRUE, result.metadata().get("grounded_output_present"));
            assertEquals(Boolean.TRUE, result.metadata().get("file_backed_artifact"));
            assertEquals(outputFile.toString(), result.metadata().get("output_file_path"));
            assertTrue(Files.exists(outputFile));
            assertEquals("# Notes\nGrounded summary from the loop doc.\n", Files.readString(outputFile));

            Object rawTrace = result.metadata().get("tool_chain_trace");
            assertInstanceOf(List.class, rawTrace);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) rawTrace;
            assertEquals(2, trace.size());
            assertEquals("read_file", trace.get(0).get("selected_tool"));
            assertEquals("write_file", trace.get(1).get("selected_tool"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(2, invocations.size());
            assertEquals("write_file", invocations.get(0).toolName());
            assertEquals("read_file", invocations.get(1).toolName());
        }
    }

    @Test
    void autoWriteFilesTimeoutFallsBackToMinimalRunnableScaffold() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-fallback"));
        Path outputDir = workspace.resolve("demo-project");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-fallback.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-fallback",
                "codex",
                List.of("visual_demo"),
                List.of("list_files", "write_files"),
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
                .register(new WriteFilesTool(workerRegistry, toolPolicy));

            FailingImageSequencedLlmClient llmClient = new FailingImageSequencedLlmClient(List.of(
                """
                {"needs_tool":true,"tool_name":"list_files","tool_arguments":{"path":".","recursive":false,"max_entries":100},"reason":"Inspect the available local files before writing the bundle."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"No image-viewing tool is available, so stop planning after the filesystem probe."}
                """
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
                "task_multi_fallback",
                "session_multi_fallback",
                null,
                "fallback directory artifact",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create a runnable scaffold even if richer generation times out.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Use the local references when possible, but still write a runnable scaffold into the output directory.",
                    "output_dir", outputDir.toString(),
                    "image_inputs", List.of("D:\\gitAll\\open\\20260506-141916.jpg")
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool fallback", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals("auto_grounded_directory_write", result.metadata().get("tool_chain_termination_reason"));
            assertEquals("minimal_directory_fallback", result.metadata().get("auto_write_generation_mode"));
            assertEquals(Boolean.TRUE, result.metadata().get("directory_backed_artifact"));
            assertTrue(Files.exists(outputDir.resolve("index.html")));
            assertTrue(Files.exists(outputDir.resolve("style.css")));
            assertTrue(Files.exists(outputDir.resolve("script.js")));
            assertTrue(Files.exists(outputDir.resolve("README.md")));
            assertTrue(Files.readString(outputDir.resolve("index.html")).contains("Autonomous Scaffold"));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(2, invocations.size());
            assertEquals("write_files", invocations.get(0).toolName());
            assertEquals("list_files", invocations.get(1).toolName());
        }
    }

    @Test
    void autoWriteFilesTimeoutFallsBackToMinimalRunnableFullStackBundleWhenContractRequiresIt() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace-fullstack-fallback"));
        Path outputDir = workspace.resolve("demo-fullstack");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("multi-tool-fullstack-fallback.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-fullstack-fallback",
                "codex",
                List.of("visual_demo"),
                List.of("list_files", "write_files"),
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
                .register(new WriteFilesTool(workerRegistry, toolPolicy));

            FailingImageSequencedLlmClient llmClient = new FailingImageSequencedLlmClient(List.of(
                """
                {"needs_tool":true,"tool_name":"list_files","tool_arguments":{"path":".","recursive":false,"max_entries":100},"reason":"Inspect the workspace before generating the full-stack bundle."}
                """,
                """
                {"needs_tool":false,"tool_name":"","tool_arguments":{},"reason":"No richer tool path remains after the filesystem probe."}
                """
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
                "task_multi_fullstack_fallback",
                "session_multi_fullstack_fallback",
                null,
                "fallback full-stack artifact",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "Create a runnable frontend and backend bundle even if richer generation times out.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Write a runnable full-stack bundle with frontend, backend, and API status endpoint.",
                    "output_dir", outputDir.toString(),
                    "output_contract", "full-stack runnable bundle",
                    "project_kind", "frontend and backend demo",
                    "frontend_required", true,
                    "backend_required", true,
                    "api_required", true,
                    "required_components", List.of("frontend", "backend", "api", "manifest", "readme"),
                    "image_inputs", List.of("D:\\gitAll\\open\\missing-reference.png")
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "multi tool fullstack fallback", "active"));
            taskDao.insert(task);

            TaskRuntimeContext context = new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null);
            WorkerExecutionResult result = executor.executeOneRound(context, worker.workerId());

            assertTrue(result.producedArtifact());
            assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
            assertEquals("auto_grounded_directory_write", result.metadata().get("tool_chain_termination_reason"));
            assertEquals("minimal_directory_fallback", result.metadata().get("auto_write_generation_mode"));
            assertEquals(Boolean.TRUE, result.metadata().get("directory_backed_artifact"));
            assertTrue(Files.exists(outputDir.resolve("package.json")));
            assertTrue(Files.exists(outputDir.resolve("server.js")));
            assertTrue(Files.exists(outputDir.resolve("public/index.html")));
            assertTrue(Files.exists(outputDir.resolve("public/styles.css")));
            assertTrue(Files.exists(outputDir.resolve("public/app.js")));
            assertTrue(Files.exists(outputDir.resolve("README.md")));
            assertTrue(Files.readString(outputDir.resolve("server.js")).contains("/api/status"));
            assertTrue(Files.readString(outputDir.resolve("package.json")).contains("\"express\""));

            List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
            assertEquals(2, invocations.size());
            assertEquals("write_files", invocations.get(0).toolName());
            assertEquals("list_files", invocations.get(1).toolName());
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
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"stop after the repeated search guard\"}",
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

    @Test
    void planningPromptIncludesMountedContextSurface() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("planning-mounted-workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "Reference note.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planning-mounted.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-mounted",
                "codex",
                List.of("coding"),
                List.of("search_text"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy));

            CapturingSequencedLlmClient llmClient = new CapturingSequencedLlmClient(List.of(
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"No tool required for this test.\"}"
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
                "task_multi_mounted",
                "session_multi_mounted",
                null,
                "planning prompt mounted",
                "active",
                "high",
                Instant.parse("2026-05-06T06:50:00Z"),
                Instant.parse("2026-05-06T06:50:00Z"),
                null,
                null,
                null,
                null,
                "Ensure tool planning sees mounted context.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Inspect mounted context before deciding tool use.",
                    "prompt_rendering_mode", "mounted_context_primary"
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "mounted planning", "active"));
            taskDao.insert(task);

            WorkerExecutionResult result = executor.executeOneRound(mountedRuntimeContext(task), worker.workerId());

            assertTrue(llmClient.firstUserPrompt.contains("Mounted Context:"));
            assertTrue(llmClient.firstUserPrompt.contains("Pinned (1)"));
            assertTrue(llmClient.firstUserPrompt.contains("constraint/pinned/Constraints"));
            assertTrue(llmClient.firstUserPrompt.contains("Mounted Context Selection Trace:"));
            assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
            assertEquals(true, result.metadata().get("mounted_render_used"));
            assertEquals(1, result.metadata().get("mounted_pinned_count"));
        }
    }

    @Test
    void planningPromptPassesResolvedImageInputsToLlm() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("planning-image-workspace"));

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planning-image.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-image",
                "codex",
                List.of("coding"),
                List.of("search_text"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy));

            CapturingSequencedLlmClient llmClient = new CapturingSequencedLlmClient(List.of(
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"No tool required for this test.\"}"
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
                "task_multi_image",
                "session_multi_image",
                null,
                "planning prompt image",
                "active",
                "high",
                Instant.parse("2026-05-06T07:00:00Z"),
                Instant.parse("2026-05-06T07:00:00Z"),
                null,
                null,
                null,
                null,
                "Ensure tool planning sees image inputs.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Read the image mockup before deciding tool use.",
                    "image_inputs", List.of(
                        Map.of("path", "D:\\gitAll\\open\\20260506-141826.png", "media_type", "image/png"),
                        "D:\\gitAll\\open\\20260506-141916.jpg"
                    )
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "image planning", "active"));
            taskDao.insert(task);

            WorkerExecutionResult result = executor.executeOneRound(mountedRuntimeContext(task), worker.workerId());

            assertTrue(llmClient.firstUserPrompt.contains("Image Inputs Available: 2"));
            assertTrue(llmClient.firstUserPrompt.contains("D:\\gitAll\\open\\20260506-141826.png"));
            assertTrue(llmClient.firstUserPrompt.contains("D:\\gitAll\\open\\20260506-141916.jpg"));
            assertEquals(0, llmClient.firstImageInputs.size());
            assertEquals(true, result.metadata().get("image_input_used"));
            assertEquals(2, result.metadata().get("image_input_count"));
        }
    }

    @Test
    void planningPromptDefaultsToActiveContextOnlyMode() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("planning-active-only-workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "Reference note.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planning-active-only.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-active-only",
                "codex",
                List.of("coding"),
                List.of("search_text"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy));

            CapturingSequencedLlmClient llmClient = new CapturingSequencedLlmClient(List.of(
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"No tool required for this test.\"}"
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
                "task_multi_active_only",
                "session_multi_active_only",
                null,
                "planning prompt active only",
                "active",
                "high",
                Instant.parse("2026-05-06T06:55:00Z"),
                Instant.parse("2026-05-06T06:55:00Z"),
                null,
                null,
                null,
                null,
                "Ensure default planning prompt keeps mounted context behind the seam.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of("intent", "Inspect active context before deciding tool use.")
            );
            sessionDao.insert(Session.create(task.sessionId(), "active-only planning", "active"));
            taskDao.insert(task);

            WorkerExecutionResult result = executor.executeOneRound(mountedRuntimeContext(task), worker.workerId());

            assertFalse(llmClient.firstUserPrompt.contains("Mounted Context:"));
            assertTrue(llmClient.firstUserPrompt.contains("Active Context:"));
            assertEquals("active_context_only", result.metadata().get("prompt_mode"));
            assertEquals(false, result.metadata().get("mounted_render_used"));
            assertEquals(1, result.metadata().get("mounted_pinned_count"));
        }
    }

    @Test
    void planningPromptShadowModeKeepsMountedContextOutOfMainPrompt() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("planning-shadow-workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "Reference note.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planning-shadow.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-shadow",
                "codex",
                List.of("coding"),
                List.of("search_text"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy));

            CapturingSequencedLlmClient llmClient = new CapturingSequencedLlmClient(List.of(
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"No tool required for this test.\"}"
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
                "task_multi_shadow",
                "session_multi_shadow",
                null,
                "planning prompt shadow",
                "active",
                "high",
                Instant.parse("2026-05-06T06:56:00Z"),
                Instant.parse("2026-05-06T06:56:00Z"),
                null,
                null,
                null,
                null,
                "Ensure shadow mode computes mounted context without replacing the planning prompt.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Inspect mounted context in shadow mode before deciding tool use.",
                    "prompt_rendering_mode", "mounted_context_shadow"
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "shadow planning", "active"));
            taskDao.insert(task);

            WorkerExecutionResult result = executor.executeOneRound(mountedRuntimeContext(task), worker.workerId());

            assertFalse(llmClient.firstUserPrompt.contains("Mounted Context:"));
            assertTrue(llmClient.firstUserPrompt.contains("Active Context:"));
            assertEquals("mounted_context_shadow", result.metadata().get("prompt_mode"));
            assertEquals(true, result.metadata().get("mounted_context_rendered"));
            assertEquals(false, result.metadata().get("mounted_context_injected"));
            assertEquals(true, result.metadata().get("mounted_render_used"));
            assertEquals(1, result.metadata().get("mounted_context_selection_trace_count"));
            assertEquals(1, result.metadata().get("mounted_context_rendered_panel_count"));
            assertEquals(1, result.metadata().get("mounted_context_rendered_object_count"));
            assertEquals(0, result.metadata().get("mounted_context_hidden_object_count"));
            assertEquals(1, result.metadata().get("mounted_context_rendered_selection_trace_count"));
            assertEquals(false, result.metadata().get("mounted_context_budget_truncated"));
        }
    }

    @Test
    void planningPromptPrimaryModeHandlesEmptyMountedViewSafely() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("planning-empty-mounted-workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "Reference note.\n");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planning-empty-mounted.db"))) {
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            Worker worker = new Worker(
                "tool-empty-mounted",
                "codex",
                List.of("coding"),
                List.of("search_text"),
                List.of(workspace.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            );
            workerRegistry.register(worker);

            ToolPolicy toolPolicy = new ToolPolicy();
            ToolRegistry toolRegistry = new ToolRegistry()
                .register(new SearchTextTool(workerRegistry, toolPolicy));

            CapturingSequencedLlmClient llmClient = new CapturingSequencedLlmClient(List.of(
                "{\"needs_tool\":false,\"tool_name\":\"\",\"tool_arguments\":{},\"reason\":\"No tool required for this test.\"}"
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
                "task_multi_empty_mounted",
                "session_multi_empty_mounted",
                null,
                "planning prompt primary empty mounted",
                "active",
                "high",
                Instant.parse("2026-05-06T06:57:00Z"),
                Instant.parse("2026-05-06T06:57:00Z"),
                null,
                null,
                null,
                null,
                "Ensure primary mode remains safe when mounted view is empty.",
                null,
                worker.workerId(),
                "scheduler",
                null,
                Map.of(
                    "intent", "Keep primary mode continuity-safe even when mounted view is empty.",
                    "prompt_rendering_mode", "mounted_context_primary"
                )
            );
            sessionDao.insert(Session.create(task.sessionId(), "primary empty mounted planning", "active"));
            taskDao.insert(task);

            WorkerExecutionResult result = executor.executeOneRound(emptyMountedRuntimeContext(task), worker.workerId());

            assertFalse(llmClient.firstUserPrompt.contains("Mounted Context:"));
            assertTrue(llmClient.firstUserPrompt.contains("Active Context:"));
            assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
            assertEquals(true, result.metadata().get("mounted_context_rendered"));
            assertEquals(false, result.metadata().get("mounted_context_injected"));
            assertEquals(false, result.metadata().get("mounted_render_used"));
            assertEquals(0, result.metadata().get("mounted_non_empty_panel_count"));
            assertEquals(0, result.metadata().get("mounted_context_selection_trace_count"));
        }
    }

    private TaskRuntimeContext mountedRuntimeContext(Task task) {
        ActiveContext activeContext = new ActiveContext(
            "Mounted planning",
            List.of("priority=high"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: mounted planning",
            12
        );
        MountedContextView mountedView = new MountedContextView(
            null,
            task.id(),
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "constraints",
                        "/sessions/" + task.sessionId() + "/tasks/" + task.id(),
                        ContextObjectType.CONSTRAINT,
                        "",
                        "Constraints",
                        "先看 mounted context，再决定是否用工具",
                        "",
                        Instant.parse("2026-05-06T06:51:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
        );
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), List.of(), activeContext, mountedView);
    }

    private TaskRuntimeContext emptyMountedRuntimeContext(Task task) {
        ActiveContext activeContext = new ActiveContext(
            "Mounted planning empty",
            List.of("priority=high"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: mounted planning empty",
            12
        );
        return new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            activeContext,
            MountedContextView.empty(task.id())
        );
    }

    private static class SequencedLlmClient implements LlmClient {
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

        @Override
        public String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
            return chat(systemPrompt, userPrompt);
        }
    }

    private static final class FailingImageSequencedLlmClient extends SequencedLlmClient {
        private FailingImageSequencedLlmClient(List<String> responses) {
            super(responses);
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
            throw new RuntimeException("simulated image-generation timeout");
        }
    }

    private static final class CapturingSequencedLlmClient extends SequencedLlmClient {
        private String firstUserPrompt = "";
        private List<LlmImageInput> firstImageInputs = List.of();
        private boolean captured;

        private CapturingSequencedLlmClient(List<String> responses) {
            super(responses);
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
            if (!captured) {
                firstUserPrompt = userPrompt;
                firstImageInputs = imageInputs == null ? List.of() : List.copyOf(imageInputs);
                captured = true;
            }
            return super.chat(systemPrompt, userPrompt, imageInputs);
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (!captured) {
                firstUserPrompt = userPrompt;
                firstImageInputs = List.of();
                captured = true;
            }
            return super.chat(systemPrompt, userPrompt);
        }
    }
}
