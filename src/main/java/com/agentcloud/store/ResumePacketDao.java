package com.agentcloud.store;

import com.agentcloud.model.ResumePacket;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResumePacketDao extends SqlObject {

    @SqlUpdate("INSERT INTO resume_packets (id, session_id, task_id, created_at, packet_version, active_task_summary, decision_summary, artifact_summary, open_questions_json, next_step, payload_json) " +
               "VALUES (:id, :sessionId, :taskId, :createdAt, :packetVersion, :activeTaskSummary, :decisionSummary, :artifactSummary, :openQuestions, :nextStep, :payload)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("taskId") String taskId,
                @Bind("createdAt") Instant createdAt, @Bind("packetVersion") String packetVersion,
                @Bind("activeTaskSummary") String activeTaskSummary, @Bind("decisionSummary") String decisionSummary,
                @Bind("artifactSummary") String artifactSummary, @Bind("openQuestions") String openQuestions,
                @Bind("nextStep") String nextStep, @Bind("payload") String payload);

    default void insert(ResumePacket p) {
        insert(p.id(), p.sessionId(), p.taskId(), p.createdAt(), p.packetVersion(),
               p.activeTaskSummary(), p.decisionSummary(), p.artifactSummary(),
               JsonMapper.toJson(p.openQuestions()), p.nextStep(), JsonMapper.toJson(p.payload()));
    }

    @SqlQuery("SELECT * FROM resume_packets WHERE id = :id")
    Optional<ResumePacket> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM resume_packets WHERE session_id = :sessionId AND task_id = :taskId ORDER BY created_at DESC LIMIT 1")
    Optional<ResumePacket> getLatestByTask(@Bind("sessionId") String sessionId, @Bind("taskId") String taskId);

    @SqlQuery("SELECT * FROM resume_packets WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    List<ResumePacket> listBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);
}
