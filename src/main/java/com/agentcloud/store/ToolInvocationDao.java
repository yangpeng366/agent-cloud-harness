package com.agentcloud.store;

import com.agentcloud.model.ToolInvocationRecord;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;

public interface ToolInvocationDao extends SqlObject {

    @SqlUpdate("INSERT INTO tool_invocations " +
               "(id, session_id, task_id, worker_id, tool_name, arguments_json, result_summary, success, elapsed_ms, created_at, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :workerId, :toolName, :arguments, :resultSummary, :success, :elapsedMs, :createdAt, :metadata)")
    void insertRaw(@Bind("id") String id,
                   @Bind("sessionId") String sessionId,
                   @Bind("taskId") String taskId,
                   @Bind("workerId") String workerId,
                   @Bind("toolName") String toolName,
                   @Bind("arguments") String arguments,
                   @Bind("resultSummary") String resultSummary,
                   @Bind("success") boolean success,
                   @Bind("elapsedMs") Integer elapsedMs,
                   @Bind("createdAt") Instant createdAt,
                   @Bind("metadata") String metadata);

    default void insert(ToolInvocationRecord record) {
        insertRaw(
            record.id(),
            record.sessionId(),
            record.taskId(),
            record.workerId(),
            record.toolName(),
            JsonMapper.toJson(record.arguments()),
            record.resultSummary(),
            record.success(),
            record.elapsedMs(),
            record.createdAt(),
            JsonMapper.toJson(record.metadata())
        );
    }

    @SqlQuery("SELECT * FROM tool_invocations WHERE task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<ToolInvocationRecord> listByTask(@Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM tool_invocations WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<ToolInvocationRecord> listBySessionAndTask(@Bind("sessionId") String sessionId,
                                                    @Bind("taskId") String taskId,
                                                    @Bind("limit") int limit);
}
