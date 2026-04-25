package com.agentcloud.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 当前一轮执行与判断共享的活动工作面。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveContext(
    String taskFocus,
    List<String> constraints,
    List<String> keyEvents,
    List<String> keyDecisions,
    List<String> keyArtifacts,
    List<String> openQuestions,
    List<String> nextCandidates,
    List<String> riskHints,
    List<String> learnedHints,
    List<String> selectionTrace,
    String continuitySummary,
    String synthesizedContext,
    int itemBudget
) {
    public ActiveContext {
        if (taskFocus == null) taskFocus = "";
        if (constraints == null) constraints = List.of();
        if (keyEvents == null) keyEvents = List.of();
        if (keyDecisions == null) keyDecisions = List.of();
        if (keyArtifacts == null) keyArtifacts = List.of();
        if (openQuestions == null) openQuestions = List.of();
        if (nextCandidates == null) nextCandidates = List.of();
        if (riskHints == null) riskHints = List.of();
        if (learnedHints == null) learnedHints = List.of();
        if (selectionTrace == null) selectionTrace = List.of();
        if (continuitySummary == null) continuitySummary = "";
        if (synthesizedContext == null) synthesizedContext = "";
        if (itemBudget <= 0) itemBudget = 12;
    }
}
