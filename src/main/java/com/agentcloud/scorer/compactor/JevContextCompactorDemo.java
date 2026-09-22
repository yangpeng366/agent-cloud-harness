package com.agentcloud.scorer.compactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JevContextCompactorDemo -- live API latency baseline (path A main path, 2026-09-21).
 *
 * Mirrors JEV_REAL_API_DEMO.md pattern: 9-run p50/p95 latency measurement
 * for the dual-noul compact path.
 *
 * Run:
 *   TYPESAFE_API_KEY=<key> .\scripts\Test-WithJava21.ps1 exec:java \
 *       -Dexec.mainClass=com.agentcloud.scorer.compactor.JevContextCompactorDemo \
 *       -Dexec.classpathScope=test
 *
 * Without TYPESAFE_API_KEY, prints a clear skip message and exits 0.
 *
 * Expected output (real API):
 *   elapse_ms: p50=Xms p95=Yms (N=10)
 *   per-run breakdown
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public class JevContextCompactorDemo {

    public static void main(String[] args) {
        if (!JevHttpClient.isConfigured()) {
            System.out.println("SKIP: TYPESAFE_API_KEY not set; live demo requires a real key.");
            System.out.println("Fake Jev baseline (1000 runs, no network) below:");
            runFakeBaseline();
            return;
        }

        System.out.println("=== JevContextCompactor live API latency baseline (N=10) ===");
        List<Long> elapseds = new ArrayList<>();
        try {
            JevContextCompactor.JevAsker real = new JevHttpClient();
            // 10 identical runs to derive p50/p95 (mirrors JEV_REAL_API_DEMO.md 9-run form)
            for (int i = 1; i <= 10; i++) {
                long t0 = System.nanoTime();
                runOnce(real);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                elapseds.add(elapsedMs);
                System.out.println("run " + i + ": " + elapsedMs + " ms");
            }
            Collections.sort(elapseds);
            long p50 = elapseds.get(elapseds.size() / 2);
            long p95 = elapseds.get((int) Math.floor(elapseds.size() * 0.95));
            long avg = (long) elapseds.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.println("---");
            System.out.println("p50=" + p50 + " ms  p95=" + p95 + " ms  avg=" + avg + " ms  N=" + elapseds.size());
        } catch (Exception e) {
            System.out.println("FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    /** One compact run with a fixed small transcript (4 calls, fits in one request). */
    static void runOnce(JevContextCompactor.JevAsker asker) {
        List<JevContextCompactor.Message> ms = smallTranscript();
        JevContextCompactor.CompactOptions opts = new JevContextCompactor.CompactOptions(
            0.5, 2, 25_000, 30_000, 100);
        JevContextCompactor.compact(ms, asker, opts);
    }

    static List<JevContextCompactor.Message> smallTranscript() {
        List<JevContextCompactor.Message> ms = new ArrayList<>();
        ms.add(new JevContextCompactor.Message("user", "Inspect the failing test.", List.of(), List.of()));
        var tu1 = new JevContextCompactor.ToolUse("toolu_1", "Read", "{\"file\":\"src/a.ts\"}");
        ms.add(new JevContextCompactor.Message("assistant", "", List.of(tu1), List.of()));
        ms.add(new JevContextCompactor.Message("user", "", List.of(),
            List.of(new JevContextCompactor.ToolResult("toolu_1", "ok-a", false))));
        var tu2 = new JevContextCompactor.ToolUse("toolu_2", "Bash", "{\"cmd\":\"ls -la\"}");
        ms.add(new JevContextCompactor.Message("assistant", "", List.of(tu2), List.of()));
        ms.add(new JevContextCompactor.Message("user", "", List.of(),
            List.of(new JevContextCompactor.ToolResult("toolu_2", "ok-b", false))));
        return ms;
    }

    /** Fake-Jev baseline: 1000 runs, measure compact() end-to-end latency without network. */
    static void runFakeBaseline() {
        List<Long> elapseds = new ArrayList<>();
        JevContextCompactor.JevAsker fake = new JevContextCompactor.JevAsker() {
            public Map<String, Double> ask(String state, Map<String, String> questions) {
                // Realistic shape: answer every question with 0.5
                java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
                for (String name : questions.keySet()) out.put(name, 0.5);
                return out;
            }
        };
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            long s = System.nanoTime();
            runOnce(fake);
            elapseds.add((System.nanoTime() - s) / 1_000_000);
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        Collections.sort(elapseds);
        long p50 = elapseds.get(500);
        long p95 = elapseds.get(950);
        long p99 = elapseds.get(990);
        long avg = (long) elapseds.stream().mapToLong(Long::longValue).average().orElse(0);
        long max = elapseds.get(999);
        long min = elapseds.get(0);
        System.out.println("fake Jev baseline (1000 runs):");
        System.out.println("  min=" + min + "ms  p50=" + p50 + "ms  p95=" + p95 + "ms  p99=" + p99
            + "ms  max=" + max + "ms  avg=" + avg + "ms  total=" + totalMs + "ms");
    }
}