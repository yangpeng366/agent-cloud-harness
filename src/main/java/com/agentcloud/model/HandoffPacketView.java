package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffPacketView(
    String taskId,
    String fromWorker,
    String toWorker,
    HandoffPacket handoffPacket
) {}
