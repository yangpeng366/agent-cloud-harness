package com.agentcloud.store;

import com.agentcloud.model.*;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

public final class Mappers {
    private Mappers() {}

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        if (s == null || s.isBlank()) return null;
        // SQLite 可能存储为 "2026-04-21 13:46:44.123" (Timestamp 格式)
        if (s.contains(" ") && !s.contains("T")) {
            s = s.replace(" ", "T") + "Z";
        }
        return Instant.parse(s);
    }

    public static final RowMapper<Session> SESSION = (rs, ctx) -> new Session(
        rs.getString("id"), rs.getString("title"), rs.getString("status"),
        instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "closed_at"),
        rs.getString("root_task_id"), rs.getString("current_task_id"),
        rs.getString("summary"), JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<Task> TASK = (rs, ctx) -> new Task(
        rs.getString("id"), rs.getString("session_id"), rs.getString("parent_task_id"),
        rs.getString("title"), rs.getString("status"), rs.getString("priority"),
        instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
        rs.getString("owner_role"), rs.getString("summary"), rs.getString("goal"), rs.getString("next_step"),
        rs.getString("assigned_worker"), rs.getString("control_node"), rs.getString("waiting_reason"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<Decision> DECISION = (rs, ctx) -> new Decision(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("decision_type"), rs.getString("summary"),
        rs.getString("rationale"), rs.getString("impact_level"), rs.getString("supersedes_decision_id"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<Artifact> ARTIFACT = (rs, ctx) -> new Artifact(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("artifact_type"), rs.getString("title"),
        rs.getString("uri"), rs.getString("content_hash"), rs.getString("summary"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<Event> EVENT = (rs, ctx) -> new Event(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("event_type"), rs.getString("actor_type"),
        rs.getString("actor_id"), rs.getString("summary"),
        JsonMapper.fromJson(rs.getString("payload_json"))
    );

    public static final RowMapper<ResumePacket> RESUME_PACKET = (rs, ctx) -> new ResumePacket(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("packet_version"),
        rs.getString("active_task_summary"), rs.getString("decision_summary"), rs.getString("artifact_summary"),
        JsonMapper.listFromJson(rs.getString("open_questions_json")),
        rs.getString("next_step"), JsonMapper.fromJson(rs.getString("payload_json"))
    );

    public static final RowMapper<Relation> RELATION = (rs, ctx) -> new Relation(
        rs.getString("id"), rs.getString("source_type"), rs.getString("source_id"),
        rs.getString("relation_type"), rs.getString("target_type"), rs.getString("target_id"),
        instant(rs, "created_at"), JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<Skill> SKILL = (rs, ctx) -> new Skill(
        rs.getString("id"), rs.getString("name"), rs.getString("description"),
        JsonMapper.listFromJson(rs.getString("capability_tags_json")),
        JsonMapper.fromJson(rs.getString("input_schema_json")),
        JsonMapper.fromJson(rs.getString("output_schema_json")),
        (java.util.Map<String, Boolean>) (Map<?, ?>) JsonMapper.fromJson(rs.getString("dependencies_json")),
        rs.getString("risk_level"), rs.getInt("installed") == 1, rs.getInt("ready") == 1,
        instant(rs, "last_checked_at"), rs.getString("version"),
        JsonMapper.fromJson(rs.getString("metadata_json")),
        instant(rs, "created_at"), instant(rs, "updated_at")
    );

    public static final RowMapper<Checkpoint> CHECKPOINT = (rs, ctx) -> new Checkpoint(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("checkpoint_type"),
        rs.getString("consolidation_summary"),
        JsonMapper.fromJson(rs.getString("refined_packet_json")),
        JsonMapper.fromJson(rs.getString("world_model_delta_json")),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );
}
