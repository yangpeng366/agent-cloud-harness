package com.agentcloud.store;

import com.agentcloud.model.AgentRunRecord;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRunDao extends SqlObject {

    @SqlUpdate("""
        INSERT INTO agent_runs (
            id, task_id, session_id, provider_id, provider_display_name, worker_role,
            selected_worker_id, selected_model_tier, status, started_at, ended_at,
            duration_ms, summary, last_event_type, artifact_count, metadata_json
        ) VALUES (
            :id, :taskId, :sessionId, :providerId, :providerDisplayName, :workerRole,
            :selectedWorkerId, :selectedModelTier, :status, :startedAt, :endedAt,
            :durationMs, :summary, :lastEventType, :artifactCount, :metadata
        )
        """)
    void insertRaw(@Bind("id") String id,
                   @Bind("taskId") String taskId,
                   @Bind("sessionId") String sessionId,
                   @Bind("providerId") String providerId,
                   @Bind("providerDisplayName") String providerDisplayName,
                   @Bind("workerRole") String workerRole,
                   @Bind("selectedWorkerId") String selectedWorkerId,
                   @Bind("selectedModelTier") String selectedModelTier,
                   @Bind("status") String status,
                   @Bind("startedAt") Instant startedAt,
                   @Bind("endedAt") Instant endedAt,
                   @Bind("durationMs") Long durationMs,
                   @Bind("summary") String summary,
                   @Bind("lastEventType") String lastEventType,
                   @Bind("artifactCount") int artifactCount,
                   @Bind("metadata") String metadata);

    default void insert(AgentRunRecord record) {
        insertRaw(
            record.runId(),
            record.taskId(),
            record.sessionId(),
            record.providerId(),
            record.providerDisplayName(),
            record.workerRole(),
            record.selectedWorkerId(),
            record.selectedModelTier(),
            record.status(),
            record.startedAt(),
            record.endedAt(),
            record.durationMs(),
            record.summary(),
            record.lastEventType(),
            record.artifactCount(),
            JsonMapper.toJson(record.metadata())
        );
    }

    @SqlQuery("SELECT * FROM agent_runs WHERE id = :runId")
    Optional<AgentRunRecord> findById(@Bind("runId") String runId);

    @SqlQuery("SELECT * FROM agent_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT 1")
    Optional<AgentRunRecord> latestByTask(@Bind("taskId") String taskId);

    @SqlQuery("SELECT * FROM agent_runs WHERE provider_id = :providerId ORDER BY started_at DESC LIMIT :limit")
    List<AgentRunRecord> listByProvider(@Bind("providerId") String providerId, @Bind("limit") int limit);

    @SqlQuery("""
        SELECT * FROM agent_runs
        WHERE provider_id = :providerId AND lower(status) = lower(:status)
        ORDER BY started_at DESC
        LIMIT :limit
        """)
    List<AgentRunRecord> listByProviderAndStatus(@Bind("providerId") String providerId,
                                                 @Bind("status") String status,
                                                 @Bind("limit") int limit);

    @SqlQuery("""
        SELECT * FROM agent_runs
        WHERE (:providerId IS NULL OR provider_id = :providerId)
          AND (:taskId IS NULL OR task_id = :taskId)
          AND (:status IS NULL OR lower(status) = lower(:status))
          AND (:workerRole IS NULL OR lower(worker_role) = lower(:workerRole))
        ORDER BY started_at DESC
        LIMIT :limit
        """)
    List<AgentRunRecord> search(@Bind("providerId") String providerId,
                                @Bind("status") String status,
                                @Bind("workerRole") String workerRole,
                                @Bind("taskId") String taskId,
                                @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM agent_runs ORDER BY started_at DESC LIMIT :limit")
    List<AgentRunRecord> listRecent(@Bind("limit") int limit);

    @SqlQuery("""
        SELECT * FROM agent_runs
        WHERE status IN ('queued', 'starting', 'running')
        ORDER BY started_at DESC
        LIMIT :limit
        """)
    List<AgentRunRecord> listActive(@Bind("limit") int limit);
}
