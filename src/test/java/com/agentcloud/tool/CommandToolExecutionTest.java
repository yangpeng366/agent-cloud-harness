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

    private static final String POWERSHELL_SPAWN_FLAG = "ach.host.powershell.spawn";

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

    /**
     * 宿主缺陷（2026-11-28 实测：Windows 11 + JDK 21.0.9 + 本机安全防护链）：
     * 某些宿主机上 {@code new ProcessBuilder("powershell.exe", ...).start()} 永不返回——
     * 子进程被创建但初始线程一直不被调度，{@code ProcessImpl.create} 卡在 native 层。
     *
     * <p>更严重的是它持有 {@code ProcessImpl} 的 <b>类锁</b>：之后同 JVM 内任何
     * {@code ProcessBuilder.start()}（包括 git / cmd 用例与后续全部测试）都会
     * {@code BLOCKED} 在该锁上，整套测试从「单例挂起」变成「全 JVM 进程创建死亡」。
     * 同一台机器上由 cmd.exe 启动 powershell 只需 2 秒，说明不是 powershell 本身坏了。</p>
     *
     * <p>因此不在此处硬跑：默认启用（健康宿主照常覆盖），已确认宿主不健康时用
     * {@code -Dach.host.powershell.spawn=false} 显式关闭，避免一个宿主级缺陷拖死全量回归。
     * 判定与复现步骤见 {@code docs/host-powershell-spawn-guard.md}。</p>
     */
    @Test
    void powerShellToolRunsOnWindows() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.unavailableReason("powershell") == null,
            HostToolAvailability.unavailableReason("powershell"));
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(POWERSHELL_SPAWN_FLAG, "true")),
            "-" + POWERSHELL_SPAWN_FLAG + "=false：本机 powershell.exe 作为 Java 子进程创建会挂死并毒化 JVM 进程创建");

        WorkerRegistry workerRegistry = createWorkerRegistry("powershell");
        PowerShellTool powerShellTool = new PowerShellTool(workerRegistry, new ToolPolicy());

        ToolResult powerShellResult = powerShellTool.invoke(new ToolRequest(
            "session-ps",
            "task-ps",
            "command-worker",
            "powershell",
            Map.of("command", "Write-Output 'hello-powershell'", "cwd", ".")
        ));

        assertTrue(powerShellResult.success());
        assertTrue(powerShellResult.output().toLowerCase().contains("hello-powershell"));
    }

    @Test
    void cmdToolRunsOnWindows() throws Exception {
        Assumptions.assumeTrue(HostToolAvailability.unavailableReason("cmd") == null,
            HostToolAvailability.unavailableReason("cmd"));

        WorkerRegistry workerRegistry = createWorkerRegistry("cmd");
        CmdTool cmdTool = new CmdTool(workerRegistry, new ToolPolicy());

        ToolResult cmdResult = cmdTool.invoke(new ToolRequest(
            "session-cmd",
            "task-cmd",
            "command-worker",
            "cmd",
            Map.of("command", "echo hello-cmd", "cwd", ".")
        ));

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
