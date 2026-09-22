package com.agentcloud.agent.providers;

import java.util.List;
import java.util.Map;

/**
 * 声明式 Provider 协议配置，对应 harness-config.yml 中 providers[] 条目。
 * 启动时由 ProviderProtocolRegistry 转为 GenericCliProtocol 实例注册。
 *
 * <p>示例 YAML：
 * <pre>
 * - id: trae
 *   command: ["chat", "--mode", "agent", "{{prompt}}"]
 *   output_parser: text
 *   launch_mode: app_server
 *   environment:
 *     FOO: bar
 * </pre>
 */
public record ProviderProtocolConfig(
    String id,
    List<String> command,
    String outputParser,
    String launchMode,
    Map<String, String> environment,
    String binaryOverride,
    boolean prependConfiguredBinary
) {
    public ProviderProtocolConfig {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("provider id required");
        if (command == null) command = List.of();
        if (outputParser == null || outputParser.isBlank()) outputParser = "text";
        if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
        if (environment == null) environment = Map.of();
        if (binaryOverride != null && binaryOverride.isBlank()) binaryOverride = null;
    }
}
