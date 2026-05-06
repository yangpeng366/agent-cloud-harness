package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受控文本 patch 工具。
 */
public class PatchFileTool extends AbstractLocalFileTool {

    public PatchFileTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "patch_file";
    }

    @Override
    public String description() {
        return "Apply an exact UTF-8 text replacement inside an allowed existing file.";
    }

    @Override
    public String argumentContract() {
        return "{\"path\":\"draft.md\",\"old_text\":\"TODO\",\"new_text\":\"done\",\"replace_all\":false,\"expected_occurrences\":1}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Path file = resolvePath(request, true, false);
        String oldText = requiredTextArg(request.arguments(), "old_text", false);
        String newText = requiredTextArg(request.arguments(), "new_text", true);
        boolean replaceAll = booleanArg(request.arguments(), "replace_all", false);
        Integer expectedOccurrences = optionalExpectedOccurrences(request.arguments());

        if (!Files.exists(file)) {
            throw new IllegalArgumentException("file does not exist: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("path is not a file: " + file);
        }

        String original = Files.readString(file, StandardCharsets.UTF_8);
        int matchedOccurrences = countOccurrences(original, oldText);
        if (matchedOccurrences < 1) {
            throw new IllegalArgumentException("old_text not found in file: " + file);
        }
        if (expectedOccurrences != null && matchedOccurrences != expectedOccurrences) {
            throw new IllegalArgumentException(
                "old_text occurrence mismatch: expected " + expectedOccurrences + " but found " + matchedOccurrences
            );
        }
        if (!replaceAll && matchedOccurrences != 1) {
            throw new IllegalArgumentException(
                "old_text matched multiple occurrences; set replace_all=true or expected_occurrences explicitly"
            );
        }

        String patched = replaceAll
            ? original.replace(oldText, newText)
            : replaceFirstExact(original, oldText, newText);
        if (patched.equals(original)) {
            throw new IllegalArgumentException("patch did not change file content");
        }

        Files.writeString(
            file,
            patched,
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );

        int replacements = replaceAll ? matchedOccurrences : 1;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", file.toString());
        metadata.put("matched_occurrences", matchedOccurrences);
        metadata.put("replacements", replacements);
        metadata.put("replace_all", replaceAll);
        metadata.put("bytes_written", patched.getBytes(StandardCharsets.UTF_8).length);

        String summary = replacements == 1
            ? "patched 1 occurrence in " + file
            : "patched " + replacements + " occurrences in " + file;
        return new ToolResult(true, summary, summary, metadata);
    }

    private String requiredTextArg(Map<String, Object> arguments, String key, boolean allowEmpty) {
        if (arguments == null || !arguments.containsKey(key)) {
            throw new IllegalArgumentException(key + " is required");
        }
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        String text = value.toString();
        if (!allowEmpty && text.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private Integer optionalExpectedOccurrences(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("expected_occurrences")) {
            return null;
        }
        Object value = arguments.get("expected_occurrences");
        if (value == null) {
            return null;
        }
        int parsed = value instanceof Number number
            ? number.intValue()
            : Integer.parseInt(value.toString());
        if (parsed < 1) {
            throw new IllegalArgumentException("expected_occurrences must be >= 1");
        }
        return parsed;
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int fromIndex = 0;
        while (fromIndex <= content.length() - target.length()) {
            int matchIndex = content.indexOf(target, fromIndex);
            if (matchIndex < 0) {
                break;
            }
            count++;
            fromIndex = matchIndex + target.length();
        }
        return count;
    }

    private String replaceFirstExact(String content, String oldText, String newText) {
        int matchIndex = content.indexOf(oldText);
        if (matchIndex < 0) {
            return content;
        }
        return content.substring(0, matchIndex)
            + newText
            + content.substring(matchIndex + oldText.length());
    }
}
