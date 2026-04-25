package com.agentcloud.runtime;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 任务运行时上下文。
 * 统一聚合 task / packet / events / decisions / artifacts，供 execution 与 judgment 使用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRuntimeContext(
    Task task,
    ResumePacket latestPacket,
    Checkpoint latestCheckpoint,
    List<Event> recentEvents,
    List<Decision> recentDecisions,
    List<Artifact> recentArtifacts,
    ActiveContext activeContext
) {
    public TaskRuntimeContext {
        if (activeContext == null) activeContext = new ActiveContext("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 12);
    }
}
