package com.agentcloud.store;

import com.agentcloud.model.Checkpoint;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckpointDao extends SqlObject {

    @SqlUpdate("INSERT INTO checkpoints (id, session_id, task_id, created_at, checkpoint_type, consolidation_summary, refined_packet_json, world_model_delta_json, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :checkpointType, :consolidationSummary, :refinedPacket, :worldModelDelta, :metadata)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt, @Bind("checkpointType") String checkpointType,
                @Bind("consolidationSummary") String consolidationSummary, @Bind("refinedPacket") String refinedPacket,
                @Bind("worldModelDelta") String worldModelDelta, @Bind("metadata") String metadata);

    default void insert(Checkpoint c) {
        insert(c.id(), c.sessionId(), c.taskId(), c.createdAt(), c.checkpointType(),
               c.consolidationSummary(), JsonMapper.toJson(c.refinedPacket()),
               JsonMapper.toJson(c.worldModelDelta()), JsonMapper.toJson(c.metadata()));
    }

    @SqlQuery("SELECT * FROM checkpoints WHERE id = :id")
    Optional<Checkpoint> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM checkpoints WHERE task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<Checkpoint> listByTask(@Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM checkpoints WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<Checkpoint> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);
}
