package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.model.ContinuationAction;
import com.agentcloud.runtime.model.ContinuationDecision;

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

        String targetWorker = asString(metadata.get("target_worker"));
        if (targetWorker != null && !targetWorker.isBlank() && !targetWorker.equals(task.assignedWorker())) {
            return ContinuationDecision.of(ContinuationAction.HANDOFF, "metadata.target_worker requests reassignment", targetWorker);
        }

        return ContinuationDecision.of(ContinuationAction.CONTINUE, "default continue path", null);
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
