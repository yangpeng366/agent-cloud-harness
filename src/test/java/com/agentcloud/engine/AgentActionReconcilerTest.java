package com.agentcloud.engine;

import com.agentcloud.model.AgentActionDraft;
import com.agentcloud.model.AgentActionReconciliationResult;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Event;
import com.agentcloud.model.Relation;
import com.agentcloud.model.Task;
import com.agentcloud.store.AgentActionDao;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.RelationDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.worker.WorkerExecutionResult;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActionReconcilerTest {

    @Test
    void reconcileAcceptsRejectsAndSurfacesApprovalBoundaries() {
        InMemoryEventDao eventDao = new InMemoryEventDao();
        InMemoryArtifactDao artifactDao = new InMemoryArtifactDao();
        InMemoryAgentActionDao agentActionDao = new InMemoryAgentActionDao();
        InMemoryCheckpointDao checkpointDao = new InMemoryCheckpointDao();
        InMemoryTaskDao taskDao = new InMemoryTaskDao();
        InMemoryRelationDao relationDao = new InMemoryRelationDao();
        AgentActionReconciler reconciler = new AgentActionReconciler(
            eventDao,
            artifactDao,
            agentActionDao,
            checkpointDao,
            taskDao,
            relationDao
        );
        Task task = task();

        WorkerExecutionResult result = new WorkerExecutionResult(
            "summary",
            "output",
            false,
            "",
            "",
            "next",
            "high",
            "completed",
            List.of(),
            List.of(),
            List.of(
                new AgentActionDraft(
                    "WRITE_ARTIFACT",
                    "write final artifact",
                    Map.of("artifact_type", "report", "title", "Final Report", "content", "done"),
                    "low",
                    false,
                    "artifact produced",
                    "high"
                ),
                new AgentActionDraft(
                    "HANDOFF",
                    "missing target",
                    Map.of(),
                    "medium",
                    false,
                    "needs another worker",
                    "medium"
                ),
                new AgentActionDraft(
                    "SPAWN_SUBTASK",
                    "high risk fan out",
                    Map.of("title", "child", "goal", "check"),
                    "high",
                    false,
                    "fan out",
                    "medium"
                ),
                new AgentActionDraft(
                    "CHECKPOINT",
                    "save current action boundary",
                    Map.of("checkpoint_type", "agent_action_test"),
                    "low",
                    false,
                    "checkpoint before next step",
                    "high"
                ),
                new AgentActionDraft(
                    "SPAWN_SUBTASK",
                    "create child task",
                    Map.of("title", "child task", "goal", "do child work", "task_type", "coding"),
                    "medium",
                    false,
                    "child work is separate",
                    "medium"
                )
            ),
            List.of("need design context"),
            "all acceptance checks pass",
            "",
            List.of("fan_out_risk"),
            0,
            10L,
            Map.of("execution_id", "exec_1")
        );

        AgentActionReconciliationResult reconciliation = reconciler.reconcile(task, result);

        assertEquals(5, reconciliation.acceptedActions().size());
        assertEquals(1, reconciliation.rejectedActions().size());
        assertEquals(1, reconciliation.approvalNeededActions().size());
        assertEquals(7, reconciliation.decisions().size());
        assertEquals(1, artifactDao.artifacts.size());
        assertEquals("Final Report", artifactDao.artifacts.get(0).title());
        assertEquals(1, checkpointDao.checkpoints.size());
        assertEquals("agent_action_test", checkpointDao.checkpoints.get(0).checkpointType());
        assertEquals(1, taskDao.tasks.size());
        assertEquals("child task", taskDao.tasks.get(0).title());
        assertEquals(task.id(), taskDao.tasks.get(0).parentTaskId());
        assertEquals(1, relationDao.relations.size());
        assertEquals("spawns", relationDao.relations.get(0).relationType());
        assertEquals(7, agentActionDao.actions.size());
        assertTrue(agentActionDao.actions.stream().anyMatch(action -> "CHECKPOINT".equals(action.actionType())));
        assertTrue(agentActionDao.actions.stream().anyMatch(action -> "rejected".equals(action.status())));
        assertEquals(7, eventDao.events.size());
        assertTrue(eventDao.events.stream().anyMatch(event -> "agent_action_accepted".equals(event.eventType())));
        assertTrue(eventDao.events.stream().anyMatch(event -> "agent_action_rejected".equals(event.eventType())));
        assertTrue(eventDao.events.stream().anyMatch(event -> "agent_action_needs_approval".equals(event.eventType())));
        assertFalse(AgentActionReconciler.actionMaps(reconciliation.acceptedActions()).isEmpty());
    }

    private Task task() {
        return new Task(
            "task_action",
            "session_action",
            null,
            "action task",
            "active",
            "high",
            Instant.parse("2026-05-27T00:00:00Z"),
            Instant.parse("2026-05-27T00:00:00Z"),
            null,
            null,
            null,
            "summary",
            "goal",
            "next",
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding")
        );
    }

    private static final class InMemoryEventDao implements EventDao {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String eventType,
                           String actorType, String actorId, String summary, String payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Event e) {
            events.add(e);
        }

        @Override
        public Optional<Event> findById(String id) {
            return events.stream().filter(event -> event.id().equals(id)).findFirst();
        }

        @Override
        public List<Event> listBySession(String sessionId, int limit) {
            return events.stream().filter(event -> event.sessionId().equals(sessionId)).limit(limit).toList();
        }

        @Override
        public List<Event> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return events.stream()
                .filter(event -> event.sessionId().equals(sessionId) && event.taskId().equals(taskId))
                .limit(limit)
                .toList();
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

    private static final class InMemoryArtifactDao implements ArtifactDao {
        private final List<Artifact> artifacts = new ArrayList<>();

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String artifactType,
                           String title, String uri, String contentHash, String summary, String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Artifact a) {
            artifacts.add(a);
        }

        @Override
        public Optional<Artifact> findById(String id) {
            return artifacts.stream().filter(artifact -> artifact.id().equals(id)).findFirst();
        }

        @Override
        public List<Artifact> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return artifacts.stream()
                .filter(artifact -> artifact.sessionId().equals(sessionId) && artifact.taskId().equals(taskId))
                .limit(limit)
                .toList();
        }

        @Override
        public List<Artifact> listBySession(String sessionId, int limit) {
            return artifacts.stream().filter(artifact -> artifact.sessionId().equals(sessionId)).limit(limit).toList();
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

    private static final class InMemoryAgentActionDao implements AgentActionDao {
        private final List<com.agentcloud.model.AgentAction> actions = new ArrayList<>();

        @Override
        public void insert(String id, String sessionId, String taskId, String sourceExecutionId, String actionType,
                           String status, String summary, String payload, String riskLevel, boolean requiresApproval,
                           String acceptedBy, String rejectionReason, Instant createdAt, Instant updatedAt,
                           String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(com.agentcloud.model.AgentAction action) {
            actions.add(action);
        }

        @Override
        public Optional<com.agentcloud.model.AgentAction> findById(String id) {
            return actions.stream().filter(action -> action.id().equals(id)).findFirst();
        }

        @Override
        public List<com.agentcloud.model.AgentAction> listByTask(String taskId, int limit) {
            return actions.stream().filter(action -> action.taskId().equals(taskId)).limit(limit).toList();
        }

        @Override
        public List<com.agentcloud.model.AgentAction> listBySession(String sessionId, int limit) {
            return actions.stream().filter(action -> action.sessionId().equals(sessionId)).limit(limit).toList();
        }

        @Override
        public List<com.agentcloud.model.AgentAction> listByType(String actionType, int limit) {
            return actions.stream().filter(action -> action.actionType().equals(actionType)).limit(limit).toList();
        }

        @Override
        public List<com.agentcloud.model.AgentAction> listByStatus(String status, int limit) {
            return actions.stream().filter(action -> action.status().equals(status)).limit(limit).toList();
        }

        @Override
        public List<com.agentcloud.model.AgentAction> listByTypeAndStatus(String actionType, String status, int limit) {
            return actions.stream()
                .filter(action -> action.actionType().equals(actionType) && action.status().equals(status))
                .limit(limit)
                .toList();
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

    private static final class InMemoryCheckpointDao implements CheckpointDao {
        private final List<Checkpoint> checkpoints = new ArrayList<>();

        @Override
        public void insert(String id, String sessionId, String taskId, Instant createdAt, String checkpointType,
                           String consolidationSummary, String refinedPacket, String worldModelDelta, String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Checkpoint c) {
            checkpoints.add(c);
        }

        @Override
        public Optional<Checkpoint> findById(String id) {
            return checkpoints.stream().filter(checkpoint -> checkpoint.id().equals(id)).findFirst();
        }

        @Override
        public List<Checkpoint> listByTask(String taskId, int limit) {
            return checkpoints.stream().filter(checkpoint -> checkpoint.taskId().equals(taskId)).limit(limit).toList();
        }

        @Override
        public List<Checkpoint> listBySession(String sessionId, int limit) {
            return checkpoints.stream().filter(checkpoint -> checkpoint.sessionId().equals(sessionId)).limit(limit).toList();
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

    private static final class InMemoryTaskDao implements TaskDao {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public void insert(String id, String sessionId, String parentTaskId, String title, String status,
                           String priority, Instant createdAt, Instant updatedAt, Instant startedAt,
                           Instant completedAt, String ownerRole, String summary, String goal, String nextStep,
                           String assignedWorker, String controlNode, String waitingReason, String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Task t) {
            tasks.add(t);
        }

        @Override
        public Optional<Task> findById(String id) {
            return tasks.stream().filter(task -> task.id().equals(id)).findFirst();
        }

        @Override
        public List<Task> listBySession(String sessionId) {
            return tasks.stream().filter(task -> task.sessionId().equals(sessionId)).toList();
        }

        @Override
        public List<Task> listActiveBySession(String sessionId) {
            return tasks.stream()
                .filter(task -> task.sessionId().equals(sessionId) && List.of("active", "pending").contains(task.status()))
                .toList();
        }

        @Override
        public List<Task> listByStatus(String status) {
            return tasks.stream().filter(task -> task.status().equals(status)).toList();
        }

        @Override
        public List<Task> listRecent(int limit) {
            return tasks.stream().limit(limit).toList();
        }

        @Override
        public int updateState(String id, String status, Instant updatedAt, Instant completedAt, String summary,
                               String nextStep, String assignedWorker, String controlNode, String waitingReason,
                               String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateState(Task t) {
            return 0;
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

    private static final class InMemoryRelationDao implements RelationDao {
        private final List<Relation> relations = new ArrayList<>();

        @Override
        public void insert(String id, String sourceType, String sourceId, String relationType, String targetType,
                           String targetId, Instant createdAt, String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Relation r) {
            relations.add(r);
        }

        @Override
        public Optional<Relation> findById(String id) {
            return relations.stream().filter(relation -> relation.id().equals(id)).findFirst();
        }

        @Override
        public List<Relation> listBySource(String sourceType, String sourceId) {
            return relations.stream()
                .filter(relation -> relation.sourceType().equals(sourceType) && relation.sourceId().equals(sourceId))
                .toList();
        }

        @Override
        public List<Relation> listByTarget(String targetType, String targetId) {
            return relations.stream()
                .filter(relation -> relation.targetType().equals(targetType) && relation.targetId().equals(targetId))
                .toList();
        }

        @Override
        public List<Relation> listByRelationType(String relationType, int limit) {
            return relations.stream().filter(relation -> relation.relationType().equals(relationType)).limit(limit).toList();
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
