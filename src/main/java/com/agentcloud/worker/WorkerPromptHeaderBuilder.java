package com.agentcloud.worker;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.PromptFieldDeduper;

import java.util.Map;

/**
 * 统一收口 worker prompt 顶部任务头，避免 title / goal / intent 机械重复。
 */
final class WorkerPromptHeaderBuilder {

    private WorkerPromptHeaderBuilder() {
    }

    static void appendTaskHeader(StringBuilder sb, Task task, boolean includeTaskType) {
        if (sb == null || task == null) {
            return;
        }
        Map<String, Object> metadata = task.metadata();
        String title = PromptFieldDeduper.normalizePromptField(task.title());
        String goal = PromptFieldDeduper.firstDistinctNormalized(
            firstNonBlank(task.goal(), metadataString(metadata, "goal")),
            title
        );
        String intent = PromptFieldDeduper.firstDistinctNormalized(
            metadataString(metadata, "intent"),
            title,
            goal
        );
        appendLine(sb, "Task Title: " + firstNonBlank(title, "(untitled task)"));
        if (includeTaskType) {
            String taskType = PromptFieldDeduper.normalizePromptField(metadataString(metadata, "task_type"));
            if (!taskType.isBlank()) {
                appendLine(sb, "Task Type: " + taskType);
            }
        }
        appendIfPresent(sb, "Goal", goal);
        appendIfPresent(sb, "Intent", intent);
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            appendLine(sb, label + ": " + value);
        }
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        sb.append(line).append("\n");
    }
}
