package com.agentcloud.runtime.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedContextPromptRendererTest {

    @Test
    void renderIncludesPanelsObjectsAndSelectionTrace() {
        MountedContextView view = new MountedContextView(
            null,
            "task_1",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "goal",
                        "/sessions/s1/tasks/task_1",
                        ContextObjectType.TASK,
                        "",
                        "Task Goal",
                        "推进 mounted context phase 2",
                        "",
                        Instant.parse("2026-05-06T06:00:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                ),
                new MountedContextPanel(
                    MountedContextPanelName.ACTIVE,
                    "Active",
                    List.of(new ContextObject(
                        "packet",
                        "/sessions/s1/tasks/task_1/packet",
                        ContextObjectType.RESUME_PACKET,
                        "",
                        "Latest Packet",
                        "当前优先接通 mounted context 到 prompt",
                        "保持 active context 兼容",
                        Instant.parse("2026-05-06T06:01:00Z"),
                        ContextRetentionState.HOT_RAW,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved", "pinned=1", "active=1")
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("Mounted Context:"));
        assertTrue(prompt.contains("Pinned (1)"));
        assertTrue(prompt.contains("task/pinned/Task Goal"));
        assertTrue(prompt.contains("Active (1)"));
        assertTrue(prompt.contains("resume_packet/hot_raw/Latest Packet"));
        assertTrue(prompt.contains("Mounted Context Selection Trace:"));
        assertTrue(prompt.contains("compat_mode=task_runtime_context_preserved"));
    }

    @Test
    void renderOmitsEmptyPanelsAndReturnsBlankForEmptyView() {
        MountedContextView emptyView = MountedContextView.empty("task_empty");
        String emptyPrompt = new MountedContextPromptRenderer().render(emptyView);
        assertEquals("", emptyPrompt);

        MountedContextView sparseView = new MountedContextView(
            null,
            "task_sparse",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "goal",
                        "/sessions/s1/tasks/task_sparse",
                        ContextObjectType.TASK,
                        "",
                        "Task Goal",
                        "只保留非空 panel",
                        "",
                        Instant.parse("2026-05-06T06:05:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of()
        );

        String sparsePrompt = new MountedContextPromptRenderer().render(sparseView);
        assertTrue(sparsePrompt.contains("Pinned (1)"));
        assertFalse(sparsePrompt.contains("Active (0)"));
        assertFalse(sparsePrompt.contains("Archive Handles (0)"));
        assertFalse(sparsePrompt.contains("Mounted Context Selection Trace:"));
    }

    @Test
    void renderUsesStablePanelOrderAndOmitsEmptyPanelsBetweenVisibleSections() {
        MountedContextView view = new MountedContextView(
            null,
            "task_ordered_sparse",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.EVIDENCE,
                    "Evidence",
                    List.of(object("evidence_1", "Evidence 1", "第五条证据", ContextRetentionState.WARM_SUMMARY))
                ),
                new MountedContextPanel(
                    MountedContextPanelName.SIBLING,
                    "Sibling",
                    List.of(object("sibling_1", "Sibling 1", "第四条线索", ContextRetentionState.WARM_SUMMARY))
                ),
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(object("goal_1", "Goal 1", "第一条约束", ContextRetentionState.PINNED))
                )
            ),
            List.of("selection_trace=present")
        );

        String prompt = new MountedContextPromptRenderer().render(view);
        int pinnedIndex = prompt.indexOf("Pinned (1)");
        int siblingIndex = prompt.indexOf("Sibling (1)");
        int evidenceIndex = prompt.indexOf("Evidence (1)");
        int traceIndex = prompt.indexOf("Mounted Context Selection Trace:");

        assertTrue(pinnedIndex >= 0);
        assertTrue(siblingIndex >= 0);
        assertTrue(evidenceIndex >= 0);
        assertTrue(traceIndex >= 0);
        assertTrue(pinnedIndex < siblingIndex);
        assertTrue(siblingIndex < evidenceIndex);
        assertTrue(evidenceIndex < traceIndex);
        assertFalse(prompt.contains("Active (0)"));
        assertFalse(prompt.contains("Ancestor (0)"));
        assertFalse(prompt.contains("Index (0)"));
        assertFalse(prompt.contains("Archive Handles (0)"));
    }

    @Test
    void renderBoundsDensePanelsAndTruncatesLongPreview() {
        String longSummary = "这是一个很长的 mounted context 摘要，用来验证 renderer 会做 preview 截断，并且不会把整段长文本原样灌进 prompt。"
            .repeat(6);
        MountedContextView denseView = new MountedContextView(
            null,
            "task_dense",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.EVIDENCE,
                    "Evidence",
                    List.of(
                        object("artifact_1", "Artifact 1", longSummary, ContextRetentionState.HOT_RAW),
                        object("artifact_2", "Artifact 2", "第二条证据", ContextRetentionState.WARM_SUMMARY),
                        object("artifact_3", "Artifact 3", "第三条证据", ContextRetentionState.WARM_SUMMARY),
                        object("artifact_4", "Artifact 4", "第四条证据，不应该完整展开", ContextRetentionState.COLD_CAPSULE)
                    )
                )
            ),
            List.of("first", "  ", longSummary)
        );

        String prompt = new MountedContextPromptRenderer().render(denseView);

        assertTrue(prompt.contains("Evidence (4)"));
        assertTrue(prompt.contains("... +1 more"));
        assertTrue(prompt.contains("artifact/hot_raw/Artifact 1"));
        assertTrue(prompt.contains("Mounted Context Selection Trace:"));
        assertTrue(prompt.contains("first"));
        assertFalse(prompt.contains("null"));
        assertFalse(prompt.contains("Artifact 4 ->"));
        assertTrue(prompt.contains("..."));
    }

    @Test
    void renderIncludesBoundedProofEdgesForEvidenceObjects() {
        MountedContextView view = new MountedContextView(
            null,
            "task_evidence_proof",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.EVIDENCE,
                    "Evidence",
                    List.of(new ContextObject(
                        "decision_1",
                        "/sessions/s1/tasks/task_evidence_proof/decisions/decision_1",
                        ContextObjectType.DECISION,
                        "",
                        "Execution Judgment",
                        "基于最新工具证据继续执行",
                        "",
                        Instant.parse("2026-05-06T06:12:00Z"),
                        ContextRetentionState.WARM_SUMMARY,
                        List.of(new ContextReference("tool_invocation", "/tools/tool_42", "tool_42")),
                        List.of(),
                        Map.of(
                            "tool_invocation_ids", List.of("tool_42", "tool_99"),
                            "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt")
                        )
                    ))
                )
            ),
            List.of()
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("decision/warm_summary/Execution Judgment"));
        assertTrue(prompt.contains("[proof=tool:tool_42, tool:tool_99]"));
        assertFalse(prompt.contains("evidence:tool:read_file:input.txt"));
        assertFalse(prompt.contains("ref:tool_42"));
    }

    @Test
    void renderTruncatesLongProofLabelsAndDoesNotLeakRawMetadataMaps() {
        String longToolId = "tool_invocation_" + "x".repeat(90) + "_tail_should_be_truncated";
        String rawMetadataLeak = "raw_metadata_should_not_leak_into_prompt";
        MountedContextView view = new MountedContextView(
            null,
            "task_proof_budget",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.EVIDENCE,
                    "Evidence",
                    List.of(new ContextObject(
                        "artifact_1",
                        "/sessions/s1/tasks/task_proof_budget/artifacts/artifact_1",
                        ContextObjectType.ARTIFACT,
                        "",
                        "Artifact 1",
                        "关注 proof edge 截断，不要把原始 metadata map 打进 prompt。",
                        "",
                        Instant.parse("2026-05-06T06:18:00Z"),
                        ContextRetentionState.WARM_SUMMARY,
                        List.of(new ContextReference("artifact_ref", "/tmp/" + "y".repeat(96) + "_tail_ref", "")),
                        List.of(),
                        Map.of(
                            "tool_invocation_ids", List.of(longToolId),
                            "opaque_debug_map", Map.of("secret", rawMetadataLeak)
                        )
                    ))
                )
            ),
            List.of()
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("[proof="));
        assertTrue(prompt.contains("tool:tool_invocation_"));
        assertTrue(prompt.contains("..."));
        assertFalse(prompt.contains(longToolId));
        assertFalse(prompt.contains("tail_should_be_truncated"));
        assertFalse(prompt.contains(rawMetadataLeak));
        assertFalse(prompt.contains("opaque_debug_map"));
    }

    @Test
    void renderResultExposesBudgetDiagnostics() {
        MountedContextView denseView = new MountedContextView(
            null,
            "task_dense_budget",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.EVIDENCE,
                    "Evidence",
                    List.of(
                        object("artifact_1", "Artifact 1", "第一条", ContextRetentionState.HOT_RAW),
                        object("artifact_2", "Artifact 2", "第二条", ContextRetentionState.WARM_SUMMARY),
                        object("artifact_3", "Artifact 3", "第三条", ContextRetentionState.WARM_SUMMARY),
                        object("artifact_4", "Artifact 4", "第四条", ContextRetentionState.COLD_CAPSULE)
                    )
                )
            ),
            List.of("first", "second", "third", "fourth", "fifth")
        );

        MountedContextPromptRenderResult result = new MountedContextPromptRenderer().renderResult(denseView);

        assertTrue(result.hasPrompt());
        assertEquals(1, result.renderedPanelCount());
        assertEquals(3, result.renderedObjectCount());
        assertEquals(1, result.hiddenObjectCount());
        assertEquals(4, result.renderedSelectionTraceCount());
        assertEquals(1, result.hiddenSelectionTraceCount());
        assertTrue(result.budgetTruncated());
    }

    @Test
    void renderAppliesPerPanelCapsAndHandleOnlySectionsStayCompact() {
        String longSummary = "archive handle summary should stay out of the rendered line because handle panels must remain compact. "
            .repeat(5);
        MountedContextView view = new MountedContextView(
            null,
            "task_budgeted",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.ACTIVE,
                    "Active",
                    List.of(
                        object("active_1", "Active 1", "第一条 active", ContextRetentionState.HOT_RAW),
                        object("active_2", "Active 2", "第二条 active", ContextRetentionState.HOT_RAW),
                        object("active_3", "Active 3", "第三条 active", ContextRetentionState.HOT_RAW),
                        object("active_4", "Active 4", "第四条 active", ContextRetentionState.HOT_RAW),
                        object("active_5", "Active 5", "第五条 active", ContextRetentionState.HOT_RAW),
                        object("active_6", "Active 6", "第六条 active，不应完整展开", ContextRetentionState.HOT_RAW)
                    )
                ),
                new MountedContextPanel(
                    MountedContextPanelName.ARCHIVE_HANDLES,
                    "Archive Handles",
                    List.of(
                        object("archive_1", "Archive Handle 1", longSummary, ContextRetentionState.ARCHIVED_HANDLE),
                        object("archive_2", "Archive Handle 2", longSummary, ContextRetentionState.ARCHIVED_HANDLE),
                        object("archive_3", "Archive Handle 3", longSummary, ContextRetentionState.ARCHIVED_HANDLE)
                    )
                )
            ),
            List.of()
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("Active (6)"));
        assertTrue(prompt.contains("... +1 more"));
        assertTrue(prompt.contains("Active 5"));
        assertFalse(prompt.contains("Active 6 ->"));
        assertTrue(prompt.contains("Archive Handles (3)"));
        assertTrue(prompt.contains("artifact/archived_handle/Archive Handle 1"));
        assertFalse(prompt.contains("Archive Handle 1 ->"));
        assertFalse(prompt.contains(longSummary.substring(0, 40)));
        assertTrue(prompt.contains("... +1 more"));
    }

    @Test
    void renderBoundsSelectionTraceEntries() {
        List<String> selectionTrace = new ArrayList<>();
        selectionTrace.add("compat_mode=task_runtime_context_preserved");
        selectionTrace.add("pinned=1");
        selectionTrace.add("active=2");
        selectionTrace.add("evidence=3");
        selectionTrace.add("archive_handles=4");
        selectionTrace.add("very_long_trace=" + "x".repeat(260));

        MountedContextView view = new MountedContextView(
            null,
            "task_trace_budget",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(object("goal", "Goal", "trace budget", ContextRetentionState.PINNED))
                )
            ),
            selectionTrace
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("Mounted Context Selection Trace:"));
        assertTrue(prompt.contains("compat_mode=task_runtime_context_preserved"));
        assertTrue(prompt.contains("evidence=3"));
        assertFalse(prompt.contains("archive_handles=4"));
        assertFalse(prompt.contains("very_long_trace="));
        assertTrue(prompt.contains("... +2 more"));
    }

    @Test
    void renderSkipsNullObjectsWithoutDroppingLaterValidEntries() {
        MountedContextView view = new MountedContextView(
            null,
            "task_null_objects",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    Arrays.asList(
                        null,
                        object("goal_1", "Goal 1", "第一条约束", ContextRetentionState.PINNED),
                        null,
                        object("goal_2", "Goal 2", "第二条约束", ContextRetentionState.PINNED)
                    )
                )
            ),
            List.of()
        );

        String prompt = new MountedContextPromptRenderer().render(view);

        assertTrue(prompt.contains("Pinned (2)"));
        assertTrue(prompt.contains("artifact/pinned/Goal 1"));
        assertTrue(prompt.contains("artifact/pinned/Goal 2"));
        assertFalse(prompt.contains("... +"));
    }

    @Test
    void renderResultBoundsVisiblePanelsAndTracksHiddenPanels() {
        MountedContextView view = new MountedContextView(
            null,
            "task_panel_budget",
            List.of(
                new MountedContextPanel(MountedContextPanelName.PINNED, "Pinned",
                    List.of(object("goal_1", "Goal 1", "第一条", ContextRetentionState.PINNED))),
                new MountedContextPanel(MountedContextPanelName.ACTIVE, "Active",
                    List.of(object("active_1", "Active 1", "第二条", ContextRetentionState.HOT_RAW))),
                new MountedContextPanel(MountedContextPanelName.ANCESTOR, "Ancestor",
                    List.of(object("ancestor_1", "Ancestor 1", "第三条", ContextRetentionState.WARM_SUMMARY))),
                new MountedContextPanel(MountedContextPanelName.SIBLING, "Sibling",
                    List.of(object("sibling_1", "Sibling 1", "第四条", ContextRetentionState.WARM_SUMMARY))),
                new MountedContextPanel(MountedContextPanelName.EVIDENCE, "Evidence",
                    List.of(object("evidence_1", "Evidence 1", "第五条", ContextRetentionState.WARM_SUMMARY))),
                new MountedContextPanel(MountedContextPanelName.INDEX, "Index",
                    List.of(object("index_1", "Index 1", "第六条", ContextRetentionState.ARCHIVED_HANDLE)))
            ),
            List.of("pinned=1", "active=1")
        );

        MountedContextPromptRenderResult result = new MountedContextPromptRenderer().renderResult(view);

        assertTrue(result.prompt().contains("Pinned (1)"));
        assertTrue(result.prompt().contains("Sibling (1)"));
        assertFalse(result.prompt().contains("Evidence (1)"));
        assertFalse(result.prompt().contains("Index (1)"));
        assertTrue(result.prompt().contains("... +2 more panels"));
        assertEquals(4, result.renderedPanelCount());
        assertEquals(2, result.hiddenPanelCount());
        assertEquals(4, result.renderedObjectCount());
        assertEquals(2, result.hiddenObjectCount());
        assertTrue(result.budgetTruncated());
    }

    private ContextObject object(String id,
                                 String title,
                                 String summary,
                                 ContextRetentionState retentionState) {
        return new ContextObject(
            id,
            "/sessions/s1/tasks/task_dense/" + id,
            ContextObjectType.ARTIFACT,
            "",
            title,
            summary,
            "",
            Instant.parse("2026-05-06T06:10:00Z"),
            retentionState,
            List.of(),
            List.of(),
            Map.of()
        );
    }
}
