package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.List;
import java.util.Map;

public interface ProviderProtocol {

    String providerId();

    ProviderStatus detect(LocalCliProviderConfig.ResolvedConfig config);

    ProviderCliPlan buildPlan(LocalCliProviderConfig.ResolvedConfig config,
                              TaskRuntimeContext context,
                              String cwd,
                              CliCapabilityProfile profile);

    WorkerExecutionResult parseOutput(byte[] raw,
                                     ProviderCliPlan plan,
                                     long durationMs,
                                     Map<String, Object> baseMetadata);

    record ProviderStatus(boolean ready, String version, Map<String, Object> metadata) {
        public static ProviderStatus notReady() {
            return new ProviderStatus(false, null, Map.of());
        }
    }

    record ProviderCliPlan(List<String> command,
                          String promptPreview,
                          String model,
                          String stdinPrompt,
                          Map<String, String> environment,
                          String configuredBinary,
                          String executableTarget,
                          String launchMode,
                          CliCapabilityProfile cliProfile,
                          List<String> cliProfileAdjustments) {

        public ProviderCliPlan(List<String> command, String promptPreview, String model) {
            this(command, promptPreview, model, null, Map.of(), "", "", "direct", null, List.of());
        }

        public ProviderCliPlan(List<String> command, String promptPreview, String model, String stdinPrompt) {
            this(command, promptPreview, model, stdinPrompt, Map.of(), "", "", "direct", null, List.of());
        }

        public String commandPreview() {
            return String.join(" ", command);
        }
    }
}
