package com.agentcloud.store;

import com.agentcloud.model.AgentAction;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentActionDao extends SqlObject {

    @SqlUpdate("INSERT INTO agent_actions (id, session_id, task_id, source_execution_id, action_type, status, summary, payload_json, risk_level, requires_approval, accepted_by, rejection_reason, created_at, updated_at, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :sourceExecutionId, :actionType, :status, :summary, :payload, :riskLevel, :requiresApproval, :acceptedBy, :rejectionReason, :createdAt, :updatedAt, :metadata)")
    void insert(@Bind("id") String id,
                @Bind("sessionId") String sessionId,
                @Bind("taskId") String taskId,
                @Bind("sourceExecutionId") String sourceExecutionId,
                @Bind("actionType") String actionType,
                @Bind("status") String status,
                @Bind("summary") String summary,
                @Bind("payload") String payload,
                @Bind("riskLevel") String riskLevel,
                @Bind("requiresApproval") boolean requiresApproval,
                @Bind("acceptedBy") String acceptedBy,
                @Bind("rejectionReason") String rejectionReason,
                @Bind("createdAt") Instant createdAt,
                @Bind("updatedAt") Instant updatedAt,
                @Bind("metadata") String metadata);

    default void insert(AgentAction action) {
        insert(
            action.id(),
            action.sessionId(),
            action.taskId(),
            action.sourceExecutionId(),
            action.actionType(),
            action.status(),
            action.summary(),
            JsonMapper.toJson(action.payload()),
            action.riskLevel(),
            Boolean.TRUE.equals(action.requiresApproval()),
            action.acceptedBy(),
            action.rejectionReason(),
            action.createdAt(),
            action.updatedAt(),
            JsonMapper.toJson(action.metadata())
        );
    }

    @SqlQuery("SELECT * FROM agent_actions WHERE id = :id")
    Optional<AgentAction> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM agent_actions WHERE task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<AgentAction> listByTask(@Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM agent_actions WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<AgentAction> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM agent_actions WHERE action_type = :actionType ORDER BY created_at DESC LIMIT :limit")
    List<AgentAction> listByType(@Bind("actionType") String actionType, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM agent_actions WHERE status = :status ORDER BY created_at DESC LIMIT :limit")
    List<AgentAction> listByStatus(@Bind("status") String status, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM agent_actions WHERE action_type = :actionType AND status = :status ORDER BY created_at DESC LIMIT :limit")
    List<AgentAction> listByTypeAndStatus(@Bind("actionType") String actionType,
                                          @Bind("status") String status,
                                          @Bind("limit") int limit);
}
