package com.agentcloud.judgment;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.runtime.RuntimeCognitionSurfaceAssembler;
import com.agentcloud.runtime.RuntimeFactPromptFormatter;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.context.MountedContextPromptMetrics;
import com.agentcloud.runtime.context.MountedContextPromptRenderResult;
import com.agentcloud.runtime.context.MountedContextPromptRenderer;
import com.agentcloud.runtime.context.PromptRenderingMode;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 基于 Prompt + LLM 的最小 Judgment 实现。
 * 保留规则兜底，确保未配置 LLM 时流程不中断。
 */
public class PromptBasedJudgmentService implements JudgmentService {
    private static final Logger log = LoggerFactory.getLogger(PromptBasedJudgmentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmClient llmClient;
    private final MountedContextPromptRenderer mountedContextPromptRenderer;
    private final RuntimeFactPromptFormatter runtimeFactPromptFormatter;

    public PromptBasedJudgmentService(LlmClient llmClient) {
        this(llmClient, new MountedContextPromptRenderer(), new RuntimeFactPromptFormatter());
    }

    PromptBasedJudgmentService(LlmClient llmClient, MountedContextPromptRenderer mountedContextPromptRenderer) {
        this(llmClient, mountedContextPromptRenderer, new RuntimeFactPromptFormatter());
    }

    PromptBasedJudgmentService(LlmClient llmClient,
                               MountedContextPromptRenderer mountedContextPromptRenderer,
                               RuntimeFactPromptFormatter runtimeFactPromptFormatter) {
        this.llmClient = llmClient;
        this.mountedContextPromptRenderer = mountedContextPromptRenderer == null
            ? new MountedContextPromptRenderer()
            : mountedContextPromptRenderer;
        this.runtimeFactPromptFormatter = runtimeFactPromptFormatter == null
            ? new RuntimeFactPromptFormatter(new RuntimeCognitionSurfaceAssembler())
            : runtimeFactPromptFormatter;
    }

    @Override
    public ExecutionDecision judgeExecution(JudgmentContext context) {
        String taskId = context.task().id();
        if (!isLlmAvailable()) {
            log.debug("judgeExecution fallback for task={}", taskId);
            return defaultExecutionDecision();
        }

        try {
            String system = "You are a task execution controller. "
                + "Based on the task context, decide the next action. "
                + "Respond with a JSON object containing exactly these fields: "
                + "action (continue|wait|checkpoint|handoff|escalate|done), reason (string), "
                + "next_step (string), needs_checkpoint (boolean), needs_context_reopen (boolean), "
                + "evidence_gap_detected (boolean), needs_archive_retrieval (boolean), "
                + "needs_external_fact_refresh (boolean), needs_human (boolean), "
                + "target_worker (string). No markdown, no extra text.";

            JudgmentPrompt prompt = buildExecutionPrompt(context);
            String raw = llmClient.review(system, prompt.prompt()).trim();

            JsonNode json = MAPPER.readTree(raw);
            String action = parseAction(json.path("action").asText("continue"));
            String reason = json.path("reason").asText("");
            String nextStep = json.path("next_step").asText("");
            boolean needsCheckpoint = json.path("needs_checkpoint").asBoolean("checkpoint".equals(action));
            boolean needsContextReopen = json.path("needs_context_reopen").asBoolean(false);
            boolean evidenceGapDetected = json.path("evidence_gap_detected").asBoolean(needsContextReopen);
            boolean needsArchiveRetrieval = json.path("needs_archive_retrieval").asBoolean(needsContextReopen);
            boolean needsExternalFactRefresh = json.path("needs_external_fact_refresh")
                .asBoolean(needsArchiveRetrieval);
            boolean needsHuman = json.path("needs_human").asBoolean("escalate".equals(action) || "wait".equals(action));
            String targetWorker = json.path("target_worker").asText(resolveTargetWorker(context));

            log.info("judgeExecution task={} action={} promptMode={} mountedRenderUsed={} mountedInjected={} mountedActiveCount={} mountedEvidenceCount={} mountedArchiveCount={} raw={}",
                taskId,
                action,
                prompt.metrics().promptMode(),
                prompt.metrics().mountedRenderUsed(),
                prompt.metrics().mountedInjected(),
                prompt.metrics().activeCount(),
                prompt.metrics().evidenceCount(),
                prompt.metrics().archiveCount(),
                raw);
            return new ExecutionDecision(action, reason, nextStep, needsCheckpoint, needsContextReopen,
                evidenceGapDetected, needsArchiveRetrieval, needsExternalFactRefresh, needsHuman,
                "handoff".equals(action) ? targetWorker : null);
        } catch (Exception e) {
            log.error("judgeExecution failed for task={}, fallback to continue", taskId, e);
            return defaultExecutionDecision();
        }
    }

    @Override
    public CompletionDecision judgeCompletion(JudgmentContext context) {
        String taskId = context.task().id();
        if (!isLlmAvailable()) {
            log.debug("judgeCompletion fallback for task={}", taskId);
            return defaultCompletionDecision();
        }

        try {
            String system = "You are a completion evaluator. "
                + "Evaluate whether the task output satisfies the goal. "
                + "Respond with a JSON object containing exactly these fields: "
                + "status (done|partially_done|misaligned|needs_clarification), "
                + "alignment_level (high|medium|low), reason (string), suggested_next_action (string). "
                + "No markdown, no extra text.";

            JudgmentPrompt prompt = buildCompletionPrompt(context);
            String raw = llmClient.review(system, prompt.prompt()).trim();

            JsonNode json = MAPPER.readTree(raw);
            String status = parseCompletionStatus(json.path("status").asText("partially_done"));
            String alignment = json.path("alignment_level").asText(
                json.path("alignment").asText("medium")
            );
            String reason = json.path("reason").asText("");
            String suggestedNextAction = json.path("suggested_next_action").asText("");

            log.info("judgeCompletion task={} status={} alignment={} promptMode={} mountedRenderUsed={} mountedInjected={} mountedActiveCount={} mountedEvidenceCount={} mountedArchiveCount={}",
                taskId,
                status,
                alignment,
                prompt.metrics().promptMode(),
                prompt.metrics().mountedRenderUsed(),
                prompt.metrics().mountedInjected(),
                prompt.metrics().activeCount(),
                prompt.metrics().evidenceCount(),
                prompt.metrics().archiveCount());
            return new CompletionDecision(status, alignment, reason, suggestedNextAction);
        } catch (Exception e) {
            log.error("judgeCompletion failed for task={}, fallback to incomplete", taskId, e);
            return defaultCompletionDecision();
        }
    }

    private boolean isLlmAvailable() {
        // 简单启发：如果上次调用返回空，这里仍尝试；真正不可用由 chat 内部处理
        return true;
    }

    private ExecutionDecision defaultExecutionDecision() {
        return new ExecutionDecision("continue", "default fallback", null, false, false, null);
    }

    private CompletionDecision defaultCompletionDecision() {
        return new CompletionDecision("partially_done", "medium", "default fallback", null);
    }

    private JudgmentPrompt buildExecutionPrompt(JudgmentContext context) {
        var t = context.task();
        PromptRenderingMode renderingMode = PromptRenderingMode.resolve(context.runtimeContext());
        MountedPromptResolution mountedPrompt = resolveMountedPrompt(context, renderingMode);
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(t.title()).append("\n");
        if (t.goal() != null) sb.append("Goal: ").append(t.goal()).append("\n");
        if (t.nextStep() != null) sb.append("Next Step: ").append(t.nextStep()).append("\n");
        sb.append("Status: ").append(t.status()).append("\n");
        sb.append("Execution Rules:\n");
        sb.append("- Treat the latest worker metadata below as higher-priority structured evidence than free-form text when they disagree.\n");
        sb.append("- If latest worker metadata shows the current round still requires a grounded file write, do not choose done.\n");
        sb.append("- If latest worker metadata says missing_required_current_round_write=true, the runtime must not choose done.\n");
        sb.append("- If the current mounted or active evidence is insufficient and the runtime should reopen bounded archive handles before the next round, set needs_context_reopen=true.\n");
        sb.append("- If the insufficiency is specifically an evidence coverage problem, set evidence_gap_detected=true.\n");
        sb.append("- If the next round should explicitly retrieve archived evidence handles or raw traces, set needs_archive_retrieval=true.\n");
        sb.append("- If the next round should refresh facts from outside the current archived/runtime evidence, set needs_external_fact_refresh=true.\n");
        appendMountedContext(sb, mountedPrompt);
        if (context.runtimeContext() != null
            && context.runtimeContext().activeContext() != null
            && !context.runtimeContext().activeContext().synthesizedContext().isBlank()) {
            sb.append("Active Context:\n").append(context.runtimeContext().activeContext().synthesizedContext()).append("\n");
        }
        if (context.workerOutput() != null && !context.workerOutput().isBlank()) {
            sb.append("Latest Worker Output: ").append(context.workerOutput()).append("\n");
        }
        runtimeFactPromptFormatter.append(sb, context.runtimeFactSet());
        appendLatestWorkerMetadata(sb, context.latestWorkerMetadata());
        sb.append("What should the runtime do next?");
        return new JudgmentPrompt(sb.toString(), mountedPrompt.metrics());
    }

    private JudgmentPrompt buildCompletionPrompt(JudgmentContext context) {
        var t = context.task();
        PromptRenderingMode renderingMode = PromptRenderingMode.resolve(context.runtimeContext());
        MountedPromptResolution mountedPrompt = resolveMountedPrompt(context, renderingMode);
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(t.title()).append("\n");
        if (t.goal() != null) sb.append("Goal: ").append(t.goal()).append("\n");
        sb.append("Evaluation Rules:\n");
        sb.append("- Treat the latest worker output below as the authoritative evidence for the current round.\n");
        sb.append("- Treat the latest worker metadata below as higher-priority structured evidence than free-form text when they disagree.\n");
        sb.append("- Older artifact summaries inside Active Context may describe earlier incomplete rounds; do not treat them as contradictions if the latest worker output and latest task state indicate the task is now complete.\n");
        sb.append("- Optional post-run QA, optional verification, or 'mark task complete' should not by themselves force partially_done.\n");
        sb.append("- If latest worker metadata says more_declared_rounds_remain=true, do not mark the task done.\n");
        sb.append("- If latest worker metadata says missing_required_current_round_write=true, do not mark the task done.\n");
        sb.append("- For grounded-output tasks, do not infer that the current round performed the final grounded write unless latest worker metadata explicitly shows a grounded write tool together with grounded_output_present=true, or clearly shows the expected output already exists and the current round is only a verification/closeout step.\n");
        appendMountedContext(sb, mountedPrompt);
        if (context.runtimeContext() != null
            && context.runtimeContext().activeContext() != null
            && !context.runtimeContext().activeContext().synthesizedContext().isBlank()) {
            sb.append("Active Context:\n").append(context.runtimeContext().activeContext().synthesizedContext()).append("\n");
        }
        if (context.workerOutput() != null && !context.workerOutput().isBlank()) {
            sb.append("Latest Worker Output (current round): ").append(context.workerOutput()).append("\n");
        }
        runtimeFactPromptFormatter.append(sb, context.runtimeFactSet());
        appendLatestWorkerMetadata(sb, context.latestWorkerMetadata());
        sb.append("Is the task sufficiently complete and aligned with the goal?");
        return new JudgmentPrompt(sb.toString(), mountedPrompt.metrics());
    }

    private MountedPromptResolution resolveMountedPrompt(JudgmentContext context,
                                                         PromptRenderingMode renderingMode) {
        MountedContextPromptRenderResult renderResult = renderingMode != null && renderingMode.shouldRenderMountedPrompt()
            ? mountedContextPromptRenderer.renderResult(context != null ? context.runtimeContext() : null)
            : MountedContextPromptRenderResult.empty();
        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(
            context != null ? context.runtimeContext() : null,
            renderingMode,
            renderResult
        );
        return new MountedPromptResolution(renderResult, metrics);
    }

    private void appendMountedContext(StringBuilder sb, MountedPromptResolution mountedPrompt) {
        if (mountedPrompt == null || mountedPrompt.metrics() == null || !mountedPrompt.metrics().mountedInjected()) {
            return;
        }
        String mountedContextPrompt = mountedPrompt.renderResult() == null ? "" : mountedPrompt.renderResult().prompt();
        if (mountedContextPrompt.isBlank()) {
            return;
        }
        sb.append(mountedContextPrompt);
    }

    private void appendLatestWorkerMetadata(StringBuilder sb, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        sb.append("Latest Worker Metadata:\n");
        appendMetadataLine(sb, metadata, "selected_worker");
        appendMetadataLine(sb, metadata, "selected_worker_type");
        appendMetadataLine(sb, metadata, "selected_model_tier");
        appendMetadataLine(sb, metadata, "execution_role");
        appendMetadataLine(sb, metadata, "why_selected");
        appendMetadataLine(sb, metadata, "preferred_worker_hint");
        appendMetadataLine(sb, metadata, "learning_hint_applied");
        appendMetadataLine(sb, metadata, "fallback_reason");
        appendMetadataLine(sb, metadata, "route_source");
        appendMetadataLine(sb, metadata, "model_mode");
        appendMetadataLine(sb, metadata, "orchestration_stage");
        appendMetadataLine(sb, metadata, "prompt_mode");
        appendMetadataLine(sb, metadata, "mounted_context_rendered");
        appendMetadataLine(sb, metadata, "mounted_render_used");
        appendMetadataLine(sb, metadata, "mounted_context_injected");
        appendMetadataLine(sb, metadata, "mounted_context_panel_count");
        appendMetadataLine(sb, metadata, "mounted_context_non_empty_panel_count");
        appendMetadataLine(sb, metadata, "mounted_context_selection_trace_count");
        appendMetadataLine(sb, metadata, "mounted_pinned_count");
        appendMetadataLine(sb, metadata, "mounted_active_count");
        appendMetadataLine(sb, metadata, "mounted_ancestor_count");
        appendMetadataLine(sb, metadata, "mounted_sibling_count");
        appendMetadataLine(sb, metadata, "mounted_evidence_count");
        appendMetadataLine(sb, metadata, "mounted_index_count");
        appendMetadataLine(sb, metadata, "mounted_archive_count");
        appendMetadataLine(sb, metadata, "evidence_gap_detected");
        appendMetadataLine(sb, metadata, "needs_archive_retrieval");
        appendMetadataLine(sb, metadata, "needs_external_fact_refresh");
        appendMetadataLine(sb, metadata, "planner_worker");
        appendMetadataLine(sb, metadata, "executor_worker");
        appendMetadataLine(sb, metadata, "target_worker");
        appendMetadataLine(sb, metadata, "tool_aware_executor");
        appendMetadataLine(sb, metadata, "tool_execution_mode");
        appendMetadataLine(sb, metadata, "tool_name");
        appendMetadataLine(sb, metadata, "tool_success");
        appendMetadataLine(sb, metadata, "tool_summary");
        appendMetadataLine(sb, metadata, "tool_plan_reason");
        appendMetadataLine(sb, metadata, "auto_write_generation_mode");
        appendMetadataLine(sb, metadata, "auto_write_generation_error");
        appendMetadataLine(sb, metadata, "output_file_required");
        appendMetadataLine(sb, metadata, "output_file_path");
        appendMetadataLine(sb, metadata, "output_file_exists");
        appendMetadataLine(sb, metadata, "output_dir_required");
        appendMetadataLine(sb, metadata, "output_dir_path");
        appendMetadataLine(sb, metadata, "output_dir_exists");
        appendMetadataLine(sb, metadata, "output_dir_entry_count");
        appendMetadataLine(sb, metadata, "file_backed_artifact");
        appendMetadataLine(sb, metadata, "directory_backed_artifact");
        appendMetadataLine(sb, metadata, "grounded_output_present");
        appendMetadataLine(sb, metadata, "grounding_mode");
        appendMetadataLine(sb, metadata, "more_declared_rounds_remain");
        appendMetadataLine(sb, metadata, "current_round_requires_write");
        appendMetadataLine(sb, metadata, "missing_required_current_round_write");
        appendMetadataLine(sb, metadata, "current_round_instruction");
        appendMetadataLine(sb, metadata, "next_round_instruction");
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
    }

    private void appendMetadataLine(StringBuilder sb, Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return;
        }
        if (text.length() > 400) {
            text = text.substring(0, 400) + "...";
        }
        sb.append("- ").append(key).append(": ").append(text).append("\n");
    }

