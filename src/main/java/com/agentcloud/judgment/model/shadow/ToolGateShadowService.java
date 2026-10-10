package com.agentcloud.judgment.model.shadow;

import com.agentcloud.model.ToolInvocationRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M3 Tool Gate 阶段旁路观察服务。
 * <p>
 * 与 RouterShadowService 同套 Provider-agnostic 契约，但 kind=TOOL_GATE，
 * 从 ToolInvocationRecord 抽取 toolName / arguments / touchedPaths / envKeys 等
 * 最小字段构造 JudgmentRequest.normalizedInput。Provider 给出 allow / block /
 * approval_required 之一，并写一条 DecisionEvent JSONL。
 * <p>
 * 真正接入 WorkerExecutor 在 M3 集成阶段（M4 候选）；本类只负责"事后观察 + 评分"。
 */
public class ToolGateShadowService {

    private final ToolGateProvider provider;
    private final DecisionEventSink sink;

    public ToolGateShadowService(ToolGateProvider provider, DecisionEventSink sink) {
        if (provider == null) throw new IllegalArgumentException("provider required");
        if (sink == null) throw new IllegalArgumentException("sink required");
        this.provider = provider;
        this.sink = sink;
    }

    /**
     * 观察一次工具调用并写 DecisionEvent。
     *
     * @param record ToolInvocationRecord（参见 com.agentcloud.model.ToolInvocationRecord）
     * @return Provider 给出的 JudgmentDecision；调用方继续按 ACH 原 ToolPolicy 路径走
     */
    public JudgmentDecision observe(ToolInvocationRecord record) {
        if (record == null) throw new IllegalArgumentException("record required");
        if (record.id() == null || record.id().isBlank()) {
            throw new IllegalArgumentException("record.id required");
        }

        String judgmentId = UUID.randomUUID().toString();
        String runId = record.sessionId() == null ? "" : record.sessionId();
        String turnId = record.executionId() == null ? "" : record.executionId();
        String taskId = record.taskId() == null ? "" : record.taskId();
        String subjectRef = "tool:" + (record.toolName() == null ? "" : record.toolName());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tool_name", record.toolName());
        input.put("args_blob", flattenArgs(record.arguments()));
        input.put("env_keys", extractEnvKeys(record.arguments()));
        input.put("touched_paths", record.touchedPaths());
        input.put("destructive_side_effect", isDestructiveToolName(record.toolName()));
        input.put("out_of_workspace", false);
        input.put("reversible", true);
        input.put("needs_human", false);

        Map<String, String> questions = new LinkedHashMap<>();
        questions.put("destructive_side_effect", "bool");
        questions.put("out_of_workspace", "bool");
        questions.put("reversible", "bool");
        questions.put("needs_human", "bool");

        JudgmentRequest req = new JudgmentRequest(
                judgmentId,
                runId,
                turnId,
                JudgmentKind.TOOL_GATE,
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
            decision = JudgmentDecision.fallback(judgmentId, "approval_required",
                    "provider_error:" + e.getClass().getSimpleName(), provider.providerRef(), 0L);
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("subject_ref", subjectRef);
        digest.put("tool_invocation_id", record.id());
        digest.put("input_keys", input.keySet());

        DecisionEvent event = new DecisionEvent(
                judgmentId,
                java.time.Instant.now(),
                runId,
                turnId,
                taskId,
                subjectRef,
                "tool_gate",
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

    private static String flattenArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.valueOf(e.getKey())).append('=').append(String.valueOf(e.getValue()));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractEnvKeys(Map<String, Object> args) {
        if (args == null) return List.of();
        Object env = args.get("env");
        if (env instanceof Map<?, ?> m) {
            return m.keySet().stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static boolean isDestructiveToolName(String toolName) {
        if (toolName == null) return false;
        String lower = toolName.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("delete_file") || lower.equals("rm") || lower.equals("drop_table")
                || lower.equals("wipe_workspace") || lower.equals("reset_session");
    }
}