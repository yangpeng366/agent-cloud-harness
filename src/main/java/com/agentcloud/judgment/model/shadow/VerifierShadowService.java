package com.agentcloud.judgment.model.shadow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M3 Verifier 阶段旁路观察服务（按第 50 号笔记的 M 编号）。
 * <p>
 * 与 RouterShadowService / ToolGateShadowService 同套 Provider-agnostic 契约；
 * kind=VERIFIER，action 取值 accept / retry_once / handoff。
 * <p>
 * 输入来自执行结果（WorkerExecutionResult 摘要），normalize 成最小字段：
 *   quality / grounded / completion（由调用方计算并传入，本类不做语义判别）
 *   needs_human / high_stakes / has_errors
 * <p>
 * Provider 给 JudgmentDecision → 写 DecisionEvent(JSONL, shadowMode=record_only) →
 * 返回 decision；调用方继续按 ACH 既有路径走 retry_once 或 accept。
 */
public class VerifierShadowService {

    private final VerifierProvider provider;
    private final DecisionEventSink sink;

    public VerifierShadowService(VerifierProvider provider, DecisionEventSink sink) {
        if (provider == null) throw new IllegalArgumentException("provider required");
        if (sink == null) throw new IllegalArgumentException("sink required");
        this.provider = provider;
        this.sink = sink;
    }

    /**
     * 观察一次 verifier 判断并写 DecisionEvent。
     *
     * @param runId        所属 Run
     * @param turnId       所属 Turn（executionId 形式）
     * @param taskId       所属 Task
     * @param subjectRef   通常是 workerId 或 artifact fingerprint
     * @param quality      0~1，答案是否覆盖请求
     * @param grounded     0~1，事实是否由本轮证据支持
     * @param completion   0~1，缺口程度（0=完成、1=完全缺失）
     * @param highStakes   是否高风险任务（部署 / 数据修改 / 外部发布）
     * @param needsHuman   是否强制人工
     * @return Provider 给出的 JudgmentDecision；调用方继续按 ACH 原路径走
     */
    public JudgmentDecision observe(String runId, String turnId, String taskId, String subjectRef,
                                    double quality, double grounded, double completion,
                                    boolean highStakes, boolean needsHuman) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId required");

        String judgmentId = UUID.randomUUID().toString();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("quality", quality);
        input.put("grounded", grounded);
        input.put("completion", completion);
        input.put("high_stakes", highStakes);
        input.put("needs_human", needsHuman);
        input.put("has_errors", false);

        Map<String, String> questions = new LinkedHashMap<>();
        questions.put("quality", "score");
        questions.put("grounded", "score");
        questions.put("completion", "score");
        questions.put("needs_human", "bool");
        questions.put("high_stakes", "bool");

        JudgmentRequest req = new JudgmentRequest(
                judgmentId,
                runId,
                turnId,
                JudgmentKind.VERIFIER,
                subjectRef,
                input,
                "shadow-default",
                questions,
                200L
        );

        JudgmentDecision decision;
        try {
            decision = provider.decide(req);
        } catch (RuntimeException e) {
            decision = JudgmentDecision.fallback(judgmentId, "handoff",
                    "verification_unavailable:" + e.getClass().getSimpleName(), provider.providerRef(), 0L);
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("subject_ref", subjectRef == null ? "" : subjectRef);
        digest.put("task_id", taskId == null ? "" : taskId);
        digest.put("input_keys", input.keySet());

        DecisionEvent event = new DecisionEvent(
                judgmentId,
                java.time.Instant.now(),
                runId,
                turnId,
                taskId == null ? "" : taskId,
                subjectRef == null ? "" : subjectRef,
                "verifier",
                decision.thresholdSetId(),
                decision.thresholdSetId(),
                decision.scores(),
                decision.confidence(),
                decision.action(),
                decision.degraded(),
                provider.providerRef(),
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