    private void appendBullet(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return;
        }
        sb.append("- ").append(key).append(": ").append(text).append("\n");
    }

    private String resolveTargetWorker(JudgmentContext context) {
        if (context.task().metadata() == null) {
            return null;
        }
        Object targetWorker = context.task().metadata().get("target_worker");
        return targetWorker != null ? targetWorker.toString() : null;
    }

    private String parseAction(String raw) {
        raw = raw == null ? "" : raw.trim().toLowerCase();
        return switch (raw) {
            case "continue", "wait", "checkpoint", "handoff", "escalate", "done" -> raw;
            default -> {
                if (raw.contains("done")) yield "done";
                if (raw.contains("escalate")) yield "escalate";
                if (raw.contains("handoff")) yield "handoff";
                if (raw.contains("checkpoint")) yield "checkpoint";
                if (raw.contains("wait")) yield "wait";
                yield "continue";
            }
        };
    }

    private String parseCompletionStatus(String raw) {
        raw = raw == null ? "" : raw.trim().toLowerCase();
        return switch (raw) {
            case "done", "partially_done", "misaligned", "needs_clarification" -> raw;
            case "complete" -> "done";
            case "partial", "incomplete" -> "partially_done";
            default -> raw.contains("clarification") ? "needs_clarification"
                : raw.contains("align") ? "misaligned"
                : raw.contains("done") || raw.contains("complete") ? "done"
                : "partially_done";
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record JudgmentPrompt(String prompt, MountedContextPromptMetrics metrics) {}

    private record MountedPromptResolution(MountedContextPromptRenderResult renderResult,
                                           MountedContextPromptMetrics metrics) {}
}
