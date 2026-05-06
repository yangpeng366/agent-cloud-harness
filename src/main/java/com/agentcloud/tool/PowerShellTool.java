package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;

import java.nio.file.Path;
import java.util.List;

/**
 * Windows PowerShell 工具。
 */
public class PowerShellTool extends AbstractCommandTool {

    public PowerShellTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "powershell";
    }

    @Override
    public String description() {
        return "Run a guarded PowerShell command inside the allowed working directory.";
    }

    @Override
    public String argumentContract() {
        return "{\"command\":\"Get-ChildItem .\",\"cwd\":\".\",\"timeout_ms\":15000}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws Exception {
        String unavailableReason = HostToolAvailability.unavailableReason(name());
        if (unavailableReason != null) {
            throw new IllegalStateException(unavailableReason);
        }

        Worker worker = requireWorker(request);
        String command = stringArg(request.arguments(), "command");
        toolPolicy.ensureCommandTextAllowed(name(), command);

        Path cwd = resolveWorkingDirectory(request, worker);
        int timeoutMs = toolPolicy.resolveCommandTimeoutMs(request.arguments());
        int maxOutputChars = toolPolicy.resolveCommandMaxOutputChars(request.arguments());
        List<String> commandLine = windowsPowerShellCommand(command);
        return executeProcess(commandLine, cwd, timeoutMs, maxOutputChars, "powershell " + command);
    }
}
