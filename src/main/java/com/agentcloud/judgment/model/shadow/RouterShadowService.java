package com.agentcloud.judgment.model.shadow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M1 Shadow 阶段 Router 旁路观察服务。
 * <p>
 * 调用 {@link RouterProvider#decide(JudgmentRequest)} → 包装 JudgmentRequest + JudgmentDecision →
 * 通过 {@link DecisionEventSink} 追加一条 DecisionEvent。**不**修改 WorkerRouter 既有路径，
 * 路由结果由调用方继续按 ACH 现有逻辑走；Router judgment 仅作可审计 shadow 旁路。
 * <p>
 * M2+ 再讨论把 decision 写回 WorkerExecutionEnvelope.routing_decision_id。
 */
public class RouterShadowService {

    private final RouterProvider provider;
    private final DecisionEventSink sink;
    private final String modelRef;

    public RouterShadowService(RouterProvider provider, DecisionEventSink sink) {
        this(provider, sink, provider.providerRef());
    }

    public RouterShadowService(RouterProvider provider, DecisionEventSink sink, String modelRef) {
        if (provider == null) throw new IllegalArgumentException("provider required");
        if (sink == null) throw new IllegalArgumentException("sink required");
        this.provider = provider;
        this.sink = sink;
        this.modelRef = modelRef == null || modelRef.isBlank() ? "unknown" : modelRef;
    }

    /**
     * 触发一次 Router 旁路判断并写 DecisionEvent。
     *
     * @param runId  所属 Run
     * @param turnId 所属 Turn
     * @param taskId 所属 Task（可空）
     * @param input  已脱敏的最小事实集（参见 JudgmentRequest.normalizedInput 字段）
     * @return Provider 给出的 JudgmentDecision；调用方继续按 ACH 原路径走
     */
    public JudgmentDecision observe(String runId, String turnId, String taskId,
                                    String subjectRef, Map<String, Object> input) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId required");

        String judgmentId = UUID.randomUUID().toString();
        Map<String, Object> safeInput = input == null ? Map.of() : input;

        Map<String, String> questions = new LinkedHashMap<>();
        questions.put("difficulty", "score");
        questions.put("side_effect_risk", "score");
        questions.put("needs_browser", "bool");
        questions.put("budget_exhausted", "bool");
        questions.put("human_requested", "bool");
        questions.put("high_stakes", "bool");

        JudgmentRequest req = new JudgmentRequest(
                judgmentId,
                runId,
                turnId,
                JudgmentKind.ROUTER,
                subjectRef,
                safeInput,
                "shadow-default",
                questions,
                200L
        );

        JudgmentDecision decision = provider.decide(req);

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("subject_ref", subjectRef == null ? "" : subjectRef);
        digest.put("task_id", taskId == null ? "" : taskId);
        digest.put("input_keys", safeInput.keySet());

        DecisionEvent event = new DecisionEvent(
                judgmentId,
                java.time.Instant.now(),
                runId,
                turnId,
                taskId,
                subjectRef,
                "router",
                decision.thresholdSetId(),
                decision.thresholdSetId(),
                decision.scores(),
                decision.confidence(),
                decision.action(),
                decision.degraded(),
                modelRef,
                decision.latencyMs(),
                decision.costUnits(),
                decision.reasonCodes(),
                digest,
                "record_only"
        );

        sink.append(event);
        return decision;
    }
}