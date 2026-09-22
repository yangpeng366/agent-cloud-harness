package com.agentcloud.worker;

import com.agentcloud.agent.providers.HarnessConfig;
import com.agentcloud.agent.providers.ProviderProtocolConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ProviderProtocolRegistry {

    private final Map<String, ProviderProtocol> protocols = new LinkedHashMap<>();

    public ProviderProtocolRegistry() {
    }

    public ProviderProtocolRegistry register(ProviderProtocol protocol) {
        protocols.put(protocol.providerId().toLowerCase(), protocol);
        return this;
    }

    public ProviderProtocolRegistry registerGeneric(String providerId, List<String> commandTemplate) {
        return register(new GenericCliProtocol(providerId, commandTemplate));
    }

    public ProviderProtocolRegistry registerGeneric(String providerId, List<String> commandTemplate,
                                                   GenericCliProtocol.OutputParser parser) {
        return register(new GenericCliProtocol(providerId, commandTemplate, parser, Map.of(), "direct"));
    }

    public ProviderProtocolRegistry registerGeneric(String providerId, List<String> commandTemplate,
                                                   GenericCliProtocol.OutputParser parser,
                                                   Map<String, String> environment) {
        return register(new GenericCliProtocol(providerId, commandTemplate, parser, environment, "direct"));
    }

    public ProviderProtocol get(String providerId) {
        if (providerId == null) {
            return null;
        }
        return protocols.get(providerId.toLowerCase());
    }

    public ProviderProtocol getOrDefault(String providerId, Supplier<ProviderProtocol> defaultSupplier) {
        ProviderProtocol found = get(providerId);
        return found != null ? found : defaultSupplier.get();
    }

    public List<ProviderProtocol> all() {
        return List.copyOf(protocols.values());
    }

    public static ProviderProtocolRegistry defaultRegistry() {
        return new ProviderProtocolRegistry()
            .register(new ClaudeProtocol())
            .register(new CursorProtocol())
            .register(new DeepSeekProtocol())
            .register(new ReasonixProtocol())
            .register(new GeminiProtocol())
            .register(new KimiProtocol())
            .register(new CopilotProtocol())
            .register(new OpenCodeProtocol())
            .register(new CodeBuddyProtocol())
            .register(new DevecoProtocol())
            .register(new PiProtocol())
            .register(new TraeProtocol());
    }

    /**
     * 从 HarnessConfig 构建 registry：先加载内置默认，再用 YAML 声明的 providers 覆盖/追加。
     * YAML 中声明的 provider 会替换同 id 的内置协议。
     */
    public static ProviderProtocolRegistry fromConfig(HarnessConfig config) {
        ProviderProtocolRegistry registry = defaultRegistry();
        if (config != null && config.providers() != null) {
            for (ProviderProtocolConfig p : config.providers()) {
                GenericCliProtocol.OutputParser parser = parseOutputParser(p.outputParser());
                registry.register(new GenericCliProtocol(
                    p.id(), p.command(), parser, p.environment(),
                    p.launchMode(), p.prependConfiguredBinary(), p.binaryOverride()
                ));
            }
        }
        return registry;
    }

    private static GenericCliProtocol.OutputParser parseOutputParser(String name) {
        if (name == null || name.isBlank()) return GenericCliProtocol.OutputParser.TEXT;
        return switch (name.toLowerCase()) {
            case "json" -> GenericCliProtocol.OutputParser.JSON;
            case "lines" -> GenericCliProtocol.OutputParser.LINES;
            case "stream_json", "stream-json" -> GenericCliProtocol.OutputParser.STREAM_JSON;
            default -> GenericCliProtocol.OutputParser.TEXT;
        };
    }
}
