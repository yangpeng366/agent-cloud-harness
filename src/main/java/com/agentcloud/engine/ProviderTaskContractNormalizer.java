package com.agentcloud.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一归一化 provider worker 所需的本地执行合同。
 * 只补路径和结构化边界，不读取文件内容。
 */
final class ProviderTaskContractNormalizer {
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("(?i)\\b[a-z]:\\\\[^\\s\"'<>|，,；;。)）]+");
    private static final List<String> WORKSPACE_KEYS = List.of(
        "workspace_roots",
        "workspaces",
        "workspace_root",
        "workspace",
        "working_directory",
        "cwd",
        "repo_path",
        "target_path"
    );
    private static final List<String> LOCAL_PATH_KEYS = List.of(
        "reference_paths",
        "reference_files",
        "input_paths",
        "target_paths",
        "target_files",
        "repo_path",
        "workspace_root",
        "workspace_roots"
    );
    private static final List<String> CONTRACT_KEYS = List.of(
        "reference_docs",
        "reference_files",
        "reference_paths",
        "context_files",
        "input_files",
        "input_paths",
        "deliverables",
        "expected_outputs",
        "output_files",
        "output_paths",
        "desired_output_file",
        "desired_output_dir",
        "output_file",
        "output_dir",
        "required_checks",
        "investigation_goals",
        "verification_targets",
        "acceptance_checks",
        "validation_commands",
        "validation_command",
        "verification_commands",
        "verification_command",
        "test_commands",
        "test_command",
        "build_commands",
        "build_command",
        "check_commands",
        "check_command",
        "acceptance_criteria",
        "done_criteria",
        "success_criteria",
        "allowed_paths",
        "write_scope",
        "edit_scope",
        "owned_paths",
        "target_files",
        "target_paths",
        "modifiable_paths"
    );

    private ProviderTaskContractNormalizer() {
    }

    static void copyContractFields(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || source.isEmpty() || target == null) {
            return;
        }
        for (String key : CONTRACT_KEYS) {
            Object value = source.get(key);
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    static void normalize(Map<String, Object> metadata, String... textSources) {
        if (metadata == null) {
            return;
        }
        List<String> workspaces = inferWorkspaceRoots(metadata, textSources);
        if (!workspaces.isEmpty()) {
            metadata.putIfAbsent("workspace_roots", workspaces);
            String workspace = workspaces.get(0);
            metadata.putIfAbsent("workspace_root", workspace);
            metadata.putIfAbsent("workspace", workspace);
            metadata.putIfAbsent("working_directory", workspace);
            metadata.putIfAbsent("cwd", workspace);
            metadata.putIfAbsent("repo_path", workspace);
        }
        List<String> localPaths = inferLocalPaths(metadata, textSources);
        if (!localPaths.isEmpty()) {
            metadata.putIfAbsent("reference_paths", localPaths);
            metadata.putIfAbsent("target_paths", localPaths);
        }
    }

    static List<String> workspaceRoots(Map<String, Object> metadata) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (metadata != null) {
            for (String key : WORKSPACE_KEYS) {
                addWorkspaceValue(roots, metadata.get(key));
            }
        }
        return List.copyOf(roots);
    }

    private static List<String> inferWorkspaceRoots(Map<String, Object> metadata, String... textSources) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (metadata != null) {
            for (String key : WORKSPACE_KEYS) {
                addWorkspaceValue(roots, metadata.get(key));
            }
        }
        for (String source : normalizedTextSources(metadata, textSources)) {
            Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(source);
            while (matcher.find()) {
                addWorkspaceValue(roots, matcher.group());
            }
        }
        return List.copyOf(roots);
    }

    private static List<String> inferLocalPaths(Map<String, Object> metadata, String... textSources) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (metadata != null) {
            for (String key : LOCAL_PATH_KEYS) {
                addLocalPathValue(paths, metadata.get(key));
            }
        }
        for (String source : normalizedTextSources(metadata, textSources)) {
            Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(source);
            while (matcher.find()) {
                addLocalPathValue(paths, matcher.group());
            }
        }
        return List.copyOf(paths);
    }

    private static List<String> normalizedTextSources(Map<String, Object> metadata, String... textSources) {
        ArrayList<String> sources = new ArrayList<>();
        if (textSources != null) {
            for (String source : textSources) {
                addIfNotBlank(sources, source);
            }
        }
        addIfNotBlank(sources, metadataString(metadata, "intent"));
        addIfNotBlank(sources, metadataString(metadata, "goal"));
        addIfNotBlank(sources, metadataString(metadata, "title"));
        return sources;
    }

    private static void addWorkspaceValue(Set<String> roots, Object value) {
        if (roots == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addWorkspaceValue(roots, item);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addWorkspaceValue(roots, java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        String text = blankToNull(stringValue(value));
        if (text == null) {
            return;
        }
        Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(text);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            String root = workspaceRootFromPath(stripTrailingPathNoise(matcher.group()));
            if (root != null) {
                roots.add(root);
            }
        }
        if (!matched) {
            String root = workspaceRootFromPath(stripTrailingPathNoise(text));
            if (root != null) {
                roots.add(root);
            }
        }
    }

    private static void addLocalPathValue(Set<String> paths, Object value) {
        if (paths == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addLocalPathValue(paths, item);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addLocalPathValue(paths, java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        String text = blankToNull(stringValue(value));
        if (text == null) {
            return;
        }
        Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(text);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            String path = stripTrailingPathNoise(matcher.group());
            if (path != null) {
                paths.add(path);
            }
        }
        if (!matched) {
            String path = stripTrailingPathNoise(text);
            if (path != null) {
                paths.add(path);
            }
        }
    }

    private static String workspaceRootFromPath(String rawPath) {
        String normalized = blankToNull(rawPath);
        if (normalized == null) {
            return null;
        }
        try {
            Path path = Paths.get(normalized).toAbsolutePath().normalize();
            Path cursor = Files.isDirectory(path) ? path : path.getParent();
            while (cursor != null) {
                if (Files.isDirectory(cursor.resolve(".git"))
                    || Files.exists(cursor.resolve("pom.xml"))
                    || Files.exists(cursor.resolve("package.json"))) {
                    return cursor.toString();
                }
                cursor = cursor.getParent();
            }
        } catch (RuntimeException ignored) {
            // 非法路径片段继续走 D:\gitAll\<repo> 的文本兜底。
        }
        return gitAllRepoRootFromText(normalized);
    }

    private static String gitAllRepoRootFromText(String pathText) {
        String normalized = blankToNull(pathText);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("\\gitall\\");
        if (marker < 0) {
            return null;
        }
        int start = marker + "\\gitall\\".length();
        int nextSlash = normalized.indexOf('\\', start);
        if (nextSlash <= start) {
            return null;
        }
        return normalized.substring(0, nextSlash);
    }

    private static String stripTrailingPathNoise(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        while (!normalized.isEmpty()) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == '，' || last == '；' || last == '。') {
                normalized = normalized.substring(0, normalized.length() - 1);
                continue;
            }
            break;
        }
        return normalized;
    }

    private static void addIfNotBlank(List<String> values, String value) {
        String normalized = blankToNull(value);
        if (values != null && normalized != null) {
            values.add(normalized);
        }
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        return stringValue(metadata.get(key));
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
