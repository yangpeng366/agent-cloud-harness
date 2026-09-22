package com.agentcloud.scorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevPatrolPostprocessTest -- fake Jev contract test for Jev-Patrol-L3.
 * (See docs/INITIATIVES/Jev-Patrol-L3.md and docs/JEV_PATROL_INTEGRATION_PLAN.md §L3.)
 *
 * Mirrors the path B JevDecisionTreeTest fake Jev shape (no TYPESAFE_API_KEY).
 *
 * Contract: After each patrol round lands `.tmp\patrol-last-<project>.md` (11-17K),
 * a Jev postprocess pass extracts 5-8 structured `key_decisions` and writes them
 * to `.tmp\patrol-decisions-<project>-<stamp>.json` per docs/JEV_PATROL_INTEGRATION_PLAN.md §L3.
 * Bitable 「变更摘要」 field consumes this JSON downstream.
 *
 * Key fields per decision:
 *   - id: stable hash of (project + round + line)
 *   - text: 1-line summary (≤ 120 chars)
 *   - confidence: Jev noul probability in [0,1] (decides whether to surface)
 *   - source_line: originating line number in patrol-last-*.md
 *   - category: one of progress / blocker / decision / artifact / open_question
 *
 * Categories use the `key_decisions` cookbook pattern from JEV_OFFICIAL_SKILL_ABSORPTION.md.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("Jev patrol postprocess contract test (Jev-Patrol-L3 fake Jev)")
public class JevPatrolPostprocessTest {

    /** Key decision record (production-shape, JSON-serializable). */
    record KeyDecision(
        String id,
        String text,
        double confidence,
        int sourceLine,
        String category
    ) {}

    /** Patrol decisions payload (production-shape, JSON-serializable). */
    record PatrolDecisions(
        String projectName,
        String roundId,
        String stamp,
        int sourceLineCount,
        List<KeyDecision> keyDecisions,
        double coverage,  // keyDecisions.size() / 5.0 (target = 1.0 when ≥5)
        String summary    // one-line rollup for Bitable 「变更摘要」field
    ) {}

    /** Decision category enum (production-shape). */
    static final List<String> CATEGORIES = List.of(
        "progress", "blocker", "decision", "artifact", "open_question"
    );

    /** Threshold for surfacing a key decision (Bitable only displays >= 0.5). */
    static final double SURFACE_THRESHOLD = 0.5;

    /** Target range for key_decisions count per docs/JEV_PATROL_INTEGRATION_PLAN.md §L3. */
    static final int MIN_KEY_DECISIONS = 5;
    static final int MAX_KEY_DECISIONS = 8;

