package com.agentcloud.worker;

import com.agentcloud.llm.LlmClient;
import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWorkerExecutorMountedContextPromptTest {

    @Test
    void executeOneRoundDefaultsToActiveContextOnly() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContext(null), "codex");

        assertFalse(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Active Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Runtime Facts:"));
        assertTrue(llmClient.lastUserPrompt.contains("Runtime Cognition Surface:"));
        assertTrue(llmClient.lastUserPrompt.contains("Route Surface:"));
        assertTrue(llmClient.lastUserPrompt.contains("- selected_worker: codex"));
        assertTrue(llmClient.lastUserPrompt.contains("- prompt_mode: active_context_only"));
        assertEquals("active_context_only", result.metadata().get("prompt_rendering_mode"));
        assertEquals("active_context_only", result.metadata().get("mounted_context_mode"));
        assertEquals("active_context_only", result.metadata().get("prompt_mode"));
        assertEquals(false, result.metadata().get("mounted_context_injected"));
        assertEquals(false, result.metadata().get("mounted_render_used"));
        assertEquals(1, result.metadata().get("mounted_pinned_count"));
        assertEquals(1, llmClient.chatCalls);
    }

    @Test
    void executeOneRoundAnnotatesSessionScopeRecentMessages() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextWithSessionScopeMessage(), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Recent Messages:"));
        assertTrue(llmClient.lastUserPrompt.contains("user [user_note] {session}: task 结束后，用户在同一 session 补充了新的方向"));
        assertEquals(1, llmClient.chatCalls);
        assertEquals("active_context_only", result.metadata().get("prompt_mode"));
    }

    @Test
    void executeOneRoundShadowModeKeepsMountedContextOutOfPrompt() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContext("mounted_context_shadow"), "codex");

        assertFalse(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Active Context:"));
        assertEquals("mounted_context_shadow", result.metadata().get("prompt_rendering_mode"));
        assertEquals("mounted_context_shadow", result.metadata().get("mounted_context_mode"));
        assertEquals("mounted_context_shadow", result.metadata().get("prompt_mode"));
        assertEquals(true, result.metadata().get("mounted_context_rendered"));
        assertEquals(false, result.metadata().get("mounted_context_injected"));
        assertEquals(true, result.metadata().get("mounted_render_used"));
        assertEquals(1, result.metadata().get("mounted_non_empty_panel_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_panel_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_object_count"));
        assertEquals(0, result.metadata().get("mounted_context_hidden_object_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_selection_trace_count"));
        assertEquals(false, result.metadata().get("mounted_context_budget_truncated"));
    }

    @Test
    void executeOneRoundPrimaryModeInjectsMountedContextIntoPrompt() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContext("mounted_context_primary"), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Pinned (1)"));
        assertTrue(llmClient.lastUserPrompt.contains("constraint/pinned/Constraints"));
        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context Selection Trace:"));
        assertTrue(llmClient.lastUserPrompt.contains("compat_mode=task_runtime_context_preserved"));
        assertTrue(llmClient.lastUserPrompt.contains("Active Context:"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_rendering_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("mounted_context_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
        assertEquals(true, result.metadata().get("mounted_context_injected"));
        assertEquals(true, result.metadata().get("mounted_render_used"));
        assertEquals(1, result.metadata().get("mounted_pinned_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_panel_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_object_count"));
        assertEquals(0, result.metadata().get("mounted_context_hidden_object_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_selection_trace_count"));
        assertEquals(0, result.metadata().get("mounted_context_hidden_selection_trace_count"));
        assertEquals(false, result.metadata().get("mounted_context_budget_truncated"));
    }

    @Test
    void executeOneRoundResolvesPrimaryModeFromPromptModeAlias() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextFromPromptModeAlias("mounted_context_primary"), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context Selection Trace:"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_rendering_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("mounted_context_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
        assertEquals(true, result.metadata().get("mounted_context_injected"));
        assertEquals(true, result.metadata().get("mounted_render_used"));
    }

    @Test
    void executeOneRoundResolvesPrimaryModeFromLatestPacketAlias() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextFromLatestPacketPromptModeAlias("mounted_context_primary"), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context Selection Trace:"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_rendering_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("mounted_context_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
        assertEquals(true, result.metadata().get("mounted_context_injected"));
        assertEquals(true, result.metadata().get("mounted_render_used"));
    }

    @Test
    void executeOneRoundPrimaryModeTracksHiddenMountedPanelsWhenRendererBoundsPrompt() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextWithDenseMountedPanels("mounted_context_primary"), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("... +2 more panels"));
        assertFalse(llmClient.lastUserPrompt.contains("Evidence (1)"));
        assertEquals(4, result.metadata().get("mounted_context_rendered_panel_count"));
        assertEquals(2, result.metadata().get("mounted_context_hidden_panel_count"));
        assertEquals(4, result.metadata().get("mounted_context_rendered_object_count"));
        assertEquals(2, result.metadata().get("mounted_context_hidden_object_count"));
        assertEquals(true, result.metadata().get("mounted_context_budget_truncated"));
    }

    @Test
    void executeOneRoundIgnoresNullMountedObjectsInTelemetryCounts() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextWithNullMountedObjects("mounted_context_primary"), "codex");

        assertTrue(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Pinned (1)"));
        assertFalse(llmClient.lastUserPrompt.contains("Active ("));
        assertEquals(1, result.metadata().get("mounted_pinned_count"));
        assertEquals(0, result.metadata().get("mounted_active_count"));
        assertEquals(1, result.metadata().get("mounted_non_empty_panel_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_panel_count"));
        assertEquals(1, result.metadata().get("mounted_context_rendered_object_count"));
        assertEquals(0, result.metadata().get("mounted_context_hidden_object_count"));
    }

    @Test
    void executeOneRoundPrimaryModeHandlesEmptyMountedViewSafely() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextWithEmptyMountedView("mounted_context_primary"), "codex");

        assertFalse(llmClient.lastUserPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.lastUserPrompt.contains("Active Context:"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_rendering_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("mounted_context_mode"));
        assertEquals("mounted_context_primary", result.metadata().get("prompt_mode"));
        assertEquals(true, result.metadata().get("mounted_context_rendered"));
        assertEquals(false, result.metadata().get("mounted_context_injected"));
        assertEquals(false, result.metadata().get("mounted_render_used"));
        assertEquals(0, result.metadata().get("mounted_non_empty_panel_count"));
        assertEquals(0, result.metadata().get("mounted_context_selection_trace_count"));
        assertEquals(0, result.metadata().get("mounted_context_rendered_panel_count"));
        assertEquals(0, result.metadata().get("mounted_context_rendered_object_count"));
    }

    @Test
    void executeOneRoundPassesResolvedImageInputsToLlm() {
        RecordingLlmClient llmClient = new RecordingLlmClient(responseJson());
        DefaultWorkerExecutor executor = new DefaultWorkerExecutor(llmClient);

        WorkerExecutionResult result = executor.executeOneRound(runtimeContextWithImageInputs(), "codex");

        assertEquals(2, llmClient.lastImageInputs.size());
        assertEquals("D:\\gitAll\\open\\20260506-141826.png", llmClient.lastImageInputs.get(0).path());
        assertEquals("image/png", llmClient.lastImageInputs.get(0).mediaType());
        assertEquals("D:\\gitAll\\open\\20260506-141916.jpg", llmClient.lastImageInputs.get(1).path());
        assertEquals(true, result.metadata().get("image_input_used"));
        assertEquals(2, result.metadata().get("image_input_count"));
    }

    private TaskRuntimeContext runtimeContext(String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Use the mounted context surface.");
        if (promptRenderingMode != null) {
            metadata.put("prompt_rendering_mode", promptRenderingMode);
        }
        Task task = new Task(
            "task_1",
            "session_1",
            null,
            "mounted context prompt",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "验证 mounted context 进入 prompt",
            "继续推进",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context prompt",
            List.of("priority=high"),
            List.of("[runtime_context_built] mounted context 已生成"),
            List.of("继续推进"),
            List.of("artifact summary"),
            List.of("是否切换 judgment?"),
            List.of("补 prompt 测试"),
            List.of("不要破坏兼容性"),
            List.of("保留关键约束"),
            List.of("budget=12"),
            "summary",
            "Task Focus: mounted context prompt",
            12
        );
        MountedContextView mountedView = new MountedContextView(
            null,
            task.id(),
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "constraints",
                        "/sessions/session_1/tasks/task_1",
                        ContextObjectType.CONSTRAINT,
                        "",
                        "Constraints",
                        "保持兼容，不要破坏旧执行链",
                        "",
                        Instant.parse("2026-05-06T06:31:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
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
            mountedView
        );
    }

    private TaskRuntimeContext runtimeContextWithDenseMountedPanels(String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Use the mounted context surface.");
        if (promptRenderingMode != null) {
            metadata.put("prompt_rendering_mode", promptRenderingMode);
        }
        Task task = new Task(
            "task_dense_panels",
            "session_1",
            null,
            "mounted context prompt dense",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "验证 mounted context panel budget",
            "继续推进",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context prompt",
            List.of("priority=high"),
            List.of("[runtime_context_built] mounted context 已生成"),
            List.of("继续推进"),
            List.of("artifact summary"),
            List.of("是否切换 judgment?"),
            List.of("补 prompt 测试"),
            List.of("不要破坏兼容性"),
            List.of("保留关键约束"),
            List.of("budget=12"),
            "summary",
            "Task Focus: mounted context prompt",
            12
        );
        MountedContextView mountedView = new MountedContextView(
            null,
            task.id(),
            List.of(
                panel(MountedContextPanelName.PINNED, "constraints", "Constraints", ContextObjectType.CONSTRAINT, ContextRetentionState.PINNED),
                panel(MountedContextPanelName.ACTIVE, "active", "Active Focus", ContextObjectType.ARTIFACT, ContextRetentionState.HOT_RAW),
                panel(MountedContextPanelName.ANCESTOR, "ancestor", "Ancestor Packet", ContextObjectType.RESUME_PACKET, ContextRetentionState.WARM_SUMMARY),
                panel(MountedContextPanelName.SIBLING, "sibling", "Sibling Note", ContextObjectType.ARTIFACT, ContextRetentionState.WARM_SUMMARY),
                panel(MountedContextPanelName.EVIDENCE, "evidence", "Evidence", ContextObjectType.DECISION, ContextRetentionState.WARM_SUMMARY),
                panel(MountedContextPanelName.INDEX, "index", "Index Handle", ContextObjectType.ARTIFACT, ContextRetentionState.ARCHIVED_HANDLE)
            ),
            List.of("compat_mode=task_runtime_context_preserved")
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
            mountedView
        );
    }

    private TaskRuntimeContext runtimeContextWithNullMountedObjects(String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Keep mounted telemetry aligned with rendered prompt.");
        if (promptRenderingMode != null) {
            metadata.put("prompt_rendering_mode", promptRenderingMode);
        }
        Task task = new Task(
            "task_null_mounted",
            "session_1",
            null,
            "mounted context null telemetry",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "验证 null mounted object 不应污染 telemetry",
            "继续推进",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context null telemetry",
            List.of("priority=high"),
            List.of("[runtime_context_built] mounted context 已生成"),
            List.of("继续推进"),
            List.of("artifact summary"),
            List.of("是否切换 judgment?"),
            List.of("补 telemetry 测试"),
            List.of("不要破坏兼容性"),
            List.of("保留关键约束"),
            List.of("budget=12"),
            "summary",
            "Task Focus: mounted context null telemetry",
            12
        );
        MountedContextView mountedView = new MountedContextView(
            null,
            task.id(),
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    Arrays.asList(
                        null,
                        new ContextObject(
                            "constraints",
                            "/sessions/session_1/tasks/task_null_mounted",
                            ContextObjectType.CONSTRAINT,
                            "",
                            "Constraints",
                            "保持兼容，不要破坏旧执行链",
                            "",
                            Instant.parse("2026-05-06T06:31:00Z"),
                            ContextRetentionState.PINNED,
                            List.of(),
                            List.of(),
                            Map.of()
                        )
                    )
                ),
                new MountedContextPanel(
                    MountedContextPanelName.ACTIVE,
                    "Active",
                    Arrays.asList(null, null)
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
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
            mountedView
        );
    }

    private MountedContextPanel panel(MountedContextPanelName name,
                                      String id,
                                      String title,
                                      ContextObjectType type,
                                      ContextRetentionState retentionState) {
        return new MountedContextPanel(
            name,
            name.title(),
            List.of(new ContextObject(
                id,
                "/sessions/session_1/tasks/task_dense_panels/" + id,
                type,
                "",
                title,
                "保持兼容，不要破坏旧执行链",
                "",
                Instant.parse("2026-05-06T06:31:00Z"),
                retentionState,
                List.of(),
                List.of(),
                Map.of()
            ))
        );
    }

    private TaskRuntimeContext runtimeContextWithImageInputs() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Turn the image mockup into code.");
        metadata.put("image_inputs", List.of(
            Map.of("path", "D:\\gitAll\\open\\20260506-141826.png", "media_type", "image/png"),
            "D:\\gitAll\\open\\20260506-141916.jpg"
        ));
        Task task = new Task(
            "task_vision_1",
            "session_1",
            null,
            "vision prompt",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "根据图片生成前后端代码",
            "先理解设计图",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Vision task",
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
            "Task Focus: vision prompt",
            12
        );
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), List.of(), activeContext, MountedContextView.empty(task.id()));
    }

    private TaskRuntimeContext runtimeContextWithSessionScopeMessage() {
        TaskRuntimeContext base = runtimeContext(null);
        SessionMessage sessionMessage = new SessionMessage(
            "msg_session_scope",
            base.task().sessionId(),
            null,
            "user",
            "user_note",
            "task 结束后，用户在同一 session 补充了新的方向，不要从零开始。",
            Instant.parse("2026-05-06T06:40:00Z"),
            Map.of("source_surface", "dialogue", "continuity_scope", "session")
        );
        return new TaskRuntimeContext(
            base.task(),
            base.latestPacket(),
            base.latestCheckpoint(),
            base.recentEvents(),
            base.recentDecisions(),
            base.recentArtifacts(),
            base.recentToolInvocations(),
            List.of(sessionMessage),
            base.activeContext(),
            base.mountedContextView()
        );
    }

    private TaskRuntimeContext runtimeContextFromPromptModeAlias(String promptMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Use the mounted context surface.");
        if (promptMode != null) {
            metadata.put("prompt_mode", promptMode);
        }
        Task task = new Task(
            "task_alias_1",
            "session_1",
            null,
            "mounted context alias prompt",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "验证 prompt_mode alias 兼容 mounted context",
            "继续推进",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context alias prompt",
            List.of("priority=high"),
            List.of("[runtime_context_built] mounted context 已生成"),
            List.of("继续推进"),
            List.of("artifact summary"),
            List.of("是否切换 judgment?"),
            List.of("补 alias 测试"),
            List.of("不要破坏兼容性"),
            List.of("保留关键约束"),
            List.of("budget=12"),
            "summary",
            "Task Focus: mounted context alias prompt",
            12
        );
        MountedContextView mountedView = new MountedContextView(
            null,
            task.id(),
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "constraints",
                        "/sessions/session_1/tasks/task_alias_1",
                        ContextObjectType.CONSTRAINT,
                        "",
                        "Constraints",
                        "保持兼容，不要破坏旧执行链",
                        "",
                        Instant.parse("2026-05-06T06:31:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
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
            mountedView
        );
    }

    private TaskRuntimeContext runtimeContextWithEmptyMountedView(String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Keep primary mode continuity-safe even when mounted view is empty.");
        if (promptRenderingMode != null) {
            metadata.put("prompt_rendering_mode", promptRenderingMode);
        }
        Task task = new Task(
            "task_empty_mounted",
            "session_1",
            null,
            "mounted context empty view",
            "active",
            "high",
            Instant.parse("2026-05-06T06:30:00Z"),
            Instant.parse("2026-05-06T06:30:00Z"),
            null,
            null,
            null,
            "summary",
            "验证 mounted view 为空时 default executor 仍安全",
            "继续推进",
            "codex",
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context empty view",
            List.of("priority=high"),
            List.of("[runtime_context_built] mounted context 已生成"),
            List.of("继续推进"),
            List.of("artifact summary"),
            List.of("是否切换 judgment?"),
            List.of("补 empty mounted 测试"),
            List.of("不要破坏兼容性"),
            List.of("保留关键约束"),
            List.of("budget=12"),
            "summary",
            "Task Focus: mounted context empty view",
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
            MountedContextView.empty(task.id())
        );
    }

    private TaskRuntimeContext runtimeContextFromLatestPacketPromptModeAlias(String promptMode) {
        TaskRuntimeContext base = runtimeContext(null);
        ResumePacket latestPacket = new ResumePacket(
            UUID.randomUUID().toString(),
            base.task().sessionId(),
            base.task().id(),
            Instant.parse("2026-05-06T06:32:00Z"),
            "1.1",
            "summary",
            null,
            null,
            List.of(),
            "继续推进",
            Map.of(
                "prompt_mode", promptMode,
                "next_step", "继续推进"
            )
        );
        return new TaskRuntimeContext(
            base.task(),
            latestPacket,
            null,
            base.recentEvents(),
            base.recentDecisions(),
            base.recentArtifacts(),
            base.recentMessages(),
            base.activeContext(),
            base.mountedContextView()
        );
    }

    private String responseJson() {
        return """
            {"summary":"ok","output_text":"done","produced_artifact":false,"artifact_title":"","artifact_content":"","suggested_next_step":"","confidence":"high"}
            """;
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final String response;
        private int chatCalls;
        private String lastUserPrompt = "";
        private List<LlmImageInput> lastImageInputs = List.of();

        private RecordingLlmClient(String response) {
            this.response = response.strip();
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            chatCalls++;
            lastUserPrompt = userPrompt;
            return response;
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
            chatCalls++;
            lastUserPrompt = userPrompt;
            lastImageInputs = imageInputs == null ? List.of() : List.copyOf(imageInputs);
            return response;
        }
    }
}
