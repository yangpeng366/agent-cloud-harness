package com.agentcloud.runtime.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 为后续 demotion/reload 预留的 capsule 表达。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextCapsule(
    String id,
    String scopePath,
    String outcome,
    List<String> keyDecisions,
    List<String> reusableFindings,
    List<String> unresolvedRisks,
    List<ContextReference> evidenceRefs,
    List<String> nextFollowups,
    ContextRetentionState retentionState
) {
    public ContextCapsule {
        if (id == null) id = "";
        if (scopePath == null) scopePath = "";
        if (outcome == null) outcome = "";
        if (keyDecisions == null) keyDecisions = List.of();
        if (reusableFindings == null) reusableFindings = List.of();
        if (unresolvedRisks == null) unresolvedRisks = List.of();
        if (evidenceRefs == null) evidenceRefs = List.of();
        if (nextFollowups == null) nextFollowups = List.of();
        if (retentionState == null) retentionState = ContextRetentionState.COLD_CAPSULE;
    }
}
