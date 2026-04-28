package com.agentcloud.store;

import com.agentcloud.model.Session;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionDao extends SqlObject {

    @SqlUpdate("INSERT INTO sessions (id, title, status, created_at, updated_at, closed_at, root_task_id, current_task_id, summary, metadata_json) " +
               "VALUES (:id, :title, :status, :createdAt, :updatedAt, :closedAt, :rootTaskId, :currentTaskId, :summary, :metadata)")
    void insert(@Bind("id") String id, @Bind("title") String title, @Bind("status") String status,
                @Bind("createdAt") Instant createdAt, @Bind("updatedAt") Instant updatedAt,
                @Bind("closedAt") Instant closedAt, @Bind("rootTaskId") String rootTaskId,
                @Bind("currentTaskId") String currentTaskId, @Bind("summary") String summary,
                @Bind("metadata") String metadata);

    default void insert(Session s) {
        insert(s.id(), s.title(), s.status(), s.createdAt(), s.updatedAt(), s.closedAt(),
               s.rootTaskId(), s.currentTaskId(), s.summary(), JsonMapper.toJson(s.metadata()));
    }

    @SqlQuery("SELECT * FROM sessions WHERE id = :id")
    Optional<Session> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM sessions WHERE status = :status ORDER BY updated_at DESC")
    List<Session> listByStatus(@Bind("status") String status);

    @SqlQuery("SELECT * FROM sessions ORDER BY updated_at DESC")
    List<Session> listAll();

    @SqlUpdate("UPDATE sessions SET status = :status, updated_at = :updatedAt, closed_at = :closedAt, " +
               "current_task_id = :currentTaskId, summary = :summary WHERE id = :id")
    int updateState(@Bind("id") String id, @Bind("status") String status, @Bind("updatedAt") Instant updatedAt,
                    @Bind("closedAt") Instant closedAt, @Bind("currentTaskId") String currentTaskId,
                    @Bind("summary") String summary);

    default int updateState(String id, String status, Instant updatedAt, String currentTaskId, String summary) {
        return updateState(id, status, updatedAt, null, currentTaskId, summary);
    }

    @SqlUpdate("UPDATE sessions SET updated_at = :updatedAt WHERE id = :id")
    int touch(@Bind("id") String id, @Bind("updatedAt") Instant updatedAt);

    default List<Session> listActive() {
        return listByStatus("active");
    }
}