    /**
     * Fake Jev key_decisions extraction.
     *
     * Real Jev would call `classify` or `key_decisions` cookbook pattern with the
     * full patrol-last-*.md as state. Fake Jev scans for category markers:
     *   - "## Progress" lines -> category=progress
     *   - "## Blocker" lines  -> category=blocker
     *   - "## Decision" lines -> category=decision
     *   - "## Artifact" lines -> category=artifact
     *   - "?" at line end     -> category=open_question
     *
     * confidence = Jev noul (fake: 0.85 for explicit marker, 0.5 for "?" heuristic, 0.2 for noise).
     */
    static PatrolDecisions extractKeyDecisions(
            String projectName, String roundId, String stamp, String patrolMd) {
        if (patrolMd == null || patrolMd.isBlank()) {
            return new PatrolDecisions(projectName, roundId, stamp, 0,
                List.of(), 0.0, "empty patrol md");
        }
        String[] lines = patrolMd.split("\\R");
        List<KeyDecision> decisions = new ArrayList<>();
        Pattern progressH = Pattern.compile("^##?\\s*Progress\\b", Pattern.CASE_INSENSITIVE);
        Pattern blockerH = Pattern.compile("^##?\\s*Blocker\\b", Pattern.CASE_INSENSITIVE);
        Pattern decisionH = Pattern.compile("^##?\\s*Decision\\b", Pattern.CASE_INSENSITIVE);
        Pattern artifactH = Pattern.compile("^##?\\s*Artifact\\b", Pattern.CASE_INSENSITIVE);

        String currentCategory = null;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Detect section header -> update currentCategory
            String detected = null;
            if (progressH.matcher(line).find()) detected = "progress";
            else if (blockerH.matcher(line).find()) detected = "blocker";
            else if (decisionH.matcher(line).find()) detected = "decision";
            else if (artifactH.matcher(line).find()) detected = "artifact";
            if (detected != null) {
                currentCategory = detected;
                continue;  // header itself is not a decision
            }
            // Skip lines outside any section, or pure section headers (#, ##, etc.)
            if (currentCategory == null) continue;
            // Extract bullet item as a decision
            // Strip leading "- " or "* " or "1. " etc.
            String text = line;
            text = text.replaceFirst("^[-*]\\s+", "");
            text = text.replaceFirst("^\\d+\\.\\s+", "");
            if (text.isEmpty()) continue;
            String id = projectName + "_" + roundId + "_L" + (i + 1);
            String trimmed = text.length() > 120 ? text.substring(0, 117) + "..." : text;
            decisions.add(new KeyDecision(id, trimmed, 0.85, i + 1, currentCategory));
        }
        // Open-question detection (lines ending with ? outside any section, or as standalone)
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.endsWith("?") && line.length() > 5 && line.length() < 200) {
                String id = projectName + "_" + roundId + "_Q" + (i + 1);
                String trimmed = line.length() > 120 ? line.substring(0, 117) + "..." : line;
                decisions.add(new KeyDecision(id, trimmed, 0.55, i + 1, "open_question"));
            }
        }
        // Cap to MAX_KEY_DECISIONS, prefer higher confidence
        decisions.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        if (decisions.size() > MAX_KEY_DECISIONS) {
            decisions = new ArrayList<>(decisions.subList(0, MAX_KEY_DECISIONS));
        }
        double coverage = Math.min(1.0, decisions.size() / (double) MIN_KEY_DECISIONS);
        String summary = decisions.isEmpty()
            ? "no key decisions extracted"
            : String.format("%d key decisions across %d categories",
                decisions.size(),
                decisions.stream().map(KeyDecision::category).distinct().count());
        return new PatrolDecisions(projectName, roundId, stamp, lines.length,
            decisions, coverage, summary);
    }

    @Test
    @DisplayName("patrol-md with 3 Progress + 2 Blocker + 1 Decision + 1 Artifact = 7 key_decisions")
    void extractsKeyDecisionsByCategory() {
        String md = """
            # Patrol Round 42 - 巴中方正云雀
            ## Progress
            - Phase 3 集成测试通过率 95%
            - mock data 接口稳定
            - 与 Bitable 双向同步验证 OK
            ## Blocker
            - 客户环境 Tomcat 启动慢需排查
            - 60min 巡检偶发超时
            ## Decision
            - 决定走 puppeteer-core acceptance 套件而非 OpenEyes
            ## Artifact
            - 5 条 acceptance 验证用例固化
            """;
        PatrolDecisions pd = extractKeyDecisions("巴中方正云雀-见报成品接入预研", "R42", "2026-09-21T15:30", md);
        assertEquals(7, pd.keyDecisions().size());
        long progress = pd.keyDecisions().stream().filter(d -> "progress".equals(d.category())).count();
        long blocker = pd.keyDecisions().stream().filter(d -> "blocker".equals(d.category())).count();
        long decision = pd.keyDecisions().stream().filter(d -> "decision".equals(d.category())).count();
        long artifact = pd.keyDecisions().stream().filter(d -> "artifact".equals(d.category())).count();
        assertEquals(3, progress);
        assertEquals(2, blocker);
        assertEquals(1, decision);
        assertEquals(1, artifact);
        assertEquals(1.0, pd.coverage(), "7 decisions >= 5 target -> coverage 1.0");
    }

    @Test
    @DisplayName("empty patrol-md returns empty key_decisions + summary='empty patrol md' (no NPE)")
    void emptyPatrolMdHandledGracefully() {
        PatrolDecisions pd = extractKeyDecisions("p", "R1", "s", "");
        assertEquals(0, pd.keyDecisions().size());
        assertEquals(0.0, pd.coverage());
        assertEquals("empty patrol md", pd.summary());
        // null md also handled
        PatrolDecisions pd2 = extractKeyDecisions("p", "R1", "s", null);
        assertEquals(0, pd2.keyDecisions().size());
    }

    @Test
    @DisplayName("confidence ∈ [0,1] for every key_decision (API contract)")
    void confidenceInRange() {
        String md = """
            ## Progress
            - step A done
            ## Decision
            - step B chosen
            ## Artifact
            - step C artifact
            - step D artifact
            - step E artifact
            ## Progress
            - step F done
            """;
        PatrolDecisions pd = extractKeyDecisions("p", "R1", "s", md);
        assertFalse(pd.keyDecisions().isEmpty());
        for (KeyDecision d : pd.keyDecisions()) {
            assertTrue(d.confidence() >= 0.0 && d.confidence() <= 1.0,
                "decision " + d.id() + " confidence=" + d.confidence() + " out of [0,1]");
        }
    }

    @Test
    @DisplayName("patrol-decisions JSON roundtrip: Jackson serialize + deserialize preserves all fields")
    void jsonRoundTripPreservesFields() throws Exception {
        String md = """
            ## Progress
            - A done
            - B done
            - C done
            ## Blocker
            - D blocker
            ## Decision
            - E decision
            """;
        PatrolDecisions pd = extractKeyDecisions("巴中方正云雀", "R42", "2026-09-21T15:30", md);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(pd);
        PatrolDecisions restored = mapper.readValue(json, PatrolDecisions.class);
        assertEquals(pd.projectName(), restored.projectName());
        assertEquals(pd.roundId(), restored.roundId());
        assertEquals(pd.stamp(), restored.stamp());
        assertEquals(pd.sourceLineCount(), restored.sourceLineCount());
        assertEquals(pd.keyDecisions().size(), restored.keyDecisions().size());
        for (int i = 0; i < pd.keyDecisions().size(); i++) {
            KeyDecision a = pd.keyDecisions().get(i);
            KeyDecision b = restored.keyDecisions().get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.text(), b.text());
            assertEquals(a.confidence(), b.confidence());
            assertEquals(a.sourceLine(), b.sourceLine());
            assertEquals(a.category(), b.category());
        }
    }

    @Test
    @DisplayName("categories covered: 5 categories distinguishable in output")
    void allCategoriesDistinguishable() {
        String md = """
            ## Progress
            - A done
            ## Blocker
            - B blocked
            ## Decision
            - C decided
            ## Artifact
            - D artifact
            Why is X not working?
            """;
        PatrolDecisions pd = extractKeyDecisions("p", "R1", "s", md);
        assertTrue(pd.keyDecisions().size() >= MIN_KEY_DECISIONS,
            "should extract >= " + MIN_KEY_DECISIONS + " decisions across 5 categories");
        Map<String, Long> catCount = new LinkedHashMap<>();
        for (KeyDecision d : pd.keyDecisions()) {
            catCount.merge(d.category(), 1L, Long::sum);
        }
        assertEquals(5, catCount.size(),
            "all 5 categories represented: " + catCount.keySet());
        for (String cat : CATEGORIES) {
            assertTrue(catCount.containsKey(cat), "category " + cat + " missing");
        }
    }

    @Test
    @DisplayName("MAX_KEY_DECISIONS cap: >8 decisions still produces <= 8 (prefer higher confidence)")
    void maxKeyDecisionsCap() {
        // Construct 12 high-confidence Progress entries (12 > MAX_KEY_DECISIONS=8)
        StringBuilder md = new StringBuilder("## Progress\n");
        for (int i = 0; i < 12; i++) {
            md.append("- step ").append(i).append(" done\n");
        }
        PatrolDecisions pd = extractKeyDecisions("p", "R1", "s", md.toString());
        assertEquals(MAX_KEY_DECISIONS, pd.keyDecisions().size(),
            "should cap to MAX_KEY_DECISIONS=" + MAX_KEY_DECISIONS);
        assertTrue(pd.coverage() >= 1.0);
    }
}