package com.agentcloud.store;

import com.agentcloud.model.Goal;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GoalDao extends SqlObject {

    @SqlUpdate("INSERT INTO goals (id, session_id, parent_goal_id, title, status, phase, source_task_id, active_task_id, " +
               "objective, success_criteria_json, constraints_json, budget_json, progress_json, outcome_summary, " +
               "revision, opened_at, updated_at, closed_at, metadata_json) " +
               "VALUES (:id, :sessionId, :parentGoalId, :title, :status, :phase, :sourceTaskId, :activeTaskId, " +
               ":objective, :successCriteria, :constraints, :budget, :progress, :outcomeSummary, " +
               ":revision, :openedAt, :updatedAt, :closedAt, :metadata)")
    void insert(@Bind("id") String id, @Bind("sessionId") String sessionId, @Bind("parentGoalId") String parentGoalId,
                @Bind("title") String title, @Bind("status") String status, @Bind("phase") String phase,
                @Bind("sourceTaskId") String sourceTaskId, @Bind("activeTaskId") String activeTaskId,
                @Bind("objective") String objective,
                @Bind("successCriteria") String successCriteria, @Bind("constraints") String constraints,
                @Bind("budget") String budget, @Bind("progress") String progress,
                @Bind("outcomeSummary") String outcomeSummary, @Bind("revision") int revision,
                @Bind("openedAt") Instant openedAt, @Bind("updatedAt") Instant updatedAt,
                @Bind("closedAt") Instant closedAt, @Bind("metadata") String metadata);

    default void insert(Goal g) {
        insert(g.id(), g.sessionId(), g.parentGoalId(), g.title(), g.status(), g.phase(),
               g.sourceTaskId(), g.activeTaskId(), g.objective(),
               JsonMapper.toJson(g.successCriteria()), JsonMapper.toJson(g.constraints()),
               JsonMapper.toJson(g.budget()), JsonMapper.toJson(g.progress()),
               g.outcomeSummary(), g.revision() == null ? 1 : g.revision(),
               g.openedAt(), g.updatedAt(), g.closedAt(), JsonMapper.toJson(g.metadata()));
    }

    @SqlQuery("SELECT * FROM goals WHERE id = :id")
    Optional<Goal> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM goals WHERE session_id = :sessionId ORDER BY updated_at DESC")
    List<Goal> listBySession(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT * FROM goals WHERE status = :status ORDER BY updated_at DESC")
    List<Goal> listByStatus(@Bind("status") String status);

    @SqlQuery("SELECT * FROM goals ORDER BY updated_at DESC LIMIT :limit")
    List<Goal> listRecent(@Bind("limit") int limit);

    @SqlQuery("SELECT * FROM goals WHERE session_id = :sessionId AND status = :status ORDER BY updated_at DESC")
    List<Goal> listBySessionAndStatus(@Bind("sessionId") String sessionId, @Bind("status") String status);

    @SqlUpdate("UPDATE goals SET status = :status, phase = :phase, updated_at = :updatedAt WHERE id = :id")
    int updateStatus(@Bind("id") String id, @Bind("status") String status, @Bind("phase") String phase,
                     @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE goals SET active_task_id = :activeTaskId, updated_at = :updatedAt WHERE id = :id")
    int updateActiveTask(@Bind("id") String id, @Bind("activeTaskId") String activeTaskId,
                         @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE goals SET progress_json = :progress, active_task_id = COALESCE(:activeTaskId, active_task_id), updated_at = :updatedAt WHERE id = :id")
    int updateProgress(@Bind("id") String id, @Bind("progress") String progress,
                       @Bind("activeTaskId") String activeTaskId, @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE goals SET status = :status, phase = :phase, outcome_summary = :outcomeSummary, " +
               "closed_at = :closedAt, updated_at = :updatedAt WHERE id = :id")
    int close(@Bind("id") String id, @Bind("status") String status, @Bind("phase") String phase,
              @Bind("outcomeSummary") String outcomeSummary, @Bind("closedAt") Instant closedAt,
              @Bind("updatedAt") Instant updatedAt);
}