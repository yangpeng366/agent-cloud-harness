package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * M0 Shadow 写 JSONL / outbox 的不可变事件信封。
 * 不写原命令、密钥、长文本；只写 hash/length/scope/path_len 等摘要字段（参见第 49 号笔记）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionEvent(
    String eventId,
    Instant occurredAt,
    String runId,
    String turnId,
    String taskId,
    String subjectRef,
    String kind,
    String policyVersion,
    String thresholdSetId,
    Map<String, Double> scores,
    double confidence,
    String action,
    boolean degraded,
    String modelRef,
    long latencyMs,
    double costUnits,
    Map<String, String> reasonCodes,
    Map<String, Object> inputDigest,
    String shadowMode
) {
    public DecisionEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId required");
        }
        if (occurredAt == null) occurredAt = Instant.now();
        if (kind == null || kind.isBlank()) kind = "router";
        if (policyVersion == null || policyVersion.isBlank()) policyVersion = "shadow-default";
        if (thresholdSetId == null || thresholdSetId.isBlank()) thresholdSetId = "shadow-default";
        if (action == null || action.isBlank()) action = "balanced";
        if (scores == null) scores = Map.of();
        if (reasonCodes == null) reasonCodes = Map.of();
        if (inputDigest == null) inputDigest = Map.of();
        if (shadowMode == null || shadowMode.isBlank()) shadowMode = "record_only";
    }

    public static DecisionEvent from(String eventId, JudgmentRequest req, JudgmentDecision dec) {
        return new DecisionEvent(
                eventId,
                Instant.now(),
                req.runId(),
                req.turnId(),
                null,
                req.subjectRef(),
                req.kind().name().toLowerCase(),
                req.policyVersion(),
                dec.thresholdSetId(),
                dec.scores(),
                dec.confidence(),
                dec.action(),
                dec.degraded(),
                dec.modelRef(),
                dec.latencyMs(),
                dec.costUnits(),
                dec.reasonCodes(),
                Map.of(),
                "record_only"
        );
    }
}