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
        statusCache.put(providerId, new CachedStatus(status, Instant.now()));
        return status;
    }

    private boolean cacheExpired(CachedStatus cached, Instant now) {
        if (cached == null || cached.cachedAt() == null) {
            return true;
        }
        return cached.cachedAt().plus(STATUS_CACHE_TTL).isBefore(now);
    }

    private record CachedStatus(AgentProviderStatus status, Instant cachedAt) {}
}
