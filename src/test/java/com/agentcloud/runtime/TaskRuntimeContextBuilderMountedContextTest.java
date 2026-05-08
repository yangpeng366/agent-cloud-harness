package com.agentcloud.runtime;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.ToolInvocationDao;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRuntimeContextBuilderMountedContextTest {

    @Test
    void buildAttachesMountedContextViewWithoutChangingExistingRuntimeFields() {
        Task task = new Task(
            "task_1",
            "session_1",
            null,
            "runtime context",
            "active",
            "high",
            Instant.parse("2026-05-06T05:00:00Z"),
            Instant.parse("2026-05-06T05:00:00Z"),
            null,
            null,
            null,
            "已有摘要",
            "实现 mounted context seam",
            "保持现有执行器行为",
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding")
        );
        Event event = new Event(
            "event_1",
            task.sessionId(),
            task.id(),
            Instant.parse("2026-05-06T05:00:01Z"),
            "task_progressed",
            "system",
            "control_graph",
            "进入兼容 mounted context 路径。",
            Map.of()
        );
        Decision decision = new Decision(
            "decision_1",
            task.sessionId(),
            task.id(),
            Instant.parse("2026-05-06T05:00:02Z"),
            "execution_judgment",
            "继续推进",
            "风险可控，继续构建并行视图。",
            "medium",
            null,
            Map.of(
                "judgment_stage", "execution",
                "selected_worker", "codex",
                "action", "continue",
                "next_step", "观察 seam 是否稳定",
                "tool_invocation_ids", List.of("tool_1"),
                "evidence_refs", List.of("tool:read_file:docs/ARCHITECTURE.md")
            )
        );
        Artifact artifact = new Artifact(
            "artifact_1",
            task.sessionId(),
            task.id(),
            Instant.parse("2026-05-06T05:00:03Z"),
            "worker_artifact",
            "Mounted view builder",
            null,
            null,
            "已实现 panel-based seam。",
            Map.of()
        );
        SessionMessage message = new SessionMessage(
            "msg_1",
            task.sessionId(),
            task.id(),
            "assistant",
            "task_progress",
            "mounted context view 已生成。",
            Instant.parse("2026-05-06T05:00:04Z"),
            Map.of()
        );
        ToolInvocationRecord toolInvocation = new ToolInvocationRecord(
            "tool_1",
            task.sessionId(),
            task.id(),
            "codex",
            "exec_1",
            "read_file",
            Map.of("path", "docs/ARCHITECTURE.md"),
            "读取 architecture 文档并提取 Phase 2B 要点。",
            "succeeded",
            true,
            88,
            List.of("docs/ARCHITECTURE.md"),
            Instant.parse("2026-05-06T05:00:04Z"),
            Map.of("trace_kind", "tool")
        );
        ResumePacket packet = new ResumePacket(
            "packet_1",
            task.sessionId(),
            task.id(),
            Instant.parse("2026-05-06T05:00:05Z"),
            "1.1",
            "保留原有 ActiveContext",
            "新增 mounted panels",
            "runtime context 已扩展",
            List.of("是否继续切 prompt?"),
            "观察 seam 是否稳定",
            Map.of(
                "assigned_worker", "codex",
                "current_status", "active",
                "current_node", "continue"
            )
        );
        Checkpoint checkpoint = new Checkpoint(
            "checkpoint_1",
            task.sessionId(),
            task.id(),
            Instant.parse("2026-05-06T05:00:06Z"),
            "periodic",
            "checkpoint summary",
            Map.of("key_decisions", List.of("保守演进")),
            Map.of(),
            Map.of()
        );

        TaskRuntimeContextBuilder builder = new TaskRuntimeContextBuilder(
            new StubEventDao(event),
            new StubDecisionDao(decision),
            new StubArtifactDao(artifact),
            new StubResumePacketDao(packet),
            new StubCheckpointDao(checkpoint),
            new StubToolInvocationDao(toolInvocation),
            new StubSessionMessageDao(message),
            new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            ),
            new StubLearningMemoryService()
        );

        TaskRuntimeContext runtimeContext = builder.build(task);

        assertEquals(1, runtimeContext.recentEvents().size());
        assertEquals(1, runtimeContext.recentDecisions().size());
        assertEquals(1, runtimeContext.recentArtifacts().size());
        assertEquals(1, runtimeContext.recentToolInvocations().size());
        assertEquals(1, runtimeContext.recentMessages().size());
        assertNotNull(runtimeContext.activeContext());
        assertNotNull(runtimeContext.mountedContextView());
        assertEquals(task.id(), runtimeContext.mountedContextView().taskId());
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.PINNED).stream()
            .anyMatch(object -> object.retentionState() == ContextRetentionState.PINNED));
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.RESUME_PACKET));
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.ARTIFACT));
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.TOOL_INVOCATION
                && object.summary().contains("architecture")));
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.DECISION
                && "execution".equals(object.metadata().get("judgment_stage"))
                && "codex".equals(object.metadata().get("selected_worker"))
                && object.summary().contains("action=continue")));
        assertTrue(runtimeContext.mountedContextView().objects(MountedContextPanelName.INDEX).stream()
            .anyMatch(object -> object.summary().contains("tool_invocations=1")));
        assertTrue(runtimeContext.mountedContextView().selectionTrace().stream()
            .anyMatch(item -> item.contains("evidence_decision_window=1/1")));
    }

    private static final class StubLearningMemoryService extends LearningMemoryService {
        private StubLearningMemoryService() {
            super(null);
        }

        @Override
        public List<String> contextRetentionHints(String taskType) {
            return List.of("优先保留关键约束");
        }
    }

    private record StubEventDao(Event event) implements EventDao {
        @Override
        public Optional<Event> findById(String id) {
            return Optional.ofNullable(event);
        }

        @Override
        public List<Event> listBySession(String sessionId, int limit) {
            return List.of(event);
        }

        @Override
        public List<Event> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return List.of(event);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String eventType, String actorType, String actorId, String summary, String payloadJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubDecisionDao(Decision decision) implements DecisionDao {
        @Override
        public Optional<Decision> findById(String id) {
            return Optional.ofNullable(decision);
        }

        @Override
        public List<Decision> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return List.of(decision);
        }

        @Override
        public List<Decision> listBySession(String sessionId, int limit) {
            return List.of(decision);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String decisionType, String summary, String rationale, String impactLevel, String supersedesDecisionId, String metadataJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubArtifactDao(Artifact artifact) implements ArtifactDao {
        @Override
        public Optional<Artifact> findById(String id) {
            return Optional.ofNullable(artifact);
        }

        @Override
        public List<Artifact> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return List.of(artifact);
        }

        @Override
        public List<Artifact> listBySession(String sessionId, int limit) {
            return List.of(artifact);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String artifactType, String title, String uri, String contentHash, String summary, String metadataJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubResumePacketDao(ResumePacket packet) implements ResumePacketDao {
        @Override
        public Optional<ResumePacket> findById(String id) {
            return Optional.ofNullable(packet);
        }

        @Override
        public Optional<ResumePacket> getLatestByTask(String sessionId, String taskId) {
            return Optional.of(packet);
        }

        @Override
        public List<ResumePacket> listByTask(String sessionId, String taskId, int limit) {
            return List.of(packet);
        }

        @Override
        public List<ResumePacket> listBySession(String sessionId, int limit) {
            return List.of(packet);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String packetVersion, String activeTaskSummary, String decisionSummary, String artifactSummary, String openQuestionsJson, String nextStep, String payloadJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubCheckpointDao(Checkpoint checkpoint) implements CheckpointDao {
        @Override
        public Optional<Checkpoint> findById(String id) {
            return Optional.ofNullable(checkpoint);
        }

        @Override
        public List<Checkpoint> listByTask(String taskId, int limit) {
            return List.of(checkpoint);
        }

        @Override
        public List<Checkpoint> listBySession(String sessionId, int limit) {
            return List.of(checkpoint);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String checkpointType, String consolidationSummary, String refinedPacketJson, String worldModelDeltaJson, String metadataJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubSessionMessageDao(SessionMessage message) implements SessionMessageDao {
        @Override
        public List<SessionMessage> listBySession(String sessionId, int limit) {
            return List.of(message);
        }

        @Override
        public List<SessionMessage> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return List.of(message);
        }

        @Override
        public void insert(String id, String sessionId, String taskId, String role, String messageType, String content, Instant createdAt, String metadataJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }

    private record StubToolInvocationDao(ToolInvocationRecord record) implements ToolInvocationDao {
        @Override
        public void insertRaw(String id, String sessionId, String taskId, String workerId, String executionId, String toolName, String arguments, String resultSummary, String status, boolean success, Integer elapsedMs, String touchedPaths, Instant createdAt, String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolInvocationRecord> listByTask(String taskId, int limit) {
            return List.of(record);
        }

        @Override
        public List<ToolInvocationRecord> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return List.of(record);
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }
}
