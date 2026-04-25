package com.agentcloud.store;

import com.agentcloud.model.LearningMemory;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LearningMemoryDao extends SqlObject {

    @SqlUpdate("INSERT INTO learning_memories " +
               "(id, session_id, task_id, created_at, memory_type, state, hint_key, summary, confidence_score, reinforcement_count, evidence_json, metadata_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :memoryType, :state, :hintKey, :summary, :confidenceScore, :reinforcementCount, :evidence, :metadata)")
    void insert(@Bind("id") String id,
                @Bind("sessionId") String sessionId,
                @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt,
                @Bind("memoryType") String memoryType,
                @Bind("state") String state,
                @Bind("hintKey") String hintKey,
                @Bind("summary") String summary,
                @Bind("confidenceScore") Double confidenceScore,
                @Bind("reinforcementCount") Integer reinforcementCount,
                @Bind("evidence") String evidence,
                @Bind("metadata") String metadata);

    default void insert(LearningMemory memory) {
        insert(memory.id(), memory.sessionId(), memory.taskId(), memory.createdAt(),
            memory.memoryType(), memory.state(), memory.hintKey(), memory.summary(),
            memory.confidenceScore(), memory.reinforcementCount(),
            JsonMapper.toJson(memory.evidence()), JsonMapper.toJson(memory.metadata()));
    }

    @SqlUpdate("UPDATE learning_memories SET state = :state, confidence_score = :confidenceScore, " +
               "reinforcement_count = :reinforcementCount, evidence_json = :evidence, metadata_json = :metadata " +
               "WHERE id = :id")
    void update(@Bind("id") String id,
                @Bind("state") String state,
                @Bind("confidenceScore") Double confidenceScore,
                @Bind("reinforcementCount") Integer reinforcementCount,
                @Bind("evidence") String evidence,
                @Bind("metadata") String metadata);

    default void update(LearningMemory memory) {
        update(memory.id(), memory.state(), memory.confidenceScore(), memory.reinforcementCount(),
            JsonMapper.toJson(memory.evidence()), JsonMapper.toJson(memory.metadata()));
    }

    @SqlQuery("SELECT * FROM learning_memories WHERE id = :id")
    Optional<LearningMemory> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM learning_memories WHERE task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    List<LearningMemory> listByTask(@Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM learning_memories WHERE memory_type = :memoryType ORDER BY created_at DESC LIMIT :limit")
    List<LearningMemory> listByType(@Bind("memoryType") String memoryType, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM learning_memories WHERE memory_type = :memoryType AND hint_key = :hintKey ORDER BY created_at DESC LIMIT 1")
    Optional<LearningMemory> findLatestByTypeAndHintKey(@Bind("memoryType") String memoryType, @Bind("hintKey") String hintKey);
}
