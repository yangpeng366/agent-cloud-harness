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
import com.agentcloud.tool.HostToolAvailability;
import com.agentcloud.tool.OpenEyesTool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenEyesToolChainE2ETest {

    private static final String WINDOW_TITLE = "ACHOpenEyesE2E";
    private static final String TYPED_TEXT = "hello";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void executorRunsFourStepOpenEyesChain() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openeyes.e2e.go"),
            "Pass -Dopeneyes.e2e.go=true to enable the OpenEyes four-step e2e (real UI side effects).");
        Assumptions.assumeTrue(HostToolAvailability.isToolCapabilityAvailable("openeyes"),
            "eyes CLI not on PATH; skipping OpenEyes e2e");
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
            "OpenEyes e2e requires Windows for the WinForms isolation window");
        Assumptions.assumeTrue(cursorInjectionAvailable(),
            "Windows cursor input injection is unavailable; skipping OpenEyes e2e");

        Path capturePath = tempDir.resolve("e2e-capture.png");
        Path scriptPath = tempDir.resolve("e2e-window.ps1");
        Files.writeString(scriptPath, isolationScript(), StandardCharsets.UTF_8);

        Process windowProcess = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString())
            .redirectErrorStream(true)
            .start();

        int hwnd;
        try {
            hwnd = waitForWindow(Duration.ofSeconds(25));
            assertNotEquals(0, hwnd, "isolated window hwnd must be discovered");

            try (DatabaseManager db = new DatabaseManager(tempDir.resolve("openeyes-e2e.db"))) {
                SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
                TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
                ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
                WorkerRegistry workerRegistry = createWorkerRegistry();
                Task task = createTask();
                persistTask(sessionDao, taskDao, task);

                QueuedLlmClient llmClient = new QueuedLlmClient(chainPlannerResponses(hwnd, capturePath.toString()),
                    "{\"summary\":\"OpenEyes four-step chain completed.\",\"output_text\":\"window -> click -> type -> capture verified.\",\"produced_artifact\":false,\"artifact_title\":\"\",\"artifact_content\":\"\",\"suggested_next_step\":\"\",\"confidence\":\"high\"}");

                ToolAwareWorkerExecutor executor = createExecutor(workerRegistry, toolInvocationDao, llmClient);
                WorkerExecutionResult result = executor.executeOneRound(
                    createRuntimeContext(task),
                    "openeyes-worker"
                );

                llmClient.assertExhausted();

                assertEquals("multi_tool_round", result.metadata().get("tool_execution_mode"));
                assertEquals(4, ((Number) result.metadata().get("tool_chain_step_count")).intValue());

                List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(task.id(), 10);
                assertEquals(4, invocations.size(), "four tool invocations must land in ToolInvocationDao");

                for (ToolInvocationRecord record : invocations) {
                    assertEquals("openeyes", record.toolName(), "all steps must go through OpenEyesTool");
                    assertTrue(record.success(), "openeyes step must succeed: " + record.resultSummary());
                    Number stepIndex = (Number) record.metadata().get("tool_chain_step_index");
                    assertNotNull(stepIndex, "tool_chain_step_index must be recorded");
                }

                List<String> subcommands = invocations.stream()
                    .sorted((a, b) -> Integer.compare(((Number) a.metadata().get("tool_chain_step_index")).intValue(),
                        ((Number) b.metadata().get("tool_chain_step_index")).intValue()))
                    .map(record -> ((String) record.arguments().get("subcommand")).trim())
                    .toList();
                assertEquals("windows list", subcommands.get(0));
                assertTrue(subcommands.get(1).startsWith("click"), "second step must be click: " + subcommands.get(1));
                assertTrue(subcommands.get(2).startsWith("type"), "third step must be type: " + subcommands.get(2));
                assertTrue(subcommands.get(3).startsWith("capture"), "fourth step must be capture: " + subcommands.get(3));

                assertTrue(Files.exists(capturePath), "capture PNG must exist: " + capturePath);
                long bytes = Files.size(capturePath);
                assertTrue(bytes > 1024, "capture PNG must be non-trivial (size=" + bytes + ")");
            }
        } finally {
            if (windowProcess != null) {
                if (windowProcess.isAlive()) {
                    windowProcess.destroy();
                }
                if (!windowProcess.waitFor(5, TimeUnit.SECONDS)) {
                    windowProcess.destroyForcibly();
                }
            }
        }
    }

    private boolean cursorInjectionAvailable() {
        try {
            Point original = MouseInfo.getPointerInfo().getLocation();
            Robot robot = new Robot();
            robot.mouseMove(1, 1);
            Point moved = MouseInfo.getPointerInfo().getLocation();
            if (moved.x != 1 || moved.y != 1) {
                return false;
            }
            robot.mouseMove(original.x, original.y);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
    private String[] chainPlannerResponses(int hwnd, String capturePath) {
        String clickSubcommand = "click --window " + hwnd + " --x 160 --y 120 --go";
        String typeSubcommand = "type --text " + TYPED_TEXT;
        String captureSubcommand = "capture --window " + hwnd + " --out " + capturePath + " --json";
        return new String[] {
            planJson("windows list", "Enumerate top-level windows so we can target the isolation form."),
            planJson(clickSubcommand, "Focus the isolation form before typing into it."),
            planJson(typeSubcommand, "Type hello into the isolation textbox."),
            planJson(captureSubcommand, "Capture a screenshot of the isolation form after typing.")
        };
    }

    private static String planJson(String subcommand, String reason) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"needs_tool\":true,\"tool_name\":\"openeyes\",\"tool_arguments\":{\"subcommand\":\"");
        sb.append(subcommand.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append("\"},\"reason\":\"");
        sb.append(reason.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append("\"}");
        return sb.toString();
    }

    private int waitForWindow(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastOutput = "";
        while (System.nanoTime() < deadline) {
            String output = runEyes("windows", "list", "--title-contains", WINDOW_TITLE);
            if (!output.isBlank()) {
                JsonNode root = MAPPER.readTree(output);
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        if (WINDOW_TITLE.equals(node.path("title").asText())) {
                            return node.path("hwnd").asInt(0);
                        }
                    }
                }
                lastOutput = output;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Failed to discover isolation window '" + WINDOW_TITLE + "': " + lastOutput);
    }

    private String hwndTitle(int hwnd, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String output = runEyes("windows", "list", "--title-contains", WINDOW_TITLE);
            if (!output.isBlank()) {
                JsonNode root = MAPPER.readTree(output);
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        if (node.path("hwnd").asInt(0) == hwnd) {
                            return node.path("title").asText("");
                        }
                    }
                }
            }
            Thread.sleep(250);
        }
        return "";
    }

    private String runEyes(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("eyes");
        for (String arg : args) {
            command.add(arg);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        return new String(stdout, StandardCharsets.UTF_8);
    }

    private static String isolationScript() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Add-Type -AssemblyName System.Windows.Forms\n");
        sb.append("Add-Type -AssemblyName System.Drawing\n");
        sb.append("$form = New-Object System.Windows.Forms.Form\n");
        sb.append("$form.Text = '").append(WINDOW_TITLE).append("'\n");
        sb.append("$form.StartPosition = 'Manual'\n");
        sb.append("$form.Location = New-Object System.Drawing.Point(80, 80)\n");
        sb.append("$form.ClientSize = New-Object System.Drawing.Size(320, 240)\n");
        sb.append("$form.BackColor = [System.Drawing.Color]::White\n");
        sb.append("$form.TopMost = $true\n");
        sb.append("$box = New-Object System.Windows.Forms.TextBox\n");
        sb.append("$box.Multiline = $true\n");
        sb.append("$box.Dock = 'Fill'\n");
        sb.append("$form.Controls.Add($box)\n");
        sb.append("$box.Add_TextChanged({\n");
        sb.append("    $form.Text = '").append(WINDOW_TITLE).append("-' + $box.Text\n");
        sb.append("})\n");
        sb.append("$null = $form.ShowDialog()\n");
        return sb.toString();
    }

    private ToolAwareWorkerExecutor createExecutor(WorkerRegistry workerRegistry,
                                                   ToolInvocationDao toolInvocationDao,
                                                   LlmClient llmClient) {
        ToolPolicy toolPolicy = new ToolPolicy();
        ToolRegistry toolRegistry = new ToolRegistry()
            .register(new OpenEyesTool(workerRegistry, toolPolicy));
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

    private WorkerRegistry createWorkerRegistry() {
        WorkerRegistry workerRegistry = new WorkerRegistry();
        workerRegistry.register(new Worker(
            "openeyes-worker",
            "native-tool",
            List.of("ui"),
            List.of("openeyes"),
            List.of(tempDir.toString()),
            Map.of("eyes_cli", true),
            Map.of("role", "ui_automation"),
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
        sessionDao.insert(Session.create(task.sessionId(), "openeyes e2e session", "active"));
        taskDao.insert(task);
    }

    private Task createTask() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent", "Drive isolation window via OpenEyes four-step chain.");
        return new Task(
            "task-openeyes-e2e",
            "session-openeyes-e2e",
            null,
            "OpenEyes four-step e2e",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            "Drive an isolation window via OpenEyes four-step chain.",
            "Drive an isolation window via OpenEyes four-step chain.",
            "",
            "openeyes-worker",
            "continue",
            null,
            metadata
        );
    }

    private static final class QueuedLlmClient implements LlmClient {
        private final ArrayDeque<String> responses = new ArrayDeque<>();

        private QueuedLlmClient(String[] initialResponses, String... responses) {
            for (String response : initialResponses) {
                this.responses.addLast(response.strip());
            }
            for (String response : responses) {
                this.responses.addLast(response.strip());
            }
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected llm call");
            }
            return responses.removeFirst();
        }

        private void assertExhausted() {
            assertTrue(responses.isEmpty(), "unconsumed llm responses: " + responses.size());
        }
    }
}