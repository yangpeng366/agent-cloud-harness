package com.agentcloud.judgment.model.shadow.stats;

import com.agentcloud.judgment.model.shadow.DecisionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 Calibration 后续：DecisionEvent reasonCodes 分布统计。
 * <p>
 * 按 kind (router / tool_gate / verifier) 聚合 reasonCodes 的分布，
 * 用于观察启发式 Provider 的判断依据占比（如 router 中
 * "difficulty_or_needs_browser_above_strong_min" 占多数 vs
 * "side_effect_risk_above_human_threshold" 占多数）。
 * <p>
 * 与 DecisionEventStats 共享 reader / 错误处理；不连 ACH runtime，纯离线。
 */
public final class DecisionEventReasonStats {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private DecisionEventReasonStats() {}

    public record ReasonSummary(
            long totalEvents,
            long skippedLines,
            Map<String, Long> reasonsByKind,
            Map<String, Long> reasonsByKindAndAction,
            Map<String, Long> degradedReasons
    ) {}

    public static ReasonSummary compute(List<DecisionEvent> events) {
        Map<String, Long> reasonsByKind = new LinkedHashMap<>();
        Map<String, Long> reasonsByKindAndAction = new LinkedHashMap<>();
        Map<String, Long> degradedReasons = new LinkedHashMap<>();
        long count = 0;

        for (DecisionEvent e : events) {
            if (e == null) continue;
            count++;
            String kind = e.kind() == null ? "unknown" : e.kind();
            String action = e.action() == null ? "unknown" : e.action();
            accumulateReasons(e, kind, action, reasonsByKind, reasonsByKindAndAction, degradedReasons);
        }

        return new ReasonSummary(count, 0L, reasonsByKind, reasonsByKindAndAction, degradedReasons);
    }

    private static void accumulateReasons(DecisionEvent e, String kind, String action,
                                          Map<String, Long> reasonsByKind,
                                          Map<String, Long> reasonsByKindAndAction,
                                          Map<String, Long> degradedReasons) {
        if (e.reasonCodes() == null) return;
        for (Map.Entry<String, String> rc : e.reasonCodes().entrySet()) {
            if (rc.getValue() == null) continue;
            String policyCode = rc.getValue();
            String policyKey = kind + ":" + policyCode;
            reasonsByKind.merge(policyKey, 1L, Long::sum);
            String kindActionPolicy = kind + ":" + action + ":" + policyCode;
            reasonsByKindAndAction.merge(kindActionPolicy, 1L, Long::sum);
            if (e.degraded()) {
                degradedReasons.merge(policyCode, 1L, Long::sum);
            }
        }
    }

    public static ReasonSummary compute(Path jsonlFile) throws IOException {
        if (jsonlFile == null || !Files.exists(jsonlFile)) {
            return empty();
        }
        Map<String, Long> reasonsByKind = new LinkedHashMap<>();
        Map<String, Long> reasonsByKindAndAction = new LinkedHashMap<>();
        Map<String, Long> degradedReasons = new LinkedHashMap<>();
        long count = 0;
        long skipped = 0;

        try (BufferedReader r = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                DecisionEvent e;
                try {
                    e = MAPPER.readValue(line, DecisionEvent.class);
                } catch (Exception parseEx) {
                    skipped++;
                    continue;
                }
                count++;
                String kind = e.kind() == null ? "unknown" : e.kind();
                String action = e.action() == null ? "unknown" : e.action();
                accumulateReasons(e, kind, action, reasonsByKind, reasonsByKindAndAction, degradedReasons);
            }
        }

        return new ReasonSummary(count, skipped, reasonsByKind, reasonsByKindAndAction, degradedReasons);
    }

    private static ReasonSummary empty() {
        return new ReasonSummary(0L, 0L, Map.of(), Map.of(), Map.of());
    }
}