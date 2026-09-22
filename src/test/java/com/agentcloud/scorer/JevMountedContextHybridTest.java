package com.agentcloud.scorer;

import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevMountedContextHybridTest -- fake Jev contract test for Jev-Graph-A
 * (mounted_context_view panel keep_probability hybrid formula).
 * See docs/INITIATIVES/Jev-Graph-A.md and docs/JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md §5.
 *
 * Mirrors the path B JevDecisionTreeTest fake Jev shape (no TYPESAFE_API_KEY).
 *
 * Contract: each MountedContextPanel gets a `jev_panel_weight` in [0,1]
 * computed as alpha * structural_score + gamma * jev_keep_probability.
 * Production: the weight becomes a continuous bias signal in
 * mounted_context_view selection -- soft gate, not hard binary keep/drop.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("MountedContextView panel hybrid weight (Jev-Graph-A fake Jev)")
public class JevMountedContextHybridTest {

    /** Fake Jev score (deterministic, mirrors fast-jev-compaction tests/fakeJev). */
    static double fakeJev(String panelName, List<ContextObject> objects) {
        if (objects == null || objects.isEmpty()) return 0.50;
        // Panels with explicit DECIDED objects get high prob; BLOCKER objects get low.
        boolean hasDecided = objects.stream().anyMatch(o -> o.summary() != null && o.summary().contains("DECIDED"));
        boolean hasBlocker = objects.stream().anyMatch(o -> o.summary() != null && o.summary().contains("BLOCKER"));
        if (hasDecided && !hasBlocker) return 0.95;
        if (hasBlocker && !hasDecided) return 0.10;
        if (hasDecided && hasBlocker) return 0.60;
        return 0.50;
    }

    /** Production-shape hybrid weight: alpha*structural + gamma*jev. */
    record HybridWeight(double structural, double jev, double alpha, double gamma, double value) {}

    static HybridWeight hybridWeight(double structural, double jev, double alpha, double gamma) {
        return new HybridWeight(structural, jev, alpha, gamma,
            alpha * structural + gamma * jev);
    }

