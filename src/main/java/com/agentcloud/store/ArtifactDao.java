package com.agentcloud.store;

import com.agentcloud.model.Artifact;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArtifactDao extends SqlObject {

    @SqlUpdate("INSERT INTO artifacts (id, session_id, task_id, created_at, artifact_type, title, uri, content_hash, summary, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :artifactType, :title, :uri, :contentHash, :summary, :metadata)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt, @Bind("artifactType") String artifactType,
                @Bind("title") String title, @Bind("uri") String uri, @Bind("contentHash") String contentHash,
                @Bind("summary") String summary, @Bind("metadata") String metadata);

    default void insert(Artifact a) {
        insert(a.id(), a.sessionId(), a.taskId(), a.createdAt(), a.artifactType(),
               a.title(), a.uri(), a.contentHash(), a.summary(), JsonMapper.toJson(a.metadata()));
    }

    @SqlQuery("SELECT * FROM artifacts WHERE id = :id")
    Optional<Artifact> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM artifacts WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<Artifact> listBySessionAndTask(@Bind("sessionId") String sessionId, @Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM artifacts WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<Artifact> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);
}
