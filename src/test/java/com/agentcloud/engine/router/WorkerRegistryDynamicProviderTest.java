package com.agentcloud.engine.router;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.worker.ProviderExecutionSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkerRegistryDynamicProviderTest {

    @Test
    void dynamicProviderNativeWorkerIsNotBlockedBySupportMatrix() {
        String providerId = "local_dynamic_agent";
        ProviderExecutionSupport.registerProviderNativeCli(providerId);

        AgentProviderRegistry providers = new AgentProviderRegistry();
        providers.register(new LocalCliAgentProvider(
            providerId,
            "Local Dynamic Agent",
            List.of("coding"),
            Map.of("provider_discovery", true),
            "definitely-missing-local-dynamic-agent",
            null,
            null
        ));

        WorkerRegistry workers = new WorkerRegistry(providers);
        workers.registerProviderNativeWorker(providerId, List.of("coding"), Map.of("provider_discovery", true));

        WorkerRegistry.ReadinessCheck readiness = workers.checkReadiness(providerId);

        assertNotNull(workers.get(providerId));
        assertEquals(true, readiness.checks().get("executor_backend:provider_native_cli"));
        assertEquals(false, readiness.checks().get("provider:" + providerId));
    }
}
