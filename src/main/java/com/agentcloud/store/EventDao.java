package com.agentcloud.store;

import com.agentcloud.model.Event;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventDao extends SqlObject {

    @SqlUpdate("INSERT INTO events (id, session_id, task_id, created_at, event_type, actor_type, actor_id, summary, payload_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :eventType, :actorType, :actorId, :summary, :payload)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt, @Bind("eventType") String eventType,
                @Bind("actorType") String actorType, @Bind("actorId") String actorId,
                @Bind("summary") String summary, @Bind("payload") String payload);

    default void insert(Event e) {
        insert(e.id(), e.sessionId(), e.taskId(), e.createdAt(), e.eventType(),
               e.actorType(), e.actorId(), e.summary(), JsonMapper.toJson(e.payload()));
    }

    @SqlQuery("SELECT * FROM events WHERE id = :id")
    Optional<Event> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM events WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<Event> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM events WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<Event> listBySessionAndTask(@Bind("sessionId") String sessionId, @Bind("taskId") String taskId, @Bind("limit") int limit);
}
