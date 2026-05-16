package com.agentcloud.runtime;

import java.util.Locale;

/**
 * 统一收口 prompt / active context 的字段规范化与重复判断。
 */
public final class PromptFieldDeduper {

    private PromptFieldDeduper() {
    }

    public static String normalizePromptField(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    public static boolean isPromptFieldDuplicate(String left, String right) {
        String normalizedLeft = dedupeKey(left);
        String normalizedRight = dedupeKey(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    public static String firstDistinctNormalized(String candidate, String... existingValues) {
        String normalizedCandidate = normalizePromptField(candidate);
        if (normalizedCandidate.isBlank()) {
            return "";
        }
        if (existingValues == null || existingValues.length == 0) {
            return normalizedCandidate;
        }
        for (String existingValue : existingValues) {
            if (isPromptFieldDuplicate(normalizedCandidate, existingValue)) {
                return "";
            }
        }
        return normalizedCandidate;
    }

    private static String dedupeKey(String value) {
        return normalizePromptField(value).toLowerCase(Locale.ROOT);
    }
}
