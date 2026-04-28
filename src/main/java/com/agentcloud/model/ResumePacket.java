package com.agentcloud.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumePacket(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String packetVersion,
    String activeTaskSummary,
    String decisionSummary,
    String artifactSummary,
    List<String> openQuestions,
    String nextStep,
    Map<String, Object> payload,
    PacketTaskIdentity taskIdentity,
    String currentObjective,
    String currentStatus,
    String currentNode,
    String assignedWorker,
    String latestSummary,
    List<String> blockers,
    List<PacketArtifactRef> recentArtifacts,
    List<PacketDecisionRef> recentDecisions,
    Boolean machineReadableFirst
) {
    public ResumePacket(String id, String sessionId, String taskId, Instant createdAt, String packetVersion,
                        String activeTaskSummary, String decisionSummary, String artifactSummary,
                        List<String> openQuestions, String nextStep, Map<String, Object> payload) {
        this(
            id, sessionId, taskId, createdAt, packetVersion,
            activeTaskSummary, decisionSummary, artifactSummary,
            openQuestions, nextStep, payload,
            null, null, null, null, null, null, null, null, null, null
        );
    }

    public ResumePacket {
        if (createdAt == null) createdAt = Instant.now();
        if (packetVersion == null || packetVersion.isBlank()) packetVersion = "1.1";
        if (payload == null) payload = Map.of();
        if (openQuestions == null) openQuestions = readStringList(payload.get("open_questions"));
        if (openQuestions == null) openQuestions = List.of();
        if (nextStep == null || nextStep.isBlank()) nextStep = firstNonBlank(readString(payload, "next_step"), readString(payload, "resume_hint"));
        if (taskIdentity == null) taskIdentity = resolveTaskIdentity(payload, taskId, sessionId, activeTaskSummary);
        if (currentObjective == null || currentObjective.isBlank()) {
            currentObjective = firstNonBlank(readString(payload, "current_objective"), readString(payload, "active_goal"));
        }
        if (currentStatus == null || currentStatus.isBlank()) {
            currentStatus = firstNonBlank(readString(payload, "current_status"), readString(payload, "task_status"));
        }
        if (currentNode == null || currentNode.isBlank()) currentNode = readString(payload, "current_node");
        if (assignedWorker == null || assignedWorker.isBlank()) assignedWorker = readString(payload, "assigned_worker");
        if (latestSummary == null || latestSummary.isBlank()) {
            latestSummary = firstNonBlank(readString(payload, "latest_summary"), activeTaskSummary, decisionSummary, artifactSummary);
        }
        if (blockers == null) blockers = readStringList(payload.get("blockers"));
        if (blockers == null) blockers = List.of();
        if (recentArtifacts == null) {
            recentArtifacts = readArtifactRefs(payload.get("recent_artifacts"), payload.get("relevant_artifacts"));
        }
        if (recentArtifacts == null) recentArtifacts = List.of();
        if (recentDecisions == null) {
            recentDecisions = readDecisionRefs(payload.get("recent_decisions"), payload.get("recent_decision_summaries"));
        }
        if (recentDecisions == null) recentDecisions = List.of();
        if (machineReadableFirst == null) {
            machineReadableFirst = readBoolean(payload.get("machine_readable_first"));
        }
        if (machineReadableFirst == null) machineReadableFirst = Boolean.TRUE;
    }

    private static PacketTaskIdentity resolveTaskIdentity(Map<String, Object> payload, String taskId, String sessionId,
                                                          String activeTaskSummary) {
        Object raw = payload.get("task_identity");
        if (raw instanceof PacketTaskIdentity identity) {
            return identity;
        }
        if (raw instanceof Map<?, ?> map) {
            return new PacketTaskIdentity(
                firstNonBlank(mapString(map, "task_id"), taskId),
                firstNonBlank(mapString(map, "session_id"), sessionId),
                mapString(map, "parent_task_id"),
                firstNonBlank(mapString(map, "title"), readString(payload, "task_title"), activeTaskSummary),
                firstNonBlank(mapString(map, "task_type"), readString(payload, "task_type"))
            );
        }
        return new PacketTaskIdentity(
            taskId,
            sessionId,
            readString(payload, "parent_task_id"),
            firstNonBlank(readString(payload, "task_title"), activeTaskSummary),
            readString(payload, "task_type")
        );
    }

    private static List<PacketArtifactRef> readArtifactRefs(Object raw, Object legacy) {
        List<PacketArtifactRef> items = new ArrayList<>();
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof PacketArtifactRef ref) {
                    items.add(ref);
                } else if (value instanceof Map<?, ?> map) {
                    items.add(new PacketArtifactRef(
                        mapString(map, "artifact_type"),
                        mapString(map, "title"),
                        mapString(map, "summary"),
                        mapString(map, "created_at")
                    ));
                } else if (value != null) {
                    items.add(new PacketArtifactRef(null, value.toString(), null, null));
                }
            }
        } else if (legacy instanceof List<?> legacyValues) {
            for (Object value : legacyValues) {
                if (value != null) {
                    items.add(new PacketArtifactRef(null, value.toString(), null, null));
                }
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    private static List<PacketDecisionRef> readDecisionRefs(Object raw, Object legacy) {
        List<PacketDecisionRef> items = new ArrayList<>();
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof PacketDecisionRef ref) {
                    items.add(ref);
                } else if (value instanceof Map<?, ?> map) {
                    items.add(new PacketDecisionRef(
                        mapString(map, "decision_type"),
                        mapString(map, "summary"),
                        mapString(map, "rationale"),
                        mapString(map, "created_at")
                    ));
                } else if (value != null) {
                    items.add(new PacketDecisionRef(null, value.toString(), null, null));
                }
            }
        }
        if (items.isEmpty() && legacy instanceof List<?> legacyValues) {
            for (Object value : legacyValues) {
                if (value != null) {
                    items.add(new PacketDecisionRef(null, value.toString(), null, null));
                }
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                items.add(value.toString());
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    private static String readString(Map<String, Object> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static String mapString(Map<?, ?> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = payload.get(key);
        if (value == null && key.contains("_")) {
            value = payload.get(toCamelCase(key));
        }
        return value == null ? null : value.toString();
    }

    private static Boolean readBoolean(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw != null) {
            return Boolean.parseBoolean(raw.toString());
        }
        return null;
    }

    private static String toCamelCase(String snakeCase) {
        if (snakeCase == null || snakeCase.isBlank() || !snakeCase.contains("_")) {
            return snakeCase;
        }
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (char ch : snakeCase.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                builder.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
