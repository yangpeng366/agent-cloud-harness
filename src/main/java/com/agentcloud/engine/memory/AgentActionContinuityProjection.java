package com.agentcloud.engine.memory;

import com.agentcloud.model.AgentAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将已协调 action 压缩成 packet/checkpoint 可续跑字段。
 */
public final class AgentActionContinuityProjection {
    private AgentActionContinuityProjection() {
    }

    public static Map<String, Object> from(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return Map.of();
        }
        List<AgentAction> recent = actions.stream()
            .filter(Objects::nonNull)
            .limit(10)
            .toList();
        if (recent.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("recent_actions", recent.stream().limit(5).map(AgentActionContinuityProjection::toRef).toList());
        putFiltered(projection, "accepted_actions", recent, "accepted");
        putFiltered(projection, "rejected_actions", recent, "rejected");
        putFiltered(projection, "approval_needed_actions", recent, "needs_approval");

        List<String> contextRequests = collectContextRequests(recent);
        if (!contextRequests.isEmpty()) {
            projection.put("action_context_requests", contextRequests);
        }

        String summary = recent.stream()
            .limit(5)
            .map(AgentActionContinuityProjection::summaryLine)
            .filter(line -> line != null && !line.isBlank())
            .collect(Collectors.joining(" | "));
        if (!summary.isBlank()) {
            projection.put("action_summary", summary);
        }
        return projection;
    }

    private static void putFiltered(Map<String, Object> projection, String key, List<AgentAction> actions, String status) {
        List<Map<String, Object>> items = actions.stream()
            .filter(action -> status.equalsIgnoreCase(action.status()))
            .limit(5)
            .map(AgentActionContinuityProjection::toRef)
            .toList();
        if (!items.isEmpty()) {
            projection.put(key, items);
        }
    }

    private static Map<String, Object> toRef(AgentAction action) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", action.id());
        ref.put("action_type", action.actionType());
        ref.put("status", action.status());
        ref.put("summary", action.summary());
        ref.put("payload", action.payload());
        ref.put("risk_level", action.riskLevel());
        ref.put("requires_approval", action.requiresApproval());
        ref.put("rejection_reason", action.rejectionReason());
        ref.put("created_at", action.createdAt() != null ? action.createdAt().toString() : null);
        return ref;
    }

    private static List<String> collectContextRequests(List<AgentAction> actions) {
        List<String> requests = new ArrayList<>();
        for (AgentAction action : actions) {
            if (!"REQUEST_CONTEXT".equalsIgnoreCase(action.actionType())) {
                continue;
            }
            Object neededContext = action.payload().get("needed_context");
            if (neededContext instanceof List<?> values) {
                values.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(value -> !value.isBlank())
                    .forEach(requests::add);
            } else if (neededContext != null && !neededContext.toString().isBlank()) {
                requests.add(neededContext.toString());
            } else if (action.summary() != null && !action.summary().isBlank()) {
                requests.add(action.summary());
            }
        }
        return requests.stream().distinct().limit(5).toList();
    }

    private static String summaryLine(AgentAction action) {
        String type = action.actionType() == null || action.actionType().isBlank() ? "ACTION" : action.actionType();
        String status = action.status() == null || action.status().isBlank() ? "unknown" : action.status();
        String summary = action.summary() == null ? "" : action.summary();
        return summary.isBlank() ? type + " " + status : type + " " + status + ": " + summary;
    }
}
