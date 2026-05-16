package com.agentcloud.worker;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContextBuilder;
import com.agentcloud.runtime.PromptFieldDeduper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPromptHeaderBuilderTest {

    @Test
    void taskHeaderOmitsDuplicateGoalAndIntent() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent", "D:/gitAll/Articleeditor 项目中 前端页面 点进去 修改轨迹 页面后");
        Task task = new Task(
            "task_1",
            "session_1",
            null,
            "D:/gitAll/Articleeditor 项目中 前端页面 点进去 修改轨迹 页面后",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            "D:/gitAll/Articleeditor 项目中 前端页面 点进去 修改轨迹 页面后",
            null,
            "codex",
            null,
            null,
            metadata
        );

        StringBuilder sb = new StringBuilder();
        WorkerPromptHeaderBuilder.appendTaskHeader(sb, task, false);

        String prompt = sb.toString();
        assertTrue(prompt.contains("Task Title: D:/gitAll/Articleeditor 项目中 前端页面 点进去 修改轨迹 页面后"));
        assertFalse(prompt.contains("Goal:"));
        assertFalse(prompt.contains("Intent:"));
    }

    @Test
    void activeContextTaskFocusPrefersNextStepOnlyWhenDistinct() {
        Map<String, Object> metadata = Map.of("intent", "继续收 prompt 去重");
        Task task = new Task(
            "task_2",
            "session_2",
            null,
            "继续收 prompt 去重",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            "继续收 prompt 去重",
            "补 runtime 公共 helper 并跑回归",
            "codex",
            null,
            null,
            metadata
        );

        ActiveContextBuilder builder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );

        String synthesized = builder.build(task, null, null, List.of(), List.of(), List.of(), List.of()).synthesizedContext();
        assertTrue(synthesized.contains("Task Focus: 补 runtime 公共 helper 并跑回归"));
        assertFalse(synthesized.contains("Task Focus: 继续收 prompt 去重"));
    }

    @Test
    void promptFieldDeduperTreatsWhitespaceOnlyDifferencesAsDuplicate() {
        assertTrue(PromptFieldDeduper.isPromptFieldDuplicate("foo   bar", " foo bar "));
    }
}
