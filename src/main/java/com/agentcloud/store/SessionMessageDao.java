package com.agentcloud.store;

import com.agentcloud.model.SessionMessage;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;

public interface SessionMessageDao extends SqlObject {

    @SqlUpdate("INSERT INTO session_messages (id, session_id, task_id, role, message_type, content, created_at, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :role, :messageType, :content, :createdAt, :metadata)")
    void insert(@Bind("id") String id,
                @Bind("sessionId") String sessionId,
                @Bind("taskId") String taskId,
                @Bind("role") String role,
                @Bind("messageType") String messageType,
                @Bind("content") String content,
                @Bind("createdAt") Instant createdAt,
                @Bind("metadata") String metadata);

    default void insert(SessionMessage message) {
        insert(
            message.id(),
            message.sessionId(),
            message.taskId(),
            message.role(),
            message.messageType(),
            message.content(),
            message.createdAt(),
            JsonMapper.toJson(message.metadata())
        );
    }

    @SqlQuery("SELECT * FROM (" +
              "SELECT * FROM session_messages WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit" +
              ") ORDER BY created_at ASC")
    List<SessionMessage> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM (" +
              "SELECT * FROM session_messages WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT :limit" +
              ") ORDER BY created_at ASC")
    List<SessionMessage> listBySessionAndTask(@Bind("sessionId") String sessionId,
                                              @Bind("taskId") String taskId,
                                              @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM session_messages WHERE id = :id")
    SessionMessage findById(@Bind("id") String id);

    @SqlUpdate("UPDATE session_messages SET task_id = :taskId, metadata_json = :metadata WHERE id = :id")
    int updateBinding(@Bind("id") String id,
                      @Bind("taskId") String taskId,
                      @Bind("metadata") String metadata);
}
