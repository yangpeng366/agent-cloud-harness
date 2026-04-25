package com.agentcloud.cli;

import com.agentcloud.engine.*;
import com.agentcloud.engine.memory.ContextReconstructor;
import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.PromptBasedJudgmentService;
import com.agentcloud.llm.LlmConfig;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.llm.OpenAiCompatibleClient;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.ActiveContextBuilder;
import com.agentcloud.server.NioHttpServer;
import com.agentcloud.store.*;
import com.agentcloud.tool.ListFilesTool;
import com.agentcloud.tool.ReadFileTool;
import com.agentcloud.tool.SearchTextTool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.agentcloud.tool.WriteFileTool;
import com.agentcloud.worker.DefaultWorkerExecutor;
import com.agentcloud.worker.ToolAwareWorkerExecutor;
import com.agentcloud.worker.WorkerExecutor;
import com.agentcloud.worker.WorkerExecutorRouter;
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
        LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
        ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

        // 引擎
        LearningMemoryService learningMemoryService = new LearningMemoryService(learningMemoryDao);
        WorkerRegistry workerRegistry = new WorkerRegistry();
        WorkerRouter workerRouter = new WorkerRouter(workerRegistry, learningMemoryService);
        PacketBuilder packetBuilder = new PacketBuilder(decisionDao, artifactDao, taskDao);
        ContextReconstructor reconstructor = new ContextReconstructor(taskDao, decisionDao, artifactDao, eventDao, relationDao);

        // 新增: Consolidation Layer
        ConsolidationService consolidation = new ConsolidationService(decisionDao, artifactDao, eventDao, checkpointDao, taskDao);
        RuntimeJudgmentService runtimeJudgmentService = new RuntimeJudgmentService();

        // Phase 1 新增: LLM Adapter Layer
        LlmConfig llmConfig = new LlmConfig();
        LlmClient llmClient = new OpenAiCompatibleClient(llmConfig);
        log.info("LLM adapter initialized. available={} model={}", llmConfig.available(), llmConfig.model());

        // Phase 2 新增: Active Context / Working Memory Layer
        ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );

        // Runtime Context Builder
        TaskRuntimeContextBuilder runtimeContextBuilder =
            new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, learningMemoryService
            );

        // Phase 1 新增: Judgment Layer（当前为占位实现，保留 RuntimeJudgmentService 给 TaskService 使用）
        JudgmentService judgmentService = new PromptBasedJudgmentService(llmClient);

        // Phase 1/2: Worker Execution Layer（tool-aware 第一版：单次工具计划 + 单次工具调用）
        ToolPolicy toolPolicy = new ToolPolicy();
        ToolRegistry toolRegistry = new ToolRegistry()
            .register(new ListFilesTool(workerRegistry, toolPolicy))
            .register(new ReadFileTool(workerRegistry, toolPolicy))
            .register(new SearchTextTool(workerRegistry, toolPolicy))
            .register(new WriteFileTool(workerRegistry, toolPolicy));

        WorkerExecutor defaultWorkerExecutor = new DefaultWorkerExecutor(llmClient);
        WorkerExecutor toolAwareWorkerExecutor = new ToolAwareWorkerExecutor(
            workerRegistry,
            toolRegistry,
            toolPolicy,
            toolInvocationDao,
            llmClient,
            defaultWorkerExecutor
        );
        WorkerExecutor workerExecutor = new WorkerExecutorRouter(
            workerRegistry, defaultWorkerExecutor, toolAwareWorkerExecutor
        );

        // 新增: Control Node Graph（注入 Phase 1 新组件）
        ControlNodeGraph controlGraph = new ControlNodeGraph(
            taskDao, eventDao, sessionDao, packetDao, workerRouter, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService,
            artifactDao, decisionDao, learningMemoryService
        );

        // 新增: Skill Registry + Router
        SkillRegistry skillRegistry = new SkillRegistry(skillDao);
        SkillRouter skillRouter = new SkillRouter(skillRegistry);

        SessionService sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao);
        TaskService taskService = new TaskService(
            taskDao, sessionDao, eventDao, packetDao, workerRouter, packetBuilder, controlGraph,
            runtimeJudgmentService, runtimeContextBuilder, consolidation, learningMemoryService, toolInvocationDao
        );

        // NIO HTTP Server (虚拟线程)
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));
        NioHttpServer server = new NioHttpServer(
            port, taskService, sessionService, workerRegistry, skillRegistry, consolidation, learningMemoryService
        );
        server.start();

        log.info("Control plane ready. API: http://localhost:{}", port);
        log.info("Web console: http://localhost:{}/console/", port);
        log.info("Endpoints:");
        log.info("  POST /api/v1/sessions       - create session");
        log.info("  GET  /api/v1/sessions       - list sessions");
        log.info("  GET  /api/v1/sessions/{id}/messages - list session messages");
        log.info("  POST /api/v1/sessions/{id}/messages - append session message");
        log.info("  POST /api/v1/tasks          - create task");
        log.info("  GET  /api/v1/tasks          - list tasks");
        log.info("  GET  /api/v1/tasks/{id}     - get task");
        log.info("  POST /api/v1/tasks/{id}/state      - update state");
        log.info("  GET  /api/v1/tasks/{id}/select_worker - preview routing");
        log.info("  GET  /api/v1/tasks/{id}/runtime_context - inspect working memory/runtime context");
        log.info("  GET  /api/v1/tasks/{id}/judgment_trace - inspect latest execution/completion judgment");
        log.info("  GET  /api/v1/tasks/{id}/live_flow - inspect aggregated live flow diagnostics");
        log.info("  GET  /api/v1/tasks/{id}/tool_trace - inspect recent tool invocation trace");
        log.info("  GET  /api/v1/tasks/{id}/pause      - pause task");
        log.info("  GET  /api/v1/tasks/{id}/resume     - resume task");
        log.info("  GET  /api/v1/tasks/{id}/continue   - continue task");
        log.info("  GET  /api/v1/tasks/{id}/escalate   - escalate task");
        log.info("  GET  /api/v1/tasks/{id}/refresh_packet - refresh resume packet");
        log.info("  GET  /api/v1/tasks/{id}/handoff_packet - preview handoff packet");
        log.info("  POST /api/v1/tasks/{id}/handoff    - handoff task");
        log.info("  GET  /api/v1/workers        - list workers");
        log.info("  GET  /api/v1/workers/{id}/readiness - check readiness");
        log.info("  GET  /api/v1/skills         - list skills");
        log.info("  POST /api/v1/skills         - register skill");
        log.info("  GET  /api/v1/skills/{id}/readiness - skill readiness");
        log.info("  GET  /api/v1/checkpoints/{taskId} - list checkpoints");
        log.info("  GET  /api/v1/learning_memories/{taskId} - list learning memories by task");
        log.info("  GET  /api/v1/learning_memories?memory_type=... - list learning memories by type");
        log.info("  GET  /api/v1/health         - health check");

        // 保持运行
        Thread.currentThread().join();
    }
}
