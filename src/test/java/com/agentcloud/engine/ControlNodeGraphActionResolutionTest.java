package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextReference;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.worker.WorkerExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ControlNodeGraphActionResolutionTest {

    @Test
    void checkpointPlusDoneResolvesToCheckpointThenDone() throws Exception {
        assertEquals("checkpoint_then_done", invokeResolveAction("checkpoint", "done", "high", false, false, false));
    }

    @Test
    void plainCheckpointStillResolvesToCheckpoint() throws Exception {
        assertEquals("checkpoint", invokeResolveAction("checkpoint", "partially_done", "high", false, false, false));
    }

    @Test
    void continuePlusDoneResolvesToDone() throws Exception {
        assertEquals("done", invokeResolveAction("continue", "done", "high", false, false, false));
    }

    @Test
    void doneExecutionWithUnfinishedCompletionResolvesToCheckpoint() throws Exception {
        assertEquals("checkpoint", invokeResolveAction("done", "partially_done", "medium", false, false, false));
    }

    @Test
    void doneExecutionWithLowAlignmentCompletionResolvesToCheckpoint() throws Exception {
        assertEquals("checkpoint", invokeResolveAction("done", "done", "low", false, false, false));
    }

    @Test
    void continuePlusContextReopenResolvesToCheckpoint() throws Exception {
        assertEquals("reopen", invokeResolveAction("continue", "partially_done", "medium", true, false, false));
    }

    @Test
    void continuePlusArchiveRetrievalResolvesToCheckpoint() throws Exception {
        assertEquals("archive_retrieval", invokeResolveAction("continue", "partially_done", "medium", false, true, false));
    }

    @Test
    void continuePlusExternalFactRefreshResolvesToCheckpoint() throws Exception {
        assertEquals("external_fact_refresh", invokeResolveAction("continue", "partially_done", "medium", false, false, true));
    }

    @Test
    void finalizeCompletedTaskClearsNextStepAndSetsCompletedAt() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("finalizeCompletedTask", Task.class);
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withControlNode("scheduler")
            .withNextStep("stale-next-step");

        Task finalized = (Task) method.invoke(graph, task);

        assertEquals("done", finalized.status());
        assertEquals("end", finalized.controlNode());
        assertNull(finalized.nextStep());
        assertNotNull(finalized.completedAt());
    }

    @Test
    void workerArtifactMetadataProjectsProviderRunFilesAndBoundedOutputText() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        WorkerExecutionResult result = new WorkerExecutionResult(
            "summary",
            "x".repeat(16_384),
            false,
            "",
            "",
            "",
            "medium",
            "failed",
            List.of(),
            List.of(),
            0,
            10L,
            Map.of(
                "provider_output_truncated", true,
                "provider_output_total_bytes", 1_050_000L,
                "provider_output_sqlite_limit_chars", 16_384,
                "provider_stdout_path", "D:\\tmp\\provider-runs\\stdout.log",
                "provider_last_message_path", "D:\\tmp\\provider-runs\\last_message.md"
            )
        );

        Method outputMethod = ControlNodeGraph.class.getDeclaredMethod("artifactOutputText", WorkerExecutionResult.class);
        outputMethod.setAccessible(true);
        String outputText = (String) outputMethod.invoke(graph, result);

        Method metadataMethod = ControlNodeGraph.class.getDeclaredMethod(
            "buildWorkerArtifactMetadata",
            WorkerExecutionResult.class,
            Object[].class
        );
        metadataMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) metadataMethod.invoke(
            graph,
            result,
            new Object[]{"output_text", outputText}
        );

        assertTrue(outputText.length() < 17_000);
        assertTrue(outputText.contains("provider output truncated"));
        assertEquals(true, metadata.get("provider_output_truncated"));
        assertEquals("D:\\tmp\\provider-runs\\stdout.log", metadata.get("provider_stdout_path"));
        assertTrue(metadata.get("latest_worker_metadata") instanceof Map<?, ?>);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectLatestWorkerMetadataKeepsRouteAndFallbackFields() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("selectLatestWorkerMetadata", Map.class);
        method.setAccessible(true);

        Map<String, Object> selected = (Map<String, Object>) method.invoke(graph, Map.ofEntries(
            Map.entry("image_input_count", 2),
            Map.entry("image_input_used", true),
            Map.entry("prompt_mode", "mounted_context_primary"),
            Map.entry("mounted_render_used", true),
            Map.entry("mounted_context_panel_count", 7),
            Map.entry("selected_worker", "kimi-local-doc"),
            Map.entry("selected_model_tier", "small"),
            Map.entry("execution_role", "executor"),
            Map.entry("why_selected", "selected by capability match"),
            Map.entry("preferred_worker_hint", "kimi-local-doc"),
            Map.entry("learning_hint_applied", true),
            Map.entry("fallback_reason", "fallback to any ready worker"),
            Map.entry("route_source", "learning_memory"),
            Map.entry("ignored", "should not leak")
        ));

        assertEquals("kimi-local-doc", selected.get("selected_worker"));
        assertEquals("small", selected.get("selected_model_tier"));
        assertEquals("executor", selected.get("execution_role"));
        assertEquals("selected by capability match", selected.get("why_selected"));
        assertEquals("kimi-local-doc", selected.get("preferred_worker_hint"));
        assertEquals(Boolean.TRUE, selected.get("learning_hint_applied"));
        assertEquals("fallback to any ready worker", selected.get("fallback_reason"));
        assertEquals("learning_memory", selected.get("route_source"));
        assertEquals(2, selected.get("image_input_count"));
        assertEquals(Boolean.TRUE, selected.get("image_input_used"));
        assertEquals("mounted_context_primary", selected.get("prompt_mode"));
        assertEquals(Boolean.TRUE, selected.get("mounted_render_used"));
        assertEquals(7, selected.get("mounted_context_panel_count"));
        assertTrue(!selected.containsKey("ignored"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectLatestWorkerMetadataKeepsProviderErrorDiagnostics() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("selectLatestWorkerMetadata", Map.class);
        method.setAccessible(true);

        Map<String, Object> selected = (Map<String, Object>) method.invoke(graph, Map.ofEntries(
            Map.entry("provider_id", "codex"),
            Map.entry("execution_backend", "provider_app_server"),
            Map.entry("provider_session_id", "thread-codex-001"),
            Map.entry("provider_thread_id", "thread-codex-001"),
            Map.entry("resume_provider_session_id", "thread-codex-001"),
            Map.entry("provider_error", "codex turn completion timed out"),
            Map.entry("provider_turn_status", "timeout"),
            Map.entry("provider_failure_class", "provider_runtime_transient"),
            Map.entry("provider_failure_reason", "turn timed out"),
            Map.entry("provider_retryable", true),
            Map.entry("raw_provider_payload", "should not leak")
        ));

        assertEquals("codex", selected.get("provider_id"));
        assertEquals("provider_app_server", selected.get("execution_backend"));
        assertEquals("thread-codex-001", selected.get("provider_session_id"));
        assertEquals("thread-codex-001", selected.get("provider_thread_id"));
        assertEquals("thread-codex-001", selected.get("resume_provider_session_id"));
        assertEquals("codex turn completion timed out", selected.get("provider_error"));
        assertEquals("timeout", selected.get("provider_turn_status"));
        assertEquals("provider_runtime_transient", selected.get("provider_failure_class"));
        assertEquals("turn timed out", selected.get("provider_failure_reason"));
        assertEquals(Boolean.TRUE, selected.get("provider_retryable"));
        assertTrue(!selected.containsKey("raw_provider_payload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void augmentLatestWorkerMetadataKeepsProviderErrorDiagnostics() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "augmentLatestWorkerMetadata",
            Map.class,
            WorkerExecutionResult.class
        );
        method.setAccessible(true);

        WorkerExecutionResult result = new WorkerExecutionResult(
            "thread not found: 27316",
            "thread not found: 27316",
            false,
            null,
            null,
            null,
            "low",
            "timeout",
            List.of(),
            List.of(),
            0,
            120_000L,
            Map.of(
                "provider_error", "codex turn completion timed out",
                "provider_turn_status", "timeout",
                "provider_failure_class", "provider_runtime_transient",
                "provider_failure_reason", "turn timed out",
                "provider_retryable", true
            )
        );

        Map<String, Object> metadata = (Map<String, Object>) method.invoke(graph, Map.of(), result);

        assertEquals("timeout", metadata.get("execution_status"));
        assertEquals(120_000L, metadata.get("duration_ms"));
        assertEquals("codex turn completion timed out", metadata.get("provider_error"));
        assertEquals("timeout", metadata.get("provider_turn_status"));
        assertEquals("provider_runtime_transient", metadata.get("provider_failure_class"));
        assertEquals("turn timed out", metadata.get("provider_failure_reason"));
        assertEquals(Boolean.TRUE, metadata.get("provider_retryable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildExecutionBoundaryKeepsMountedContextAndPromptFields() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("buildExecutionBoundary", Map.class);
        method.setAccessible(true);

        Object raw = method.invoke(graph, Map.ofEntries(
            Map.entry("execution_id", "exec-1"),
            Map.entry("execution_status", "succeeded"),
            Map.entry("selected_worker", "codex"),
            Map.entry("tool_chain_step_count", 2),
            Map.entry("prompt_mode", "mounted_context_primary"),
            Map.entry("mounted_context_rendered", true),
            Map.entry("mounted_render_used", true),
            Map.entry("mounted_context_injected", true),
            Map.entry("mounted_context_panel_count", 7),
            Map.entry("mounted_context_non_empty_panel_count", 3),
            Map.entry("mounted_context_selection_trace_count", 4),
            Map.entry("mounted_context_rendered_object_count", 5),
            Map.entry("mounted_context_hidden_object_count", 2),
            Map.entry("mounted_context_rendered_selection_trace_count", 3),
            Map.entry("mounted_context_hidden_selection_trace_count", 1),
            Map.entry("mounted_context_budget_truncated", true),
            Map.entry("mounted_pinned_count", 1),
            Map.entry("mounted_active_count", 4),
            Map.entry("mounted_evidence_count", 2),
            Map.entry("mounted_archive_count", 1)
        ));

        assertNotNull(raw);
        com.agentcloud.runtime.model.RuntimeFactSet.ExecutionBoundary boundary =
            (com.agentcloud.runtime.model.RuntimeFactSet.ExecutionBoundary) raw;
        assertEquals("mounted_context_primary", boundary.metadata().get("prompt_mode"));
        assertEquals(Boolean.TRUE, boundary.metadata().get("mounted_context_rendered"));
        assertEquals(Boolean.TRUE, boundary.metadata().get("mounted_render_used"));
        assertEquals(Boolean.TRUE, boundary.metadata().get("mounted_context_injected"));
        assertEquals(7, boundary.metadata().get("mounted_context_panel_count"));
        assertEquals(3, boundary.metadata().get("mounted_context_non_empty_panel_count"));
        assertEquals(4, boundary.metadata().get("mounted_context_selection_trace_count"));
        assertEquals(5, boundary.metadata().get("mounted_context_rendered_object_count"));
        assertEquals(2, boundary.metadata().get("mounted_context_hidden_object_count"));
        assertEquals(3, boundary.metadata().get("mounted_context_rendered_selection_trace_count"));
        assertEquals(1, boundary.metadata().get("mounted_context_hidden_selection_trace_count"));
        assertEquals(Boolean.TRUE, boundary.metadata().get("mounted_context_budget_truncated"));
        assertEquals(1, boundary.metadata().get("mounted_pinned_count"));
        assertEquals(4, boundary.metadata().get("mounted_active_count"));
        assertEquals(2, boundary.metadata().get("mounted_evidence_count"));
        assertEquals(1, boundary.metadata().get("mounted_archive_count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildExecutionBoundaryKeepsProviderBackendFields() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("buildExecutionBoundary", Map.class);
        method.setAccessible(true);

        Object raw = method.invoke(graph, Map.ofEntries(
            Map.entry("execution_status", "timeout"),
            Map.entry("selected_worker", "codex"),
            Map.entry("execution_backend", "provider_app_server"),
            Map.entry("provider_id", "codex"),
            Map.entry("provider_session_id", "thread-codex-001"),
            Map.entry("provider_thread_id", "thread-codex-001"),
            Map.entry("resume_provider_session_id", "thread-codex-001"),
            Map.entry("provider_error", "codex turn completion timed out"),
            Map.entry("provider_turn_status", "timeout"),
            Map.entry("provider_failure_class", "provider_runtime_transient"),
            Map.entry("provider_failure_reason", "turn timed out"),
            Map.entry("provider_retryable", true)
        ));

        assertNotNull(raw);
        com.agentcloud.runtime.model.RuntimeFactSet.ExecutionBoundary boundary =
            (com.agentcloud.runtime.model.RuntimeFactSet.ExecutionBoundary) raw;
        assertEquals("provider_app_server", boundary.metadata().get("execution_backend"));
        assertEquals("codex", boundary.metadata().get("provider_id"));
        assertEquals("thread-codex-001", boundary.metadata().get("provider_session_id"));
        assertEquals("thread-codex-001", boundary.metadata().get("provider_thread_id"));
        assertEquals("thread-codex-001", boundary.metadata().get("resume_provider_session_id"));
        assertEquals("codex turn completion timed out", boundary.metadata().get("provider_error"));
        assertEquals("timeout", boundary.metadata().get("provider_turn_status"));
        assertEquals("provider_runtime_transient", boundary.metadata().get("provider_failure_class"));
        assertEquals("turn timed out", boundary.metadata().get("provider_failure_reason"));
        assertEquals(Boolean.TRUE, boundary.metadata().get("provider_retryable"));
    }

    @Test
    void resolveRecoveryFailureTextPrefersProviderErrorOverThreadNoise() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveRecoveryFailureText", Map.class, String.class);
        method.setAccessible(true);

        String summary = (String) method.invoke(graph, Map.of(
            "selected_worker", "codex",
            "provider_error", "codex turn completion timed out",
            "provider_failure_reason", "turn timed out",
            "provider_turn_status", "timeout",
            "failure_summary_readable", "worker codex failed: thread not found (27316)",
            "output_text", "thread not found: 27316"
        ), "thread not found: 27316");

        assertEquals("worker codex failed: codex turn completion timed out", summary);
        assertTrue(!summary.contains("thread not found"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reopenCandidatePathsConsumesCapsuleRefsBeforeHandleFallback() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("reopenCandidatePaths", TaskRuntimeContext.class);
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high");
        MountedContextView mountedContextView = new MountedContextView(
            null,
            task.id(),
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.ARCHIVE_HANDLES,
                    "Archive Handles",
                    List.of(
                        new ContextObject(
                            "task_1:reopen-capsule",
                            "/sessions/session_1/tasks/task_1/archive/reopen_capsule",
                            ContextObjectType.CAPSULE,
                            "/sessions/session_1/tasks/task_1",
                            "Reopen Capsule",
                            "targeted reopen",
                            "targeted reopen",
                            Instant.parse("2026-05-09T09:00:00Z"),
                            ContextRetentionState.COLD_CAPSULE,
                            List.of(
                                new ContextReference("reopen_candidate", "/sessions/session_1/tasks/task_1/checkpoints", "checkpoints"),
                                new ContextReference("reopen_candidate", "/sessions/session_1/tasks/task_1/packets/packet_1", "packet_1")
                            ),
                            List.of(),
                            Map.of(
                                "reopen_candidate_paths", List.of(
                                    "/sessions/session_1/tasks/task_1/checkpoints",
                                    "/sessions/session_1/tasks/task_1/packets/packet_1"
                                ),
                                "target_path", "/sessions/session_1/tasks/task_1/checkpoints"
                            )
                        ),
                        new ContextObject(
                            "task_1:artifact-history",
                            "/sessions/session_1/tasks/task_1/archive/artifact-history",
                            ContextObjectType.HANDLE,
                            "/sessions/session_1/tasks/task_1",
                            "Artifact History",
                            "reload artifact history",
                            "reload artifact history",
                            Instant.parse("2026-05-09T09:00:01Z"),
                            ContextRetentionState.ARCHIVED_HANDLE,
                            List.of(new ContextReference("handle", "/sessions/session_1/tasks/task_1/artifacts", "artifacts")),
                            List.of(),
                            Map.of("target_path", "/sessions/session_1/tasks/task_1/artifacts")
                        )
                    )
                )
            ),
            List.of()
        );
        TaskRuntimeContext context = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new ActiveContext("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 12),
            mountedContextView
        );

        List<String> paths = (List<String>) method.invoke(graph, context);

        assertEquals(List.of(
            "/sessions/session_1/tasks/task_1/checkpoints",
            "/sessions/session_1/tasks/task_1/packets/packet_1",
            "/sessions/session_1/tasks/task_1/artifacts"
        ), paths);
    }

    @Test
    void sameStateTreatsMetadataMutationAsStateChange() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("sameState", Task.class, Task.class);
        method.setAccessible(true);

        Task base = Task.create("task_1", "session_1", "demo", "active", "high")
            .withControlNode("scheduler")
            .withMetadata(Map.of("model_mode", "orchestrated", "orchestration_stage", "plan_pending"));
        Task changed = base.withMetadata(Map.of("model_mode", "orchestrated", "orchestration_stage", "execution_pending"));

        assertEquals(Boolean.FALSE, method.invoke(graph, base, changed));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maybePlanFailureRecoverySchedulesSameWorkerRetryForTransientFailure() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of());
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex"
        ), "thread not found: 15252");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);

        assertEquals("same_worker_retry_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.TRUE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.FALSE, autoHandoff.invoke(directive));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maybePlanFailureRecoveryFallsBackToHumanGateAfterRetryAndHandoffBudget() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of(
                "auto_same_worker_retry_count", 1,
                "auto_handoff_count", 1
            ));
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "provider unavailable",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "provider unavailable");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);

        assertEquals("human_gate_required", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.FALSE, autoHandoff.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoveryTreatsEmptyExecutionStatusAsTransientFailure() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of("auto_same_worker_retry_count", 1));
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "empty",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        recoveryStage.setAccessible(true);
        autoHandoff.setAccessible(true);

        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.TRUE, autoHandoff.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoverySanitizesReadableFailureSummary() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        String noisy = "����: û���ҵ����� \"19120\"��\n"
            + "我会先把和“下一步规划”最相关的文档与路线图过一遍。\n"
            + ".github\n"
            + "docs\\ARCHITECTURE.md\n"
            + "---";
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("claude")
            .withMetadata(Map.of());
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", noisy,
            "selected_worker", "claude"
        ), noisy);

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method failureSummaryReadable = directiveClass.getDeclaredMethod("failureSummaryReadable");
        failureSummaryReadable.setAccessible(true);

        String summary = String.valueOf(failureSummaryReadable.invoke(directive));
        assertEquals("worker claude failed: thread not found (19120)", summary);
        assertFalse(summary.contains(".github"));
        assertFalse(summary.contains("ARCHITECTURE"));
        assertFalse(summary.contains("我会先把"));
    }

    @Test
    void synthesizeFailedExecutionResultSanitizesReadableFailureSummaryAtSource() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "synthesizeFailedExecutionResult",
            Task.class,
            com.agentcloud.engine.router.WorkerRouter.RouteResult.class,
            com.agentcloud.model.Worker.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            RuntimeException.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("claude")
            .withMetadata(Map.of());
        RuntimeException error = new RuntimeException(
            "����: û���ҵ����� \"19120\"��\n"
                + "我会先把和“下一步规划”最相关的文档与路线图过一遍。\n"
                + ".github\n"
                + "docs\\ARCHITECTURE.md\n"
                + "---"
        );

        Object raw = method.invoke(graph, task, null, null, null, null, null, null, null, error);
        assertNotNull(raw);
        com.agentcloud.worker.WorkerExecutionResult result = (com.agentcloud.worker.WorkerExecutionResult) raw;

        assertEquals("worker claude failed: thread not found (19120)", result.outputText());
        assertEquals("worker claude failed: thread not found (19120)", result.artifactContent());
        assertEquals(
            "worker claude failed: thread not found (19120)",
            String.valueOf(result.metadata().get("failure_summary_readable"))
        );
        assertFalse(result.outputText().contains(".github"));
        assertFalse(result.outputText().contains("我会先把"));
    }

    @Test
    void enrichTaskFromJudgmentPrefersReadableFailureSummaryForTaskSummary() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "enrichTaskFromJudgment",
            Task.class,
            WorkerExecutionResult.class,
            String.class,
            String.class,
            String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withSummary("旧摘要")
            .withNextStep("旧下一步");
        String noisy = "����: û���ҵ����� \"19120\"��\n"
            + "我会先把和“下一步规划”最相关的文档与路线图过一遍。\n"
            + ".github\n"
            + "docs\\ARCHITECTURE.md\n"
            + "---";
        WorkerExecutionResult result = new WorkerExecutionResult(
            noisy,
            noisy,
            false,
            "",
            noisy,
            "Inspect failure trace.",
            "low",
            "failed",
            List.of(),
            List.of("worker round failed"),
            0,
            0L,
            Map.of(
                "selected_worker", "codex",
                "failure_summary_readable", "worker codex failed: thread not found (19120)"
            )
        );

        Task updated = (Task) method.invoke(
            graph,
            task,
            result,
            noisy,
            "Inspect failure trace.",
            "Inspect failure trace."
        );

        assertEquals("worker codex failed: thread not found (19120)", updated.summary());
        assertEquals("Inspect failure trace.", updated.nextStep());
        assertFalse(updated.summary().contains(".github"));
        assertFalse(updated.summary().contains("我会先把"));
        assertFalse(updated.summary().contains("����"));
    }

    @Test
    void enrichTaskFromJudgmentUsesExecutionJudgmentNextStepBeforeCompletionAndWorkerOutput() throws Exception {
        Task updated = invokeEnrichTaskFromJudgment(
            Task.create("task_1", "session_1", "demo", "active", "high")
                .withNextStep("旧下一步"),
            new WorkerExecutionResult(
                "summary",
                "output",
                false,
                "",
                "",
                "worker suggested next",
                "medium",
                "succeeded",
                List.of(),
                List.of(),
                0,
                0L,
                Map.of()
            ),
            "latest output",
            "execution judgment next",
            "completion judgment next"
        );

        assertEquals("execution judgment next", updated.nextStep());
    }

    @Test
    void enrichTaskFromJudgmentFallsBackToCompletionThenWorkerNextStep() throws Exception {
        Task completionUpdated = invokeEnrichTaskFromJudgment(
            Task.create("task_1", "session_1", "demo", "active", "high")
                .withNextStep("旧下一步"),
            new WorkerExecutionResult(
                "summary",
                "output",
                false,
                "",
                "",
                "worker suggested next",
                "medium",
                "succeeded",
                List.of(),
                List.of(),
                0,
                0L,
                Map.of()
            ),
            "latest output",
            "",
            "completion judgment next"
        );
        assertEquals("completion judgment next", completionUpdated.nextStep());

        Task workerUpdated = invokeEnrichTaskFromJudgment(
            Task.create("task_2", "session_1", "demo", "active", "high")
                .withNextStep("旧下一步"),
            new WorkerExecutionResult(
                "summary",
                "output",
                false,
                "",
                "",
                "worker suggested next",
                "medium",
                "succeeded",
                List.of(),
                List.of(),
                0,
                0L,
                Map.of()
            ),
            "latest output",
            "",
            ""
        );
        assertEquals("worker suggested next", workerUpdated.nextStep());
    }

    @Test
    void maybePlanFailureRecoverySchedulesAutoHandoffAfterRetryBudget() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of("auto_same_worker_retry_count", 1));
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "thread not found: 15252");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);
        handoffTarget.setAccessible(true);

        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.TRUE, autoHandoff.invoke(directive));
        assertEquals("kimi", handoffTarget.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoveryIgnoresStaleMetadataAssignedWorkerWhenSelectingFallback() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of(
                "assigned_worker", "codex",
                "auto_same_worker_retry_count", 1,
                "task_type", "coding"
            ));
        registry.markTemporarilyUnavailable("codex", 60_000L, "thread not found");
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex"
        ), "thread not found: 15252");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        recoveryStage.setAccessible(true);
        autoHandoff.setAccessible(true);
        handoffTarget.setAccessible(true);

        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.TRUE, autoHandoff.invoke(directive));
        assertEquals("cursor", handoffTarget.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoveryPrefersCodingWorkerOverOpenclawForCodingTask() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("claude")
            .withMetadata(Map.of(
                "assigned_worker", "claude",
                "auto_same_worker_retry_count", 1,
                "task_type", "coding"
            ));

        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "claude",
            "candidate_workers", List.of("openclaw-native", "deepseek"),
            "fallback_workers", List.of("openclaw-native")
        ), "thread not found: 15252");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        recoveryStage.setAccessible(true);
        handoffTarget.setAccessible(true);

        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals("codex", handoffTarget.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoveryAvoidsHotFailingProviderWhenAlternateProviderExists() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        AgentRunService agentRunService = new AgentRunService(
            agentRunDao(
                providerRun("claude", "failed", "thread not found", "worker claude failed: thread not found (23524)"),
                providerRun("claude", "failed", "timeout", "worker claude failed: timeout"),
                providerRun("codex", "completed", "completed", "completed")
            ),
            null
        );
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, router, null, null,
            null, null, null, null, null, null, agentRunService
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("claude")
            .withMetadata(Map.of(
                "assigned_worker", "claude",
                "auto_same_worker_retry_count", 1,
                "task_type", "coding",
                "provider_id", "claude"
            ));

        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "failure_summary_readable", "worker claude failed: thread not found (15252)",
            "selected_worker", "claude",
            "provider_id", "claude",
            "candidate_workers", List.of("cursor", "codex"),
            "fallback_workers", List.of("cursor", "codex")
        ), "thread not found: 15252");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        handoffTarget.setAccessible(true);

        assertEquals("codex", handoffTarget.invoke(directive));
        WorkerRegistry.ReadinessCheck previousWorkerReadiness = registry.checkReadiness("claude");
        assertFalse(previousWorkerReadiness.ready());
        assertFalse(previousWorkerReadiness.checks().getOrDefault("runtime_available", true));
        assertTrue(previousWorkerReadiness.reason().contains("temporarily unavailable"));
        assertTrue(previousWorkerReadiness.reason().contains("thread not found"));
    }

    @Test
    void applyRecoveryDirectiveSyncsMetadataAssignedWorkerDuringAutoHandoff() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method planMethod = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        planMethod.setAccessible(true);
        Method applyMethod = ControlNodeGraph.class.getDeclaredMethod(
            "applyRecoveryDirective", Task.class, Class.forName("com.agentcloud.engine.ControlNodeGraph$RecoveryDirective")
        );
        applyMethod.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of(
                "assigned_worker", "codex",
                "auto_same_worker_retry_count", 1
            ));
        Object directive = planMethod.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "thread not found: 15252");

        Task updated = (Task) applyMethod.invoke(graph, task, directive);
        assertEquals("kimi", updated.assignedWorker());
        assertEquals("kimi", updated.metadata().get("assigned_worker"));
        assertEquals("kimi", updated.metadata().get("target_worker"));
        assertEquals("codex", updated.metadata().get("previous_worker"));
    }

    @Test
    void applyRecoveryDirectiveClearsProviderContinuationMetadataDuringSameWorkerRetry() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method planMethod = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        planMethod.setAccessible(true);
        Method applyMethod = ControlNodeGraph.class.getDeclaredMethod(
            "applyRecoveryDirective", Task.class, Class.forName("com.agentcloud.engine.ControlNodeGraph$RecoveryDirective")
        );
        applyMethod.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.ofEntries(
                Map.entry("assigned_worker", "codex"),
                Map.entry("task_type", "coding"),
                Map.entry("auto_same_worker_retry_count", 0),
                Map.entry("provider_session_id", "thread-codex-001"),
                Map.entry("provider_thread_id", "thread-codex-001"),
                Map.entry("codex_thread_id", "thread-codex-001"),
                Map.entry("resume_provider_session_id", "thread-codex-001")
            ));
        Object directive = planMethod.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex"
        ), "thread not found: 15252");

        Task updated = (Task) applyMethod.invoke(graph, task, directive);

        assertEquals("codex", updated.assignedWorker());
        assertEquals("same_worker_retry_scheduled", updated.metadata().get("recovery_stage"));
        assertEquals("fresh_session", updated.metadata().get("recovery_execution_mode"));
        assertEquals(1, updated.metadata().get("auto_same_worker_retry_count"));
        assertEquals("codex", updated.metadata().get("assigned_worker"));
        assertEquals("codex", updated.metadata().get("previous_worker"));
        assertFalse(updated.metadata().containsKey("provider_session_id"));
        assertFalse(updated.metadata().containsKey("provider_thread_id"));
        assertFalse(updated.metadata().containsKey("codex_thread_id"));
        assertFalse(updated.metadata().containsKey("resume_provider_session_id"));
    }

    @Test
    void applyRecoveryDirectiveClearsProviderContinuationMetadataDuringAutoHandoff() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method planMethod = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        planMethod.setAccessible(true);
        Method applyMethod = ControlNodeGraph.class.getDeclaredMethod(
            "applyRecoveryDirective", Task.class, Class.forName("com.agentcloud.engine.ControlNodeGraph$RecoveryDirective")
        );
        applyMethod.setAccessible(true);
        Class<?> directiveClass = Class.forName("com.agentcloud.engine.ControlNodeGraph$RecoveryDirective");
        Method handoffTargetMethod = directiveClass.getDeclaredMethod("handoffTarget");
        handoffTargetMethod.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.ofEntries(
                Map.entry("assigned_worker", "codex"),
                Map.entry("task_type", "coding"),
                Map.entry("auto_same_worker_retry_count", 1),
                Map.entry("provider_session_id", "thread-codex-001"),
                Map.entry("provider_thread_id", "thread-codex-001"),
                Map.entry("codex_thread_id", "thread-codex-001"),
                Map.entry("resume_provider_session_id", "thread-codex-001")
            ));
        Object directive = planMethod.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "thread not found: 15252",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "thread not found: 15252");

        String handoffTarget = String.valueOf(handoffTargetMethod.invoke(directive));
        Task updated = (Task) applyMethod.invoke(graph, task, directive);

        assertEquals(handoffTarget, updated.assignedWorker());
        assertEquals("auto_handoff_scheduled", updated.metadata().get("recovery_stage"));
        assertEquals("fresh_session", updated.metadata().get("recovery_execution_mode"));
        assertEquals(1, updated.metadata().get("auto_same_worker_retry_count"));
        assertEquals(handoffTarget, updated.metadata().get("assigned_worker"));
        assertEquals("codex", updated.metadata().get("previous_worker"));
        assertEquals(handoffTarget, updated.metadata().get("target_worker"));
        assertFalse(updated.metadata().containsKey("provider_session_id"));
        assertFalse(updated.metadata().containsKey("provider_thread_id"));
        assertFalse(updated.metadata().containsKey("codex_thread_id"));
        assertFalse(updated.metadata().containsKey("resume_provider_session_id"));
    }

    @Test
    void maybePlanFailureRecoveryClassifiesTaskEnvironmentBlocked() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of());
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "python: can't open file 'missing.py': [Errno 2] No such file or directory",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "python: can't open file 'missing.py': [Errno 2] No such file or directory");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method failureClass = directiveClass.getDeclaredMethod("failureClass");
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        failureClass.setAccessible(true);
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);

        assertEquals("task_environment_blocked", failureClass.invoke(directive));
        assertEquals("human_gate_required", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.FALSE, autoHandoff.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoverySchedulesAutoHandoffForDeterministicBackendFailure() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of());
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "provider does not support required tool call mode",
            "selected_worker", "codex",
            "candidate_workers", List.of("codex", "kimi")
        ), "provider does not support required tool call mode");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method failureClass = directiveClass.getDeclaredMethod("failureClass");
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        failureClass.setAccessible(true);
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);
        handoffTarget.setAccessible(true);

        assertEquals("worker_backend_deterministic", failureClass.invoke(directive));
        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.TRUE, autoHandoff.invoke(directive));
        assertEquals("kimi", handoffTarget.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoverySchedulesAutoHandoffForLocalWorkspaceAccessRefusal() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("deepseek")
            .withMetadata(Map.of(
                "task_type", "coding",
                "workspace_root", "D:\\gitAll\\articleeditor"
            ));
        String refusal = "我目前无法直接访问本地文件或路径，请粘贴文档内容。";
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "completed",
            "output_text", refusal,
            "selected_worker", "deepseek",
            "candidate_workers", List.of("deepseek", "codex")
        ), refusal);

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method failureClass = directiveClass.getDeclaredMethod("failureClass");
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        Method handoffTarget = directiveClass.getDeclaredMethod("handoffTarget");
        failureClass.setAccessible(true);
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);
        handoffTarget.setAccessible(true);

        assertEquals("worker_backend_deterministic", failureClass.invoke(directive));
        assertEquals("auto_handoff_scheduled", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.TRUE, autoHandoff.invoke(directive));
        assertEquals("codex", handoffTarget.invoke(directive));
    }

    @Test
    void maybePlanFailureRecoveryClassifiesPartialResultOrQualityRisk() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex")
            .withMetadata(Map.of());
        Object directive = method.invoke(graph, task, Map.of(
            "execution_status", "failed",
            "output_text", "result quality uncertain after writing output",
            "selected_worker", "codex",
            "tool_invocation_ids", List.of("tool_call_1"),
            "unfinished_items", List.of("verify generated patch"),
            "grounded_output_present", true,
            "candidate_workers", List.of("codex", "kimi")
        ), "result quality uncertain after writing output");

        assertNotNull(directive);
        Class<?> directiveClass = directive.getClass();
        Method failureClass = directiveClass.getDeclaredMethod("failureClass");
        Method recoveryStage = directiveClass.getDeclaredMethod("recoveryStage");
        Method sameWorkerRetry = directiveClass.getDeclaredMethod("sameWorkerRetry");
        Method autoHandoff = directiveClass.getDeclaredMethod("autoHandoff");
        failureClass.setAccessible(true);
        recoveryStage.setAccessible(true);
        sameWorkerRetry.setAccessible(true);
        autoHandoff.setAccessible(true);

        assertEquals("partial_result_or_quality_risk", failureClass.invoke(directive));
        assertEquals("human_gate_required", recoveryStage.invoke(directive));
        assertEquals(Boolean.FALSE, sameWorkerRetry.invoke(directive));
        assertEquals(Boolean.FALSE, autoHandoff.invoke(directive));
    }

    private String invokeResolveAction(String executionAction,
                                      String completionStatus,
                                      String alignmentLevel,
                                      boolean needsContextReopen,
                                      boolean needsArchiveRetrieval,
                                      boolean needsExternalFactRefresh) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "resolveAction", String.class, String.class, String.class, boolean.class, boolean.class, boolean.class
        );
        method.setAccessible(true);
        return (String) method.invoke(
            graph,
            executionAction,
            completionStatus,
            alignmentLevel,
            needsContextReopen,
            needsArchiveRetrieval,
            needsExternalFactRefresh
        );
    }

    private Task invokeEnrichTaskFromJudgment(Task task,
                                             WorkerExecutionResult result,
                                             String latestOutput,
                                             String executionNextStep,
                                             String completionNextAction) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "enrichTaskFromJudgment",
            Task.class,
            WorkerExecutionResult.class,
            String.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        return (Task) method.invoke(
            graph,
            task,
            result,
            latestOutput,
            executionNextStep,
            completionNextAction
        );
    }

    private AgentRunRecord providerRun(String providerId,
                                       String status,
                                       String workerExecutionStatus,
                                       String summary) {
        return new AgentRunRecord(
            "arun_" + providerId + "_" + status + "_" + workerExecutionStatus,
            "task_provider_probe",
            "session_provider_probe",
            providerId,
            providerId,
            "executor",
            providerId,
            "strong",
            status,
            Instant.parse("2026-05-15T10:00:00Z"),
            Instant.parse("2026-05-15T10:00:05Z"),
            5000L,
            summary,
            "run.failed",
            0,
            Map.of(
                "worker_execution_status", workerExecutionStatus,
                "failure_summary_readable", summary
            )
        );
    }

    @SuppressWarnings("unchecked")
    private AgentRunDao agentRunDao(AgentRunRecord... records) {
        List<AgentRunRecord> stored = new ArrayList<>(List.of(records));
        return (AgentRunDao) Proxy.newProxyInstance(
            AgentRunDao.class.getClassLoader(),
            new Class<?>[]{AgentRunDao.class},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "testAgentRunDao";
                }
                return switch (method.getName()) {
                    case "listByProvider" -> {
                        String providerId = (String) args[0];
                        int limit = (Integer) args[1];
                        yield stored.stream()
                            .filter(run -> providerId.equals(run.providerId()))
                            .limit(limit)
                            .toList();
                    }
                    case "latestByTask", "findById" -> Optional.empty();
                    case "listByProviderAndStatus", "search", "listRecent", "listActive" -> List.of();
                    case "insert", "insertRaw" -> null;
                    default -> {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == List.class) {
                            yield List.of();
                        }
                        if (returnType == Optional.class) {
                            yield Optional.empty();
                        }
                        if (returnType == int.class || returnType == Integer.class) {
                            yield 0;
                        }
                        if (returnType == boolean.class || returnType == Boolean.class) {
                            yield false;
                        }
                        yield null;
                    }
                };
            }
        );
    }
}
