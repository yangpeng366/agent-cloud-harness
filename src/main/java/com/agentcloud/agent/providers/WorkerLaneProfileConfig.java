package com.agentcloud.agent.providers;

/**
 * Worker lane 内的 provider profile 配置，对应 harness-config.yml 中的 workers[].profile。
 */
public record WorkerLaneProfileConfig(
    String model,
    String modelProvider
) {
    public WorkerLaneProfileConfig {
        if (model == null) model = "";
        if (modelProvider == null) modelProvider = "";
    }
}