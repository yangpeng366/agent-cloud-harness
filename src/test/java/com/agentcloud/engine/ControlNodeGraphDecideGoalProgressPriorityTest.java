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
import com.agentcloud.model.LearningMemory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 Loop 验收标准 #2：decide 必须消费 goal 进度，而非只看单轮执行结果。
 *
 * 当 subgoal_status 存在时，goal progress 判断优先于 execution result 的 done/continue。
 * 核心场景：
 * - execution 说 done 但 subgoal 有 blocked -> human_gate（goal 优先）
 * - execution 说 done 但 subgoal 还有 open -> continue（goal 优先）
 * - execution 说 continue 但 subgoal 全部 done -> done（goal 优先）
 * - execution 说 continue 但 subgoal 有 blocked -> human_gate（goal 优先）
 */
class ControlNodeGraphDecideGoalProgressPriorityTest {

    @Test
    void doneExecutionWithBlockedSubgoalOverridesToHumanGate() throws Exception {
        // execution 说 done，但 subgoal 有 blocked -> human_gate
        assertEquals("human_gate", invokeResolveAction(
            "done", "done", "high", false, false, false,
            List.of("done", "blocked")
        ));
    }

    @Test
    void doneExecutionWithOpenSubgoalsOverridesToCheckpoint() throws Exception {
        // execution 说 done，但 subgoal 还有 in_progress -> goal action = continue
        // goal 说 continue 时不允许直接 done，改为 checkpoint 保存进度
        assertEquals("checkpoint", invokeResolveAction(
            "done", "done", "high", false, false, false,
            List.of("done", "in_progress")
        ));
    }

    @Test
    void continueExecutionWithAllSubgoalsDoneOverridesToDone() throws Exception {
        // execution 说 continue，但 subgoal 全部 done -> done
        assertEquals("done", invokeResolveAction(
            "continue", "partially_done", "medium", false, false, false,
            List.of("done", "completed")
        ));
    }

    @Test
    void continueExecutionWithBlockedSubgoalOverridesToHumanGate() throws Exception {
        // execution 说 continue，但 subgoal 有 blocked -> human_gate
        assertEquals("human_gate", invokeResolveAction(
            "continue", "partially_done", "medium", false, false, false,
            List.of("done", "blocked")
        ));
    }

    @Test
    void checkpointThenDoneWithBlockedSubgoalOverridesToHumanGate() throws Exception {
        // execution 说 checkpoint + done，但 subgoal 有 blocked -> human_gate
        assertEquals("human_gate", invokeResolveAction(
            "checkpoint", "done", "high", false, false, false,
            List.of("done", "blocked")
        ));
    }

    @Test
    void doneExecutionWithAllSubgoalsDoneStaysDone() throws Exception {
        // execution 说 done 且 subgoal 也全部 done -> done
        assertEquals("done", invokeResolveAction(
            "done", "done", "high", false, false, false,
            List.of("done", "completed")
        ));
    }

    @Test
    void noSubgoalStatusFallsBackToExecutionResult() throws Exception {
        // 没有 subgoal_status 时，行为与原来一致
        assertEquals("done", invokeResolveAction(
            "done", "done", "high", false, false, false,
            null
        ));
    }

    @Test
    void emptySubgoalStatusFallsBackToExecutionResult() throws Exception {
        // 空 subgoal_status 时，行为与原来一致
        assertEquals("done", invokeResolveAction(
            "done", "done", "high", false, false, false,
            List.of()
        ));
    }

    @Test
    void doneExecutionLowAlignmentWithBlockedSubgoalStillHumanGate() throws Exception {
        // execution 说 done 但 alignment=low，通常走 checkpoint
        // 但 subgoal 有 blocked -> human_gate 优先
        assertEquals("human_gate", invokeResolveAction(
            "done", "done", "low", false, false, false,
            List.of("done", "blocked")
        ));
    }

    @Test
    void continueExecutionWithOpenSubgoalsStaysContinue() throws Exception {
        // execution 说 continue，subgoal 还有 open -> goal action = continue -> 继续走原逻辑
        assertEquals("continue", invokeResolveAction(
            "continue", "partially_done", "medium", false, false, false,
            List.of("done", "in_progress")
        ));
    }

    @SuppressWarnings("unchecked")
    private String invokeResolveAction(String executionAction,
                                       String completionStatus,
                                       String alignmentLevel,
                                       boolean needsContextReopen,
                                       boolean needsArchiveRetrieval,
                                       boolean needsExternalFactRefresh,
                                       Object subgoalStatus) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "resolveAction",
            String.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class,
            Object.class
        );
        method.setAccessible(true);
        return (String) method.invoke(
            graph,
            executionAction, completionStatus, alignmentLevel,
            needsContextReopen, needsArchiveRetrieval, needsExternalFactRefresh,
            subgoalStatus
        );
    }
}
