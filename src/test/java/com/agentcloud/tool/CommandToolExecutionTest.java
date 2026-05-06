package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandToolExecutionTest {

    @TempDir
    Path tempDir;

    @Test
    void shellToolExecutesGuardedEchoCommand() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.isToolCapabilityAvailable("shell"), "shell not available");

        WorkerRegistry workerRegistry = createWorkerRegistry("shell");
        ShellTool shellTool = new ShellTool(workerRegistry, new ToolPolicy());

        ToolResult result = shellTool.invoke(new ToolRequest(
            "session-shell",
            "task-shell",
            "command-worker",
            "shell",
            Map.of("command", "echo hello-shell", "cwd", ".")
        ));

        assertTrue(result.success());
        assertTrue(result.output().toLowerCase().contains("hello-shell"));
        assertEquals(0, ((Number) result.metadata().get("exit_code")).intValue());
    }

    @Test
    void shellToolRejectsDangerousCommandPattern() {
        WorkerRegistry workerRegistry = createWorkerRegistry("shell");
        ShellTool shellTool = new ShellTool(workerRegistry, new ToolPolicy());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> shellTool.invoke(new ToolRequest(
            "session-shell",
            "task-shell",
            "command-worker",
            "shell",
            Map.of("command", "rm -rf .", "cwd", ".")
        )));

        assertTrue(error.getMessage().contains("dangerous command rejected"));
    }

    @Test
    void gitToolRunsReadOnlyStatusInsideScopedRepository() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.isToolCapabilityAvailable("git"), "git not available");

        runExternal(tempDir, List.of("git", "init"));
        Files.writeString(tempDir.resolve("notes.txt"), "tracked later");

        WorkerRegistry workerRegistry = createWorkerRegistry("git");
        GitTool gitTool = new GitTool(workerRegistry, new ToolPolicy());

        ToolResult result = gitTool.invoke(new ToolRequest(
            "session-git",
            "task-git",
            "command-worker",
            "git",
            Map.of("args", List.of("status", "--short"), "cwd", ".")
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("notes.txt"));
        assertEquals(0, ((Number) result.metadata().get("exit_code")).intValue());
    }

    @Test
    void gitToolRejectsDisallowedSubcommand() {
        WorkerRegistry workerRegistry = createWorkerRegistry("git");
        GitTool gitTool = new GitTool(workerRegistry, new ToolPolicy());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> gitTool.invoke(new ToolRequest(
            "session-git",
            "task-git",
            "command-worker",
            "git",
            Map.of("args", List.of("reset", "--hard"), "cwd", ".")
        )));

        assertTrue(error.getMessage().contains("git subcommand not allowed"));
    }

    @Test
    void powerShellAndCmdToolsRunOnWindows() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.unavailableReason("powershell") == null,
            HostToolAvailability.unavailableReason("powershell"));
        Assumptions.assumeTrue(HostToolAvailability.unavailableReason("cmd") == null,
            HostToolAvailability.unavailableReason("cmd"));

        WorkerRegistry workerRegistry = createWorkerRegistry("powershell", "cmd");
        ToolPolicy toolPolicy = new ToolPolicy();
        PowerShellTool powerShellTool = new PowerShellTool(workerRegistry, toolPolicy);
        CmdTool cmdTool = new CmdTool(workerRegistry, toolPolicy);

        ToolResult powerShellResult = powerShellTool.invoke(new ToolRequest(
            "session-ps",
            "task-ps",
            "command-worker",
            "powershell",
            Map.of("command", "Write-Output 'hello-powershell'", "cwd", ".")
        ));
        ToolResult cmdResult = cmdTool.invoke(new ToolRequest(
            "session-cmd",
            "task-cmd",
            "command-worker",
            "cmd",
            Map.of("command", "echo hello-cmd", "cwd", ".")
        ));

        assertTrue(powerShellResult.success());
        assertTrue(powerShellResult.output().toLowerCase().contains("hello-powershell"));
        assertTrue(cmdResult.success());
        assertTrue(cmdResult.output().toLowerCase().contains("hello-cmd"));
    }

    private WorkerRegistry createWorkerRegistry(String... tools) {
        WorkerRegistry workerRegistry = new WorkerRegistry();
        workerRegistry.register(new Worker(
            "command-worker",
            "codex",
            List.of("ops"),
            List.of(tools),
            List.of(tempDir.toString()),
            Map.of("api_key", true),
            Map.of("model_tier", "strong"),
            false,
            true
        ));
        return workerRegistry;
    }

    private void runExternal(Path cwd, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("command failed: " + command);
        }
    }
}
