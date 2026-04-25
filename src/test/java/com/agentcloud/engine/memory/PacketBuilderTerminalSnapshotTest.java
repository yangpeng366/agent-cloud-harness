package com.agentcloud.engine.memory;

import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PacketBuilderTerminalSnapshotTest {

    @Test
    void doneTaskPacketKeepsDoneStatusAndClearsNextStep() {
        PacketBuilder builder = new PacketBuilder(
            emptyDao(DecisionDao.class),
            emptyDao(ArtifactDao.class),
            emptyDao(TaskDao.class)
        );

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withSummary("finished summary")
            .withControlNode("scheduler")
            .withNextStep("stale-next-step")
            .withStatus("done")
            .withControlNode("end")
            .withCompletedAt(Instant.parse("2026-04-24T11:10:00Z"))
            .withNextStep(null);
        Session session = Session.create("session_1", "demo session", "active");

        var packet = builder.buildResumePacket(task, session);

        assertEquals("done", packet.payload().get("task_status"));
        assertNull(packet.nextStep());
    }

    @SuppressWarnings("unchecked")
    private static <T> T emptyDao(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "emptyDao(" + type.getSimpleName() + ")";
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == List.class) {
                    return List.of();
                }
                if (returnType == Optional.class) {
                    return Optional.empty();
                }
                if (returnType == int.class || returnType == Integer.class) {
                    return 0;
                }
                if (returnType == boolean.class || returnType == Boolean.class) {
                    return false;
                }
                return null;
            }
        );
    }
}
