package com.agentcloud.worker;

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
}
