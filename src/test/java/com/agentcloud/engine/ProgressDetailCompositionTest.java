package com.agentcloud.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * E1.2 长任务收口合同：验证 additive progress_detail 字段的语义构造（引用 blocked subgoal 标题）。
 * 通过反射调用 ControlNodeGraph.buildProgressDetail，覆盖 all-done / blocked-with-title /
 * in-progress-open / no-subgoals / mixed / 长标题截断。
 */
class ProgressDetailCompositionTest {

    @Test
    void allSubgoalsDoneDetailHasNoBlockedTitles() throws Exception {
        assertEquals("2/2 done",
            buildProgressDetail(List.of(
                Map.of("title", "auth", "status", "done"),
                Map.of("title", "db", "status", "completed"))));
    }

    @Test
    void blockedSubgoalDetailNamesBlockerTitle() throws Exception {
        assertEquals("1/2 done, 1 blocked; blocked: API integration",
            buildProgressDetail(List.of(
                Map.of("title", "auth", "status", "done"),
                Map.of("title", "API integration", "status", "blocked"))));
    }

    @Test
    void inProgressSubgoalsCountAsOpen() throws Exception {
        assertEquals("0/2 done, 2 open",
            buildProgressDetail(List.of(
                Map.of("title", "auth", "status", "in_progress"),
                Map.of("title", "deploy", "status", "pending"))));
    }

    @Test
    void noSubgoalsReturnsNull() throws Exception {
        assertNull(buildProgressDetail(List.of()));
        assertNull(buildProgressDetail(null));
    }

    @Test
    void mixedSubgoalsBreaksDownDoneBlockedOpenAndNamesBlocker() throws Exception {
        assertEquals("1/3 done, 1 blocked, 1 open; blocked: API",
            buildProgressDetail(List.of(
                Map.of("title", "auth", "status", "done"),
                Map.of("title", "API", "status", "blocked"),
                Map.of("title", "deploy", "status", "in_progress"))));
    }

    @Test
    void longBlockedTitleIsTruncated() throws Exception {
        String longTitle = "A".repeat(60);
        String detail = buildProgressDetail(List.of(
            Map.of("title", longTitle, "status", "blocked")));
        assertEquals("0/1 done, 1 blocked; blocked: " + "A".repeat(47) + "...", detail);
    }

    private String buildProgressDetail(Object rawSubgoalStatus) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("buildProgressDetail", Object.class);
        method.setAccessible(true);
        return (String) method.invoke(graph, rawSubgoalStatus);
    }
}
