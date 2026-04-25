package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskControlResult(
    String taskId,
    String state,
    String controlNode,
    String assignedWorker,
    String decision,
    String reason,
    boolean packetRefreshed,
    String resumePacketId
) {}
