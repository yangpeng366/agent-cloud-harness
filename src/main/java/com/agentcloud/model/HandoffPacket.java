package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffPacket(
    String packetVersion,
    Boolean machineReadableFirst,
    PacketTaskIdentity taskIdentity,
    String fromWorker,
    String toWorker,
    String currentObjective,
    String currentStatus,
    String currentNode,
    String whyHandoff,
    List<String> whatDone,
    List<String> whatRemaining,
    List<String> cautions,
    String resumeHint,
    String latestSummary,
    String handoffSummary,
    Map<String, Object> metadata
) {
    public HandoffPacket {
        if (packetVersion == null || packetVersion.isBlank()) packetVersion = "1.0";
        if (machineReadableFirst == null) machineReadableFirst = Boolean.TRUE;
        if (whatDone == null) whatDone = List.of();
        if (whatRemaining == null) whatRemaining = List.of();
        if (cautions == null) cautions = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
