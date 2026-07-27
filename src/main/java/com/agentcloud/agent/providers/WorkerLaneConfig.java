package com.agentcloud.agent.providers;

import java.util.List;
import java.util.Map;

/**
 * 单条声明式 worker lane 配置，对应 harness-config.yml 中的 workers[].entry。
 * 由 HarnessConfigLoader 解析后注入 WorkerRegistry。
 */
public record WorkerLaneConfig(
    String id,
    String provider,
    String modelTier,
    String costClass,
    Integer selectionPriority,
    List<String> capabilities,
    WorkerLaneProfileConfig profile,
    Map<String, Object> metadata
) {
    public WorkerLaneConfig {
        if (id == null) id = "";
        if (provider == null) provider = "codex";
        if (modelTier == null || modelTier.isBlank()) modelTier = "small";
        if (costClass == null || costClass.isBlank()) costClass = "free_auto";
        if (capabilities == null) capabilities = List.of("chat", "code", "session");
        if (profile == null) profile = new WorkerLaneProfileConfig(null, null);
        if (metadata == null) metadata = Map.of();
    }
}