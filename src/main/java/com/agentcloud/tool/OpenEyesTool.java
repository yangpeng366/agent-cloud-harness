package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the ACH worker tool registry to the local OpenEyes CLI / MCP server.
 *
 * Default OFF: requires `eyes` CLI on PATH. Reads `eyes_tool.subcommand` and
 * forwards optional arguments through `ProcessBuilder`. Output is captured
 * verbatim and parsed when possible (e.g. `windows list --title-contains`).
 */
public class OpenEyesTool extends AbstractCommandTool {

    public static final String TOOL_NAME = "openeyes";
    private static final Logger log = LoggerFactory.getLogger(OpenEyesTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OpenEyesTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Invoke the local OpenEyes CLI (`eyes <subcommand>`) for window/detect/click/browser_* operations.";
    }

    @Override
    public String argumentContract() {
        return "{\"subcommand\":\"windows list\",\"title_contains\":\"\",\"timeout_ms\":15000}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws Exception {
        String unavailable = HostToolAvailability.unavailableReason("openeyes");
        if (unavailable != null) {
            throw new IllegalStateException(unavailable);
        }
        com.agentcloud.model.Worker worker = requireWorker(request);

        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String subcommand = stringArg(arguments, "subcommand").trim();
        if (subcommand.isBlank()) {
            throw new IllegalArgumentException("subcommand is required");
        }
        if (subcommand.contains("\n") || subcommand.contains("\r")) {
            throw new IllegalArgumentException("subcommand must be a single line");
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add("eyes");
        for (String token : subcommand.split("\\s+")) {
            if (!token.isBlank()) {
                commandLine.add(token);
            }
        }
        Object optionalTitleContains = arguments.get("title_contains");
        if (optionalTitleContains != null && !optionalTitleContains.toString().isBlank()) {
            commandLine.add("--title-contains");
            commandLine.add(optionalTitleContains.toString());
        }
        Object optionalRegex = arguments.get("regex");
        if (optionalRegex != null && !optionalRegex.toString().isBlank()) {
            commandLine.add("--regex");
            commandLine.add(optionalRegex.toString());
        }
        Object optionalDryRun = arguments.get("dry_run");
        if (optionalDryRun != null && Boolean.parseBoolean(optionalDryRun.toString())) {
            commandLine.add("--dry-run");
        }

        Path cwd = resolveWorkingDirectory(request, worker);
        int timeoutMs = toolPolicy.resolveCommandTimeoutMs(arguments);
        int maxOutputChars = toolPolicy.resolveCommandMaxOutputChars(arguments);

        ToolResult result = executeProcess(commandLine, cwd, timeoutMs, maxOutputChars,
            "openeyes " + subcommand);

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("subcommand", subcommand);
        if (result.success()) {
            ObjectNode parsed = tryParse(result.output());
            if (parsed != null && parsed.isArray()) {
                metadata.put("openeyes_window_count", parsed.size());
                metadata.put("openeyes_first_title", firstTitle(parsed));
            } else if (parsed != null && parsed.has("serverInfo")) {
                metadata.put("openeyes_server", parsed.path("serverInfo").path("name").asText(""));
                metadata.put("openeyes_server_version", parsed.path("serverInfo").path("version").asText(""));
            }
        }
        log.info("openeyes tool subcommand={} success={} elapsed_ms={}",
            subcommand, result.success(), metadata.get("elapsed_ms"));
        return new ToolResult(result.success(), result.summary(), result.output(), metadata);
    }

    private static ObjectNode tryParse(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty() || !(trimmed.startsWith("[") || trimmed.startsWith("{"))) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstTitle(ObjectNode parsed) {
        JsonNode items = parsed;
        if (items == null || !items.isArray()) {
            return "";
        }
        ArrayNode array = (ArrayNode) items;
        if (array.isEmpty()) {
            return "";
        }
        JsonNode first = array.get(0);
        return first == null ? "" : first.path("title").asText("");
    }
}
