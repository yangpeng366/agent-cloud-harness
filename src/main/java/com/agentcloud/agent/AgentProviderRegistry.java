package com.agentcloud.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class AgentProviderRegistry {
    private static final Duration STATUS_CACHE_TTL =
        Duration.ofSeconds(Long.getLong("agent.provider.status.cache.seconds", 5L));

    private final Map<String, AgentProvider> providers = new LinkedHashMap<>();
    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> cliProfileCache = new ConcurrentHashMap<>();

    public AgentProviderRegistry register(AgentProvider provider) {
        if (provider == null) {
            return this;
        }
        String providerId = provider.descriptor().providerId();
        providers.put(providerId, provider);
        statusCache.remove(providerId);
        return this;
    }

    public AgentProvider get(String providerId) {
        return providers.get(providerId);
    }

    public List<AgentProvider> list() {
        return new ArrayList<>(providers.values());
    }

    public AgentProviderStatus status(String providerId) {
        AgentProvider provider = providers.get(providerId);
        if (provider == null) {
            return null;
        }
        CachedStatus cached = statusCache.get(providerId);
        Instant now = Instant.now();
        if (cached != null && !cacheExpired(cached, now)) {
            return cached.status();
        }
        return refresh(providerId);
    }

    public List<AgentProviderStatus> listStatuses() {
        return providers.keySet().stream()
            .map(this::status)
            .filter(Objects::nonNull)
            .toList();
    }

    public AgentProviderStatus refresh(String providerId) {
        AgentProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("provider not found");
        }
        AgentProviderStatus status = provider.refreshStatus();
        AgentProviderStatus merged = mergeCachedCliProfile(providerId, status);
        rememberCliProfile(providerId, merged);
        statusCache.put(providerId, new CachedStatus(merged, Instant.now()));
        return merged;
    }

    public AgentProviderStatus dispatchPreflight(String providerId) {
        AgentProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("provider not found");
        }
        AgentProviderStatus status = provider.dispatchPreflight();
        rememberCliProfile(providerId, status);
        AgentProviderStatus merged = mergeCachedCliProfile(providerId, status);
        statusCache.put(providerId, new CachedStatus(merged, Instant.now()));
        return merged;
    }

    public Map<String, Object> cliProfileMetadata(String providerId) {
        Map<String, Object> cached = cliProfileCache.get(providerId);
        return cached == null ? Map.of() : cached;
    }

    private void rememberCliProfile(String providerId, AgentProviderStatus status) {
        if (providerId == null || status == null || status.metadata() == null || status.metadata().isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> profile = new LinkedHashMap<>();
        copyProfileKey(status.metadata(), profile, "cli_profile_evidence_available");
        copyProfileKey(status.metadata(), profile, "supports_yolo");
        copyProfileKey(status.metadata(), profile, "supports_model");
        copyProfileKey(status.metadata(), profile, "supports_json_output");
        copyProfileKey(status.metadata(), profile, "supports_resume");
        copyProfileKey(status.metadata(), profile, "supports_workspace_arg");
        copyProfileKey(status.metadata(), profile, "supports_work_dir_arg");
        copyProfileKey(status.metadata(), profile, "supports_output_file");
        if (!profile.isEmpty()) {
            profile.put("cli_profile_cached_at", Instant.now().toString());
            cliProfileCache.put(providerId, Map.copyOf(profile));
        }
    }

    private AgentProviderStatus mergeCachedCliProfile(String providerId, AgentProviderStatus status) {
        if (status == null) {
            return null;
        }
        Map<String, Object> cached = cliProfileCache.get(providerId);
        if (cached == null || cached.isEmpty()) {
            return status;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (status.metadata() != null) {
            metadata.putAll(status.metadata());
        }
        for (Map.Entry<String, Object> entry : cached.entrySet()) {
            metadata.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new AgentProviderStatus(
            status.providerId(),
            status.installed(),
            status.version(),
            status.authStatus(),
            status.ready(),
            status.readinessReason(),
            status.checkedAt(),
            Map.copyOf(metadata)
        );
    }

    private void copyProfileKey(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private boolean cacheExpired(CachedStatus cached, Instant now) {
        if (cached == null || cached.cachedAt() == null) {
            return true;
        }
        return cached.cachedAt().plus(STATUS_CACHE_TTL).isBefore(now);
    }

    private record CachedStatus(AgentProviderStatus status, Instant cachedAt) {}
}
