package com.agentcloud.store;

import com.agentcloud.model.Task;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskDao extends SqlObject {

    @SqlUpdate("INSERT INTO tasks (id, session_id, parent_task_id, title, status, priority, created_at, updated_at, started_at, completed_at, owner_role, summary, goal, next_step, assigned_worker, control_node, waiting_reason, metadata_json) " +
               "VALUES (:id, :sessionId, :parentTaskId, :title, :status, :priority, :createdAt, :updatedAt, :startedAt, :completedAt, :ownerRole, :summary, :goal, :nextStep, :assignedWorker, :controlNode, :waitingReason, :metadata)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("parentTaskId") String parentTaskId,
                @Bind("title") String title, @Bind("status") String status, @Bind("priority") String priority,
                @Bind("createdAt") Instant createdAt, @Bind("updatedAt") Instant updatedAt,
                @Bind("startedAt") Instant startedAt, @Bind("completedAt") Instant completedAt,
                @Bind("ownerRole") String ownerRole, @Bind("summary") String summary,
                @Bind("goal") String goal, @Bind("nextStep") String nextStep,
                @Bind("assignedWorker") String assignedWorker, @Bind("controlNode") String controlNode,
                @Bind("waitingReason") String waitingReason, @Bind("metadata") String metadata);

    default void insert(Task t) {
        insert(t.id(), t.sessionId(), t.parentTaskId(), t.title(), t.status(), t.priority(),
               t.createdAt(), t.updatedAt(), t.startedAt(), t.completedAt(), t.ownerRole(),
               t.summary(), t.goal(), t.nextStep(), t.assignedWorker(), t.controlNode(), t.waitingReason(),
               JsonMapper.toJson(t.metadata()));
    }

    @SqlQuery("SELECT * FROM tasks WHERE id = :id")
    Optional<Task> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM tasks WHERE session_id = :sessionId ORDER BY updated_at DESC")
    List<Task> listBySession(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT * FROM tasks WHERE session_id = :sessionId AND status IN ('active', 'pending') ORDER BY updated_at DESC")
    List<Task> listActiveBySession(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT * FROM tasks WHERE status = :status ORDER BY updated_at DESC")
    List<Task> listByStatus(@Bind("status") String status);

    @SqlQuery("SELECT * FROM tasks ORDER BY updated_at DESC LIMIT :limit")
    List<Task> listRecent(@Bind("limit") int limit);

    @SqlUpdate("UPDATE tasks SET status = :status, updated_at = :updatedAt, completed_at = COALESCE(:completedAt, completed_at), summary = :summary, next_step = :nextStep, assigned_worker = :assignedWorker, control_node = :controlNode, waiting_reason = :waitingReason WHERE id = :id")
    int updateState(@Bind("id") String id, @Bind("status") String status, @Bind("updatedAt") Instant updatedAt,
                    @Bind("completedAt") Instant completedAt,
                    @Bind("summary") String summary, @Bind("nextStep") String nextStep,
                    @Bind("assignedWorker") String assignedWorker, @Bind("controlNode") String controlNode,
                    @Bind("waitingReason") String waitingReason);

    default int updateState(Task t) {
        return updateState(t.id(), t.status(), Instant.now(), t.completedAt(), t.summary(), t.nextStep(),
                           t.assignedWorker(), t.controlNode(), t.waitingReason());
    }
}
