package com.agentcloud.worker;

import com.agentcloud.engine.IdGenerator;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.store.JsonMapper;
import com.agentcloud.store.ToolInvocationDao;
import com.agentcloud.tool.Tool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.agentcloud.tool.ToolRequest;
import com.agentcloud.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具感知执行器。
 * 第一版采用双阶段协议：先让 LLM 决定是否需要单次工具调用，再基于工具结果收敛最终执行结果。
 */
public class ToolAwareWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolAwareWorkerExecutor.class);
    private static final ObjectMapper MAPPER = JsonMapper.MAPPER;
    private static final Pattern ROUND_INSTRUCTION_PATTERN =
        Pattern.compile("(?im)^Round\\s+(\\d+)\\s*:\\s*(.+)$");

    private final WorkerRegistry workerRegistry;
    private final ToolRegistry toolRegistry;
    private final ToolPolicy toolPolicy;
    private final ToolInvocationDao toolInvocationDao;
    private final LlmClient llmClient;
    private final WorkerExecutor fallbackExecutor;

    public ToolAwareWorkerExecutor(WorkerRegistry workerRegistry,
                                   ToolRegistry toolRegistry,
                                   ToolPolicy toolPolicy,
                                   ToolInvocationDao toolInvocationDao,
                                   LlmClient llmClient,
                                   WorkerExecutor fallbackExecutor) {
        this.workerRegistry = workerRegistry;
        this.toolRegistry = toolRegistry;
        this.toolPolicy = toolPolicy;
        this.toolInvocationDao = toolInvocationDao;
        this.llmClient = llmClient;
        this.fallbackExecutor = fallbackExecutor;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        Worker worker = workerRegistry.get(workerId);
        if (worker == null) {
            return fallbackExecutor.executeOneRound(context, workerId);
        }

        long startMs = System.currentTimeMillis();
        TaskToolState toolStateBefore = inspectTaskToolState(context);
        boolean currentRoundRequiresWrite = requiresCurrentRoundWrite(toolStateBefore);
        if (currentRoundRequiresWrite && !instructionSuggestsFileRead(toolStateBefore.currentRoundInstruction())) {
            log.info("Current round requires grounded write, skipping planning and going direct auto-write. task={} worker={}",
                context.task().id(), worker.workerId());
            return autoGroundedWriteFallback(
                context,
                worker,
                toolStateBefore,
                new ToolPlan(true, "write_file", Map.of(), "direct_required_write_round", ""),
                startMs
            );
        }

        ToolPlan plan = planTool(context, worker, toolStateBefore);
        if (!plan.needsTool() || plan.toolName().isBlank()) {
            if (currentRoundRequiresWrite) {
                return autoGroundedWriteFallback(context, worker, toolStateBefore, plan, startMs);
            }
            return delegateWithMetadata(
                context,
                workerId,
                "planned_no_tool_fallback",
                Map.of(
                    "tool_aware_executor", true,
                    "tool_plan_reason", plan.reason(),
                    "tool_plan_raw", truncate(plan.rawResponse(), 1200)
                )
            );
        }

        ToolExecutionOutcome outcome = invokeTool(context, worker, plan);
        long totalDurationMs = System.currentTimeMillis() - startMs;
        TaskToolState toolStateAfter = inspectTaskToolState(context);
        WorkerExecutionResult finalized = finalizeResult(
            context,
            worker,
            plan,
            outcome,
            toolStateBefore,
            toolStateAfter,
            totalDurationMs
        );
        WorkerExecutionResult grounded = applyGroundingGuards(
            context,
            plan,
            outcome,
            finalized,
            toolStateBefore,
            toolStateAfter,
            totalDurationMs
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(grounded.metadata());
        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", "single_tool_round");
        metadata.put("tool_name", plan.toolName());
        metadata.put("tool_plan_reason", plan.reason());
        metadata.put("tool_arguments", plan.toolArguments());
        metadata.put("tool_success", outcome.result().success());
        metadata.put("tool_summary", outcome.result().summary());
        metadata.put("tool_elapsed_ms", outcome.elapsedMs());
        metadata.put("tool_output_preview", truncate(outcome.result().output(), 500));
        metadata.put("tool_round_index", toolStateAfter.totalToolCount());
        metadata.put("declared_round_count", toolStateAfter.declaredRoundCount());
        metadata.put("output_file_path", toolStateAfter.outputFilePath());
        metadata.put("output_file_exists", toolStateAfter.outputFileExists());
        metadata.put("output_file_size", toolStateAfter.outputFileSize());
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            metadata.put("current_round_instruction", toolStateBefore.currentRoundInstruction());
        }
        if (toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
            metadata.put("next_round_instruction", toolStateAfter.currentRoundInstruction());
        }
        if (!worker.toolScope().isEmpty()) {
            metadata.put("tool_scope", worker.toolScope());
        }
        metadata.put("tool_plan_raw", truncate(plan.rawResponse(), 1200));
        metadata.put("tool_trace_metadata", outcome.traceMetadata());

        return new WorkerExecutionResult(
            grounded.summary(),
            grounded.outputText(),
            grounded.producedArtifact(),
            grounded.artifactTitle(),
            grounded.artifactContent(),
            grounded.suggestedNextStep(),
            grounded.confidence(),
            grounded.tokenUsage(),
            totalDurationMs,
            metadata
        );
    }

    private ToolPlan planTool(TaskRuntimeContext context, Worker worker, TaskToolState toolState) {
        try {
            String raw = llmClient.chat(
                buildPlanningSystemPrompt(worker),
                buildPlanningUserPrompt(context, worker, toolState)
            );
            ToolPlan parsed = parseToolPlan(raw);
            log.info("Tool planning completed. task={} worker={} needsTool={} tool={}",
                context.task().id(), worker.workerId(), parsed.needsTool(), parsed.toolName());
            return parsed;
        } catch (Exception e) {
            log.warn("Tool planning failed, fallback to default executor. task={} worker={} reason={}",
                context.task().id(), worker.workerId(), e.getMessage());
            return new ToolPlan(false, "", Map.of(), "planning_failed: " + e.getMessage(), "");
        }
    }

    private ToolExecutionOutcome invokeTool(TaskRuntimeContext context, Worker worker, ToolPlan plan) {
        ToolRequest request = new ToolRequest(
            context.task().sessionId(),
            context.task().id(),
            worker.workerId(),
            plan.toolName(),
            plan.toolArguments()
        );

        long startedAt = System.currentTimeMillis();
        ToolResult result;
        LinkedHashMap<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("planning_reason", plan.reason());
        traceMetadata.put("tool_execution_mode", "single_tool_round");

        try {
            toolPolicy.ensureToolAllowed(worker, plan.toolName());
            Tool tool = toolRegistry.get(plan.toolName());
            if (tool == null) {
                throw new IllegalArgumentException("tool not registered: " + plan.toolName());
            }
            result = tool.invoke(request);
            traceMetadata.putAll(result.metadata());
        } catch (Exception e) {
            String message = e.getMessage() == null ? "tool invocation failed" : e.getMessage();
            traceMetadata.put("error", message);
            result = new ToolResult(false, "tool invocation failed: " + message, "", Map.of("error", message));
        }

        int elapsedMs = (int) (System.currentTimeMillis() - startedAt);
        toolInvocationDao.insert(new ToolInvocationRecord(
            IdGenerator.newId("tool"),
            context.task().sessionId(),
            context.task().id(),
            worker.workerId(),
            plan.toolName(),
            request.arguments(),
            result.summary(),
            result.success(),
            elapsedMs,
            Instant.now(),
            traceMetadata
        ));

        log.info("Tool invocation recorded. task={} worker={} tool={} success={} elapsedMs={}",
            context.task().id(), worker.workerId(), plan.toolName(), result.success(), elapsedMs);
        return new ToolExecutionOutcome(request, result, elapsedMs, traceMetadata);
    }

    private WorkerExecutionResult finalizeResult(TaskRuntimeContext context,
                                                 Worker worker,
                                                 ToolPlan plan,
                                                 ToolExecutionOutcome outcome,
                                                 TaskToolState toolStateBefore,
                                                 TaskToolState toolStateAfter,
                                                 long totalDurationMs) {
        try {
            String raw = llmClient.chat(
                buildFinalizationSystemPrompt(worker),
                buildFinalizationUserPrompt(context, worker, plan, outcome, toolStateBefore, toolStateAfter)
            );
            WorkerExecutionResult parsed = parseExecutionResult(raw, totalDurationMs);
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
            metadata.put("tool_finalize_raw", truncate(raw, 1200));
            return new WorkerExecutionResult(
                parsed.summary(),
                parsed.outputText(),
                parsed.producedArtifact(),
                parsed.artifactTitle(),
                parsed.artifactContent(),
                parsed.suggestedNextStep(),
                parsed.confidence(),
                parsed.tokenUsage(),
                totalDurationMs,
                metadata
            );
        } catch (Exception e) {
            log.warn("Tool finalization failed, using direct tool result fallback. task={} worker={} tool={} reason={}",
                context.task().id(), worker.workerId(), plan.toolName(), e.getMessage());
            return directToolFallback(plan, outcome, totalDurationMs);
        }
    }

    private WorkerExecutionResult applyGroundingGuards(TaskRuntimeContext context,
                                                       ToolPlan plan,
                                                       ToolExecutionOutcome outcome,
                                                       WorkerExecutionResult finalized,
                                                       TaskToolState toolStateBefore,
                                                       TaskToolState toolStateAfter,
                                                       long totalDurationMs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(finalized.metadata());
        boolean outputFileRequired = toolStateAfter.outputFilePath() != null && !toolStateAfter.outputFilePath().isBlank();
        boolean fileBackedArtifact = "write_file".equals(plan.toolName())
            && outcome.result().success()
            && toolStateAfter.outputFileExists();
        boolean moreDeclaredRoundsRemain = toolStateAfter.currentRoundInstruction() != null
            && !toolStateAfter.currentRoundInstruction().isBlank();
        boolean currentRoundRequiresWrite = requiresCurrentRoundWrite(toolStateBefore);
        boolean missingRequiredCurrentRoundWrite = currentRoundRequiresWrite && !fileBackedArtifact;

        metadata.put("output_file_required", outputFileRequired);
        metadata.put("file_backed_artifact", fileBackedArtifact);
        metadata.put("more_declared_rounds_remain", moreDeclaredRoundsRemain);
        metadata.put("current_round_requires_write", currentRoundRequiresWrite);
        metadata.put("missing_required_current_round_write", missingRequiredCurrentRoundWrite);

        String suggestedNextStep = firstNonBlank(
            moreDeclaredRoundsRemain ? toolStateAfter.currentRoundInstruction() : null,
            missingRequiredCurrentRoundWrite ? requiredWriteNextStep(toolStateBefore, toolStateAfter) : null,
            finalized.suggestedNextStep(),
            defaultNextStep(toolStateAfter, outputFileRequired)
        );

        String groundedPrefix = buildGroundedPrefix(
            plan,
            toolStateBefore,
            toolStateAfter,
            outputFileRequired,
            fileBackedArtifact,
            moreDeclaredRoundsRemain,
            missingRequiredCurrentRoundWrite
        );
        String groundedOutput = mergeGroundedOutput(groundedPrefix, finalized.outputText(), finalized.artifactContent());

        if (moreDeclaredRoundsRemain) {
            metadata.put("grounding_mode", "remaining_declared_rounds");
            String summary = firstNonBlank(
                summaryForCurrentRound(plan, fileBackedArtifact, toolStateBefore, toolStateAfter),
                finalized.summary(),
                outcome.result().summary()
            );
            String artifactTitle = fileBackedArtifact
                ? firstNonBlank(finalized.artifactTitle(), fileName(toolStateAfter.outputFilePath()))
                : "";
            String artifactContent = fileBackedArtifact
                ? firstNonBlank(finalized.artifactContent(), stringValue(outcome.request().arguments().get("content")))
                : finalized.artifactContent();
            return new WorkerExecutionResult(
                summary,
                groundedOutput,
                fileBackedArtifact,
                artifactTitle,
                artifactContent,
                suggestedNextStep,
                "medium",
                finalized.tokenUsage(),
                totalDurationMs,
                metadata
            );
        }

        if (missingRequiredCurrentRoundWrite) {
            metadata.put("grounding_mode", "missing_required_current_round_write");
            String summary = firstNonBlank(
                "本轮尚未执行当前轮要求的文件写入，不能视为终稿完成。",
                finalized.summary(),
                outcome.result().summary()
            );
            return new WorkerExecutionResult(
                summary,
                groundedOutput,
                false,
                "",
                "",
                suggestedNextStep,
                "medium",
                finalized.tokenUsage(),
                totalDurationMs,
                metadata
            );
        }

        if (outputFileRequired && !toolStateAfter.outputFileExists()) {
            metadata.put("grounding_mode", "awaiting_output_file");
            String summary = firstNonBlank(
                outcome.result().success()
                    ? "当前轮已完成，但目标文章文件尚未落盘。"
                    : "当前轮工具调用失败，目标文章文件尚未落盘。",
                finalized.summary(),
                outcome.result().summary()
            );
            return new WorkerExecutionResult(
                summary,
                groundedOutput,
                false,
                "",
                finalized.artifactContent(),
                suggestedNextStep,
                outcome.result().success() ? "medium" : "low",
                finalized.tokenUsage(),
                totalDurationMs,
                metadata
            );
        }

        if (fileBackedArtifact) {
            metadata.put("grounding_mode", "file_backed_artifact");
            return new WorkerExecutionResult(
                finalized.summary(),
                groundedOutput,
                true,
                firstNonBlank(finalized.artifactTitle(), fileName(toolStateAfter.outputFilePath())),
                firstNonBlank(finalized.artifactContent(), stringValue(outcome.request().arguments().get("content"))),
                suggestedNextStep,
                finalized.confidence(),
                finalized.tokenUsage(),
                totalDurationMs,
                metadata
            );
        }

        metadata.put("grounding_mode", "pass_through");
        return new WorkerExecutionResult(
            finalized.summary(),
            groundedOutput,
            finalized.producedArtifact(),
            finalized.artifactTitle(),
            finalized.artifactContent(),
            suggestedNextStep,
            finalized.confidence(),
            finalized.tokenUsage(),
            totalDurationMs,
            metadata
        );
    }

    private WorkerExecutionResult directToolFallback(ToolPlan plan, ToolExecutionOutcome outcome, long totalDurationMs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(outcome.traceMetadata());
        metadata.put("parser", "tool_direct_fallback");
        metadata.put("tool_name", plan.toolName());
        metadata.put("tool_arguments", plan.toolArguments());

        boolean wroteFile = "write_file".equals(plan.toolName()) && outcome.result().success();
        String output = firstNonBlank(outcome.result().output(), outcome.result().summary());
        return new WorkerExecutionResult(
            outcome.result().summary(),
            truncate(output, 1200),
            wroteFile,
            wroteFile ? stringValue(outcome.request().arguments().get("path")) : "",
            wroteFile ? truncate(stringValue(outcome.request().arguments().get("content")), 1200) : "",
            outcome.result().success() ? "" : "Inspect tool failure and adjust path or arguments.",
            outcome.result().success() ? "medium" : "low",
            0,
            totalDurationMs,
            metadata
        );
    }

    private WorkerExecutionResult delegateWithMetadata(TaskRuntimeContext context,
                                                       String workerId,
                                                       String mode,
                                                       Map<String, Object> extraMetadata) {
        WorkerExecutionResult delegated = fallbackExecutor.executeOneRound(context, workerId);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(delegated.metadata());
        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", mode);
        metadata.putAll(extraMetadata);
        return new WorkerExecutionResult(
            delegated.summary(),
            delegated.outputText(),
            delegated.producedArtifact(),
            delegated.artifactTitle(),
            delegated.artifactContent(),
            delegated.suggestedNextStep(),
            delegated.confidence(),
            delegated.tokenUsage(),
            delegated.durationMs(),
            metadata
        );
    }

    private WorkerExecutionResult autoGroundedWriteFallback(TaskRuntimeContext context,
                                                            Worker worker,
                                                            TaskToolState toolStateBefore,
                                                            ToolPlan originalPlan,
                                                            long startMs) {
        AutoWriteDraft draft = generateAutoWriteDraft(context, worker, toolStateBefore, originalPlan);
        if (draft.content().isBlank()) {
            return missingRequiredWriteWithoutTool(
                context,
                toolStateBefore,
                originalPlan,
                startMs,
                buildAutoWriteFailureMetadata(originalPlan, draft)
            );
        }

        LinkedHashMap<String, Object> writeArguments = new LinkedHashMap<>();
        writeArguments.put("path", toolStateBefore.outputFilePath());
        writeArguments.put("content", draft.content());
        ToolPlan syntheticWritePlan = new ToolPlan(
            true,
            "write_file",
            writeArguments,
            "auto_grounded_required_write",
            firstNonBlank(originalPlan.rawResponse(), draft.rawResponse())
        );

        ToolExecutionOutcome outcome = invokeTool(context, worker, syntheticWritePlan);
        long totalDurationMs = System.currentTimeMillis() - startMs;
        TaskToolState toolStateAfter = inspectTaskToolState(context);

        LinkedHashMap<String, Object> generatedMetadata = new LinkedHashMap<>();
        generatedMetadata.put("parser", "auto_write_generation");
        generatedMetadata.put("auto_write_raw", truncate(draft.rawResponse(), 1200));
        generatedMetadata.put("auto_write_original_plan_reason", originalPlan.reason());
        generatedMetadata.put("auto_write_original_plan_raw", truncate(originalPlan.rawResponse(), 1200));
        generatedMetadata.put("auto_write_content_length", draft.content().length());

        WorkerExecutionResult generated = new WorkerExecutionResult(
            firstNonBlank(draft.summary(), outcome.result().summary(), "已自动生成并写入当前轮文件内容。"),
            "",
            outcome.result().success(),
            firstNonBlank(draft.artifactTitle(), fileName(toolStateAfter.outputFilePath())),
            draft.content(),
            firstNonBlank(draft.suggestedNextStep(), toolStateAfter.currentRoundInstruction()),
            draft.confidence(),
            0,
            totalDurationMs,
            generatedMetadata
        );

        WorkerExecutionResult grounded = applyGroundingGuards(
            context,
            syntheticWritePlan,
            outcome,
            generated,
            toolStateBefore,
            toolStateAfter,
            totalDurationMs
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(grounded.metadata());
        LinkedHashMap<String, Object> toolArgumentsPreview = new LinkedHashMap<>();
        toolArgumentsPreview.put("path", toolStateBefore.outputFilePath());
        toolArgumentsPreview.put("content_length", draft.content().length());
        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", "auto_grounded_required_write");
        metadata.put("tool_name", "write_file");
        metadata.put("tool_plan_reason", firstNonBlank(originalPlan.reason(), "planner_returned_no_tool"));
        metadata.put("tool_arguments", toolArgumentsPreview);
        metadata.put("tool_success", outcome.result().success());
        metadata.put("tool_summary", outcome.result().summary());
        metadata.put("tool_elapsed_ms", outcome.elapsedMs());
        metadata.put("tool_output_preview", truncate(outcome.result().output(), 500));
        metadata.put("tool_round_index", toolStateAfter.totalToolCount());
        metadata.put("declared_round_count", toolStateAfter.declaredRoundCount());
        metadata.put("output_file_path", toolStateAfter.outputFilePath());
        metadata.put("output_file_exists", toolStateAfter.outputFileExists());
        metadata.put("output_file_size", toolStateAfter.outputFileSize());
        metadata.put("tool_plan_raw", truncate(originalPlan.rawResponse(), 1200));
        metadata.put("tool_trace_metadata", outcome.traceMetadata());
        if (!toolStateBefore.currentRoundInstruction().isBlank()) {
            metadata.put("current_round_instruction", toolStateBefore.currentRoundInstruction());
        }
        if (!toolStateAfter.currentRoundInstruction().isBlank()) {
            metadata.put("next_round_instruction", toolStateAfter.currentRoundInstruction());
        }
        if (!worker.toolScope().isEmpty()) {
            metadata.put("tool_scope", worker.toolScope());
        }

        return new WorkerExecutionResult(
            grounded.summary(),
            grounded.outputText(),
            grounded.producedArtifact(),
            grounded.artifactTitle(),
            grounded.artifactContent(),
            grounded.suggestedNextStep(),
            grounded.confidence(),
            grounded.tokenUsage(),
            totalDurationMs,
            metadata
        );
    }

    private AutoWriteDraft generateAutoWriteDraft(TaskRuntimeContext context,
                                                  Worker worker,
                                                  TaskToolState toolStateBefore,
                                                  ToolPlan originalPlan) {
        try {
            String raw = llmClient.chat(
                buildAutoWriteSystemPrompt(worker),
                buildAutoWriteUserPrompt(context, worker, toolStateBefore, originalPlan)
            );
            AutoWriteDraft parsed = parseAutoWriteDraft(raw);
            log.info("Auto write generation completed. task={} worker={} contentLength={}",
                context.task().id(), worker.workerId(), parsed.content().length());
            return parsed;
        } catch (Exception e) {
            log.warn("Auto write generation failed. task={} worker={} reason={}",
                context.task().id(), worker.workerId(), e.getMessage());
            return new AutoWriteDraft(
                "",
                "",
                "",
                "",
                "low",
                "",
                firstNonBlank(e.getClass().getSimpleName(), "generation_failed")
                    + ": " + firstNonBlank(e.getMessage(), "unknown error")
            );
        }
    }

    private WorkerExecutionResult missingRequiredWriteWithoutTool(TaskRuntimeContext context,
                                                                  TaskToolState toolStateBefore,
                                                                  ToolPlan plan,
                                                                  long startMs) {
        return missingRequiredWriteWithoutTool(context, toolStateBefore, plan, startMs, Map.of());
    }

    private WorkerExecutionResult missingRequiredWriteWithoutTool(TaskRuntimeContext context,
                                                                  TaskToolState toolStateBefore,
                                                                  ToolPlan plan,
                                                                  long startMs,
                                                                  Map<String, Object> extraMetadata) {
        long totalDurationMs = System.currentTimeMillis() - startMs;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        String executionMode = extraMetadata == null
            ? ""
            : stringValue(extraMetadata.get("tool_execution_mode"));
        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", firstNonBlank(executionMode, "planned_no_tool_required_write"));
        metadata.put("tool_plan_reason", plan.reason());
        metadata.put("tool_plan_raw", truncate(plan.rawResponse(), 1200));
        metadata.put("output_file_required", !toolStateBefore.outputFilePath().isBlank());
        metadata.put("output_file_path", toolStateBefore.outputFilePath());
        metadata.put("output_file_exists", toolStateBefore.outputFileExists());
        metadata.put("output_file_size", toolStateBefore.outputFileSize());
        metadata.put("file_backed_artifact", false);
        metadata.put("grounding_mode", "missing_required_current_round_write");
        metadata.put("current_round_requires_write", true);
        metadata.put("missing_required_current_round_write", true);
        metadata.put("declared_round_count", toolStateBefore.declaredRoundCount());
        metadata.put("tool_round_index", toolStateBefore.totalToolCount());
        if (!toolStateBefore.currentRoundInstruction().isBlank()) {
            metadata.put("current_round_instruction", toolStateBefore.currentRoundInstruction());
        }
        if (!toolStateBefore.nextRoundInstruction().isBlank()) {
            metadata.put("next_round_instruction", toolStateBefore.nextRoundInstruction());
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        String requiredNextStep = requiredWriteNextStep(toolStateBefore, toolStateBefore);
        String groundedOutput = buildGroundedPrefix(
            new ToolPlan(false, "", Map.of(), plan.reason(), plan.rawResponse()),
            toolStateBefore,
            toolStateBefore,
            !toolStateBefore.outputFilePath().isBlank(),
            false,
            false,
            true
        );
        return new WorkerExecutionResult(
            "本轮要求写文件，但 planning 未触发 write_file，不能视为完成。",
            groundedOutput,
            false,
            "",
            "",
            requiredNextStep,
            "medium",
            0,
            totalDurationMs,
            metadata
        );
    }

    private ToolPlan parseToolPlan(String raw) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new ToolPlan(false, "", Map.of(), "empty planning response", safeRaw);
        }

        try {
            JsonNode json = MAPPER.readTree(safeRaw);
            boolean needsTool = json.path("needs_tool").asBoolean(false);
            String toolName = json.path("tool_name").asText("");
            Map<String, Object> toolArguments = json.has("tool_arguments") && !json.get("tool_arguments").isNull()
                ? MAPPER.convertValue(json.get("tool_arguments"), Map.class)
                : Map.of();
            String reason = json.path("reason").asText("");
            return new ToolPlan(needsTool, toolName, toolArguments, reason, safeRaw);
        } catch (Exception e) {
            log.warn("Failed to parse tool plan JSON, fallback to no-tool path: {}", e.getMessage());
            return new ToolPlan(false, "", Map.of(), "plan_parse_failed", safeRaw);
        }
    }

    private WorkerExecutionResult parseExecutionResult(String raw, long durationMs) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new WorkerExecutionResult("", "", false, "", "", "", "low", 0, durationMs, Map.of("parser", "empty"));
        }

        try {
            JsonNode json = MAPPER.readTree(safeRaw);
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parser", "json");
            return new WorkerExecutionResult(
                json.path("summary").asText(""),
                json.path("output_text").asText(""),
                json.path("produced_artifact").asBoolean(false),
                json.path("artifact_title").asText(""),
                json.path("artifact_content").asText(""),
                json.path("suggested_next_step").asText(""),
                json.path("confidence").asText("medium"),
                0,
                durationMs,
                metadata
            );
        } catch (Exception e) {
            log.warn("Failed to parse final worker JSON output, falling back to raw text: {}", e.getMessage());
            String fallbackSummary = safeRaw.length() > 280 ? safeRaw.substring(0, 280) + "..." : safeRaw;
            return new WorkerExecutionResult(
                fallbackSummary,
                safeRaw,
                false,
                "",
                "",
                "",
                "medium",
                0,
                durationMs,
                Map.of("parser", "raw_text")
            );
        }
    }

    private AutoWriteDraft parseAutoWriteDraft(String raw) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new AutoWriteDraft("", "", "", "", "low", safeRaw, "empty_response");
        }

        try {
            JsonNode json = MAPPER.readTree(safeRaw);
            return new AutoWriteDraft(
                json.path("summary").asText(""),
                json.path("artifact_title").asText(""),
                json.path("content").asText(""),
                json.path("suggested_next_step").asText(""),
                json.path("confidence").asText("medium"),
                safeRaw,
                json.path("content").asText("").isBlank() ? "empty_content" : ""
            );
        } catch (Exception e) {
            log.warn("Failed to parse auto-write JSON output: {}", e.getMessage());
            return new AutoWriteDraft(
                "",
                "",
                "",
                "",
                "low",
                safeRaw,
                "parse_failed: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName())
            );
        }
    }

    private String buildAutoWriteSystemPrompt(Worker worker) {
        return "You are a grounded file-writing worker. Worker ID: " + worker.workerId() + ". "
            + "The planner failed to select write_file, but the current round still requires a grounded write to the expected output file. "
            + "Generate the exact full file content that should be written for the current round only. "
            + "Return a JSON object with exactly these fields: "
            + "summary (string), artifact_title (string), content (string), suggested_next_step (string), confidence (high|medium|low). "
            + "The content field must contain the exact text to write into the target file, with no markdown fences and no explanation outside JSON. "
            + "If this is the final round, suggested_next_step should be empty. "
            + "Preserve useful existing material, improve weak sections, and satisfy the explicit current round instruction.";
    }

    private String buildPlanningSystemPrompt(Worker worker) {
        return "You are a tool-planning worker. Worker ID: " + worker.workerId() + ". "
            + "Decide the next grounded tool call for the current round only. "
            + "Available tools: " + toolRegistry.listToolNames() + ". "
            + "Allowed tools for this worker: " + worker.toolCapabilities() + ". "
            + "Allowed path scope: " + worker.toolScope() + ". "
            + "If the task intent contains explicit Round N instructions, follow the current round instruction and do not skip future rounds. "
            + "If the current round instruction explicitly requires writing, overwriting, patching, or updating the expected output file, "
            + "you must return needs_tool=true and tool_name=write_file for that grounded write. "
            + "Do not return needs_tool=false for a round that still requires a grounded file write. "
            + "Return a JSON object with exactly these fields: "
            + "needs_tool (boolean), tool_name (string), tool_arguments (object), reason (string). "
            + "If no tool is required, set needs_tool to false, tool_name to empty string, and tool_arguments to {}. "
            + "Use at most one tool. Prefer relative paths inside the allowed scope. No markdown, no extra text.";
    }

    private String buildPlanningUserPrompt(TaskRuntimeContext context, Worker worker, TaskToolState toolState) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context));
        sb.append("\n\nWorker Tool Capabilities: ").append(worker.toolCapabilities());
        if (!worker.toolScope().isEmpty()) {
            sb.append("\nWorker Tool Scope: ").append(worker.toolScope());
        }
        if (toolState.currentRoundInstruction() != null && !toolState.currentRoundInstruction().isBlank()) {
            sb.append("\nCurrent Round Instruction: ").append(toolState.currentRoundInstruction());
        }
        if (toolState.nextRoundInstruction() != null && !toolState.nextRoundInstruction().isBlank()) {
            sb.append("\nNext Declared Round: ").append(toolState.nextRoundInstruction());
        }
        sb.append("\nCurrent Round Requires Write: ").append(requiresCurrentRoundWrite(toolState));
        sb.append("\nPrior Successful Tool Rounds: ").append(toolState.successfulToolCount());
        sb.append("\nPrior Tool Trace:\n").append(toolState.recentToolTrace());
        if (toolState.outputFilePath() != null && !toolState.outputFilePath().isBlank()) {
            sb.append("\nExpected Output File: ").append(toolState.outputFilePath());
            sb.append("\nOutput File Exists: ").append(toolState.outputFileExists());
            sb.append("\nOutput File Size: ").append(toolState.outputFileSize());
        }
        sb.append("\nDecide whether you need exactly one tool call before producing the current-round answer.");
        sb.append(" Use write_file only when you already know the exact content to write.");
        return sb.toString();
    }

    private String buildAutoWriteUserPrompt(TaskRuntimeContext context,
                                            Worker worker,
                                            TaskToolState toolState,
                                            ToolPlan originalPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context, false));
        sb.append("\n\nWorker Tool Capabilities: ").append(worker.toolCapabilities());
        if (!worker.toolScope().isEmpty()) {
            sb.append("\nWorker Tool Scope: ").append(worker.toolScope());
        }
        if (toolState.currentRoundInstruction() != null && !toolState.currentRoundInstruction().isBlank()) {
            sb.append("\nCurrent Round Instruction: ").append(toolState.currentRoundInstruction());
        }
        if (toolState.nextRoundInstruction() != null && !toolState.nextRoundInstruction().isBlank()) {
            sb.append("\nNext Declared Round: ").append(toolState.nextRoundInstruction());
        }
        sb.append("\nPlanner Returned No Tool: true");
        sb.append("\nOriginal Planning Reason: ").append(firstNonBlank(originalPlan.reason(), "(empty)"));
        sb.append("\nCurrent Round Requires Write: ").append(requiresCurrentRoundWrite(toolState));
        sb.append("\nPrior Tool Trace:\n").append(truncate(toolState.recentToolTrace(), 2000));
        String carryForwardNotes = extractCarryForwardNotes(context, 5000);
        if (!carryForwardNotes.isBlank()) {
            sb.append("\nCarry-Forward Notes From Prior Rounds:\n").append(carryForwardNotes);
        }
        if (toolState.outputFilePath() != null && !toolState.outputFilePath().isBlank()) {
            sb.append("\nExpected Output File: ").append(toolState.outputFilePath());
            sb.append("\nOutput File Exists: ").append(toolState.outputFileExists());
            sb.append("\nOutput File Size: ").append(toolState.outputFileSize());
            String existingFile = loadExistingOutputFile(toolState.outputFilePath(), 12000);
            if (!existingFile.isBlank()) {
                sb.append("\nExisting Output File Content:\n").append(existingFile);
            }
        }
        sb.append("\nWrite the exact full content that should overwrite the output file for the current round.");
        sb.append(" Do not describe what to write; provide the final text itself in content.");
        return sb.toString();
    }

    private String buildFinalizationSystemPrompt(Worker worker) {
        return "You are a tool-enabled execution worker. Worker ID: " + worker.workerId() + ". "
            + "You already received one tool result. Produce the current-round result, not the whole task unless the provided evidence proves the whole task is complete. "
            + "Return a JSON object containing exactly these fields: "
            + "summary (string), output_text (string), produced_artifact (boolean), "
            + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
            + "confidence (high|medium|low). "
            + "Ground every claim in the provided tool result and file state. "
            + "Never claim that a file was written unless the current tool is write_file and Tool Success is true. "
            + "If a declared future round remains, explicitly say the overall task is not complete yet and set suggested_next_step to that next round. "
            + "If an output file is required and it does not exist after this round, produced_artifact must be false. "
            + "Keep summary concise, output_text under 1200 characters, artifact_content under 1600 characters, "
            + "and mention tool failure clearly if the tool did not succeed. No markdown, no extra text.";
    }

    private String buildFinalizationUserPrompt(TaskRuntimeContext context,
                                               Worker worker,
                                               ToolPlan plan,
                                               ToolExecutionOutcome outcome,
                                               TaskToolState toolStateBefore,
                                               TaskToolState toolStateAfter) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context));
        sb.append("\n\nWorker Tool Capabilities: ").append(worker.toolCapabilities());
        if (!worker.toolScope().isEmpty()) {
            sb.append("\nWorker Tool Scope: ").append(worker.toolScope());
        }
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            sb.append("\nCurrent Round Instruction: ").append(toolStateBefore.currentRoundInstruction());
        }
        if (toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
            sb.append("\nNext Declared Round: ").append(toolStateAfter.currentRoundInstruction());
        }
        sb.append("\nPrior Tool Trace Before This Round:\n").append(toolStateBefore.recentToolTrace());
        sb.append("\n\nTool Planning Reason: ").append(plan.reason());
        sb.append("\nTool Name: ").append(plan.toolName());
        sb.append("\nTool Arguments: ").append(JsonMapper.toJson(plan.toolArguments()));
        sb.append("\nTool Success: ").append(outcome.result().success());
        sb.append("\nTool Summary: ").append(outcome.result().summary());
        sb.append("\nTool Metadata: ").append(JsonMapper.toJson(outcome.result().metadata()));
        if (toolStateAfter.outputFilePath() != null && !toolStateAfter.outputFilePath().isBlank()) {
            sb.append("\nExpected Output File: ").append(toolStateAfter.outputFilePath());
            sb.append("\nOutput File Exists After Tool: ").append(toolStateAfter.outputFileExists());
            sb.append("\nOutput File Size After Tool: ").append(toolStateAfter.outputFileSize());
        }
        if (!outcome.result().output().isBlank()) {
            sb.append("\nTool Output:\n").append(truncate(outcome.result().output(), 6000));
        }
        sb.append("\n\nProduce the grounded current-round execution result now.");
        return sb.toString();
    }

    private String buildTaskPrompt(TaskRuntimeContext context) {
        return buildTaskPrompt(context, true);
    }

    private String buildTaskPrompt(TaskRuntimeContext context, boolean includeFullActiveContext) {
        var task = context.task();
        StringBuilder sb = new StringBuilder();
        sb.append("Task Title: ").append(task.title()).append("\n");
        if (task.goal() != null && !task.goal().isBlank()) {
            sb.append("Goal: ").append(task.goal()).append("\n");
        }
        if (task.metadata() != null && task.metadata().get("intent") != null) {
            sb.append("Intent: ").append(task.metadata().get("intent")).append("\n");
        }
        if (task.nextStep() != null && !task.nextStep().isBlank()) {
            sb.append("Next Step: ").append(task.nextStep()).append("\n");
        }
        if (includeFullActiveContext
            && context.activeContext() != null
            && !context.activeContext().synthesizedContext().isBlank()) {
            sb.append("\nActive Context:\n").append(context.activeContext().synthesizedContext()).append("\n");
        } else if (context.latestPacket() != null && context.latestPacket().activeTaskSummary() != null) {
            sb.append("\nContext Summary: ").append(context.latestPacket().activeTaskSummary()).append("\n");
        } else if (context.activeContext() != null && !context.activeContext().synthesizedContext().isBlank()) {
            sb.append("\nContext Summary: ")
                .append(truncate(context.activeContext().synthesizedContext(), 2200))
                .append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> buildAutoWriteFailureMetadata(ToolPlan originalPlan, AutoWriteDraft draft) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_execution_mode", "auto_grounded_required_write_failed");
        metadata.put("auto_write_generation_mode", autoWriteGenerationMode(draft));
        metadata.put("auto_write_raw", truncate(draft.rawResponse(), 1200));
        metadata.put("auto_write_original_plan_reason", originalPlan.reason());
        metadata.put("auto_write_original_plan_raw", truncate(originalPlan.rawResponse(), 1200));
        if (!draft.failureReason().isBlank()) {
            metadata.put("auto_write_generation_error", truncate(draft.failureReason(), 500));
        }
        return metadata;
    }

    private String autoWriteGenerationMode(AutoWriteDraft draft) {
        if (draft == null) {
            return "unknown";
        }
        if (!draft.failureReason().isBlank()) {
            int separator = draft.failureReason().indexOf(':');
            return separator > 0
                ? draft.failureReason().substring(0, separator).trim()
                : draft.failureReason().trim();
        }
        if (draft.rawResponse().isBlank()) {
            return "empty_response";
        }
        if (draft.content().isBlank()) {
            return "empty_content";
        }
        return "generated";
    }

    private TaskToolState inspectTaskToolState(TaskRuntimeContext context) {
        List<ToolInvocationRecord> invocations = toolInvocationDao.listByTask(context.task().id(), 20);
        int successfulToolCount = 0;
        int successfulReadCount = 0;
        int successfulWriteCount = 0;
        List<ToolInvocationRecord> chronological = new ArrayList<>(invocations);
        java.util.Collections.reverse(chronological);
        for (ToolInvocationRecord invocation : invocations) {
            if (!invocation.success()) {
                continue;
            }
            successfulToolCount++;
            if ("read_file".equals(invocation.toolName())) {
                successfulReadCount++;
            }
            if ("write_file".equals(invocation.toolName())) {
                successfulWriteCount++;
            }
        }

        String outputFilePath = metadataString(context.task().metadata(), "output_file");
        boolean outputFileExists = false;
        long outputFileSize = 0L;
        if (outputFilePath != null && !outputFilePath.isBlank()) {
            try {
                Path outputPath = Path.of(outputFilePath).toAbsolutePath().normalize();
                outputFileExists = Files.exists(outputPath);
                if (outputFileExists) {
                    outputFileSize = Files.size(outputPath);
                }
                outputFilePath = outputPath.toString();
            } catch (InvalidPathException | IOException e) {
                log.warn("Failed to inspect output file state. task={} path={} reason={}",
                    context.task().id(), outputFilePath, e.getMessage());
            }
        }

        int declaredRoundCount = countDeclaredRounds(context.task());
        int currentRoundIndex = successfulToolCount + 1;
        String currentRoundInstruction = resolveRoundInstruction(context.task(), currentRoundIndex);
        String nextRoundInstruction = resolveRoundInstruction(context.task(), currentRoundIndex + 1);
        if (invocations.isEmpty()) {
            currentRoundIndex = 1;
            nextRoundInstruction = resolveRoundInstruction(context.task(), 2);
        }

        PendingRoundState pendingRound = resolvePendingRoundState(context);
        if (pendingRound != null && !pendingRound.currentRoundInstruction().isBlank()) {
            currentRoundIndex = pendingRound.currentRoundIndex() > 0 ? pendingRound.currentRoundIndex() : currentRoundIndex;
            currentRoundInstruction = pendingRound.currentRoundInstruction();
            nextRoundInstruction = pendingRound.nextRoundInstruction();
        }

        return new TaskToolState(
            outputFilePath,
            outputFileExists,
            outputFileSize,
            invocations.size(),
            successfulToolCount,
            successfulReadCount,
            successfulWriteCount,
            invocations.isEmpty() ? "" : invocations.get(0).toolName(),
            buildRecentToolTrace(chronological),
            declaredRoundCount,
            currentRoundIndex,
            currentRoundInstruction,
            nextRoundInstruction
        );
    }

    @SuppressWarnings("unchecked")
    private PendingRoundState resolvePendingRoundState(TaskRuntimeContext context) {
        if (context == null || context.recentArtifacts() == null || context.recentArtifacts().isEmpty()) {
            return null;
        }
        for (var artifact : context.recentArtifacts()) {
            if (artifact == null || artifact.metadata() == null || artifact.metadata().isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = artifact.metadata();
            Object nested = metadata.get("latest_worker_metadata");
            if (nested instanceof Map<?, ?> nestedMap) {
                Map<String, Object> selected = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        selected.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                PendingRoundState pending = pendingRoundStateFromMetadata(context, selected);
                if (pending != null) {
                    return pending;
                }
            }
            PendingRoundState pending = pendingRoundStateFromMetadata(context, metadata);
            if (pending != null) {
                return pending;
            }
        }
        return null;
    }

    private PendingRoundState pendingRoundStateFromMetadata(TaskRuntimeContext context, Map<String, Object> metadata) {
        String groundingMode = metadataString(metadata, "grounding_mode");
        if (!"missing_required_current_round_write".equalsIgnoreCase(groundingMode)) {
            return null;
        }
        String currentInstruction = metadataString(metadata, "current_round_instruction");
        if (currentInstruction.isBlank()) {
            return null;
        }
        int currentRoundIndex = findDeclaredRoundNumber(context.task(), currentInstruction);
        String nextInstruction = currentRoundIndex > 0
            ? resolveRoundInstruction(context.task(), currentRoundIndex + 1)
            : metadataString(metadata, "next_round_instruction");
        return new PendingRoundState(currentRoundIndex, currentInstruction, nextInstruction);
    }

    private int countDeclaredRounds(com.agentcloud.model.Task task) {
        Matcher matcher = ROUND_INSTRUCTION_PATTERN.matcher(metadataString(task.metadata(), "intent"));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String resolveRoundInstruction(com.agentcloud.model.Task task, int roundNumber) {
        if (roundNumber < 1) {
            return "";
        }
        Matcher matcher = ROUND_INSTRUCTION_PATTERN.matcher(metadataString(task.metadata(), "intent"));
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == roundNumber) {
                return matcher.group(2).trim();
            }
        }
        return "";
    }

    private int findDeclaredRoundNumber(com.agentcloud.model.Task task, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return 0;
        }
        Matcher matcher = ROUND_INSTRUCTION_PATTERN.matcher(metadataString(task.metadata(), "intent"));
        String target = instruction.trim();
        while (matcher.find()) {
            if (target.equals(matcher.group(2).trim())) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 0;
    }

    private String buildRecentToolTrace(List<ToolInvocationRecord> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return "(no prior tool rounds)";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ToolInvocationRecord invocation : invocations) {
            sb.append("- Round ").append(index++)
                .append(": ").append(invocation.toolName())
                .append(" success=").append(invocation.success())
                .append(" summary=").append(truncate(invocation.resultSummary(), 200));
            if (invocation.arguments() != null && !invocation.arguments().isEmpty()) {
                sb.append(" args=").append(truncate(JsonMapper.toJson(invocation.arguments()), 240));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String summaryForCurrentRound(ToolPlan plan,
                                          boolean fileBackedArtifact,
                                          TaskToolState toolStateBefore,
                                          TaskToolState toolStateAfter) {
        if ("read_file".equals(plan.toolName()) && instructionSuggestsFileWrite(toolStateBefore.currentRoundInstruction())) {
            return "本轮仅完成了读取或分析，尚未执行当前轮要求的文件写入。";
        }
        if ("read_file".equals(plan.toolName())) {
            return "已完成当前轮参考资料读取与整理，整体任务仍需继续。";
        }
        if (fileBackedArtifact && toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
            return "已完成当前轮文件写入，整体任务仍需继续下一轮。";
        }
        if (fileBackedArtifact) {
            return "已完成当前轮文件写入。";
        }
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            return "已完成当前轮目标，整体任务仍需继续。";
        }
        return "";
    }

    private String buildGroundedPrefix(ToolPlan plan,
                                       TaskToolState toolStateBefore,
                                       TaskToolState toolStateAfter,
                                       boolean outputFileRequired,
                                       boolean fileBackedArtifact,
                                       boolean moreDeclaredRoundsRemain,
                                       boolean missingRequiredCurrentRoundWrite) {
        StringBuilder sb = new StringBuilder();
        if (moreDeclaredRoundsRemain) {
            sb.append("Current round finished, but the overall task is not complete yet.");
            if (toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
                sb.append(" Next step: ").append(toolStateAfter.currentRoundInstruction()).append(".");
            }
        } else if (missingRequiredCurrentRoundWrite) {
            sb.append("The current round has not performed the required grounded file write yet.");
            if ("read_file".equals(plan.toolName())) {
                sb.append(" This round only completed reading and analysis.");
            }
            if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
                sb.append(" Required current-round action: ").append(toolStateBefore.currentRoundInstruction()).append(".");
            }
        } else if (outputFileRequired && !toolStateAfter.outputFileExists()) {
            sb.append("The required output file has not been written yet.");
            if ("read_file".equals(plan.toolName())) {
                sb.append(" This round only completed reading and analysis.");
            }
        } else if (fileBackedArtifact) {
            sb.append("The output file exists and is grounded by a successful write_file call.");
        }
        return sb.toString();
    }

    private String mergeGroundedOutput(String groundedPrefix, String outputText, String artifactContent) {
        String mainBody = firstNonBlank(outputText, artifactContent);
        if (groundedPrefix == null || groundedPrefix.isBlank()) {
            return mainBody == null ? "" : mainBody;
        }
        if (mainBody == null || mainBody.isBlank()) {
            return groundedPrefix;
        }
        return groundedPrefix + "\n\n" + mainBody;
    }

    private String defaultNextStep(TaskToolState toolState, boolean outputFileRequired) {
        if (toolState.currentRoundInstruction() != null && !toolState.currentRoundInstruction().isBlank()) {
            return toolState.currentRoundInstruction();
        }
        if (outputFileRequired && (toolState.outputFilePath() != null && !toolState.outputFilePath().isBlank())
            && !toolState.outputFileExists()) {
            return "Write the next article version to '" + toolState.outputFilePath() + "'.";
        }
        return "";
    }

    private String requiredWriteNextStep(TaskToolState toolStateBefore, TaskToolState toolStateAfter) {
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            return toolStateBefore.currentRoundInstruction();
        }
        if (toolStateAfter.outputFilePath() != null && !toolStateAfter.outputFilePath().isBlank()) {
            return "Use write_file to write the required article version to '" + toolStateAfter.outputFilePath() + "'.";
        }
        return "Use write_file to complete the required current-round file update.";
    }

    private boolean requiresCurrentRoundWrite(TaskToolState toolState) {
        return toolState != null
            && toolState.outputFilePath() != null
            && !toolState.outputFilePath().isBlank()
            && instructionSuggestsFileWrite(toolState.currentRoundInstruction());
    }

    private boolean instructionSuggestsFileWrite(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String normalized = instruction.toLowerCase();
        if (normalized.contains("do not write")
            || normalized.contains("don't write")
            || normalized.contains("not write yet")
            || normalized.contains("do not overwrite")
            || normalized.contains("不要写")
            || normalized.contains("先不要写")
            || normalized.contains("暂不写")
            || normalized.contains("无需写")
            || normalized.contains("不要覆盖")
            || normalized.contains("暂不覆盖")) {
            return false;
        }
        return normalized.contains("write")
            || normalized.contains("overwrite")
            || normalized.contains("rewrite")
            || normalized.contains("patch")
            || normalized.contains("append")
            || normalized.contains("write_file")
            || normalized.contains("写")
            || normalized.contains("改写")
            || normalized.contains("覆盖")
            || normalized.contains("写入")
            || normalized.contains("终稿");
    }

    private boolean instructionSuggestsFileRead(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String normalized = instruction.toLowerCase();
        if (normalized.contains("do not read")
            || normalized.contains("don't read")
            || normalized.contains("无需读取")
            || normalized.contains("不要读取")
            || normalized.contains("不用读取")) {
            return false;
        }
        return normalized.contains("read_file")
            || normalized.contains("read ")
            || normalized.contains(" reread")
            || normalized.contains("读取")
            || normalized.contains("重读")
            || normalized.contains("查看参考");
    }

    @SuppressWarnings("unchecked")
    private String extractCarryForwardNotes(TaskRuntimeContext context, int maxChars) {
        if (context == null || context.recentArtifacts() == null || context.recentArtifacts().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (var artifact : context.recentArtifacts()) {
            if (artifact == null || artifact.metadata() == null || artifact.metadata().isEmpty()) {
                continue;
            }
            String outputText = metadataString(artifact.metadata(), "output_text");
            String artifactContent = metadataString(artifact.metadata(), "artifact_content");
            String suggestedNextStep = metadataString(artifact.metadata(), "suggested_next_step");
            String note = firstNonBlank(outputText, artifactContent, artifact.summary());
            if (note == null || note.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("- Prior Round Note: ").append(truncate(note, 2200));
            if (!suggestedNextStep.isBlank()) {
                sb.append("\n  Suggested Next Step Then: ").append(truncate(suggestedNextStep, 400));
            }
            kept++;
            if (sb.length() >= maxChars || kept >= 2) {
                break;
            }
        }
        return truncate(sb.toString(), maxChars);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path value = Path.of(path);
            Path fileName = value.getFileName();
            return fileName == null ? value.toString() : fileName.toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    private String loadExistingOutputFile(String path, int maxChars) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path file = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return "";
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() <= maxChars) {
                return content;
            }
            return content.substring(0, maxChars) + "\n...[truncated]";
        } catch (IOException | InvalidPathException e) {
            log.warn("Failed to load existing output file. path={} reason={}", path, e.getMessage());
            return "";
        }
    }

    private String truncate(String text, int limit) {
        if (text == null || text.isBlank() || limit < 1) {
            return text == null ? "" : text;
        }
        return text.length() > limit ? text.substring(0, limit) + "..." : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ToolPlan(
        boolean needsTool,
        String toolName,
        Map<String, Object> toolArguments,
        String reason,
        String rawResponse
    ) {
        private ToolPlan {
            if (toolName == null) toolName = "";
            if (toolArguments == null) toolArguments = Map.of();
            if (reason == null) reason = "";
            if (rawResponse == null) rawResponse = "";
        }
    }

    private record ToolExecutionOutcome(
        ToolRequest request,
        ToolResult result,
        int elapsedMs,
        Map<String, Object> traceMetadata
    ) {
        private ToolExecutionOutcome {
            if (traceMetadata == null) traceMetadata = Map.of();
        }
    }

    private record TaskToolState(
        String outputFilePath,
        boolean outputFileExists,
        long outputFileSize,
        int totalToolCount,
        int successfulToolCount,
        int successfulReadCount,
        int successfulWriteCount,
        String lastToolName,
        String recentToolTrace,
        int declaredRoundCount,
        int currentRoundIndex,
        String currentRoundInstruction,
        String nextRoundInstruction
    ) {
        private TaskToolState {
            if (outputFilePath == null) outputFilePath = "";
            if (lastToolName == null) lastToolName = "";
            if (recentToolTrace == null || recentToolTrace.isBlank()) recentToolTrace = "(no prior tool rounds)";
            if (currentRoundInstruction == null) currentRoundInstruction = "";
            if (nextRoundInstruction == null) nextRoundInstruction = "";
        }
    }

    private record PendingRoundState(
        int currentRoundIndex,
        String currentRoundInstruction,
        String nextRoundInstruction
    ) {
        private PendingRoundState {
            if (currentRoundInstruction == null) currentRoundInstruction = "";
            if (nextRoundInstruction == null) nextRoundInstruction = "";
        }
    }

    private record AutoWriteDraft(
        String summary,
        String artifactTitle,
        String content,
        String suggestedNextStep,
        String confidence,
        String rawResponse,
        String failureReason
    ) {
        private AutoWriteDraft {
            if (summary == null) summary = "";
            if (artifactTitle == null) artifactTitle = "";
            if (content == null) content = "";
            if (suggestedNextStep == null) suggestedNextStep = "";
            if (confidence == null || confidence.isBlank()) confidence = "medium";
            if (rawResponse == null) rawResponse = "";
            if (failureReason == null) failureReason = "";
        }
    }
}
