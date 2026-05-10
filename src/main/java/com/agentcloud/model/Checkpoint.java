package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Checkpoint(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String checkpointType,     // periodic | pause_before | escalate_before | handoff_before | halt_before | reopen_before | archive_retrieval_before | external_fact_refresh_before | session_end
    String consolidationSummary,
    Map<String, Object> refinedPacket,
    Map<String, Object> worldModelDelta,
    Map<String, Object> metadata
) {
    public Checkpoint {
        if (createdAt == null) createdAt = Instant.now();
    }
}
