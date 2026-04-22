package com.agentcloud.store;

import com.agentcloud.model.Relation;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RelationDao extends SqlObject {

    @SqlUpdate("INSERT INTO relations (id, source_type, source_id, relation_type, target_type, target_id, created_at, metadata_json) " +
               "VALUES (:id, :sourceType, :sourceId, :relationType, :targetType, :targetId, :createdAt, :metadata)")
    void insert(@Bind("id") String id, @Bind("sourceType") String sourceType, @Bind("sourceId") String sourceId,
                @Bind("relationType") String relationType, @Bind("targetType") String targetType,
                @Bind("targetId") String targetId, @Bind("createdAt") Instant createdAt, @Bind("metadata") String metadata);

    default void insert(Relation r) {
        insert(r.id(), r.sourceType(), r.sourceId(), r.relationType(), r.targetType(), r.targetId(), r.createdAt(), JsonMapper.toJson(r.metadata()));
    }

    @SqlQuery("SELECT * FROM relations WHERE id = :id")
    Optional<Relation> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM relations WHERE source_type = :sourceType AND source_id = :sourceId ORDER BY created_at DESC")
    List<Relation> listBySource(@Bind("sourceType") String sourceType, @Bind("sourceId") String sourceId);

    @SqlQuery("SELECT * FROM relations WHERE target_type = :targetType AND target_id = :targetId ORDER BY created_at DESC")
    List<Relation> listByTarget(@Bind("targetType") String targetType, @Bind("targetId") String targetId);

    @SqlQuery("SELECT * FROM relations WHERE relation_type = :relationType ORDER BY created_at DESC LIMIT :limit")
    List<Relation> listByRelationType(@Bind("relationType") String relationType, @Bind("limit") int limit);
}
