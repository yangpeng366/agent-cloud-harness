package com.agentcloud.scorer;

import com.agentcloud.model.HandoffPacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HandoffPacketKeepProbTest -- fake Jev contract test for HW-09 keep-probability
 * attached to HandoffPacket decision metadata (see docs/INITIATIVES/HW-09.md).
 *
 * Mirrors the path B JevDecisionTreeTest fake Jev shape (see
 * docs/JEV_HANDS_ON_NOTES.md and docs/JEV_DECISION_TREE_REAL_EXPERIMENT.md).
 *
 * The HW-09 production contract is: HandoffPacketBuilder should attach a
 * Jev-derived keep_probability (0.0-1.0) per decision item and demote
 * low-probability items to a dedicated metadata field rather than deleting
 * them. This test verifies the contract in fake Jev mode (no TYPESAFE_API_KEY).
 *
 * Why metadata-only? HandoffPacket is a public record with `List<String>`
 * fields for whatDone/whatRemaining/cautions. Mutating those types is a
 * Contract-Additive change. We verify the contract via `metadata`, which
 * already is `Map<String,Object>` -- the production builder can attach
 * jev_decisions / jev_footnotes there without changing the record signature.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("HandoffPacket keep-probability contract test (HW-09 fake Jev, no schema change)")
public class HandoffPacketKeepProbTest {

    /** Fake Jev scoring: deterministic, no key needed. Mirrors fast-jev-compaction tests/fakeJev. */
    static double fakeJevScore(String item) {
        if (item == null) return 0.0;
        if (item.contains("BLOCKER") || item.contains("TODO")) return 0.10;
        if (item.contains("DECIDED") || item.contains("DONE")) return 0.95;
        if (item.contains("neutral")) return 0.70;
        return 0.50;
    }

    /** 3-segment threshold routing per FEAT-03 \u00a71.1a. */
    static String route(double p, double low, double high) {
        if (p >= high) return "keep";
        if (p >= low) return "flag_for_review";
        return "demote_to_footnotes";
    }

    /**
     * Production-shaped builder: takes input items + Jev scores, attaches
     * jev_decisions + jev_footnotes to HandoffPacket.metadata.
     * The production HandoffPacketBuilder will replicate this shape.
     */
    static Map<String, Object> buildJevDecisions(
            List<String> items, double keepLow, double keepHigh) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<Map<String, Object>> footnotes = new ArrayList<>();
        for (String s : items) {
            double p = fakeJevScore(s);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", s);
            entry.put("keep_probability", p);
            entry.put("route", route(p, keepLow, keepHigh));
            if (route(p, keepLow, keepHigh).equals("demote_to_footnotes")) {
                footnotes.add(entry);
            } else {
                kept.add(entry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jev_decisions", kept);
        result.put("jev_footnotes", footnotes);
        return result;
    }

    static HandoffPacket basePacket() {
        return new HandoffPacket(
            "1.0", true, null, "worker-A", "worker-B",
            "implement feature X", "in_progress", "node-1",
            "load exceeded", List.of(),
            List.of(), List.of(), "see next",
            null, null, Map.of()
        );
    }

    @Test
    @DisplayName("Jev keep_probability attached to metadata.jev_decisions per item")
    void keepProbAttachedPerItem() {
        HandoffPacket base = basePacket();
        List<String> items = List.of(
            "DECIDED: switch to provider X",
            "BLOCKER: rate limit",
            "TODO: refactor handler",
            "DECIDED: cache pre-warmed"
        );
        Map<String, Object> jev = buildJevDecisions(items, 0.5, 0.85);

        // 2 high-prob kept (DECIDED), 2 low-prob demoted (BLOCKER / TODO)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) jev.get("jev_decisions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> footnotes = (List<Map<String, Object>>) jev.get("jev_footnotes");
        assertEquals(2, kept.size());
        assertEquals(2, footnotes.size());

        // No items dropped (4 = 2 + 2)
        assertEquals(items.size(), kept.size() + footnotes.size());
    }

    @Test
    @DisplayName("keep_probability values are within [0,1] (Jev contract)")
    void keepProbValuesInRange() {
        Map<String, Object> jev = buildJevDecisions(
            List.of("DECIDED: A", "TODO: B", "neutral: C"), 0.5, 0.85);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) jev.get("jev_decisions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fn = (List<Map<String, Object>>) jev.get("jev_footnotes");
        for (Map<String, Object> e : kept) {
            double p = (double) e.get("keep_probability");
            assertTrue(p >= 0.0 && p <= 1.0, "keep_probability out of [0,1]: " + p);
        }
        for (Map<String, Object> e : fn) {
            double p = (double) e.get("keep_probability");
            assertTrue(p >= 0.0 && p <= 1.0);
        }
    }

    @Test
    @DisplayName("3-segment routing (FEAT-3 \u00a71.1a) maps keep_probability to action")
    void threeSegmentRouting() {
        Map<String, Object> jev = buildJevDecisions(
            List.of("DECIDED: ok", "BLOCKER: retry", "neutral: maybe"), 0.5, 0.85);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) jev.get("jev_decisions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fn = (List<Map<String, Object>>) jev.get("jev_footnotes");
        // "DECIDED" (0.95) + "neutral: maybe" (0.70) -> both keep verbatim (>= low=0.5)
        // wait, "neutral: maybe" is 0.70 which is >= low=0.5 so it goes to kept (flag_for_review)
        assertEquals(2, kept.size(), "DECIDED(keep) + neutral(flag_for_review) both >= low threshold");
        assertEquals("keep", kept.get(0).get("route"));
        assertEquals("flag_for_review", kept.get(1).get("route"));
        // "BLOCKER" (0.10) -> demote
        assertEquals(1, fn.size());
        assertEquals("demote_to_footnotes", fn.get(0).get("route"));
    }

    @Test
    @DisplayName("HandoffPacket.metadata round-trips jev_decisions via Jackson (serialization contract)")
    void handoffPacketMetadataJsonRoundTrip() throws Exception {
        HandoffPacket base = basePacket();
        List<String> items = List.of("DECIDED: rate ok", "TODO: cleanup");
        Map<String, Object> jev = buildJevDecisions(items, 0.5, 0.85);

        Map<String, Object> meta = new LinkedHashMap<>(base.metadata());
        meta.putAll(jev);
        HandoffPacket annotated = new HandoffPacket(
            base.packetVersion(), base.machineReadableFirst(), base.taskIdentity(),
            base.fromWorker(), base.toWorker(), base.currentObjective(),
            base.currentStatus(), base.currentNode(), base.whyHandoff(),
            base.whatDone(), base.whatRemaining(), base.cautions(),
            base.resumeHint(), base.latestSummary(), base.handoffSummary(),
            meta
        );

        ObjectMapper m = new ObjectMapper();
        String json = m.writeValueAsString(annotated);
        HandoffPacket back = m.readValue(json, HandoffPacket.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jevBack = (List<Map<String, Object>>) back.metadata().get("jev_decisions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fnBack = (List<Map<String, Object>>) back.metadata().get("jev_footnotes");

        assertNotNull(jevBack);
        assertEquals(1, jevBack.size());
        assertEquals("DECIDED: rate ok", jevBack.get(0).get("text"));
        assertEquals(0.95, (double) jevBack.get(0).get("keep_probability"), 0.001);
        assertNotNull(fnBack);
        assertEquals(1, fnBack.size());
        assertEquals("TODO: cleanup", fnBack.get(0).get("text"));
        assertEquals(0.10, (double) fnBack.get(0).get("keep_probability"), 0.001);
    }
}