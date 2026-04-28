package com.agentcloud.store;

import com.agentcloud.model.ExperimentRunRecord;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExperimentRunDao extends SqlObject {

    @SqlUpdate("""
        INSERT INTO experiment_runs (
            id, session_id, task_id, experiment_name, task_case_key, task_title, task_type,
            task_length_bucket, model_mode, total_steps, completion_status, acceptance_result,
            total_cost, strong_model_cost_ratio, handoff_count, resume_count, human_gate_count,
            failure_reason, recovery_success, final_artifact_quality_note, created_at, updated_at, metadata_json
        ) VALUES (
            :id, :sessionId, :taskId, :experimentName, :taskCaseKey, :taskTitle, :taskType,
            :taskLengthBucket, :modelMode, :totalSteps, :completionStatus, :acceptanceResult,
            :totalCost, :strongModelCostRatio, :handoffCount, :resumeCount, :humanGateCount,
            :failureReason, :recoverySuccess, :finalArtifactQualityNote, :createdAt, :updatedAt, :metadata
        )
        ON CONFLICT(task_id) DO UPDATE SET
            experiment_name = excluded.experiment_name,
            task_case_key = excluded.task_case_key,
            task_title = excluded.task_title,
            task_type = excluded.task_type,
            task_length_bucket = excluded.task_length_bucket,
            model_mode = excluded.model_mode,
            total_steps = excluded.total_steps,
            completion_status = excluded.completion_status,
            acceptance_result = excluded.acceptance_result,
            total_cost = excluded.total_cost,
            strong_model_cost_ratio = excluded.strong_model_cost_ratio,
            handoff_count = excluded.handoff_count,
            resume_count = excluded.resume_count,
            human_gate_count = excluded.human_gate_count,
            failure_reason = excluded.failure_reason,
            recovery_success = excluded.recovery_success,
            final_artifact_quality_note = excluded.final_artifact_quality_note,
            updated_at = excluded.updated_at,
            metadata_json = excluded.metadata_json
        """)
    void upsertRaw(@Bind("id") String id,
                   @Bind("sessionId") String sessionId,
                   @Bind("taskId") String taskId,
                   @Bind("experimentName") String experimentName,
                   @Bind("taskCaseKey") String taskCaseKey,
                   @Bind("taskTitle") String taskTitle,
                   @Bind("taskType") String taskType,
                   @Bind("taskLengthBucket") String taskLengthBucket,
                   @Bind("modelMode") String modelMode,
                   @Bind("totalSteps") int totalSteps,
                   @Bind("completionStatus") String completionStatus,
                   @Bind("acceptanceResult") String acceptanceResult,
                   @Bind("totalCost") double totalCost,
                   @Bind("strongModelCostRatio") Double strongModelCostRatio,
                   @Bind("handoffCount") int handoffCount,
                   @Bind("resumeCount") int resumeCount,
                   @Bind("humanGateCount") int humanGateCount,
                   @Bind("failureReason") String failureReason,
                   @Bind("recoverySuccess") Boolean recoverySuccess,
                   @Bind("finalArtifactQualityNote") String finalArtifactQualityNote,
                   @Bind("createdAt") Instant createdAt,
                   @Bind("updatedAt") Instant updatedAt,
                   @Bind("metadata") String metadata);

    default void upsert(ExperimentRunRecord record) {
        upsertRaw(
            record.id(),
            record.sessionId(),
            record.taskId(),
            record.experimentName(),
            record.taskCaseKey(),
            record.taskTitle(),
            record.taskType(),
            record.taskLengthBucket(),
            record.modelMode(),
            record.totalSteps(),
            record.completionStatus(),
            record.acceptanceResult(),
            record.totalCost(),
            record.strongModelCostRatio(),
            record.handoffCount(),
            record.resumeCount(),
            record.humanGateCount(),
            record.failureReason(),
            record.recoverySuccess(),
            record.finalArtifactQualityNote(),
            record.createdAt(),
            record.updatedAt(),
            JsonMapper.toJson(record.metadata())
        );
    }

    @SqlQuery("SELECT * FROM experiment_runs WHERE task_id = :taskId")
    Optional<ExperimentRunRecord> findByTaskId(@Bind("taskId") String taskId);

    @SqlQuery("""
        SELECT * FROM experiment_runs
        WHERE (:experimentName IS NULL OR experiment_name = :experimentName)
          AND (:taskCaseKey IS NULL OR task_case_key = :taskCaseKey)
          AND (:taskLengthBucket IS NULL OR task_length_bucket = :taskLengthBucket)
          AND (:modelMode IS NULL OR model_mode = :modelMode)
        ORDER BY updated_at DESC
        LIMIT :limit
        """)
    List<ExperimentRunRecord> listFiltered(@Bind("experimentName") String experimentName,
                                           @Bind("taskCaseKey") String taskCaseKey,
                                           @Bind("taskLengthBucket") String taskLengthBucket,
                                           @Bind("modelMode") String modelMode,
                                           @Bind("limit") int limit);
}
