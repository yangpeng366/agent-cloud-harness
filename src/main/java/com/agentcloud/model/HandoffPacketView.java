package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffPacketView(
    String taskId,
    String fromWorker,
    String toWorker,
    Map<String, Object> handoffPacket
) {}
