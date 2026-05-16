package com.agentcloud.worker;

import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.llm.LlmImageInputResolver;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为 provider-native / app-server 执行器统一构建任务合同。
 * 控制面只提供边界、上下文与交付要求，不替 agent 规定具体执行步骤。
 */
final class ProviderTaskPromptBuilder {

    private ProviderTaskPromptBuilder() {
    }

    static String build(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return "Execute the assigned task with full autonomy and return the concrete result.";
        }

        Task task = context.task();
        Map<String, Object> metadata = task.metadata();
        StringBuilder sb = new StringBuilder();
        WorkerPromptHeaderBuilder.appendTaskHeader(sb, task, false);
        appendIfPresent(sb, "Priority", task.priority());
        appendIfPresent(sb, "Current Summary", task.summary());
        appendIfPresent(sb, "Suggested Next Step", task.nextStep());
        appendIfPresent(sb, "Task Type", metadataString(metadata, "task_type"));
        appendIfPresent(sb, "Assigned Worker", task.assignedWorker());
        appendIfPresent(sb, "Model Mode", metadataString(metadata, "model_mode"));
        appendIfPresent(sb, "Orchestration Stage", metadataString(metadata, "orchestration_stage"));

        List<String> workspacePaths = collectOrderedValues(metadata,
            "cwd",
            "workspace",
            "workspace_root",
            "working_directory",
            "workspaces",
            "workspace_paths"
        );
        if (!workspacePaths.isEmpty()) {
            appendBlankLine(sb);
            appendLine(sb, "Workspaces:");
            for (String workspacePath : workspacePaths) {
                appendLine(sb, "- " + workspacePath);
            }
        }

        List<String> references = collectOrderedValues(metadata,
            "reference_docs",
            "reference_files",
            "context_files",
            "input_paths"
        );
        if (!references.isEmpty()) {
            appendBlankLine(sb);
            appendLine(sb, "Reference Inputs:");
            for (String reference : references) {
                appendLine(sb, "- " + reference);
            }
        }

        List<String> outputs = collectOrderedValues(metadata,
            "deliverables",
            "expected_outputs",
            "output_files",
            "output_paths"
        );
        String singleOutput = firstNonBlank(
            metadataString(metadata, "desired_output_file"),
            metadataString(metadata, "output_file"),
            metadataString(metadata, "output_dir")
        );
        if (singleOutput != null) {
            outputs.add(singleOutput);
        }
        if (!outputs.isEmpty()) {
            appendBlankLine(sb);
            appendLine(sb, "Expected Deliverables:");
            for (String output : dedupe(outputs)) {
                appendLine(sb, "- " + output);
            }
        }

        List<String> investigationGoals = collectOrderedValues(metadata,
            "required_checks",
            "investigation_goals",
            "verification_targets",
            "acceptance_checks"
        );
        if (!investigationGoals.isEmpty()) {
            appendBlankLine(sb);
            appendLine(sb, "Required Checks:");
            for (String item : investigationGoals) {
                appendLine(sb, "- " + item);
            }
        }

        if (context.activeContext() != null && context.activeContext().synthesizedContext() != null
            && !context.activeContext().synthesizedContext().isBlank()) {
            appendBlankLine(sb);
            appendLine(sb, "Active Context:");
            appendLine(sb, context.activeContext().synthesizedContext());
        }

        List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
        if (!imageInputs.isEmpty()) {
            appendBlankLine(sb);
            appendLine(sb, "Image Inputs:");
            for (LlmImageInput imageInput : imageInputs) {
                StringBuilder line = new StringBuilder("- " + imageInput.path());
                if (imageInput.mediaType() != null && !imageInput.mediaType().isBlank()) {
                    line.append(" (").append(imageInput.mediaType()).append(")");
                }
                appendLine(sb, line.toString());
            }
        }

        appendBlankLine(sb);
        appendLine(sb, "Execution Contract:");
        appendLine(sb, "- You may choose the implementation strategy, tool usage, and investigation order.");
        appendLine(sb, "- Use the provided workspaces and references as the primary operating context.");
        appendLine(sb, "- Prefer making real progress on the target repository over only describing a plan.");
        appendLine(sb, "- If build, debug, or release steps are discoverable from the repo, surface them concretely.");
        appendLine(sb, "- Return the best concrete execution result for this task.");
        return sb.toString().trim();
    }

    static String defaultSystemPrompt(String providerDisplayName) {
        String name = providerDisplayName == null || providerDisplayName.isBlank()
            ? "the agent"
            : providerDisplayName;
        return "You are " + name + " running inside Agent Cloud Harness. "
            + "Agent Cloud Harness is a thin control plane that provides memory, continuation, and observation support. "
            + "You have broad autonomy to inspect the workspace, choose tools, decide the execution order, and complete the task concretely.";
    }

    static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> collectOrderedValues(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty() || keys == null || keys.length == 0) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (String key : keys) {
            Object raw = metadata.get(key);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    addNormalized(values, item);
                }
                continue;
            }
            if (raw.getClass().isArray() && raw instanceof Object[] array) {
                for (Object item : array) {
                    addNormalized(values, item);
                }
                continue;
            }
            String text = raw.toString();
            if (text.contains("\n")) {
                for (String part : text.split("\\R")) {
                    addNormalized(values, part);
                }
                continue;
            }
            if (text.contains("|")) {
                for (String part : text.split("\\|")) {
                    addNormalized(values, part);
                }
                continue;
            }
            addNormalized(values, raw);
        }
        return dedupe(values);
    }

    private static List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                ordered.add(value);
            }
        }
        return List.copyOf(ordered);
    }

    private static void addNormalized(List<String> values, Object raw) {
        if (raw == null) {
            return;
        }
        String normalized = raw.toString().trim();
        if (!normalized.isEmpty()) {
            values.add(normalized);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            appendLine(sb, label + ": " + value);
        }
    }

    private static void appendBlankLine(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null) {
            return;
        }
        sb.append(line).append('\n');
    }
}
