package com.agentcloud.runtime.context;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                "blockers", List.of("需要保证旧执行链不变")
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
                "open_questions", List.of("后续是否把 mounted panel 直接渲染进 prompt")
            ),
            Map.of(),
            Map.of()
        );
        Decision decision = new Decision(
            "decision_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "execution_judgment",
            "保持兼容",
            "先并行构建 mounted view，再观察 live flow 和 runtime_context 响应。",
            "high",
            null,
            Map.of("next_step", "补 builder seam 测试")
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
            List.of(event),
            List.of(decision),
            List.of(artifact),
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
            .anyMatch(object -> object.type() == ContextObjectType.SESSION_MESSAGE
                && object.retentionState() == ContextRetentionState.HOT_RAW));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.ARTIFACT
                && object.retentionState() == ContextRetentionState.WARM_SUMMARY));
        assertTrue(mountedView.objects(MountedContextPanelName.EVIDENCE).stream()
            .anyMatch(object -> object.type() == ContextObjectType.EVENT));
        assertEquals(1, mountedView.objects(MountedContextPanelName.INDEX).size());
        assertTrue(mountedView.objects(MountedContextPanelName.ANCESTOR).stream()
            .anyMatch(object -> object.retentionState() == ContextRetentionState.ARCHIVED_HANDLE));
        assertEquals(2, mountedView.objects(MountedContextPanelName.SIBLING).size());
        assertFalse(mountedView.objects(MountedContextPanelName.ARCHIVE_HANDLES).isEmpty());
        assertTrue(mountedView.selectionTrace().stream()
            .anyMatch(item -> item.contains("compat_mode=task_runtime_context_preserved")));
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
