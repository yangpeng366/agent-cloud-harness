package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.model.ContinuationAction;
import com.agentcloud.runtime.model.ContinuationDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小 runtime judgment 过程。
 * 第一版先用规则判断下一状态迁移，避免 continue 只做机械推进。
 */
public class RuntimeJudgmentService {

    public ContinuationDecision judge(Task task) {
        if (task == null) {
            return ContinuationDecision.of(ContinuationAction.HALT, "task missing", null);
        }
        if ("paused".equals(task.status())) {
            return ContinuationDecision.of(ContinuationAction.PAUSE, "task already paused", null);
        }
        if ("waiting_human".equals(task.status())) {
            return ContinuationDecision.of(ContinuationAction.ESCALATE, "task already waiting for human input", null);
        }

        Map<String, Object> metadata = task.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return ContinuationDecision.of(ContinuationAction.CONTINUE, "no special transition signal", null);
        }

        if (isTrue(metadata.get("auto_halt"))) {
            return ContinuationDecision.of(ContinuationAction.HALT, "metadata.auto_halt=true", null);
        }
        if (isTrue(metadata.get("pause_requested"))) {
            return ContinuationDecision.of(ContinuationAction.PAUSE, "metadata.pause_requested=true", null);
        }
        if (isTrue(metadata.get("requires_human_confirmation"))) {
            return ContinuationDecision.of(ContinuationAction.ESCALATE, "metadata.requires_human_confirmation=true", null);
        }

        ContinuationDecision goalDecision = judgeGoalProgress(metadata.get("subgoal_status"));
        if (goalDecision != null) {
            return goalDecision;
        }

        String targetWorker = asString(metadata.get("target_worker"));
        if (targetWorker != null && !targetWorker.isBlank() && !targetWorker.equals(task.assignedWorker())) {
            return ContinuationDecision.of(ContinuationAction.HANDOFF, "metadata.target_worker requests reassignment", targetWorker);
        }

        return ContinuationDecision.of(ContinuationAction.CONTINUE, "default continue path", null);
    }

    private ContinuationDecision judgeGoalProgress(Object rawSubgoalStatus) {
        List<String> statuses = readSubgoalStatuses(rawSubgoalStatus);
        if (statuses.isEmpty()) {
            return null;
        }

        int total = statuses.size();
        int done = 0;
        int blocked = 0;
        for (String status : statuses) {
            if (isDoneSubgoal(status)) {
                done++;
            } else if (isBlockedSubgoal(status)) {
                blocked++;
            }
        }

        Map<String, Object> decisionMetadata = Map.of(
            "subgoal_total", total,
            "subgoal_done_count", done,
            "subgoal_blocked_count", blocked
        );
        if (blocked > 0) {
            return new ContinuationDecision(
                ContinuationAction.ESCALATE,
                "subgoal blocked requires human gate",
                null,
                "goal_progress",
                decisionMetadata
            );
        }
        if (done == total) {
            return new ContinuationDecision(
                ContinuationAction.HALT,
                "all subgoals done",
                null,
                "goal_progress",
                decisionMetadata
            );
        }
        return new ContinuationDecision(
            ContinuationAction.CONTINUE,
            "subgoals still open",
            null,
            "goal_progress",
            decisionMetadata
        );
    }

    private List<String> readSubgoalStatuses(Object raw) {
        List<String> statuses = new ArrayList<>();
        if (raw instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                String status = readStatusValue(value);
                if (status != null) {
                    statuses.add(status);
                }
            }
        } else if (raw instanceof List<?> values) {
            for (Object value : values) {
                String status = readStatusValue(value);
                if (status != null) {
                    statuses.add(status);
                }
            }
        } else {
            String status = readStatusValue(raw);
            if (status != null) {
                statuses.add(status);
            }
        }
        return statuses;
    }

    private String readStatusValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Object status = map.get("status");
            if (status == null) {
                status = map.get("state");
            }
            return normalizeStatus(status);
        }
        return normalizeStatus(raw);
    }

    private String normalizeStatus(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim().toLowerCase();
        return text.isBlank() ? null : text;
    }

    private boolean isDoneSubgoal(String status) {
        return List.of("done", "complete", "completed", "accepted").contains(status);
    }

    private boolean isBlockedSubgoal(String status) {
        return List.of("blocked", "waiting_human", "human_gate").contains(status);
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return false;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}