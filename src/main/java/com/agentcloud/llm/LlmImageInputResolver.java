package com.agentcloud.llm;

import com.agentcloud.model.SessionMessage;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从任务运行时上下文解析图片输入。
 */
public final class LlmImageInputResolver {
    private LlmImageInputResolver() {
    }

    public static List<LlmImageInput> resolve(TaskRuntimeContext context) {
        if (context == null) {
            return List.of();
        }
        List<LlmImageInput> inputs = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        collectFromMetadata(inputs, dedupe, context.task() == null ? null : context.task().metadata());
        if (context.recentMessages() != null) {
            for (SessionMessage message : context.recentMessages()) {
                if (message == null) {
                    continue;
                }
                collectFromMetadata(inputs, dedupe, message.metadata());
            }
        }
        return List.copyOf(inputs);
    }

    @SuppressWarnings("unchecked")
    private static void collectFromMetadata(List<LlmImageInput> inputs,
                                            Set<String> dedupe,
                                            Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        addCandidate(inputs, dedupe, metadata.get("image_input"));
        addCandidate(inputs, dedupe, metadata.get("image_inputs"));
        addCandidate(inputs, dedupe, metadata.get("local_image"));
        addCandidate(inputs, dedupe, metadata.get("local_images"));
        Object attachments = metadata.get("attachments");
        if (attachments instanceof List<?> list) {
            for (Object item : list) {
                addCandidate(inputs, dedupe, item);
            }
        }
        Object references = metadata.get("references");
        if (references instanceof List<?> list) {
            for (Object item : list) {
                addCandidate(inputs, dedupe, item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addCandidate(List<LlmImageInput> inputs, Set<String> dedupe, Object candidate) {
        if (candidate == null) {
            return;
        }
        if (candidate instanceof String text) {
            addImageInput(inputs, dedupe, text, null);
            return;
        }
        if (candidate instanceof LlmImageInput imageInput) {
            addImageInput(inputs, dedupe, imageInput.path(), imageInput.mediaType());
            return;
        }
        if (candidate instanceof Map<?, ?> map) {
            Object pathValue = firstNonNull(
                map.get("path"),
                map.get("file_path"),
                map.get("local_path"),
                map.get("image_path"),
                map.get("url")
            );
            Object typeValue = firstNonNull(
                map.get("media_type"),
                map.get("mime_type"),
                map.get("content_type")
            );
            addImageInput(inputs, dedupe, stringValue(pathValue), stringValue(typeValue));
            return;
        }
        if (candidate instanceof List<?> list) {
            for (Object item : list) {
                addCandidate(inputs, dedupe, item);
            }
        }
    }

    private static void addImageInput(List<LlmImageInput> inputs,
                                      Set<String> dedupe,
                                      String rawPath,
                                      String rawMediaType) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.isBlank()) {
            return;
        }
        String normalizedKey = path + "|" + normalizeMediaType(rawMediaType);
        if (!dedupe.add(normalizedKey)) {
            return;
        }
        inputs.add(new LlmImageInput(path, normalizeMediaType(rawMediaType)));
    }

    private static String normalizeMediaType(String rawMediaType) {
        if (rawMediaType == null) {
            return null;
        }
        String trimmed = rawMediaType.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
