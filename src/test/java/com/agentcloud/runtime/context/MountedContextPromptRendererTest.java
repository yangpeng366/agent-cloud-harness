package com.agentcloud.runtime.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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
}
