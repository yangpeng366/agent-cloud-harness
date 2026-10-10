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
 * M4 Calibration 起步：DecisionEvent JSONL 离线统计 utility。
 * <p>
 * 读取一个 JSONL 文件（或一个 List<DecisionEvent>），按 kind (router / tool_gate / verifier)
 * 汇总事件数 / action 分布 / degraded 比例 / modelRef 分布 / unique run 数 / 时间窗。
 * 不修改 ACH 核心，不连 sink，纯离线计算。M4 Calibration 阶段会基于本类输出做阈值校准。
 * <p>
 * 损坏的 JSON 行一律跳过（不抛异常）；空文件返回 Summary(totalEvents=0)。
 */
public final class DecisionEventStats {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private DecisionEventStats() {}

    public record Summary(
            long totalEvents,
            Map<String, Long> eventsByKind,
            Map<String, Long> actionsByKind,
            long degradedCount,
            Map<String, Long> degradedByKind,
            long uniqueRunIds,
            Map<String, Long> modelRefsByKind,
            long firstEventMs,
            long lastEventMs,
            long skippedLines
    ) {}

    public static Summary compute(List<DecisionEvent> events) {
        Map<String, Long> eventsByKind = new LinkedHashMap<>();
        Map<String, Long> actionsByKind = new LinkedHashMap<>();
        Map<String, Long> degradedByKind = new LinkedHashMap<>();
        Map<String, Long> modelRefsByKind = new LinkedHashMap<>();
        Map<String, Long> runIds = new LinkedHashMap<>();
        long degradedCount = 0;
        long firstMs = Long.MAX_VALUE;
        long lastMs = Long.MIN_VALUE;

        long count = 0;
        for (DecisionEvent e : events) {
            if (e == null) continue;
            count++;
            String kind = e.kind() == null ? "unknown" : e.kind();
            eventsByKind.merge(kind, 1L, Long::sum);
            String action = e.action() == null ? "unknown" : e.action();
            actionsByKind.merge(kind + ":" + action, 1L, Long::sum);
            String modelRef = e.modelRef() == null ? "unknown" : e.modelRef();
            modelRefsByKind.merge(kind + ":" + modelRef, 1L, Long::sum);
            if (e.degraded()) {
                degradedCount++;
                degradedByKind.merge(kind, 1L, Long::sum);
            }
            if (e.runId() != null && !e.runId().isBlank()) {
                runIds.put(e.runId(), 1L);
            }
            if (e.occurredAt() != null) {
                long ms = e.occurredAt().toEpochMilli();
                if (ms < firstMs) firstMs = ms;
                if (ms > lastMs) lastMs = ms;
            }
        }

        long windowMs = (firstMs == Long.MAX_VALUE || lastMs == Long.MIN_VALUE) ? 0L : (lastMs - firstMs);
        long normalizedFirstMs = (firstMs == Long.MAX_VALUE) ? 0L : firstMs;
        long normalizedLastMs = (lastMs == Long.MIN_VALUE) ? 0L : lastMs;

        return new Summary(
                count,
                eventsByKind,
                actionsByKind,
                degradedCount,
                degradedByKind,
                runIds.size(),
                modelRefsByKind,
                normalizedFirstMs,
                normalizedLastMs,
                0L
        );
    }

    public static Summary compute(Path jsonlFile) throws IOException {
        if (jsonlFile == null || !Files.exists(jsonlFile)) {
            return empty();
        }
        Map<String, Long> eventsByKind = new LinkedHashMap<>();
        Map<String, Long> actionsByKind = new LinkedHashMap<>();
        Map<String, Long> degradedByKind = new LinkedHashMap<>();
        Map<String, Long> modelRefsByKind = new LinkedHashMap<>();
        Map<String, Long> runIds = new LinkedHashMap<>();
        long degradedCount = 0;
        long total = 0;
        long firstMs = Long.MAX_VALUE;
        long lastMs = Long.MIN_VALUE;
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
                total++;
                String kind = e.kind() == null ? "unknown" : e.kind();
                eventsByKind.merge(kind, 1L, Long::sum);
                String action = e.action() == null ? "unknown" : e.action();
                actionsByKind.merge(kind + ":" + action, 1L, Long::sum);
                String modelRef = e.modelRef() == null ? "unknown" : e.modelRef();
                modelRefsByKind.merge(kind + ":" + modelRef, 1L, Long::sum);
                if (e.degraded()) {
                    degradedCount++;
                    degradedByKind.merge(kind, 1L, Long::sum);
                }
                if (e.runId() != null && !e.runId().isBlank()) {
                    runIds.put(e.runId(), 1L);
                }
                if (e.occurredAt() != null) {
                    long ms = e.occurredAt().toEpochMilli();
                    if (ms < firstMs) firstMs = ms;
                    if (ms > lastMs) lastMs = ms;
                }
            }
        }

        long normalizedFirstMs = (firstMs == Long.MAX_VALUE) ? 0L : firstMs;
        long normalizedLastMs = (lastMs == Long.MIN_VALUE) ? 0L : lastMs;

        return new Summary(
                total,
                eventsByKind,
                actionsByKind,
                degradedCount,
                degradedByKind,
                runIds.size(),
                modelRefsByKind,
                normalizedFirstMs,
                normalizedLastMs,
                skipped
        );
    }

    private static Summary empty() {
        return new Summary(0L, Map.of(), Map.of(), 0L, Map.of(), 0L, Map.of(), 0L, 0L, 0L);
    }
}