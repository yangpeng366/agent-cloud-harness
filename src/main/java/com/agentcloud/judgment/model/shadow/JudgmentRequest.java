package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Provider-agnostic 判断请求。
 * 不引用 JudgmentContext 等既有类，避免对 Provider 暴露 ACH 内部对象（参见第 49 号笔记）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgmentRequest(
    String judgmentId,
    String runId,
    String turnId,
    JudgmentKind kind,
    String subjectRef,
    Map<String, Object> normalizedInput,
    String policyVersion,
    Map<String, String> requestedQuestions,
    long timeoutMs
) {
    public JudgmentRequest {
        if (judgmentId == null || judgmentId.isBlank()) {
            throw new IllegalArgumentException("judgmentId required");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId required");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind required");
        }
        if (normalizedInput == null) normalizedInput = Map.of();
        if (requestedQuestions == null) requestedQuestions = Map.of();
        if (timeoutMs <= 0) timeoutMs = 1500L;
        if (policyVersion == null || policyVersion.isBlank()) policyVersion = "shadow-default";
    }
}