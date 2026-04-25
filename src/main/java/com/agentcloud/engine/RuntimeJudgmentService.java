package com.agentcloud.engine;

import com.agentcloud.model.Task;

import java.util.Map;

/**
 * 最小 runtime judgment 过程。
 * 第一版先用规则判断下一状态迁移，避免 continue 只做机械推进。
 */
public class RuntimeJudgmentService {

    public TaskDecision judge(Task task) {
        if (task == null) {
            return new TaskDecision("halt", "task missing", null);
        }
        if ("paused".equals(task.status())) {
            return new TaskDecision("pause", "task already paused", null);
        }
        if ("waiting_human".equals(task.status())) {
            return new TaskDecision("escalate", "task already waiting for human input", null);
        }

        Map<String, Object> metadata = task.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return new TaskDecision("continue", "no special transition signal", null);
        }

        if (isTrue(metadata.get("auto_halt"))) {
            return new TaskDecision("halt", "metadata.auto_halt=true", null);
        }
        if (isTrue(metadata.get("pause_requested"))) {
            return new TaskDecision("pause", "metadata.pause_requested=true", null);
        }
        if (isTrue(metadata.get("requires_human_confirmation"))) {
            return new TaskDecision("escalate", "metadata.requires_human_confirmation=true", null);
        }

        String targetWorker = asString(metadata.get("target_worker"));
        if (targetWorker != null && !targetWorker.isBlank() && !targetWorker.equals(task.assignedWorker())) {
            return new TaskDecision("handoff", "metadata.target_worker requests reassignment", targetWorker);
        }

        return new TaskDecision("continue", "default continue path", null);
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return false;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public record TaskDecision(String action, String reason, String targetWorker) {}
}
