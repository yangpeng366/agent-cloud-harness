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

    public static final RowMapper<SessionMessage> SESSION_MESSAGE = (rs, ctx) -> new SessionMessage(
        rs.getString("id"),
        rs.getString("session_id"),
        rs.getString("task_id"),
        rs.getString("role"),
        rs.getString("message_type"),
        rs.getString("content"),
        instant(rs, "created_at"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
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

    public static final RowMapper<LearningMemory> LEARNING_MEMORY = (rs, ctx) -> new LearningMemory(
        rs.getString("id"), rs.getString("session_id"), rs.getString("task_id"),
        instant(rs, "created_at"), rs.getString("memory_type"), rs.getString("state"),
        rs.getString("hint_key"), rs.getString("summary"),
        rs.getDouble("confidence_score"), rs.getInt("reinforcement_count"),
        JsonMapper.fromJson(rs.getString("evidence_json")),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<ToolInvocationRecord> TOOL_INVOCATION = (rs, ctx) -> new ToolInvocationRecord(
        rs.getString("id"),
        rs.getString("session_id"),
        rs.getString("task_id"),
        rs.getString("worker_id"),
        rs.getString("execution_id"),
        rs.getString("tool_name"),
        JsonMapper.fromJson(rs.getString("arguments_json")),
        rs.getString("result_summary"),
        rs.getString("status"),
        rs.getInt("success") == 1,
        rs.getObject("elapsed_ms") != null ? rs.getInt("elapsed_ms") : null,
        JsonMapper.listFromJson(rs.getString("touched_paths_json")),
        instant(rs, "created_at"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<ExperimentRunRecord> EXPERIMENT_RUN = (rs, ctx) -> new ExperimentRunRecord(
        rs.getString("id"),
        rs.getString("session_id"),
        rs.getString("task_id"),
        rs.getString("experiment_name"),
        rs.getString("task_case_key"),
        rs.getString("task_title"),
        rs.getString("task_type"),
        rs.getString("task_length_bucket"),
        rs.getString("model_mode"),
        rs.getObject("total_steps") != null ? rs.getInt("total_steps") : null,
        rs.getString("completion_status"),
        rs.getString("acceptance_result"),
        rs.getObject("total_cost") != null ? rs.getDouble("total_cost") : null,
        rs.getObject("strong_model_cost_ratio") != null ? rs.getDouble("strong_model_cost_ratio") : null,
        rs.getObject("handoff_count") != null ? rs.getInt("handoff_count") : null,
        rs.getObject("resume_count") != null ? rs.getInt("resume_count") : null,
        rs.getObject("human_gate_count") != null ? rs.getInt("human_gate_count") : null,
        rs.getString("failure_reason"),
        rs.getObject("recovery_success") != null ? rs.getInt("recovery_success") == 1 : null,
        rs.getString("final_artifact_quality_note"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<AgentRunRecord> AGENT_RUN = (rs, ctx) -> new AgentRunRecord(
        rs.getString("id"),
        rs.getString("task_id"),
        rs.getString("session_id"),
        rs.getString("provider_id"),
        rs.getString("provider_display_name"),
        rs.getString("worker_role"),
        rs.getString("selected_worker_id"),
        rs.getString("selected_model_tier"),
        rs.getString("status"),
        instant(rs, "started_at"),
        instant(rs, "ended_at"),
        rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null,
        rs.getString("summary"),
        rs.getString("last_event_type"),
        rs.getObject("artifact_count") != null ? rs.getInt("artifact_count") : null,
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<AgentAction> AGENT_ACTION = (rs, ctx) -> new AgentAction(
        rs.getString("id"),
        rs.getString("session_id"),
        rs.getString("task_id"),
        rs.getString("source_execution_id"),
        rs.getString("action_type"),
        rs.getString("status"),
        rs.getString("summary"),
        JsonMapper.fromJson(rs.getString("payload_json")),
        rs.getString("risk_level"),
        rs.getInt("requires_approval") == 1,
        rs.getString("accepted_by"),
        rs.getString("rejection_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );

    public static final RowMapper<TaskRecoveryJob> TASK_RECOVERY_JOB = (rs, ctx) -> new TaskRecoveryJob(
        rs.getString("id"),
        rs.getString("task_id"),
        rs.getString("session_id"),
        rs.getString("status"),
        rs.getString("mode"),
        rs.getString("recommended_action"),
        rs.getString("target_worker"),
        rs.getString("recovery_execution_mode"),
        rs.getString("failure_class"),
        rs.getString("provider_failure_class"),
        rs.getString("status_url"),
        instant(rs, "accepted_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        rs.getString("error_message"),
        JsonMapper.fromJson(rs.getString("metadata_json"))
    );
}
