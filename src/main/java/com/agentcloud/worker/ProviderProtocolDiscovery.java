package com.agentcloud.worker;

import com.agentcloud.runtime.TextDecoding;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProviderProtocolDiscovery {
    private static final Logger log = LoggerFactory.getLogger(ProviderProtocolDiscovery.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final List<Path> searchPaths;

    public ProviderProtocolDiscovery() {
        this(List.of(
            Paths.get("providers.yaml"),
            Paths.get("providers.yml"),
            Paths.get("providers.json"),
            Paths.get("config", "providers.yaml"),
            Paths.get("config", "providers.yml"),
            Paths.get("config", "providers.json"),
            Paths.get(System.getProperty("user.home"), ".agentcloud", "providers.yaml"),
            Paths.get(System.getProperty("user.home"), ".agentcloud", "providers.yml"),
            Paths.get(System.getProperty("user.home"), ".agentcloud", "providers.json"),
            Paths.get("/etc", "agentcloud", "providers.yaml"),
            Paths.get("/etc", "agentcloud", "providers.yml"),
            Paths.get("/etc", "agentcloud", "providers.json")
        ));
    }

    public ProviderProtocolDiscovery(List<Path> searchPaths) {
        this.searchPaths = searchPaths;
    }

    public ProviderProtocolRegistry discover() {
        return discoverDetailed().registry();
    }

    public DiscoveryResult discoverDetailed() {
        ProviderProtocolRegistry registry = new ProviderProtocolRegistry();
        ArrayList<DiscoveredProvider> providers = new ArrayList<>();
        ArrayList<UnsupportedProvider> unsupportedProviders = new ArrayList<>();
        
        for (Path path : searchPaths) {
            if (Files.exists(path)) {
                try {
                    loadFromFile(path, registry, providers, unsupportedProviders);
                } catch (IOException e) {
                    log.warn("Provider protocol config ignored. path={} reason={}", path, e.getMessage());
                }
            }
        }
        
        return new DiscoveryResult(registry, List.copyOf(providers), List.copyOf(unsupportedProviders));
    }

    private void loadFromFile(Path path,
                              ProviderProtocolRegistry registry,
                              List<DiscoveredProvider> discoveredProviders,
                              List<UnsupportedProvider> unsupportedProviders) throws IOException {
        String content = Files.readString(path);
        ProvidersConfig config = isYamlPath(path)
            ? parseYamlLikeConfig(content)
            : JSON_MAPPER.readValue(content, ProvidersConfig.class);
        
        if (config.providers != null) {
            for (ProviderConfig provider : config.providers) {
                ProviderProtocol protocol = createProtocol(provider);
                if (protocol != null) {
                    registry.register(protocol);
                    DiscoveredProvider discovered = discoveredProvider(provider, path);
                    if (discovered != null) {
                        discoveredProviders.add(discovered);
                    }
                } else {
                    UnsupportedProvider unsupported = unsupportedProvider(provider, path);
                    if (unsupported != null) {
                        unsupportedProviders.add(unsupported);
                        log.warn("Provider protocol config skipped. provider={} protocol={} reason={}",
                            unsupported.id(), unsupported.protocol(), unsupported.reason());
                    }
                }
            }
        }
    }

    private boolean isYamlPath(Path path) {
        String name = path != null && path.getFileName() != null
            ? path.getFileName().toString().toLowerCase()
            : "";
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private ProvidersConfig parseYamlLikeConfig(String content) {
        ProvidersConfig result = new ProvidersConfig();
        java.util.ArrayList<ProviderConfig> providers = new java.util.ArrayList<>();
        ProviderConfig current = null;
        String section = "";
        String lastKey = "";
        for (String rawLine : content.split("\\R")) {
            String line = stripYamlComment(rawLine);
            if (line.trim().isBlank()) {
                continue;
            }
            String trimmed = stripBom(line.trim());
            if ("providers:".equals(trimmed)) {
                section = "providers";
                continue;
            }
            if ("providers".equals(section) && trimmed.startsWith("- ")) {
                String rest = trimmed.substring(2).trim();
                if (!lastKey.isBlank() && !rest.contains(":")) {
                    addYamlListValue(current, lastKey, unquote(rest));
                    continue;
                }
                current = new ProviderConfig();
                providers.add(current);
                if (!rest.isBlank()) {
                    applyYamlScalar(current, rest);
                }
                lastKey = "";
                continue;
            }
            if (current == null) {
                continue;
            }
            if (trimmed.endsWith(":")) {
                lastKey = trimmed.substring(0, trimmed.length() - 1).trim();
                continue;
            }
            if (trimmed.startsWith("- ") && !lastKey.isBlank()) {
                addYamlListValue(current, lastKey, unquote(trimmed.substring(2).trim()));
                continue;
            }
            if (("env".equals(lastKey) || "environment".equals(lastKey)) && trimmed.contains(":")) {
                addEnvironmentEntry(current, trimmed);
                continue;
            }
            applyYamlScalar(current, trimmed);
            int colon = trimmed.indexOf(':');
            lastKey = colon > 0 ? trimmed.substring(0, colon).trim() : "";
        }
        result.providers = providers;
        return result;
    }

    private String stripYamlComment(String line) {
        if (line == null) {
            return "";
        }
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private void applyYamlScalar(ProviderConfig config, String expression) {
        int colon = expression.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = expression.substring(0, colon).trim();
        String value = unquote(expression.substring(colon + 1).trim());
        switch (key) {
            case "id" -> config.id = value;
            case "displayName", "display_name", "name" -> config.displayName = value;
            case "type" -> config.type = value;
            case "protocol" -> config.protocol = value;
            case "outputParser", "output_parser" -> config.outputParser = value;
            case "launchMode", "launch_mode" -> config.launchMode = value;
            case "binaryPath", "binary_path" -> config.binaryPath = value;
            case "binary" -> config.binary = value;
            case "model" -> config.model = value;
            case "modelTier", "model_tier" -> config.modelTier = value;
            case "selectionPriority", "selection_priority" -> config.selectionPriority = parseInteger(value);
            case "command" -> config.command = parseYamlInlineList(value);
            case "args" -> config.args = parseYamlInlineList(value);
            case "dispatchProbeArgs", "dispatch_probe_args", "dispatchPreflightProbeArgs", "dispatch_preflight_probe_args" ->
                config.dispatchProbeArgs = parseYamlInlineList(value);
            case "capabilities" -> config.capabilities = parseYamlInlineList(value);
            default -> {
            }
        }
    }

    private void addYamlListValue(ProviderConfig config, String key, String value) {
        switch (key) {
            case "command" -> {
                java.util.ArrayList<String> command = new java.util.ArrayList<>(config.command != null ? config.command : List.of());
                command.add(value);
                config.command = command;
            }
            case "args" -> {
                java.util.ArrayList<String> args = new java.util.ArrayList<>(config.args != null ? config.args : List.of());
                args.add(value);
                config.args = args;
            }
            case "dispatchProbeArgs", "dispatch_probe_args", "dispatchPreflightProbeArgs", "dispatch_preflight_probe_args" -> {
                java.util.ArrayList<String> args = new java.util.ArrayList<>(
                    config.dispatchProbeArgs != null ? config.dispatchProbeArgs : List.of()
                );
                args.add(value);
                config.dispatchProbeArgs = args;
            }
            case "capabilities" -> {
                java.util.ArrayList<String> capabilities = new java.util.ArrayList<>(
                    config.capabilities != null ? config.capabilities : List.of()
                );
                capabilities.add(value);
                config.capabilities = capabilities;
            }
            case "env", "environment" -> {
                addEnvironmentEntry(config, value);
            }
            default -> {
            }
        }
    }

    private List<String> parseYamlInlineList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of(unquote(trimmed));
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        for (String item : body.split(",")) {
            items.add(unquote(item.trim()));
        }
        return items;
    }

    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
            || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void addEnvironmentEntry(ProviderConfig config, String expression) {
        int colon = expression.indexOf(':');
        if (colon < 0) {
            colon = expression.indexOf('=');
        }
        if (colon <= 0) {
            return;
        }
        java.util.LinkedHashMap<String, String> environment = new java.util.LinkedHashMap<>(
            config.environment != null ? config.environment : Map.of()
        );
        String key = expression.substring(0, colon).trim();
        String value = unquote(expression.substring(colon + 1).trim());
        if (!key.isBlank()) {
            environment.put(key, value);
            config.environment = environment;
        }
    }

    private ProviderProtocol createProtocol(ProviderConfig config) {
        String protocolType = effectiveProtocolType(config);
        if (protocolType == null) {
            return null;
        }
        
        return switch (protocolType.toLowerCase()) {
            case "generic", "native_cli_text", "native_cli_json", "native_cli_lines", "native_cli_stream_json" -> createGenericProtocol(config);
            case "deepseek" -> new DeepSeekProtocol();
            case "reasonix" -> new ReasonixProtocol();
            default -> null;
        };
    }

    private GenericCliProtocol createGenericProtocol(ProviderConfig config) {
        CommandTemplate command = effectiveCommand(config);
        if (command.parts().isEmpty()) {
            return null;
        }

        GenericCliProtocol.OutputParser parser = parseOutputParser(firstNonBlank(config.outputParser, parserFromProtocol(config.protocol)));
        Map<String, String> environment = firstNonNull(config.environment, config.env, Map.of());
        String launchMode = config.launchMode != null ? config.launchMode : "direct";

        return new GenericCliProtocol(
            config.id,
            command.parts(),
            parser,
            environment,
            launchMode,
            command.prependConfiguredBinary(),
            command.binaryOverride()
        );
    }

    private CommandTemplate effectiveCommand(ProviderConfig config) {
        if (config.command != null && !config.command.isEmpty()) {
            return new CommandTemplate(config.command, false, null);
        }
        String binary = firstNonBlank(config.binary, config.binaryPath);
        if (binary == null) {
            return new CommandTemplate(List.of(), false, null);
        }
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        if (config.args != null) {
            command.addAll(config.args);
        }
        command.add("{{prompt}}");
        return new CommandTemplate(command, true, binary);
    }

    private record CommandTemplate(List<String> parts, boolean prependConfiguredBinary, String binaryOverride) {}

    private DiscoveredProvider discoveredProvider(ProviderConfig config, Path sourcePath) {
        String id = firstNonBlank(config.id);
        if (id == null) {
            return null;
        }
        String configuredProtocolType = firstNonBlank(config.type, config.protocol);
        String protocolType = effectiveProtocolType(config);
        DiscoveryProbeResult probeResult = configuredProtocolType == null
            ? runStartupProtocolProbe(config, "startup_help_probe")
            : null;
        String binary = firstNonBlank(config.binary, config.binaryPath, firstCommandPart(config.command), id);
        List<String> capabilities = config.capabilities != null && !config.capabilities.isEmpty()
            ? List.copyOf(config.capabilities)
            : List.of("coding", "reading", "session");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("configured_from", sourcePath == null ? "provider_discovery" : sourcePath.toString());
        metadata.put("provider_discovery", true);
        metadata.put("provider_protocol", protocolType);
        if (configuredProtocolType == null && protocolType != null) {
            metadata.put("provider_protocol_inferred", true);
        }
        metadata.put("model_tier", firstNonBlank(config.modelTier, "strong"));
        metadata.put("selection_priority", config.selectionPriority != null ? config.selectionPriority : 60);
        if (config.model != null && !config.model.isBlank()) {
            metadata.put("configured_model", config.model);
        }
        if (config.dispatchProbeArgs != null && !config.dispatchProbeArgs.isEmpty()) {
            metadata.put("dispatch_probe_args", List.copyOf(config.dispatchProbeArgs));
            metadata.put("dispatch_probe_args_source", "provider_discovery_config");
        }
        appendStartupProtocolProbeMetadata(metadata, probeResult);
        return new DiscoveredProvider(
            id,
            firstNonBlank(config.displayName, id),
            capabilities,
            binary,
            protocolType,
            Map.copyOf(metadata)
        );
    }

    private DiscoveryProbeResult runStartupProtocolProbe(ProviderConfig config, String mode) {
        List<String> command = startupProtocolProbeCommand(config);
        if (command.isEmpty()) {
            return null;
        }
        long timeoutMs = durationPropertyMs("agentcloud.provider_discovery.probe_timeout_ms", 1_500L);
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new DiscoveryProbeResult(
                    mode,
                    command,
                    -1,
                    "probe timed out",
                    false,
                    "unknown"
                );
            }
            String output = TextDecoding.decodeExternalProcessOutput(process.getInputStream().readAllBytes()).trim();
            return new DiscoveryProbeResult(
                mode,
                command,
                process.exitValue(),
                truncate(firstNonBlankLine(output), 300),
                process.exitValue() == 0,
                suggestedParser(output)
            );
        } catch (IOException e) {
            return new DiscoveryProbeResult(
                mode,
                command,
                -1,
                truncate(e.getMessage(), 300),
                false,
                "unknown"
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DiscoveryProbeResult(
                mode,
                command,
                -1,
                "probe interrupted",
                false,
                "unknown"
            );
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> startupProtocolProbeCommand(ProviderConfig config) {
        String binary = firstNonBlank(config.binary, config.binaryPath);
        List<String> probeArgs = config.dispatchProbeArgs != null && !config.dispatchProbeArgs.isEmpty()
            ? config.dispatchProbeArgs
            : List.of("--help");
        if (binary != null) {
            ArrayList<String> command = new ArrayList<>();
            command.add(binary);
            command.addAll(probeArgs);
            return List.copyOf(command);
        }
        if (config.command == null || config.command.isEmpty()) {
            return List.of();
        }
        ArrayList<String> command = new ArrayList<>();
        boolean replacedPrompt = false;
        for (String part : config.command) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.contains("{{prompt}}")) {
                command.add(part.replace("{{prompt}}", String.join(" ", probeArgs)));
                replacedPrompt = true;
            } else {
                command.add(part);
            }
        }
        if (!replacedPrompt) {
            command.addAll(probeArgs);
        }
        return List.copyOf(command);
    }

    private void appendStartupProtocolProbeMetadata(Map<String, Object> metadata, DiscoveryProbeResult probeResult) {
        if (metadata == null || probeResult == null) {
            return;
        }
        metadata.put("provider_protocol_probe_mode", probeResult.mode());
        metadata.put("provider_protocol_probe_command_shape", probeCommandShape(probeResult.command()));
        metadata.put("provider_protocol_probe_exit_code", probeResult.exitCode());
        metadata.put("provider_protocol_probe_success", probeResult.success());
        metadata.put("provider_protocol_probe_suggested_parser", probeResult.suggestedParser());
        if (probeResult.outputPreview() != null && !probeResult.outputPreview().isBlank()) {
            metadata.put("provider_protocol_probe_output_preview", probeResult.outputPreview());
        }
    }

    private List<String> probeCommandShape(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        return command.stream()
            .skip(1)
            .filter(item -> item != null && !item.isBlank())
            .toList();
    }

    private String suggestedParser(String output) {
        if (output == null || output.isBlank()) {
            return "text";
        }
        String trimmed = output.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return "json";
        }
        long jsonLineCount = trimmed.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("{") && line.endsWith("}"))
            .limit(2)
            .count();
        return jsonLineCount >= 2 ? "stream_json" : "text";
    }

    private String firstNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        return output.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private long durationPropertyMs(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(100L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private UnsupportedProvider unsupportedProvider(ProviderConfig config, Path sourcePath) {
        String id = firstNonBlank(config.id);
        if (id == null) {
            return null;
        }
        String protocolType = effectiveProtocolType(config);
        if (protocolType == null) {
            return null;
        }
        String reason = switch (protocolType.toLowerCase()) {
            case "app_server_json_rpc" -> "app_server_json_rpc is only wired for built-in codex app-server, not dynamic provider discovery";
            case "mcp" -> "mcp dynamic provider protocol is not implemented";
            default -> "provider protocol is not supported by current dynamic discovery";
        };
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("configured_from", sourcePath == null ? "provider_discovery" : sourcePath.toString());
        metadata.put("provider_discovery", true);
        metadata.put("provider_discovery_supported", false);
        metadata.put("provider_protocol", protocolType);
        metadata.put("provider_discovery_unsupported_reason", reason);
        appendStartupProtocolProbeMetadata(metadata, runStartupProtocolProbe(config, "unsupported_startup_probe"));
        List<String> capabilities = config.capabilities != null && !config.capabilities.isEmpty()
            ? List.copyOf(config.capabilities)
            : List.of("coding", "reading", "session");
        return new UnsupportedProvider(
            id,
            firstNonBlank(config.displayName, id),
            capabilities,
            protocolType,
            reason,
            Map.copyOf(metadata)
        );
    }

    private String effectiveProtocolType(ProviderConfig config) {
        if (config == null) {
            return null;
        }
        String protocolType = firstNonBlank(config.type, config.protocol);
        if (protocolType != null) {
            return protocolType;
        }
        if ((config.command != null && !config.command.isEmpty()) || firstNonBlank(config.binary, config.binaryPath) != null) {
            return "native_cli_text";
        }
        return null;
    }

    private String firstCommandPart(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String first = command.get(0);
        return first == null || first.isBlank() ? null : first;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String parserFromProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol.toLowerCase()) {
            case "native_cli_json" -> "json";
            case "native_cli_lines" -> "lines";
            case "native_cli_stream_json" -> "stream_json";
            default -> "text";
        };
    }

    private GenericCliProtocol.OutputParser parseOutputParser(String parserName) {
        if (parserName == null) {
            return GenericCliProtocol.OutputParser.TEXT;
        }
        return switch (parserName.toLowerCase()) {
            case "json" -> GenericCliProtocol.OutputParser.JSON;
            case "lines" -> GenericCliProtocol.OutputParser.LINES;
            case "stream_json" -> GenericCliProtocol.OutputParser.STREAM_JSON;
            default -> GenericCliProtocol.OutputParser.TEXT;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static class ProvidersConfig {
        public List<ProviderConfig> providers;
    }

    public static class ProviderConfig {
        public String id;
        public String displayName;
        public String type;
        public String protocol;
        public List<String> command;
        public String binary;
        public List<String> args;
        public List<String> dispatchProbeArgs;
        public List<String> capabilities;
        public String outputParser;
        public Map<String, String> environment;
        public Map<String, String> env;
        public String launchMode;
        public String binaryPath;
        public String model;
        public String modelTier;
        public Integer selectionPriority;
    }

    public record DiscoveryResult(ProviderProtocolRegistry registry,
                                  List<DiscoveredProvider> providers,
                                  List<UnsupportedProvider> unsupportedProviders) {
        public DiscoveryResult(ProviderProtocolRegistry registry, List<DiscoveredProvider> providers) {
            this(registry, providers, List.of());
        }

        public DiscoveryResult {
            if (registry == null) registry = new ProviderProtocolRegistry();
            if (providers == null) providers = List.of();
            if (unsupportedProviders == null) unsupportedProviders = List.of();
        }
    }

    public record DiscoveredProvider(String id,
                                     String displayName,
                                     List<String> capabilities,
                                     String binary,
                                     String protocol,
                                     Map<String, Object> metadata) {
        public DiscoveredProvider {
            if (id == null) id = "";
            if (displayName == null || displayName.isBlank()) displayName = id;
            if (capabilities == null) capabilities = List.of();
            if (binary == null || binary.isBlank()) binary = id;
            if (metadata == null) metadata = Map.of();
        }
    }

    public record UnsupportedProvider(String id,
                                      String displayName,
                                      List<String> capabilities,
                                      String protocol,
                                      String reason,
                                      Map<String, Object> metadata) {
        public UnsupportedProvider {
            if (id == null) id = "";
            if (displayName == null || displayName.isBlank()) displayName = id;
            if (capabilities == null) capabilities = List.of();
            if (protocol == null) protocol = "";
            if (reason == null) reason = "provider protocol is not supported by current dynamic discovery";
            if (metadata == null) metadata = Map.of();
        }
    }

    private record DiscoveryProbeResult(String mode,
                                        List<String> command,
                                        int exitCode,
                                        String outputPreview,
                                        boolean success,
                                        String suggestedParser) {
        private DiscoveryProbeResult {
            if (mode == null) mode = "startup_help_probe";
            if (command == null) command = List.of();
            if (suggestedParser == null || suggestedParser.isBlank()) suggestedParser = "unknown";
        }
    }

    public static ProviderProtocolRegistry discoverWithDefaults() {
        ProviderProtocolRegistry registry = ProviderProtocolRegistry.defaultRegistry();
        ProviderProtocolDiscovery discovery = new ProviderProtocolDiscovery();
        ProviderProtocolRegistry discovered = discovery.discoverDetailed().registry();
        
        for (ProviderProtocol protocol : discovered.all()) {
            registry.register(protocol);
        }
        
        return registry;
    }
}
