package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.worker.WorkerExecutor;
import com.agentcloud.worker.WorkerExecutionResult;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1 Goal progress auto-update 测试。
 * 验证 worker 执行结果能自动迁移 subgoal_status：
 * - completed -> 当前 in_progress subgoal 标为 done
 * - failed -> 当前 in_progress subgoal 标为 blocked
 * - 无结果或无 subgoal_status -> 不更新
 */
class GoalProgressAutoUpdateTest {

    @Test
    void completedExecutionMarksInProgressSubgoalAsDone() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "in_progress"),
                    Map.of("title", "step3", "status", "pending")
                ),
                "progress_summary", "1/3 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "ok", "", false, "", "", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("done", subgoalStatus.get(0).get("status"));
        assertEquals("done", subgoalStatus.get(1).get("status"));
        assertEquals("pending", subgoalStatus.get(2).get("status"));
        assertEquals("2/3 subgoals done", updated.metadata().get("progress_summary"));
    }

    @Test
    void failedExecutionMarksInProgressSubgoalAsBlocked() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "in_progress")
                ),
                "progress_summary", "1/2 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "error", "", false, "", "", "", "low", "failed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("done", subgoalStatus.get(0).get("status"));
        assertEquals("blocked", subgoalStatus.get(1).get("status"));
        assertEquals("1/2 subgoals done", updated.metadata().get("progress_summary"));
    }

    @Test
    void noSubgoalStatusReturnsOriginalTask() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of("progress_summary", "no subgoals"));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "ok", "", false, "", "", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertEquals(task, updated);
    }

    @Test
    void noExecutionResultReturnsOriginalTask() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(Map.of("title", "step1", "status", "in_progress"))
            ));

        Task updated = invokeAutoUpdateSubgoalStatus(task, null);
        assertEquals(task, updated);
    }

    @Test
    void runningExecutionDoesNotUpdateSubgoalStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(Map.of("title", "step1", "status", "in_progress"))
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "in progress", "", false, "", "", "", "medium", "running", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertEquals(task, updated);
    }

    @Test
    void allDoneSubgoalsNotChangedByCompletedExecution() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "completed")
                ),
                "progress_summary", "2/2 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "ok", "", false, "", "", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        // 没有 in_progress subgoal 可迁移，返回原 task
        assertEquals(task, updated);
    }

    @Test
    void pendingSubgoalMigratedWhenNoInProgress() throws Exception {
        // 没有 in_progress 但有 pending -> completed 执行会把第一个 pending 标为 done
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "pending")
                ),
                "progress_summary", "1/2 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "ok", "", false, "", "", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("done", subgoalStatus.get(0).get("status"));
        assertEquals("done", subgoalStatus.get(1).get("status"));
        assertEquals("2/2 subgoals done", updated.metadata().get("progress_summary"));
    }

    @Test
    void runningExecutionMarksPendingAsInProgress() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "pending")
                )
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "running", "", false, "", "", "", "medium", "running", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("done", subgoalStatus.get(0).get("status"));
        assertEquals("in_progress", subgoalStatus.get(1).get("status"));
    }

    @Test
    void runningExecutionWithNoPendingSubgoalsReturnsOriginalTask() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "in_progress")
                )
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "running", "", false, "", "", "", "medium", "running", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        // No pending subgoal to migrate -> return original task
        assertEquals(task, updated);
    }

    @Test
    void completedExecutionWithActionGoalButNoProofKeepsSubgoalInProgress() throws Exception {
        // false-done guardrail: 目标含"写入"期望工具执行，worker 声称 completed 但无 tool/artifact 证据
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "goal", "把摘要写入 .tmp/summary.txt",
                "subgoal_status", List.of(Map.of("title", "write summary", "status", "in_progress")),
                "progress_summary", "0/1 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "done", "", false, "", "", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("in_progress", subgoalStatus.get(0).get("status"));
        assertEquals("evidence_gap_no_tool_proof", updated.metadata().get("subgoal_judgment_source"));
        assertEquals("0/1 subgoals done", updated.metadata().get("progress_summary"));
    }

    @Test
    void completedExecutionWithActionGoalAndProofMarksSubgoalDone() throws Exception {
        // guard 通过：目标期望工具执行，且 worker 确实产出 artifact -> 标 done
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "goal", "把摘要写入 .tmp/summary.txt",
                "subgoal_status", List.of(Map.of("title", "write summary", "status", "in_progress")),
                "progress_summary", "0/1 subgoals done"
            ));

        WorkerExecutionResult result = new WorkerExecutionResult(
            "done", "", true, "summary.txt", "摘要内容", "", "high", "completed", List.of(), List.of(), 0, 0L, Map.of()
        );

        Task updated = invokeAutoUpdateSubgoalStatus(task, result);
        assertNotNull(updated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgoalStatus = (List<Map<String, Object>>) updated.metadata().get("subgoal_status");
        assertEquals("done", subgoalStatus.get(0).get("status"));
        assertEquals("1/1 subgoals done", updated.metadata().get("progress_summary"));
    }
    private Task invokeAutoUpdateSubgoalStatus(Task task, WorkerExecutionResult result) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "autoUpdateSubgoalStatus", Task.class, WorkerExecutionResult.class
        );
        method.setAccessible(true);
        return (Task) method.invoke(graph, task, result);
    }
}
