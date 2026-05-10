package com.agentcloud.runtime.context;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextViewBuilderTest {

    @Test
    void buildsMountedViewPanelsFromExistingRuntimeContext() {
        Instant now = Instant.parse("2026-05-06T03:00:00Z");
        Task task = new Task(
            "task_1",
            "session_1",
            "parent_1",
            "mounted context rollout",
            "active",
            "high",
            now,
            now,
            now,
            null,
            null,
            "已有连续性摘要",
            "将 runtime context 演进为 mounted view",
            "保持兼容并补测试",
            "codex",
            "continue",
            null,
            Map.of(
                "task_type", "coding",
                "intent", "先做兼容包装，再考虑 prompt 切换",
                "sibling_task_ids", List.of("task_sibling_a", "task_sibling_b")
            )
        );
        ResumePacket packet = new ResumePacket(
            "packet_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(1),
            "1.1",
            "恢复当前 mounted context 改造",
            "最近决定先做并行视图",
            "已有一批 runtime artifact",
            List.of("是否要立即改 prompt?"),
            "新增 ContextViewBuilder 并挂到 runtime_context",
            Map.of(
                "current_status", "active",
                "current_node", "continue",
                "assigned_worker", "codex",
                "blockers", List.of("需要保证旧执行链不变"),
                "runtime_cognition_surface", Map.of(
                    "route", Map.of(
                        "selected_worker", "codex",
                        "route_source", "runtime_context_resume_surface",
                        "candidate_workers", List.of("codex", "kimi")
                    ),
                    "execution", Map.of(
                        "worker_id", "codex",
                        "prompt_mode", "mounted_context_primary",
                        "tool_invocation_ids", List.of("tool_1"),
                        "evidence_refs", List.of("tool:patch_file:ContextViewBuilder.java"),
                        "unfinished_items", List.of("补 builder seam 测试")
                    ),
                    "execution_judgment", Map.of(
                        "needs_context_reopen", true,
                        "evidence_gap_detected", true,
                        "needs_archive_retrieval", true,
                        "needs_external_fact_refresh", true,
                        "reopen_candidate_paths", List.of(
                            "/sessions/session_1/tasks/task_1/tool_invocations",
                            "/sessions/session_1/tasks/task_1/packets/packet_1"
                        )
                    )
                )
            )
        );
        Checkpoint checkpoint = new Checkpoint(
            "checkpoint_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(2),
            "pause_before",
            "checkpoint 已沉淀关键决策与 artifact。",
            Map.of(
                "key_decisions", List.of("先保留 ActiveContext 消费路径"),
                "key_artifacts", List.of("runtime_context JSON 仍兼容旧字段"),
                "open_questions", List.of("后续是否把 mounted panel 直接渲染进 prompt"),
                "runtime_cognition_surface", Map.of(
                    "route", Map.of(
                        "selected_worker", "codex",
                        "route_source", "runtime_context_checkpoint_surface",
                        "candidate_workers", List.of("codex", "kimi")
                    ),
                    "execution", Map.of(
                        "worker_id", "codex",
                        "prompt_mode", "mounted_context_shadow",
                        "tool_invocation_ids", List.of("tool_1"),
                        "evidence_refs", List.of("tool:patch_file:ContextViewBuilder.java"),
                        "unfinished_items", List.of("后续是否把 mounted panel 直接渲染进 prompt")
                    ),
                    "execution_judgment", Map.of(
                        "needs_context_reopen", true,
                        "evidence_gap_detected", true,
                        "needs_archive_retrieval", true,
                        "needs_external_fact_refresh", true,
                        "reopen_candidate_paths", List.of(
                            "/sessions/session_1/tasks/task_1/tool_invocations",
                            "/sessions/session_1/tasks/task_1/checkpoints/checkpoint_1"
                        )
                    )
                )
            ),
            Map.of(),
            Map.of()
        );
        Decision executionDecision = new Decision(
            "decision_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "execution_judgment",
            "保持兼容",
            "先并行构建 mounted view，再观察 live flow 和 runtime_context 响应。",
            "high",
            null,
            Map.ofEntries(
                entry("judgment_stage", "execution"),
                entry("selected_worker", "codex"),
                entry("action", "continue"),
                entry("next_step", "补 builder seam 测试"),
                entry("needs_context_reopen", true),
                entry("evidence_gap_detected", true),
                entry("needs_archive_retrieval", true),
                entry("needs_external_fact_refresh", true),
                entry("reopen_candidate_paths", List.of(
                    "/sessions/session_1/tasks/task_1/tool_invocations",
                    "/sessions/session_1/tasks/task_1/packets/packet_1"
                )),
                entry("tool_invocation_ids", List.of("tool_1")),
                entry("evidence_refs", List.of("tool:patch_file:ContextViewBuilder.java"))
            )
        );
        Decision completionDecision = new Decision(
            "decision_2",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "completion_judgment",
            "继续推进",
            "当前阶段已对齐目标，但仍需补 mounted seam 回归。",
            "medium",
            null,
            Map.of(
                "judgment_stage", "completion",
                "selected_worker", "codex",
                "status", "partially_done",
                "alignment_level", "medium",
                "suggested_next_action", "补 mounted seam 回归测试"
            )
        );
        Decision routingDecision = new Decision(
            "decision_3",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "route_preview",
            "预览选路",
            "仅作为调试视图，不应升入 evidence。",
            "low",
            null,
            Map.of("selected_worker", "codex")
        );
        Artifact artifact = new Artifact(
            "artifact_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(4),
            "worker_artifact",
            "Mounted view sketch",
            "memory://artifact/mounted-view",
            null,
            "草图包含 pinned、active、evidence、index panel。",
            Map.of("suggested_next_step", "接入 TaskRuntimeContextBuilder")
        );
        ToolInvocationRecord toolInvocation = new ToolInvocationRecord(
            "tool_1",
            task.sessionId(),
            task.id(),
            "codex",
            "exec_1",
            "patch_file",
            Map.of("path", "src/main/java/com/agentcloud/runtime/context/ContextViewBuilder.java"),
            "已把 tool trace 纳入 mounted evidence。",
            "succeeded",
            true,
            142,
            List.of("src/main/java/com/agentcloud/runtime/context/ContextViewBuilder.java"),
            now.plusSeconds(4),
            Map.of("source_surface", "tool_trace")
        );
        Event event = new Event(
            "event_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(5),
            "runtime_context_built",
            "system",
            "task_service",
            "新 mounted view 已并行生成。",
            Map.of("phase", "compat")
        );
        Event handoffEvent = new Event(
            "event_2",
            task.sessionId(),
            task.id(),
            now.plusSeconds(5),
            "task_control_action",
            "task_service",
            null,
            "Task control action: handoff",
            Map.of(
                "action", "handoff",
                "previous_worker", "codex",
                "assigned_worker", "kimi",
                "target_worker", "kimi",
                "prompt_mode", "mounted_context_shadow",
                "runtime_facts", Map.of(
                    "task_id", task.id(),
                    "recommended_next_step", "Apply the final mounted handoff patch."
                ),
                "runtime_cognition_surface", Map.of(
                    "route", Map.of(
                        "selected_worker", "kimi",
                        "route_source", "runtime_context_handoff_surface",
                        "candidate_workers", List.of("kimi", "codex")
                    ),
                    "execution", Map.of(
                        "worker_id", "kimi",
                        "prompt_mode", "mounted_context_shadow",
                        "execution_status", "waiting",
                        "tool_invocation_ids", List.of("tool_handoff"),
                        "evidence_refs", List.of("/tasks/task_1/handoffs/handoff-1"),
                        "unfinished_items", List.of("apply mounted handoff patch"),
                        "proof_summary", "tool=tool_handoff | evidence=/tasks/task_1/handoffs/handoff-1"
                    ),
                    "execution_judgment", Map.of(
                        "needs_context_reopen", true,
                        "evidence_gap_detected", true,
                        "needs_archive_retrieval", true,
                        "needs_external_fact_refresh", true,
                        "reopen_candidate_paths", List.of(
                            "/sessions/session_1/tasks/task_1/checkpoints",
                            "/sessions/session_1/tasks/task_1/packets/packet_handoff_1"
                        )
                    )
                )
            )
        );
        SessionMessage oldMessage = new SessionMessage(
            "msg_1",
            task.sessionId(),
            task.id(),
            "user",
            "task_note",
            "先做保守演进。",
            now.plusSeconds(6),
            Map.of("source_surface", "dialogue")
        );
        SessionMessage recentMessage = new SessionMessage(
            "msg_2",
            task.sessionId(),
            task.id(),
            "assistant",
            "task_progress",
            "已经补上 mounted view seam，下一步是写测试。",
            now.plusSeconds(7),
            Map.of("source_surface", "task_service")
        );
        ActiveContext activeContext = new ActiveContext(
            "Mounted context rollout",
            List.of("priority=high", "assigned_worker=codex"),
            List.of("[runtime_context_built] 新 mounted view 已并行生成。"),
            List.of("保持兼容"),
            List.of("Mounted view sketch: 草图包含 pinned、active、evidence、index panel。"),
            List.of("是否要立即改 prompt?"),
            List.of("补 builder seam 测试"),
            List.of("不要破坏旧执行链"),
            List.of("在 budget 允许时保留关键约束"),
            List.of("budget=12, retained_lines=8"),
            "已有连续性摘要",
            "Task Focus: Mounted context rollout\nConstraints:\n- priority=high",
            12
        );
        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            packet,
            checkpoint,
            List.of(handoffEvent, event),
            List.of(executionDecision, completionDecision, routingDecision),
            List.of(artifact),
            List.of(toolInvocation),
            List.of(oldMessage, recentMessage),
            activeContext
        );

        ContextViewBuilder builder = new ContextViewBuilder();
        MountedContextView mountedView = builder.build(runtimeContext);

        assertNotNull(mountedView);
        assertEquals(task.id(), mountedView.taskId());
        assertEquals(7, mountedView.panels().size());
        assertEquals(2, mountedView.objects(MountedContextPanelName.PINNED).size());
        assertTrue(mountedView.objects(MountedContextPanelName.PINNED).stream()
            .allMatch(object -> object.retentionState() == ContextRetentionState.PINNED));
        assertTrue(mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.RESUME_PACKET
                && object.retentionState() == ContextRetentionState.HOT_RAW));
        assertTrue(mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.CHECKPOINT
                && object.retentionState() == ContextRetentionState.HOT_RAW));
        assertTrue(mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.RESUME_PACKET
                && "mounted_context_primary".equals(object.metadata().get("prompt_mode"))
                && "runtime_context_resume_surface".equals(object.metadata().get("route_source"))
                && Boolean.TRUE.equals(object.metadata().get("needs_context_reopen"))
                && Boolean.TRUE.equals(object.metadata().get("evidence_gap_detected"))
                && Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"))
                && Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"))
                && List.of(
                    "/sessions/session_1/tasks/task_1/tool_invocations",
                    "/sessions/session_1/tasks/task_1/packets/packet_1"
                ).equals(object.metadata().get("reopen_candidate_paths"))
                && " /sessions/session_1/tasks/task_1/tool_invocations | /sessions/session_1/tasks/task_1/packets/packet_1".trim()
                    .equals(String.valueOf(object.metadata().get("reopen_summary")))
                && List.of("tool_1").equals(object.metadata().get("tool_invocation_ids"))
                && List.of("tool:patch_file:ContextViewBuilder.java").equals(object.metadata().get("evidence_refs"))
                && List.of("补 builder seam 测试").equals(object.metadata().get("unfinished_items"))));
        assertTrue(mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.CHECKPOINT
                && "mounted_context_shadow".equals(object.metadata().get("prompt_mode"))
                && "runtime_context_checkpoint_surface".equals(object.metadata().get("route_source"))
                && Boolean.TRUE.equals(object.metadata().get("needs_context_reopen"))
                && Boolean.TRUE.equals(object.metadata().get("evidence_gap_detected"))
                && Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"))
                && Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"))
                && List.of(
                    "/sessions/session_1/tasks/task_1/tool_invocations",
                    "/sessions/session_1/tasks/task_1/checkpoints/checkpoint_1"
                ).equals(object.metadata().get("reopen_candidate_paths"))
                && List.of("tool_1").equals(object.metadata().get("tool_invocation_ids"))
                && List.of("tool:patch_file:ContextViewBuilder.java").equals(object.metadata().get("evidence_refs"))
                && List.of("后续是否把 mounted panel 直接渲染进 prompt").equals(object.metadata().get("unfinished_items"))));
        assertTrue(mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.SESSION_MESSAGE
                && object.retentionState() == ContextRetentionState.HOT_RAW));
        assertEquals(3, mountedView.objects(MountedContextPanelName.ACTIVE).stream()
            .filter(object -> object.type() == ContextObjectType.DECISION)
            .count());
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.ARTIFACT
                && object.retentionState() == ContextRetentionState.WARM_SUMMARY));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.CHECKPOINT
                && object.retentionState() == ContextRetentionState.HOT_RAW
                && Boolean.TRUE.equals(object.metadata().get("rehydrated_from_archive"))
                && "/sessions/session_1/tasks/task_1/checkpoints"
                    .equals(object.metadata().get("rehydrated_target_path"))
                && object.sourceRefs().stream().anyMatch(ref ->
                    "reopen_capsule".equals(ref.refType())
                        && "/sessions/session_1/tasks/task_1/archive/reopen_capsule".equals(ref.targetPath()))));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.TOOL_INVOCATION
                && object.summary().contains("mounted evidence")));
        assertEquals(2, mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .filter(object -> object.type() == ContextObjectType.DECISION)
            .count());
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.DECISION
                && "execution".equals(object.metadata().get("judgment_stage"))
                && "codex".equals(object.metadata().get("selected_worker"))
                && Boolean.TRUE.equals(object.metadata().get("needs_context_reopen"))
                && Boolean.TRUE.equals(object.metadata().get("evidence_gap_detected"))
                && Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"))
                && Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"))
                && List.of(
                    "/sessions/session_1/tasks/task_1/tool_invocations",
                    "/sessions/session_1/tasks/task_1/packets/packet_1"
                ).equals(object.metadata().get("reopen_candidate_paths"))
                && List.of("tool_1").equals(object.metadata().get("tool_invocation_ids"))
                && List.of("tool:patch_file:ContextViewBuilder.java").equals(object.metadata().get("evidence_refs"))));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .noneMatch(object -> object.type() == ContextObjectType.DECISION
                && "route_preview".equals(object.metadata().get("decision_type"))));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.EVENT));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.EVENT
                && "handoff".equals(object.metadata().get("action"))
                && "mounted_context_shadow".equals(object.metadata().get("prompt_mode"))
                && "runtime_context_handoff_surface".equals(object.metadata().get("route_source"))
                && "waiting".equals(object.metadata().get("execution_status"))
                && Boolean.TRUE.equals(object.metadata().get("needs_context_reopen"))
                && Boolean.TRUE.equals(object.metadata().get("evidence_gap_detected"))
                && Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"))
                && Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"))
                && List.of(
                    "/sessions/session_1/tasks/task_1/checkpoints",
                    "/sessions/session_1/tasks/task_1/packets/packet_handoff_1"
                ).equals(object.metadata().get("reopen_candidate_paths"))
                && "/sessions/session_1/tasks/task_1/checkpoints | /sessions/session_1/tasks/task_1/packets/packet_handoff_1"
                    .equals(object.metadata().get("reopen_summary"))
                && List.of("tool_handoff").equals(object.metadata().get("tool_invocation_ids"))
                && List.of("/tasks/task_1/handoffs/handoff-1").equals(object.metadata().get("evidence_refs"))
                && List.of("apply mounted handoff patch").equals(object.metadata().get("unfinished_items"))
                && object.summary().contains("Apply the final mounted handoff patch.")
                && object.contentPreview().contains("runtime_context_handoff_surface")));
        assertEquals(1, mountedView.objects(MountedContextPanelName.INDEX).size());
        assertTrue(mountedView.objects(MountedContextPanelName.INDEX).stream()
            .anyMatch(object -> object.summary().contains("tool_invocations=1")));
        assertTrue(mountedView.objects(MountedContextPanelName.ANCESTOR).stream()
            .anyMatch(object -> object.retentionState() == ContextRetentionState.ARCHIVED_HANDLE));
        assertEquals(2, mountedView.objects(MountedContextPanelName.SIBLING).size());
        assertFalse(mountedView.objects(MountedContextPanelName.ARCHIVE_HANDLES).isEmpty());
        assertTrue(mountedView.objects(MountedContextPanelName.ARCHIVE_HANDLES).stream()
            .anyMatch(object -> object.type() == ContextObjectType.CAPSULE
                && object.retentionState() == ContextRetentionState.COLD_CAPSULE
                && "Reopen Capsule".equals(object.title())
                && List.of(
                    "/sessions/session_1/tasks/task_1/checkpoints",
                    "/sessions/session_1/tasks/task_1/packets/packet_handoff_1"
                ).equals(object.metadata().get("reopen_candidate_paths"))
                && "/sessions/session_1/tasks/task_1/checkpoints"
                    .equals(object.metadata().get("target_path"))
                && object.summary().contains("/sessions/session_1/tasks/task_1/checkpoints")
                && object.refs().size() == 2));
        assertTrue(mountedView.objects(MountedContextPanelName.ARCHIVE_HANDLES).stream()
            .anyMatch(object -> object.type() == ContextObjectType.CAPSULE
                && object.retentionState() == ContextRetentionState.COLD_CAPSULE
                && "Retrieval Policy Capsule".equals(object.title())
                && Boolean.TRUE.equals(object.metadata().get("needs_archive_retrieval"))
                && Boolean.TRUE.equals(object.metadata().get("needs_external_fact_refresh"))
                && List.of(
                    "/sessions/session_1/tasks/task_1/checkpoints",
                    "/sessions/session_1/tasks/task_1/packets/packet_handoff_1"
                ).equals(object.metadata().get("retrieval_candidate_paths"))
                && "/sessions/session_1/tasks/task_1/checkpoints"
                    .equals(object.metadata().get("target_path"))
                && object.summary().contains("/sessions/session_1/tasks/task_1/checkpoints")
                && object.contentPreview().contains("needs_archive_retrieval: true")
                && object.refs().size() == 2));
        assertTrue(mountedView.objects(MountedContextPanelName.ARCHIVE_HANDLES).stream()
            .anyMatch(object -> object.summary().contains("tool invocation evidence")));
        assertTrue(mountedView.selectionTrace().stream()
            .anyMatch(item -> item.contains("retention_states=pinned,hot_raw,warm_summary,cold_capsule,archived_handle")));
        assertTrue(mountedView.selectionTrace().stream()
            .anyMatch(item -> item.contains("compat_mode=task_runtime_context_preserved")));
        assertTrue(mountedView.selectionTrace().stream()
            .anyMatch(item -> item.contains("evidence_decision_window=2/2")));
        assertTrue(mountedView.selectionTrace().stream()
            .anyMatch(item -> item.contains("tool_window=1/1")));
    }

    @Test
    void taskRuntimeContextDefaultsMountedViewWhenNotProvided() {
        Task task = Task.create("task_2", "session_2", "default mounted view", "active", "high");

        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            new ActiveContext("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 12)
        );

        assertNotNull(runtimeContext.mountedContextView());
        assertEquals(task.id(), runtimeContext.mountedContextView().taskId());
        assertEquals(7, runtimeContext.mountedContextView().panels().size());
    }
}
