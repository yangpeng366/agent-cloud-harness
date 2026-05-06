package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 受控 git 只读检查工具。
 */
public class GitTool extends AbstractCommandTool {

    public GitTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public String description() {
        return "Run read-only git inspection commands inside the allowed working directory.";
    }

    @Override
    public String argumentContract() {
        return "{\"args\":[\"status\",\"--short\"],\"cwd\":\".\",\"timeout_ms\":15000}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws Exception {
        String unavailableReason = HostToolAvailability.unavailableReason(name());
        if (unavailableReason != null) {
            throw new IllegalStateException(unavailableReason);
        }

        Worker worker = requireWorker(request);
        List<String> args = commandArgs(request.arguments(), "git");
        toolPolicy.ensureGitArgsAllowed(args);

        Path cwd = resolveWorkingDirectory(request, worker);
        int timeoutMs = toolPolicy.resolveCommandTimeoutMs(request.arguments());
        int maxOutputChars = toolPolicy.resolveCommandMaxOutputChars(request.arguments());

        ArrayList<String> commandLine = new ArrayList<>();
        commandLine.add("git");
        commandLine.addAll(args);
        return executeProcess(commandLine, cwd, timeoutMs, maxOutputChars, "git " + String.join(" ", args));
    }
}
