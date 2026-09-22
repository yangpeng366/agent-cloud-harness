package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenEyesToolTest {

    @TempDir
    Path tempDir;

    @Test
    void openeyesToolRequiresSubcommand() {
        WorkerRegistry workerRegistry = createWorkerRegistry();
        OpenEyesTool tool = new OpenEyesTool(workerRegistry, new ToolPolicy());

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.invoke(new ToolRequest(
                "session-openeyes",
                "task-openeyes",
                "openeyes-worker",
                "openeyes",
                Map.of("cwd", tempDir.toString())
            ))
        );
        assertTrue(error.getMessage().contains("subcommand is required"));
    }

    @Test
    void openeyesToolRunsEyesVersion() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.isToolCapabilityAvailable("openeyes"),
            "eyes CLI not on PATH; skipping real OpenEyes probe");

        WorkerRegistry workerRegistry = createWorkerRegistry();
        OpenEyesTool tool = new OpenEyesTool(workerRegistry, new ToolPolicy());

        ToolResult result = tool.invoke(new ToolRequest(
            "session-openeyes",
            "task-openeyes",
            "openeyes-worker",
            "openeyes",
            Map.of(
                "subcommand", "windows list",
                "cwd", tempDir.toString()
            ))
        );

        assertTrue(result.success(),
            "openeyes version should succeed; output=" + result.output());
        assertEquals("windows list", result.metadata().get("subcommand"));
        assertEquals(0, ((Number) result.metadata().get("exit_code")).intValue());
    }

    @Test
    void openeyesToolRejectsMultilineSubcommand() {
        WorkerRegistry workerRegistry = createWorkerRegistry();
        OpenEyesTool tool = new OpenEyesTool(workerRegistry, new ToolPolicy());

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.invoke(new ToolRequest(
                "session-openeyes",
                "task-openeyes",
                "openeyes-worker",
                "openeyes",
                Map.of("subcommand", "windows list\nrm -rf .", "cwd", tempDir.toString())
            ))
        );
        assertTrue(error.getMessage().contains("single line"));
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
}