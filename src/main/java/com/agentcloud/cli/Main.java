package com.agentcloud.cli;

import com.agentcloud.engine.*;
import com.agentcloud.engine.memory.ContextReconstructor;
import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.server.NioHttpServer;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String home = System.getProperty("user.home");
        Path dbPath = Paths.get(home, ".agentcloud", "agent_cloud.db");

        log.info("=== Agent Cloud Harness v0.2.0 ===");
        log.info("DB path: {}", dbPath);

        // 初始化存储
        DatabaseManager db = new DatabaseManager(dbPath);

        // DAO
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        RelationDao relationDao = db.jdbi().onDemand(RelationDao.class);
        SkillDao skillDao = db.jdbi().onDemand(SkillDao.class);
        CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

        // 引擎
        WorkerRegistry workerRegistry = new WorkerRegistry();
        WorkerRouter workerRouter = new WorkerRouter(workerRegistry);
        PacketBuilder packetBuilder = new PacketBuilder(decisionDao, artifactDao, taskDao);
        ContextReconstructor reconstructor = new ContextReconstructor(taskDao, decisionDao, artifactDao, eventDao, relationDao);

        // 新增: Consolidation Layer
        ConsolidationService consolidation = new ConsolidationService(decisionDao, artifactDao, eventDao, checkpointDao, taskDao);

        // 新增: Control Node Graph
        ControlNodeGraph controlGraph = new ControlNodeGraph(taskDao, eventDao, sessionDao, workerRouter, packetBuilder, consolidation);

        // 新增: Skill Registry + Router
        SkillRegistry skillRegistry = new SkillRegistry(skillDao);
        SkillRouter skillRouter = new SkillRouter(skillRegistry);

        SessionService sessionService = new SessionService(sessionDao, taskDao);
        TaskService taskService = new TaskService(taskDao, sessionDao, eventDao, packetDao, workerRouter, packetBuilder, controlGraph);

        // NIO HTTP Server (虚拟线程)
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));
        NioHttpServer server = new NioHttpServer(port, taskService, sessionService, workerRegistry, skillRegistry, consolidation);
        server.start();

        log.info("Control plane ready. API: http://localhost:{}", port);
        log.info("Endpoints:");
        log.info("  POST /api/v1/sessions       - create session");
        log.info("  GET  /api/v1/sessions       - list sessions");
        log.info("  POST /api/v1/tasks          - create task");
        log.info("  GET  /api/v1/tasks          - list tasks");
        log.info("  GET  /api/v1/tasks/{id}     - get task");
        log.info("  POST /api/v1/tasks/{id}/state      - update state");
        log.info("  GET  /api/v1/tasks/{id}/pause      - pause task");
        log.info("  GET  /api/v1/tasks/{id}/resume     - resume task");
        log.info("  GET  /api/v1/tasks/{id}/continue   - continue task");
        log.info("  GET  /api/v1/tasks/{id}/escalate   - escalate task");
        log.info("  GET  /api/v1/tasks/{id}/refresh_packet - refresh resume packet");
        log.info("  POST /api/v1/tasks/{id}/handoff    - handoff task");
        log.info("  GET  /api/v1/workers        - list workers");
        log.info("  GET  /api/v1/workers/{id}/readiness - check readiness");
        log.info("  GET  /api/v1/skills         - list skills");
        log.info("  POST /api/v1/skills         - register skill");
        log.info("  GET  /api/v1/skills/{id}/readiness - skill readiness");
        log.info("  GET  /api/v1/checkpoints/{taskId} - list checkpoints");
        log.info("  GET  /api/v1/health         - health check");

        // 保持运行
        Thread.currentThread().join();
    }
}
