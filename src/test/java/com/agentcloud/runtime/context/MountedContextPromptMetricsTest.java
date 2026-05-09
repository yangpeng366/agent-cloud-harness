package com.agentcloud.runtime.context;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedContextPromptMetricsTest {

    @Test
    void metricsIgnoreNullObjectsAndTrackOnlyRenderableMountedEntries() {
        MountedContextView view = new MountedContextView(
            null,
            "task_metrics_nulls",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    Arrays.asList(
                        null,
                        object("goal_1", "Goal 1", "第一条目标", ContextRetentionState.PINNED)
                    )
                ),
                new MountedContextPanel(
                    MountedContextPanelName.ACTIVE,
                    "Active",
                    Arrays.asList(null, null)
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved", "  ")
        );
        TaskRuntimeContext context = runtimeContext(view, "mounted_context_shadow");
        MountedContextPromptRenderResult renderResult = new MountedContextPromptRenderer().renderResult(context);

        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(
            context,
            PromptRenderingMode.MOUNTED_CONTEXT_SHADOW,
            renderResult
        );

        assertTrue(renderResult.hasPrompt());
        assertTrue(renderResult.prompt().contains("Pinned (1)"));
        assertFalse(renderResult.prompt().contains("Active ("));
        assertEquals(1, metrics.nonEmptyPanelCount());
        assertEquals(1, metrics.pinnedCount());
        assertEquals(0, metrics.activeCount());
        assertEquals(1, metrics.renderedPanelCount());
        assertEquals(1, metrics.renderedObjectCount());
        assertTrue(metrics.mountedRenderUsed());
        assertFalse(metrics.budgetTruncated());
    }

    private TaskRuntimeContext runtimeContext(MountedContextView mountedContextView, String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Verify mounted prompt metrics alignment.");
        metadata.put("prompt_rendering_mode", promptRenderingMode);
        Task task = new Task(
            "task_metrics_nulls",
            "session_metrics_nulls",
            null,
            "mounted metrics alignment",
            "active",
            "high",
            Instant.parse("2026-05-09T07:10:00Z"),
            Instant.parse("2026-05-09T07:10:00Z"),
            null,
            null,
            null,
            "summary",
            "让 metrics 和 renderer 统计口径一致",
            "补齐 mounted telemetry 回归",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted metrics alignment",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: mounted metrics alignment",
            12
        );
        return new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            activeContext,
            mountedContextView
        );
    }

    private ContextObject object(String id,
                                 String title,
                                 String summary,
                                 ContextRetentionState retentionState) {
        return new ContextObject(
            id,
            "/sessions/session_metrics_nulls/tasks/task_metrics_nulls/" + id,
            ContextObjectType.TASK,
            "",
            title,
            summary,
            "",
            Instant.parse("2026-05-09T07:11:00Z"),
            retentionState,
            List.of(),
            List.of(),
            Map.of()
        );
    }
}
