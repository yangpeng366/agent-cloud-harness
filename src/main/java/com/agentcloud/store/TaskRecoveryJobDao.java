package com.agentcloud.store;

import com.agentcloud.model.TaskRecoveryJob;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRecoveryJobDao extends SqlObject {

    @SqlUpdate("""
        INSERT INTO task_recovery_jobs (
            id, task_id, session_id, status, mode, recommended_action, target_worker,
            recovery_execution_mode, failure_class, provider_failure_class, status_url,
            accepted_at, started_at, completed_at, error_message, metadata_json
        ) VALUES (
            :id, :taskId, :sessionId, :status, :mode, :recommendedAction, :targetWorker,
            :recoveryExecutionMode, :failureClass, :providerFailureClass, :statusUrl,
            :acceptedAt, :startedAt, :completedAt, :errorMessage, :metadata
        )
        """)
    void insertRaw(@Bind("id") String id,
                   @Bind("taskId") String taskId,
                   @Bind("sessionId") String sessionId,
                   @Bind("status") String status,
                   @Bind("mode") String mode,
                   @Bind("recommendedAction") String recommendedAction,
                   @Bind("targetWorker") String targetWorker,
                   @Bind("recoveryExecutionMode") String recoveryExecutionMode,
                   @Bind("failureClass") String failureClass,
                   @Bind("providerFailureClass") String providerFailureClass,
                   @Bind("statusUrl") String statusUrl,
                   @Bind("acceptedAt") Instant acceptedAt,
                   @Bind("startedAt") Instant startedAt,
                   @Bind("completedAt") Instant completedAt,
                   @Bind("errorMessage") String errorMessage,
                   @Bind("metadata") String metadata);

    default void insert(TaskRecoveryJob job) {
        insertRaw(
            job.id(),
            job.taskId(),
            job.sessionId(),
            job.status(),
            job.mode(),
            job.recommendedAction(),
            job.targetWorker(),
            job.recoveryExecutionMode(),
            job.failureClass(),
            job.providerFailureClass(),
            job.statusUrl(),
            job.acceptedAt(),
            job.startedAt(),
            job.completedAt(),
            job.errorMessage(),
            JsonMapper.toJson(job.metadata())
        );
    }

    @SqlUpdate("""
        UPDATE task_recovery_jobs
        SET status = :status,
            started_at = COALESCE(:startedAt, started_at),
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE id = :id
        """)
    void updateStatus(@Bind("id") String id,
                      @Bind("status") String status,
                      @Bind("startedAt") Instant startedAt,
                      @Bind("completedAt") Instant completedAt,
                      @Bind("errorMessage") String errorMessage);

    @SqlQuery("SELECT * FROM task_recovery_jobs WHERE id = :id")
    Optional<TaskRecoveryJob> findById(@Bind("id") String id);

    @SqlQuery("""
        SELECT * FROM task_recovery_jobs
        WHERE task_id = :taskId
        ORDER BY accepted_at DESC
        LIMIT :limit
        """)
    List<TaskRecoveryJob> listByTask(@Bind("taskId") String taskId, @Bind("limit") int limit);

    @SqlUpdate("""
        UPDATE task_recovery_jobs
        SET status = 'interrupted',
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE status IN ('accepted', 'running')
        """)
    int markActiveJobsInterrupted(@Bind("completedAt") Instant completedAt,
                                  @Bind("errorMessage") String errorMessage);
}