    /** Production-shape: annotate MountedContextView with jev_panel_weight per panel. */
    static List<Map<String, Object>> annotatePanels(
            MountedContextView view, double alpha, double gamma) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MountedContextPanel p : view.panels()) {
            double jev = fakeJev(p.name().name(), p.objects());
            double structural = 0.50; // baseline structural (production-shape: future
                                          // TaskRuntimeContextBuilder computes from events/decisions)
            HybridWeight h = hybridWeight(structural, jev, alpha, gamma);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("panel_name", p.name().name());
            entry.put("alpha", alpha);
            entry.put("gamma", gamma);
            entry.put("structural_score", h.structural());
            entry.put("jev_keep_probability", h.jev());
            entry.put("jev_panel_weight", h.value());
            result.add(entry);
        }
        return result;
    }

    static MountedContextView viewWithPanels(Map<MountedContextPanelName, List<ContextObject>> map) {
        List<MountedContextPanel> panels = new ArrayList<>();
        for (Map.Entry<MountedContextPanelName, List<ContextObject>> e : map.entrySet()) {
            panels.add(new MountedContextPanel(e.getKey(), e.getKey().title(), e.getValue()));
        }
        return new MountedContextView(null, "task-A", panels, List.of());
    }

    /** ContextObject is a 12-field record; only summary carries the fake-Jev signal. */
    static ContextObject obj(String label) {
        return new ContextObject(
            label, label, ContextObjectType.HANDLE, null,
            label, label, null, null,
            ContextRetentionState.WARM_SUMMARY,
            List.of(), List.of(), Map.of());
    }

    @Test
    @DisplayName("Hybrid weight alpha=0.5 gamma=0.5 produces values in [0,1] across all 7 default panels")
    void hybridWeightInRange() {
        // empty list -> MountedContextView normalizes to all 7 default panels
        MountedContextView view = new MountedContextView(null, "task-A", List.of(), List.of());
        assertEquals(7, view.panels().size(), "MountedContextView normalizes empty to 7 default panels");

        List<Map<String, Object>> annotated = annotatePanels(view, 0.5, 0.5);
        assertEquals(7, annotated.size(), "annotated list must equal 7");
        for (Map<String, Object> entry : annotated) {
            double w = (double) entry.get("jev_panel_weight");
            assertTrue(w >= 0.0 && w <= 1.0,
                "hybrid weight must be in [0,1] for panel " + entry.get("panel_name") + "; was " + w);
        }
    }

    @Test
    @DisplayName("Hybrid weight correctly sums structural + jev components (alpha=0.7 gamma=0.3)")
    void hybridWeightSum() {
        // empty -> all 7 panels
        MountedContextView view = new MountedContextView(null, "task-A", List.of(), List.of());
        List<Map<String, Object>> annotated = annotatePanels(view, 0.7, 0.3);

        // All panels: structural=0.50 (baseline), fakeJev(empty)=0.50
        // weight = 0.7*0.5 + 0.3*0.5 = 0.50
        double expectedWeight = 0.7 * 0.50 + 0.3 * 0.50;
        for (Map<String, Object> entry : annotated) {
            double weight = (double) entry.get("jev_panel_weight");
            assertEquals(expectedWeight, weight, 0.001, "weight must equal alpha*structural + gamma*jev");
            double jev = (double) entry.get("jev_keep_probability");
            assertEquals(0.50, jev, 0.001);
            double structural = (double) entry.get("structural_score");
            assertEquals(0.50, structural, 0.001);
        }
    }

    @Test
    @DisplayName("Panels with DECIDED get higher weight than panels with BLOCKER (Jev signal survives alpha weighting)")
    void decidedVsBlockerSeparation() {
        Map<MountedContextPanelName, List<ContextObject>> map = new LinkedHashMap<>();
        map.put(MountedContextPanelName.ACTIVE,    List.of(obj("DECIDED: a")));
        map.put(MountedContextPanelName.PINNED,     List.of(obj("BLOCKER: b")));
        map.put(MountedContextPanelName.ANCESTOR, List.of(obj("neutral: c")));

        MountedContextView view = viewWithPanels(map);
        List<Map<String, Object>> annotated = annotatePanels(view, 0.5, 0.5);

        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map<String, Object> e : annotated) {
            weights.put((String) e.get("panel_name"), (double) e.get("jev_panel_weight"));
        }

        // DECIDED (0.95) > neutral (0.50) > BLOCKER (0.10), all weighted by alpha=0.5 + gamma=0.5
        assertTrue(weights.get("ACTIVE") > weights.get("ANCESTOR"),
            "DECIDED panel must rank above neutral");
        assertTrue(weights.get("ANCESTOR") > weights.get("PINNED"),
            "neutral panel must rank above BLOCKER");
        assertEquals(0.5 * 0.50 + 0.5 * 0.95, weights.get("ACTIVE"), 0.001);
        assertEquals(0.5 * 0.50 + 0.5 * 0.10, weights.get("PINNED"), 0.001);
    }

    @Test
    @DisplayName("MountedContextView normalizes empty list to all 7 default panels -> 7 annotated entries")
    void emptyPanelsHandledGracefully() {
        // MountedContextView constructor auto-normalizes empty panels list to 7 defaults
        MountedContextView view = new MountedContextView(null, "task-A", List.of(), List.of());
        assertEquals(7, view.panels().size(), "empty list must yield 7 default panels");

        List<Map<String, Object>> annotated = annotatePanels(view, 0.5, 0.5);
        assertEquals(7, annotated.size(), "annotated list must have 7 entries for default panels");
        // Each entry must have a panel_name (no null pointer exception)
        for (Map<String, Object> entry : annotated) {
            assertNotNull(entry.get("panel_name"), "panel_name must not be null");
        }
    }
}