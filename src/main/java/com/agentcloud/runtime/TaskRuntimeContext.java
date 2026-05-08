package com.agentcloud.runtime;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.context.MountedContextView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 任务运行时上下文。
 * 统一聚合 task / packet / events / decisions / artifacts / tool traces，供 execution 与 judgment 使用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRuntimeContext(
    Task task,
    ResumePacket latestPacket,
    Checkpoint latestCheckpoint,
    List<Event> recentEvents,
    List<Decision> recentDecisions,
    List<Artifact> recentArtifacts,
    List<ToolInvocationRecord> recentToolInvocations,
    List<SessionMessage> recentMessages,
    ActiveContext activeContext,
    MountedContextView mountedContextView
) {
    public TaskRuntimeContext(Task task, ResumePacket latestPacket, Checkpoint latestCheckpoint,
                              List<Event> recentEvents, List<Decision> recentDecisions,
                              List<Artifact> recentArtifacts, ActiveContext activeContext) {
        this(task, latestPacket, latestCheckpoint, recentEvents, recentDecisions, recentArtifacts, List.of(), List.of(), activeContext, null);
    }

    public TaskRuntimeContext(Task task, ResumePacket latestPacket, Checkpoint latestCheckpoint,
                              List<Event> recentEvents, List<Decision> recentDecisions,
                              List<Artifact> recentArtifacts, List<SessionMessage> recentMessages,
                              ActiveContext activeContext) {
        this(task, latestPacket, latestCheckpoint, recentEvents, recentDecisions, recentArtifacts, List.of(), recentMessages, activeContext, null);
    }

    public TaskRuntimeContext(Task task, ResumePacket latestPacket, Checkpoint latestCheckpoint,
                              List<Event> recentEvents, List<Decision> recentDecisions,
                              List<Artifact> recentArtifacts, List<ToolInvocationRecord> recentToolInvocations,
                              List<SessionMessage> recentMessages, ActiveContext activeContext) {
        this(task, latestPacket, latestCheckpoint, recentEvents, recentDecisions, recentArtifacts,
            recentToolInvocations, recentMessages, activeContext, null);
    }

    public TaskRuntimeContext(Task task, ResumePacket latestPacket, Checkpoint latestCheckpoint,
                              List<Event> recentEvents, List<Decision> recentDecisions,
                              List<Artifact> recentArtifacts, List<SessionMessage> recentMessages,
                              ActiveContext activeContext, MountedContextView mountedContextView) {
        this(task, latestPacket, latestCheckpoint, recentEvents, recentDecisions, recentArtifacts,
            List.of(), recentMessages, activeContext, mountedContextView);
    }

    public TaskRuntimeContext {
        if (recentEvents == null) recentEvents = List.of();
        if (recentDecisions == null) recentDecisions = List.of();
        if (recentArtifacts == null) recentArtifacts = List.of();
        if (recentToolInvocations == null) recentToolInvocations = List.of();
        if (recentMessages == null) recentMessages = List.of();
        if (activeContext == null) activeContext = new ActiveContext("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 12);
        if (mountedContextView == null) mountedContextView = MountedContextView.empty(task != null ? task.id() : null);
    }
}
