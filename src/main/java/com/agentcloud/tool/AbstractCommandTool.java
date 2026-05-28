package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TextDecoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令类工具共享基类。
 */
abstract class AbstractCommandTool implements Tool {
    private static final Pattern ARGUMENT_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

    protected final WorkerRegistry workerRegistry;
    protected final ToolPolicy toolPolicy;

    protected AbstractCommandTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        this.workerRegistry = workerRegistry;
        this.toolPolicy = toolPolicy;
    }

    protected Worker requireWorker(ToolRequest request) {
        Worker worker = workerRegistry.get(request.workerId());
        if (worker == null) {
            throw new IllegalArgumentException("worker not found: " + request.workerId());
        }
        toolPolicy.ensureToolAllowed(worker, name());
        return worker;
    }

    protected Path resolveWorkingDirectory(ToolRequest request, Worker worker) {
        Path cwd = toolPolicy.resolveWorkingDirectory(worker, request.arguments(), request.taskMetadata());
        if (!java.nio.file.Files.exists(cwd)) {
            throw new IllegalArgumentException("cwd does not exist: " + cwd);
        }
        if (!java.nio.file.Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("cwd is not a directory: " + cwd);
        }
        return cwd;
    }

    protected String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        String result = value.toString();
        if (result.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }

    protected List<String> commandArgs(Map<String, Object> arguments, String commandPrefix) {
        Object rawArgs = arguments == null ? null : arguments.get("args");
        if (rawArgs instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                .filter(value -> value != null)
                .map(Object::toString)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        }

        String command = stringArg(arguments, "command").trim();
        if (!commandPrefix.isBlank() && command.regionMatches(true, 0, commandPrefix, 0, commandPrefix.length())) {
            command = command.substring(commandPrefix.length()).trim();
        }
        List<String> parts = splitArguments(command);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("args are required");
        }
        return parts;
    }

    protected ToolResult executeProcess(List<String> commandLine,
                                        Path cwd,
                                        int timeoutMs,
                                        int maxOutputChars,
                                        String displayCommand) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);

        long startedAt = System.currentTimeMillis();
        Process process = builder.start();
        OutputCapture capture = new OutputCapture(maxOutputChars);
        Thread drainer = Thread.ofVirtual().start(() -> capture.drain(process.getInputStream()));

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        drainer.join(2_000);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        int exitCode = finished ? process.exitValue() : -1;
        String output = capture.output();
        boolean success = finished && exitCode == 0;

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cwd", cwd.toString());
        metadata.put("command_line", commandLine);
        metadata.put("display_command", displayCommand);
        metadata.put("exit_code", exitCode);
        metadata.put("timed_out", !finished);
        metadata.put("truncated", capture.truncated());
        metadata.put("elapsed_ms", elapsedMs);
        metadata.put("output_chars", output.length());

        String summary = !finished
            ? displayCommand + " timed out after " + timeoutMs + " ms"
            : displayCommand + " exited with code " + exitCode;
        if (capture.truncated()) {
            summary += " (output truncated)";
        }
        if (!finished && output.isBlank()) {
            output = "command timed out after " + timeoutMs + " ms";
        }

        return new ToolResult(success, summary, output, metadata);
    }

    protected boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    protected List<String> windowsCmdCommand(String command) {
        return List.of("cmd.exe", "/d", "/c", command);
    }

    protected List<String> windowsPowerShellCommand(String command) {
        return List.of("powershell.exe", "-NoLogo", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command);
    }

    protected List<String> platformShellCommand(String command) {
        if (isWindows()) {
            return windowsCmdCommand(command);
        }
        return List.of("/bin/sh", "-lc", command);
    }

    private List<String> splitArguments(String command) {
        ArrayList<String> args = new ArrayList<>();
        Matcher matcher = ARGUMENT_PATTERN.matcher(command);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                args.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                args.add(matcher.group(2));
            } else if (matcher.group(3) != null) {
                args.add(matcher.group(3));
            }
        }
        return args;
    }

    private static final class OutputCapture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxOutputChars;
        private volatile boolean truncated;

        private OutputCapture(int maxOutputChars) {
            this.maxOutputChars = Math.max(1, maxOutputChars);
        }

        private void drain(InputStream input) {
            byte[] chunk = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    int remaining = maxOutputChars - buffer.size();
                    if (remaining > 0) {
                        int copyLength = Math.min(read, remaining);
                        buffer.write(chunk, 0, copyLength);
                        if (read > copyLength) {
                            truncated = true;
                        }
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                truncated = true;
            }
        }

        private boolean truncated() {
            return truncated;
        }

        private String output() {
            String text = TextDecoding.decodeExternalProcessOutput(buffer.toByteArray());
            return truncated ? text + "\n...[truncated]" : text;
        }
    }
}
