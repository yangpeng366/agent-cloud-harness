package com.agentcloud.store;

import com.agentcloud.model.Decision;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DecisionDao extends SqlObject {

    @SqlUpdate("INSERT INTO decisions (id, session_id, task_id, created_at, decision_type, summary, rationale, impact_level, supersedes_decision_id, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :decisionType, :summary, :rationale, :impactLevel, :supersedesDecisionId, :metadata)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt, @Bind("decisionType") String decisionType,
                @Bind("summary") String summary, @Bind("rationale") String rationale,
                @Bind("impactLevel") String impactLevel, @Bind("supersedesDecisionId") String supersedesDecisionId,
                @Bind("metadata") String metadata);

    default void insert(Decision d) {
        insert(d.id(), d.sessionId(), d.taskId(), d.createdAt(), d.decisionType(),
               d.summary(), d.rationale(), d.impactLevel(), d.supersedesDecisionId(), JsonMapper.toJson(d.metadata()));
    }

    @SqlQuery("SELECT * FROM decisions WHERE id = :id")
    Optional<Decision> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM decisions WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<Decision> listBySessionAndTask(@Bind("sessionId") String sessionId, @Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM decisions WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<Decision> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);
}
