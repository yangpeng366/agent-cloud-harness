package com.agentcloud.engine;

import com.agentcloud.model.Task;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一管理 task_type 的轻量推断口径。
 * 显式非 continuation 的 task_type 仍然优先；只有缺失或仍是通用 continuation 时，才尝试做代码任务提升。
 */
public final class TaskTypeHeuristics {
    private static final Pattern WINDOWS_REPO_PATH = Pattern.compile("(?i)[a-z]:\\\\[^\\r\\n]*?(gitall|workspace|repo|project)\\\\");
    private static final Pattern CODE_FILE_REFERENCE = Pattern.compile("(?i)\\.(java|kt|js|ts|tsx|jsx|py|go|rs|cpp|c|cs|php|rb|sql|xml|yml|yaml|json|md)\\b");
    private static final Pattern CODING_ACTION_KEYWORD = Pattern.compile("(?i)(fix|patch|refactor|implement|修改|改造|改代码|写代码|补丁|修复|实现|重构|新增|调试|定位|代码|仓库|repo|工程)");

    private TaskTypeHeuristics() {
    }

    public static String effectiveTaskType(Task task, String fallback, String... extraSignals) {
        if (task == null) {
            return fallback;
        }
        return effectiveTaskType(
            task.metadata(),
            fallback,
            task.title(),
            task.goal(),
            task.summary(),
            extraSignals
        );
    }

    public static String effectiveTaskType(Map<String, Object> metadata, String fallback, String... extraSignals) {
        String explicit = blankToNull(stringValue(metadata != null ? metadata.get("task_type") : null));
        if (explicit != null && !"continuation".equalsIgnoreCase(explicit)) {
            return explicit;
        }
        if (looksLikeCodingTask(metadata, extraSignals)) {
            return "coding";
        }
        return explicit != null ? explicit : fallback;
    }

    public static boolean looksLikeCodingTask(Task task, String... extraSignals) {
        return "coding".equalsIgnoreCase(effectiveTaskType(task, null, extraSignals));
    }

    public static boolean looksLikeCodingTask(Map<String, Object> metadata, String... extraSignals) {
        String combined = combinedTaskSignalText(metadata, extraSignals);
        if (combined == null) {
            return false;
        }
        String lower = combined.toLowerCase();
        boolean mentionsRepoPath = WINDOWS_REPO_PATH.matcher(combined).find();
        boolean mentionsCodeFile = CODE_FILE_REFERENCE.matcher(combined).find();
        boolean mentionsCodingAction = CODING_ACTION_KEYWORD.matcher(combined).find();
        boolean mentionsKnownRepo = lower.contains("\\gitall\\")
            || lower.contains("/src/main/")
            || lower.contains("\\src\\main\\")
            || lower.contains("pom.xml")
            || lower.contains("package.json")
            || lower.contains("articleeditor");
        return (mentionsCodingAction && (mentionsRepoPath || mentionsCodeFile || mentionsKnownRepo))
            || (mentionsRepoPath && mentionsCodeFile)
            || (mentionsKnownRepo && mentionsCodingAction);
    }

    private static String combinedTaskSignalText(Map<String, Object> metadata, String... extraSignals) {
        String joinedExtras = joinNonBlank(extraSignals);
        return firstNonBlank(
            joinNonBlank(
                joinedExtras,
                stringValue(metadata != null ? metadata.get("goal") : null),
                stringValue(metadata != null ? metadata.get("title") : null),
                stringValue(metadata != null ? metadata.get("summary") : null),
                stringValue(metadata != null ? metadata.get("intent") : null),
                stringValue(metadata != null ? metadata.get("workspace") : null),
                stringValue(metadata != null ? metadata.get("repo_path") : null),
                stringValue(metadata != null ? metadata.get("working_directory") : null),
                stringValue(metadata != null ? metadata.get("target_path") : null)
            ),
            joinedExtras
        );
    }

    private static String joinNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(normalized);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private static String effectiveTaskType(Map<String, Object> metadata,
                                            String fallback,
                                            String title,
                                            String goal,
                                            String summary,
                                            String... extraSignals) {
        return effectiveTaskType(metadata, fallback, mergeSignals(title, goal, summary, extraSignals));
    }

    private static String[] mergeSignals(String title, String goal, String summary, String... extraSignals) {
        int extraCount = extraSignals == null ? 0 : extraSignals.length;
        String[] signals = new String[3 + extraCount];
        signals[0] = title;
        signals[1] = goal;
        signals[2] = summary;
        if (extraCount > 0) {
            System.arraycopy(extraSignals, 0, signals, 3, extraCount);
        }
        return signals;
    }
}
