package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime Control Node Graph
 * 6 个最小控制节点：Intake → Scheduler → Continue → [Packet / Human Gate / Handoff]
 */
public class ControlNodeGraph {
    private static final Logger log = LoggerFactory.getLogger(ControlNodeGraph.class);
    private final TaskDao taskDao;
    private final EventDao eventDao;
    private final SessionDao sessionDao;
    private final WorkerRouter router;
    private final PacketBuilder packetBuilder;
    private final ConsolidationService consolidation;

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            WorkerRouter router, PacketBuilder packetBuilder, ConsolidationService consolidation) {
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.sessionDao = sessionDao;
        this.router = router;
        this.packetBuilder = packetBuilder;
        this.consolidation = consolidation;
    }

    public Task enter(Task task) {
        String node = task.controlNode();
        if (node == null) node = "intake";
        return switch (node) {
            case "intake" -> intakeNode(task);
            case "scheduler" -> schedulerNode(task);
            case "continue" -> continueNode(task);
            case "packet" -> packetNode(task);
            case "human_gate" -> humanGateNode(task);
            case "handoff" -> handoffNode(task);
            default -> {
                log.warn("Unknown control node: {}, task={}", node, task.id());
                yield task;
            }
        };
    }

    // === Intake Node ===
    private Task intakeNode(Task task) {
        log.info("[Intake] task={}", task.id());
        emitEvent(task, "node_intake", "Enter intake node");
        Task moved = task.withControlNode("scheduler");
        taskDao.updateState(moved);
        return schedulerNode(moved);
    }

    // === Scheduler Node ===
    private Task schedulerNode(Task task) {
        log.info("[Scheduler] task={}", task.id());
        emitEvent(task, "node_scheduler", "Scheduling task");

        // 自动路由 worker（如果还没分配）
        if (task.assignedWorker() == null || task.assignedWorker().isBlank()) {
            WorkerRouter.RouteResult route = router.selectWorker(task);
            if (route.selectedWorker() != null) {
                task = task.withAssignedWorker(route.selectedWorker());
                log.info("[Scheduler] task={} routed to worker={}", task.id(), route.selectedWorker());
            }
        }

        Task moved = task.withControlNode("continue");
        taskDao.updateState(moved);
        return continueNode(moved);
    }

    // === Continue Node ===
    private Task continueNode(Task task) {
        log.info("[Continue] task={} status={}", task.id(), task.status());
        emitEvent(task, "node_continue", "Continue evaluation");

        // 如果任务已被暂停/完成/失败，不走继续逻辑
        if (List.of("paused", "waiting", "done", "failed").contains(task.status())) {
            log.info("[Continue] task={} is {}, halting continue loop", task.id(), task.status());
            return task;
        }

        // 默认：继续推进一轮
        Task moved = task.withControlNode("continue");
        taskDao.updateState(moved);
        return moved;
    }

    // === Packet Node ===
    private Task packetNode(Task task) {
        log.info("[Packet] task={}", task.id());
        emitEvent(task, "node_packet", "Generating packet before transition");

        Session session = sessionDao.findById(task.sessionId()).orElse(null);
        if (session != null) {
            packetBuilder.buildResumePacket(task, session);
        }

        // 在关键转移前触发 consolidation
        consolidation.consolidate(task, "pause_before");

        Task moved = task.withControlNode("scheduler");
        taskDao.updateState(moved);
        return schedulerNode(moved);
    }

    // === Human Gate Node ===
    private Task humanGateNode(Task task) {
        log.info("[HumanGate] task={}", task.id());
        emitEvent(task, "node_human_gate", "Awaiting human confirmation");

        Task moved = task.withStatus("waiting_human").withControlNode("human_gate");
        taskDao.updateState(moved);
        return moved;
    }

    // === Handoff Node ===
    private Task handoffNode(Task task) {
        log.info("[Handoff] task={}", task.id());
        emitEvent(task, "node_handoff", "Executing handoff");

        consolidation.consolidate(task, "handoff_before");

        Task moved = task.withControlNode("scheduler");
        taskDao.updateState(moved);
        return schedulerNode(moved);
    }

    // === 外部触发方法 ===

    public Task triggerPause(Task task, String reason) {
        log.info("[Trigger] pause task={} reason={}", task.id(), reason);
        Task t = task.withStatus("paused").withControlNode("packet").withWaitingReason(reason);
        taskDao.updateState(t);
        return packetNode(t);
    }

    public Task triggerEscalate(Task task, String reason) {
        log.info("[Trigger] escalate task={} reason={}", task.id(), reason);
        Task t = task.withStatus("waiting_human").withControlNode("human_gate").withWaitingReason(reason);
        taskDao.updateState(t);
        return humanGateNode(t);
    }

    public Task triggerHandoff(Task task, String targetWorker) {
        log.info("[Trigger] handoff task={} to worker={}", task.id(), targetWorker);
        Task t = task.withAssignedWorker(targetWorker).withControlNode("handoff");
        taskDao.updateState(t);
        return handoffNode(t);
    }

    public Task triggerResume(Task task) {
        log.info("[Trigger] resume task={}", task.id());
        Task t = task.withStatus("active").withControlNode("scheduler").withWaitingReason(null);
        taskDao.updateState(t);
        return schedulerNode(t);
    }

    public Task triggerHalt(Task task, String reason) {
        log.info("[Trigger] halt task={} reason={}", task.id(), reason);
        Task t = task.withStatus("done").withControlNode("end").withWaitingReason(reason);
        taskDao.updateState(t);
        return t;
    }

    private void emitEvent(Task task, String eventType, String summary) {
        eventDao.insert(new Event(IdGenerator.newId("evt"), task.sessionId(), task.id(), Instant.now(),
            eventType, "control_node", null, summary, Map.of("control_node", eventType)));
    }
}
