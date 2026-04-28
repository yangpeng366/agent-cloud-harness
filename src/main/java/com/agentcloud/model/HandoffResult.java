package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffResult(
    String taskId,
    String state,
    String controlNode,
    String previousWorker,
    String assignedWorker,
    boolean handoffPacketRequired,
    String decision,
    HandoffPacket handoffPacket
) {}
