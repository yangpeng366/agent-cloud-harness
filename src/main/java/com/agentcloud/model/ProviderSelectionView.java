package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderSelectionView(
    String taskId,
    String selectedProvider,
    String providerDisplayName,
    boolean providerReady,
    String providerAuthStatus,
    String providerVersion,
    String workerRole,
    String selectedWorkerId,
    String selectedModelTier,
    String selectionReason,
    String fallbackReason,
    List<String> candidateProviders,
    Map<String, Object> metadata
) {
    public ProviderSelectionView {
        if (providerAuthStatus == null || providerAuthStatus.isBlank()) providerAuthStatus = "unknown";
        if (candidateProviders == null) candidateProviders = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
