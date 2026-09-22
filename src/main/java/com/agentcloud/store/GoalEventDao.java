package com.agentcloud.store;

import com.agentcloud.model.GoalEvent;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;

public interface GoalEventDao extends SqlObject {

    @SqlUpdate("INSERT INTO goal_events (id, goal_id, event_type, actor_type, actor_id, summary, payload_json, created_at) " +
               "VALUES (:id, :goalId, :eventType, :actorType, :actorId, :summary, :payload, :createdAt)")
    void insert(@Bind("id") String id, @Bind("goalId") String goalId, @Bind("eventType") String eventType,
                @Bind("actorType") String actorType, @Bind("actorId") String actorId, @Bind("summary") String summary,
                @Bind("payload") String payload, @Bind("createdAt") Instant createdAt);

    default void insert(GoalEvent e) {
        insert(e.id(), e.goalId(), e.eventType(), e.actorType(), e.actorId(), e.summary(),
               JsonMapper.toJson(e.payload()), e.createdAt());
    }

    @SqlQuery("SELECT * FROM goal_events WHERE goal_id = :goalId ORDER BY created_at DESC LIMIT :limit")
    List<GoalEvent> listByGoal(@Bind("goalId") String goalId, @Bind("limit") int limit);

    @SqlQuery("SELECT * FROM goal_events WHERE goal_id = :goalId ORDER BY created_at DESC")
    List<GoalEvent> listAllByGoal(@Bind("goalId") String goalId);

    @SqlQuery("SELECT COUNT(*) FROM goal_events WHERE goal_id = :goalId")
    int countByGoal(@Bind("goalId") String goalId);
}