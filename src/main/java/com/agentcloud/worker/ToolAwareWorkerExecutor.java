package com.agentcloud.worker;

import com.agentcloud.engine.IdGenerator;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.llm.LlmImageInputResolver;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.MountedContextPromptMetrics;
import com.agentcloud.runtime.context.MountedContextPromptRenderer;
import com.agentcloud.runtime.context.PromptRenderingMode;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 4;
    private static final int DIRECTORY_TASK_MAX_TOOL_ROUNDS = 8;
    private static final int IMAGE_DIRECTORY_TASK_MAX_TOOL_ROUNDS = 10;
    private static final Pattern ROUND_INSTRUCTION_PATTERN =
        Pattern.compile("(?im)^Round\\s+(\\d+)\\s*:\\s*(.+)$");

    private final WorkerRegistry workerRegistry;
    private final ToolRegistry toolRegistry;
    private final ToolPolicy toolPolicy;
    private final ToolInvocationDao toolInvocationDao;
    private final LlmClient llmClient;
    private final WorkerExecutor fallbackExecutor;
    private final MountedContextPromptRenderer mountedContextPromptRenderer;

    public ToolAwareWorkerExecutor(WorkerRegistry workerRegistry,
                                   ToolRegistry toolRegistry,
                                   ToolPolicy toolPolicy,
                                   ToolInvocationDao toolInvocationDao,
                                   LlmClient llmClient,
                                   WorkerExecutor fallbackExecutor) {
        this(workerRegistry, toolRegistry, toolPolicy, toolInvocationDao, llmClient, fallbackExecutor,
            new MountedContextPromptRenderer());
    }

    ToolAwareWorkerExecutor(WorkerRegistry workerRegistry,
                                   ToolRegistry toolRegistry,
                                   ToolPolicy toolPolicy,
                                   ToolInvocationDao toolInvocationDao,
                                   LlmClient llmClient,
                                   WorkerExecutor fallbackExecutor,
                                   MountedContextPromptRenderer mountedContextPromptRenderer) {
        this.workerRegistry = workerRegistry;
        this.toolRegistry = toolRegistry;
        this.toolPolicy = toolPolicy;
        this.toolInvocationDao = toolInvocationDao;
        this.llmClient = llmClient;
        this.fallbackExecutor = fallbackExecutor;
        this.mountedContextPromptRenderer = mountedContextPromptRenderer == null
            ? new MountedContextPromptRenderer()
            : mountedContextPromptRenderer;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        Worker worker = workerRegistry.get(workerId);
        if (worker == null) {
            return fallbackExecutor.executeOneRound(context, workerId);
        }
        PromptRenderingMode renderingMode = PromptRenderingMode.resolve(context.task());
        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(
            context,
            renderingMode,
            renderingMode.shouldRenderMountedPrompt() ? mountedContextPromptRenderer.render(context) : ""
        );
        log.info("Tool-aware prompt mode selected. task={}, worker={}, promptMode={}, mountedRenderUsed={}, mountedPanelCount={}",
            context.task().id(),
            worker.workerId(),
            metrics.promptMode(),
            metrics.mountedRenderUsed(),
            metrics.panelCount());

        TaskToolState toolStateBefore = inspectTaskToolState(context);
        if (shouldUseLegacySingleToolPath(toolStateBefore)) {
            return withPromptRenderingMetadata(executeSingleToolRound(context, worker, toolStateBefore, renderingMode),
                context, renderingMode);
        }
        return withPromptRenderingMetadata(executeMultiToolRound(context, worker, toolStateBefore, renderingMode),
            context, renderingMode);
    }

    private boolean shouldUseLegacySingleToolPath(TaskToolState toolState) {
        return toolState == null
            || toolState.declaredRoundCount() > 0
            || requiresCurrentRoundWrite(toolState);
    }

    private int resolveMaxToolRounds(TaskRuntimeContext context, TaskToolState toolState) {
        if (toolState != null && toolState.outputDirRequired()) {
            return hasImageInputs(context) ? IMAGE_DIRECTORY_TASK_MAX_TOOL_ROUNDS : DIRECTORY_TASK_MAX_TOOL_ROUNDS;
        }
        if (toolState != null && toolState.outputFileRequired()) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
        return DEFAULT_MAX_TOOL_ROUNDS;
    }

    private boolean hasImageInputs(TaskRuntimeContext context) {
        return context != null && !LlmImageInputResolver.resolve(context).isEmpty();
    }

    private WorkerExecutionResult executeSingleToolRound(TaskRuntimeContext context,
                                                         Worker worker,
                                                         TaskToolState toolStateBefore,
                                                         PromptRenderingMode renderingMode) {
        long startMs = System.currentTimeMillis();
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

        ToolPlan plan = planTool(context, worker, toolStateBefore, renderingMode);
        if (!plan.needsTool() || plan.toolName().isBlank()) {
            if (currentRoundRequiresWrite) {
                return autoGroundedWriteFallback(context, worker, toolStateBefore, plan, startMs);
            }
            return delegateWithMetadata(
                context,
                worker.workerId(),
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
        appendGroundedOutputMetadata(metadata, toolStateAfter);
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

    private WorkerExecutionResult executeMultiToolRound(TaskRuntimeContext context,
                                                        Worker worker,
                                                        TaskToolState initialToolState,
                                                        PromptRenderingMode renderingMode) {
        long startMs = System.currentTimeMillis();
        int maxToolRounds = resolveMaxToolRounds(context, initialToolState);
        TaskToolState currentToolState = initialToolState;
        TaskToolState lastToolStateBefore = initialToolState;
        TaskToolState lastToolStateAfter = initialToolState;
        ToolPlan lastPlan = null;
        ToolExecutionOutcome lastOutcome = null;
        List<ToolChainStep> toolChain = new ArrayList<>();
        String terminationReason = "planner_no_additional_tool";

        for (int stepIndex = 1; stepIndex <= maxToolRounds; stepIndex++) {
            ToolPlan plan = planTool(context, worker, currentToolState, renderingMode);
            if (!toolChain.isEmpty()) {
                toolChain = updateLastWhyNextStep(
                    toolChain,
                    firstNonBlank(plan.reason(), "continue with another grounded tool step")
                );
            }

            if (!plan.needsTool() || plan.toolName().isBlank()) {
                if (toolChain.isEmpty()) {
                    ToolPlan syntheticProbePlan = buildInitialProbePlan(worker, currentToolState, plan);
                    if (syntheticProbePlan != null) {
                        log.info("Planner returned no initial tool; executing lightweight probe instead. task={} worker={} tool={}",
                            context.task().id(), worker.workerId(), syntheticProbePlan.toolName());
                        plan = syntheticProbePlan;
                    } else {
                        return delegateWithMetadata(
                            context,
                            worker.workerId(),
                            "planned_no_tool_fallback",
                            Map.of(
                                "tool_aware_executor", true,
                                "tool_execution_mode", "multi_tool_round",
                                "tool_plan_reason", plan.reason(),
                                "tool_plan_raw", truncate(plan.rawResponse(), 1200),
                                "max_tool_rounds", maxToolRounds,
                                "tool_chain_step_count", 0,
                                "tool_chain_termination_reason", "planner_no_additional_tool"
                            ),
                            currentToolState
                        );
                    }
                }
                if (!toolChain.isEmpty() && !plan.needsTool()) {
                    if (shouldAutoGroundDirectoryWrite(worker, currentToolState)) {
                        WorkerExecutionResult autoWriteResult = autoGroundedDirectoryWriteFallback(
                            context,
                            worker,
                            currentToolState,
                            plan,
                            toolChain,
                            stepIndex,
                            maxToolRounds,
                            startMs
                        );
                        if (autoWriteResult != null) {
                            return autoWriteResult;
                        }
                    }
                    terminationReason = "planner_no_additional_tool";
                    toolChain = updateLastWhyNextStep(
                        toolChain,
                        firstNonBlank(plan.reason(), "planner requested finalization")
                    );
                    break;
                }
            }

            String repeatedGuardReason = repeatedToolGuardReason(plan, toolChain);
            if (!repeatedGuardReason.isBlank()) {
                if (toolChain.isEmpty()) {
                    return delegateWithMetadata(
                        context,
                        worker.workerId(),
                        "repeated_tool_guard_fallback",
                        Map.of(
                            "tool_aware_executor", true,
                            "tool_execution_mode", "multi_tool_round",
                            "tool_plan_reason", plan.reason(),
                            "tool_plan_raw", truncate(plan.rawResponse(), 1200),
                            "max_tool_rounds", maxToolRounds,
                            "tool_chain_step_count", 0,
                            "tool_chain_termination_reason", "repeated_tool_guard"
                        ),
                        currentToolState
                    );
                }
                terminationReason = "repeated_tool_guard";
                toolChain = updateLastWhyNextStep(toolChain, repeatedGuardReason);
                break;
            }

            lastToolStateBefore = currentToolState;
            ToolExecutionOutcome outcome = invokeTool(context, worker, plan, "multi_tool_round", stepIndex);
            TaskToolState toolStateAfter = inspectTaskToolState(context);

            toolChain.add(new ToolChainStep(
                stepIndex,
                plan.toolName(),
                plan.toolArguments(),
                outcome.result().summary(),
                plan.reason(),
                "",
                outcome.result().success(),
                outcome.elapsedMs(),
                truncate(outcome.result().output(), 1200)
            ));

            lastPlan = plan;
            lastOutcome = outcome;
            lastToolStateAfter = toolStateAfter;
            currentToolState = toolStateAfter;

            String noProgressReason = noProgressGuardReason(plan, outcome, lastToolStateBefore, lastToolStateAfter);
            if (!noProgressReason.isBlank()) {
                terminationReason = "no_progress_guard";
                toolChain = updateLastWhyNextStep(toolChain, noProgressReason);
                break;
            }

            if (stepIndex == maxToolRounds) {
                terminationReason = "max_tool_rounds_reached";
                toolChain = updateLastWhyNextStep(toolChain, "stop: max_tool_rounds reached");
                break;
            }
        }

        if (toolChain.isEmpty() || lastPlan == null || lastOutcome == null) {
            return delegateWithMetadata(
                context,
                worker.workerId(),
                "multi_tool_empty_chain_fallback",
                Map.of(
                    "tool_aware_executor", true,
                    "tool_execution_mode", "multi_tool_round",
                    "max_tool_rounds", maxToolRounds,
                    "tool_chain_step_count", 0,
                    "tool_chain_termination_reason", terminationReason
                ),
                currentToolState
            );
        }

        long totalDurationMs = System.currentTimeMillis() - startMs;
        WorkerExecutionResult finalized = finalizeMultiToolResult(
            context,
            worker,
            toolChain,
            lastToolStateBefore,
            lastToolStateAfter,
            totalDurationMs
        );
        return attachMultiToolMetadata(
            worker,
            finalized,
            toolChain,
            initialToolState,
            lastPlan,
            lastOutcome,
            lastToolStateAfter,
            maxToolRounds,
            terminationReason,
            totalDurationMs
        );
    }

    private ToolPlan planTool(TaskRuntimeContext context,
                              Worker worker,
                              TaskToolState toolState,
                              PromptRenderingMode renderingMode) {
        try {
            String raw = llmClient.chat(
                buildPlanningSystemPrompt(worker),
                buildPlanningUserPrompt(context, worker, toolState, renderingMode)
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
        return invokeTool(context, worker, plan, "single_tool_round", null);
    }

    private ToolExecutionOutcome invokeTool(TaskRuntimeContext context,
                                            Worker worker,
                                            ToolPlan plan,
                                            String executionMode,
                                            Integer stepIndex) {
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
        traceMetadata.put("tool_execution_mode", firstNonBlank(executionMode, "single_tool_round"));
        traceMetadata.put("selected_tool", plan.toolName());
        traceMetadata.put("selected_args", plan.toolArguments());
        traceMetadata.put("why_selected", plan.reason());
        if (stepIndex != null && stepIndex > 0) {
            traceMetadata.put("tool_chain_step_index", stepIndex);
        }

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
        traceMetadata.put("result_summary", result.summary());
        traceMetadata.put("tool_success", result.success());
        String toolInvocationId = IdGenerator.newId("tool");
        String executionId = context.task().id() + ":" + worker.workerId() + ":" + toolInvocationId;
        String toolStatus = result.success() ? "succeeded" : "failed";
        List<String> touchedPaths = extractTouchedPaths(request.arguments(), traceMetadata);
        toolInvocationDao.insert(new ToolInvocationRecord(
            toolInvocationId,
            context.task().sessionId(),
            context.task().id(),
            worker.workerId(),
            executionId,
            plan.toolName(),
            request.arguments(),
            result.summary(),
            toolStatus,
            result.success(),
            elapsedMs,
            touchedPaths,
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
                parsed.executionStatus(),
                parsed.evidenceRefs(),
                parsed.unfinishedItems(),
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
        boolean groundedArtifact = isGroundedArtifactProduced(plan.toolName(), outcome.result().success(), toolStateAfter);
        boolean moreDeclaredRoundsRemain = toolStateAfter.currentRoundInstruction() != null
            && !toolStateAfter.currentRoundInstruction().isBlank();
        boolean currentRoundRequiresWrite = requiresCurrentRoundWrite(toolStateBefore);
        boolean missingRequiredCurrentRoundWrite = currentRoundRequiresWrite && !groundedArtifact;

        appendGroundedOutputMetadata(metadata, toolStateAfter);
        metadata.put("file_backed_artifact", groundedArtifact && toolStateAfter.outputFileExists());
        metadata.put("directory_backed_artifact", groundedArtifact && toolStateAfter.outputDirExists());
        metadata.put("grounded_output_present", groundedArtifact);
        metadata.put("more_declared_rounds_remain", moreDeclaredRoundsRemain);
        metadata.put("current_round_requires_write", currentRoundRequiresWrite);
        metadata.put("missing_required_current_round_write", missingRequiredCurrentRoundWrite);

        String suggestedNextStep = firstNonBlank(
            moreDeclaredRoundsRemain ? toolStateAfter.currentRoundInstruction() : null,
            missingRequiredCurrentRoundWrite ? requiredWriteNextStep(toolStateBefore, toolStateAfter) : null,
            finalized.suggestedNextStep(),
            defaultNextStep(toolStateAfter)
        );

        String groundedPrefix = buildGroundedPrefix(
            plan,
            toolStateBefore,
            toolStateAfter,
            groundedArtifact,
            moreDeclaredRoundsRemain,
            missingRequiredCurrentRoundWrite
        );
        String groundedOutput = mergeGroundedOutput(groundedPrefix, finalized.outputText(), finalized.artifactContent());

        if (moreDeclaredRoundsRemain) {
            metadata.put("grounding_mode", "remaining_declared_rounds");
            String summary = firstNonBlank(
                summaryForCurrentRound(plan, groundedArtifact, toolStateBefore, toolStateAfter),
                finalized.summary(),
                outcome.result().summary()
            );
            return new WorkerExecutionResult(
                summary,
                groundedOutput,
                groundedArtifact,
                firstNonBlank(finalized.artifactTitle(), groundedArtifactTitle(toolStateAfter)),
                groundedArtifact ? resolvedGroundedArtifactContent(finalized.artifactContent(), plan, outcome, toolStateAfter, 1600) : finalized.artifactContent(),
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
                "本轮尚未执行当前轮要求的 grounded 输出写入，不能视为完成。",
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

        if (toolStateAfter.outputRequired() && !toolStateAfter.groundedOutputExists()) {
            metadata.put("grounding_mode", toolStateAfter.outputDirRequired()
                ? "awaiting_output_dir"
                : "awaiting_output_file");
            String summary = firstNonBlank(
                awaitingGroundedOutputSummary(toolStateAfter, outcome.result().success()),
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

        if (groundedArtifact) {
            metadata.put("grounding_mode", toolStateAfter.outputDirRequired()
                ? "directory_backed_artifact"
                : "file_backed_artifact");
            return new WorkerExecutionResult(
                finalized.summary(),
                groundedOutput,
                true,
                firstNonBlank(finalized.artifactTitle(), groundedArtifactTitle(toolStateAfter)),
                resolvedGroundedArtifactContent(finalized.artifactContent(), plan, outcome, toolStateAfter, 1600),
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

        TaskToolState toolStateAfter = taskToolStateFromOutcome(outcome);
        boolean groundedArtifact = isGroundedArtifactProduced(plan.toolName(), outcome.result().success(), toolStateAfter);
        appendGroundedOutputMetadata(metadata, toolStateAfter);
        metadata.put("file_backed_artifact", groundedArtifact && toolStateAfter.outputFileExists());
        metadata.put("directory_backed_artifact", groundedArtifact && toolStateAfter.outputDirExists());
        metadata.put("grounded_output_present", groundedArtifact);
        String artifactContent = groundedArtifact
            ? resolvedGroundedArtifactContent("", plan, outcome, toolStateAfter, 1200)
            : "";
        String output = firstNonBlank(outcome.result().output(), outcome.result().summary());
        return new WorkerExecutionResult(
            outcome.result().summary(),
            truncate(output, 1200),
            groundedArtifact,
            groundedArtifact ? groundedArtifactTitle(toolStateAfter) : "",
            artifactContent,
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
        return delegateWithMetadata(context, workerId, mode, extraMetadata, null);
    }

    private WorkerExecutionResult delegateWithMetadata(TaskRuntimeContext context,
                                                       String workerId,
                                                       String mode,
                                                       Map<String, Object> extraMetadata,
                                                       TaskToolState toolState) {
        WorkerExecutionResult delegated = fallbackExecutor.executeOneRound(context, workerId);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(delegated.metadata());
        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", mode);
        appendDelegatedToolStateMetadata(metadata, toolState);
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

    private WorkerExecutionResult autoGroundedDirectoryWriteFallback(TaskRuntimeContext context,
                                                                     Worker worker,
                                                                     TaskToolState toolStateBefore,
                                                                     ToolPlan originalPlan,
                                                                     List<ToolChainStep> priorToolChain,
                                                                     int nextStepIndex,
                                                                     int maxToolRounds,
                                                                     long startMs) {
        AutoWriteFilesDraft draft = generateAutoWriteFilesDraft(context, worker, toolStateBefore, originalPlan);
        if (draft.basePath().isBlank() || draft.files().isEmpty()) {
            return null;
        }

        LinkedHashMap<String, Object> writeArguments = new LinkedHashMap<>();
        writeArguments.put("base_path", draft.basePath());
        writeArguments.put("files", draft.files());
        writeArguments.put("overwrite", true);
        ToolPlan syntheticWritePlan = new ToolPlan(
            true,
            "write_files",
            writeArguments,
            "auto_grounded_directory_write",
            firstNonBlank(originalPlan.rawResponse(), draft.rawResponse())
        );

        ToolExecutionOutcome outcome = invokeTool(context, worker, syntheticWritePlan, "multi_tool_round", nextStepIndex);
        TaskToolState toolStateAfter = inspectTaskToolState(context);
        List<ToolChainStep> toolChain = new ArrayList<>(priorToolChain == null ? List.of() : priorToolChain);
        toolChain = updateLastWhyNextStep(
            toolChain,
            firstNonBlank(originalPlan.reason(), "planner returned no additional tool before auto directory write")
        );
        toolChain.add(new ToolChainStep(
            nextStepIndex,
            "write_files",
            writeArguments,
            outcome.result().summary(),
            firstNonBlank(draft.summary(), "auto generated directory grounded write"),
            "stop: auto directory grounded write completed",
            outcome.result().success(),
            outcome.elapsedMs(),
            truncate(outcome.result().output(), 1200)
        ));

        long totalDurationMs = System.currentTimeMillis() - startMs;
        WorkerExecutionResult generated = new WorkerExecutionResult(
            firstNonBlank(draft.summary(), outcome.result().summary(), "已自动生成并写入目录型 grounded 输出。"),
            "",
            outcome.result().success(),
            fileName(toolStateAfter.outputDirPath()),
            "",
            firstNonBlank(draft.suggestedNextStep(), toolStateAfter.currentRoundInstruction()),
            draft.confidence(),
            0,
            totalDurationMs,
            Map.of(
                "parser", "auto_write_files_generation",
                "auto_write_files_raw", truncate(draft.rawResponse(), 1200),
                "auto_write_original_plan_reason", originalPlan.reason(),
                "auto_write_original_plan_raw", truncate(originalPlan.rawResponse(), 1200),
                "auto_write_file_count", draft.files().size()
            )
        );

        WorkerExecutionResult finalized = attachMultiToolMetadata(
            worker,
            generated,
            toolChain,
            toolStateBefore,
            syntheticWritePlan,
            outcome,
            toolStateAfter,
            maxToolRounds,
            "auto_grounded_directory_write",
            totalDurationMs
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(finalized.metadata());
        metadata.put("auto_write_generation_mode", autoWriteFilesGenerationMode(draft));
        metadata.put("auto_write_used_images", !LlmImageInputResolver.resolve(context).isEmpty());
        return new WorkerExecutionResult(
            finalized.summary(),
            finalized.outputText(),
            finalized.producedArtifact(),
            finalized.artifactTitle(),
            finalized.artifactContent(),
            finalized.suggestedNextStep(),
            finalized.confidence(),
            finalized.executionStatus(),
            finalized.evidenceRefs(),
            finalized.unfinishedItems(),
            finalized.tokenUsage(),
            finalized.durationMs(),
            metadata
        );
    }

    private AutoWriteDraft generateAutoWriteDraft(TaskRuntimeContext context,
                                                  Worker worker,
                                                  TaskToolState toolStateBefore,
                                                  ToolPlan originalPlan) {
        try {
            List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
            String raw = llmClient.chat(
                buildAutoWriteSystemPrompt(worker),
                buildAutoWriteUserPrompt(context, worker, toolStateBefore, originalPlan),
                imageInputs
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

    private AutoWriteFilesDraft generateAutoWriteFilesDraft(TaskRuntimeContext context,
                                                            Worker worker,
                                                            TaskToolState toolStateBefore,
                                                            ToolPlan originalPlan) {
        try {
            List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
            String raw = llmClient.chat(
                buildAutoWriteFilesSystemPrompt(worker),
                buildAutoWriteFilesUserPrompt(context, worker, toolStateBefore, originalPlan),
                imageInputs
            );
            AutoWriteFilesDraft parsed = parseAutoWriteFilesDraft(raw, toolStateBefore.outputDirPath());
            log.info("Auto write-files generation completed. task={} worker={} fileCount={}",
                context.task().id(), worker.workerId(), parsed.files().size());
            return parsed;
        } catch (Exception e) {
            log.warn("Auto write-files generation failed. task={} worker={} reason={}",
                context.task().id(), worker.workerId(), e.getMessage());
            return new AutoWriteFilesDraft(
                "",
                "",
                List.of(),
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
        appendGroundedOutputMetadata(metadata, toolStateBefore);
        metadata.put("file_backed_artifact", false);
        metadata.put("directory_backed_artifact", false);
        metadata.put("grounded_output_present", false);
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
            false,
            false,
            true
        );
        return new WorkerExecutionResult(
            "本轮要求写 grounded 输出，但 planning 未触发对应写入工具，不能视为完成。",
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

    private WorkerExecutionResult finalizeMultiToolResult(TaskRuntimeContext context,
                                                          Worker worker,
                                                          List<ToolChainStep> toolChain,
                                                          TaskToolState toolStateBefore,
                                                          TaskToolState toolStateAfter,
                                                          long totalDurationMs) {
        try {
            String raw = llmClient.chat(
                buildMultiToolFinalizationSystemPrompt(worker),
                buildMultiToolFinalizationUserPrompt(context, worker, toolChain, toolStateBefore, toolStateAfter)
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
                parsed.executionStatus(),
                parsed.evidenceRefs(),
                parsed.unfinishedItems(),
                parsed.tokenUsage(),
                totalDurationMs,
                metadata
            );
        } catch (Exception e) {
            ToolChainStep lastStep = toolChain.get(toolChain.size() - 1);
            log.warn("Multi-tool finalization failed, using synthetic fallback. task={} worker={} reason={}",
                context.task().id(), worker.workerId(), e.getMessage());
            String output = formatToolChainTrace(toolChain);
            String summary = firstNonBlank(
                lastStep.resultSummary(),
                "已完成多步工具链执行，使用直接回退摘要。"
            );
            return new WorkerExecutionResult(
                summary,
                truncate(output, 1200),
                false,
                "",
                "",
                defaultNextStep(toolStateAfter),
                "medium",
                0,
                totalDurationMs,
                Map.of("parser", "multi_tool_direct_fallback")
            );
        }
    }

    private WorkerExecutionResult attachMultiToolMetadata(Worker worker,
                                                          WorkerExecutionResult finalized,
                                                          List<ToolChainStep> toolChain,
                                                          TaskToolState initialToolState,
                                                          ToolPlan lastPlan,
                                                          ToolExecutionOutcome lastOutcome,
                                                          TaskToolState finalToolState,
                                                          int maxToolRounds,
                                                          String terminationReason,
                                                          long totalDurationMs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(finalized.metadata());
        boolean groundedOutputChanged = groundedOutputStateChanged(initialToolState, finalToolState);
        boolean groundedArtifact = hasSuccessfulWrite(toolChain)
            && finalToolState.groundedOutputExists()
            && groundedOutputChanged;
        boolean fileBackedArtifact = groundedArtifact && finalToolState.outputFileExists();
        boolean directoryBackedArtifact = groundedArtifact && finalToolState.outputDirExists();

        metadata.put("tool_aware_executor", true);
        metadata.put("tool_execution_mode", "multi_tool_round");
        metadata.put("max_tool_rounds", maxToolRounds);
        metadata.put("tool_name", lastPlan.toolName());
        metadata.put("tool_plan_reason", lastPlan.reason());
        metadata.put("tool_arguments", lastPlan.toolArguments());
        metadata.put("tool_success", lastOutcome.result().success());
        metadata.put("tool_summary", lastOutcome.result().summary());
        metadata.put("tool_elapsed_ms", lastOutcome.elapsedMs());
        metadata.put("tool_output_preview", truncate(lastOutcome.result().output(), 500));
        metadata.put("tool_round_index", finalToolState.totalToolCount());
        metadata.put("declared_round_count", finalToolState.declaredRoundCount());
        appendGroundedOutputMetadata(metadata, finalToolState);
        metadata.put("file_backed_artifact", fileBackedArtifact);
        metadata.put("directory_backed_artifact", directoryBackedArtifact);
        metadata.put("grounded_output_present", groundedArtifact);
        metadata.put("tool_chain_step_count", toolChain.size());
        metadata.put("tool_chain_termination_reason", terminationReason);
        metadata.put("tool_chain_trace", toToolChainTraceMetadata(toolChain));
        if (!worker.toolScope().isEmpty()) {
            metadata.put("tool_scope", worker.toolScope());
        }

        String groundedOutput = mergeGroundedOutput(
            buildMultiToolGroundedPrefix(toolChain, finalToolState, terminationReason, groundedArtifact, maxToolRounds),
            finalized.outputText(),
            finalized.artifactContent()
        );

        String suggestedNextStep = firstNonBlank(
            finalized.suggestedNextStep(),
            defaultNextStep(finalToolState)
        );
        String artifactContent = finalized.artifactContent();
        if (groundedArtifact && (artifactContent == null || artifactContent.isBlank())) {
            artifactContent = resolvedGroundedArtifactContent(artifactContent, lastPlan, lastOutcome, finalToolState, 1600);
        }

        return new WorkerExecutionResult(
            finalized.summary(),
            groundedOutput,
            groundedArtifact || finalized.producedArtifact(),
            groundedArtifact
                ? firstNonBlank(finalized.artifactTitle(), groundedArtifactTitle(finalToolState))
                : finalized.artifactTitle(),
            artifactContent,
            suggestedNextStep,
            normalizeMultiToolConfidence(finalized.confidence(), terminationReason),
            finalized.executionStatus(),
            finalized.evidenceRefs(),
            finalized.unfinishedItems(),
            finalized.tokenUsage(),
            totalDurationMs,
            metadata
        );
    }

    private String buildMultiToolFinalizationSystemPrompt(Worker worker) {
        return "You are a tool-enabled execution worker. Worker ID: " + worker.workerId() + ". "
            + "You already completed a short grounded tool chain. Produce the current-round result using only the provided tool evidence. "
            + "Return a JSON object containing exactly these fields: "
            + "summary (string), output_text (string), produced_artifact (boolean), "
            + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
            + "confidence (high|medium|low). "
            + "Do not invent actions that are not present in the tool chain trace. "
            + "If a grounded output already exists as a file or directory, you may mark produced_artifact=true. "
            + "If the tool chain stopped because of repetition, failure, or max rounds, say so clearly. "
            + "Keep summary concise, output_text under 1200 characters, artifact_content under 1600 characters. "
            + "No markdown, no extra text.";
    }

    private String buildMultiToolFinalizationUserPrompt(TaskRuntimeContext context,
                                                        Worker worker,
                                                        List<ToolChainStep> toolChain,
                                                        TaskToolState toolStateBefore,
                                                        TaskToolState toolStateAfter) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context, true, PromptRenderingMode.resolve(context.task())));
        sb.append("\n\nWorker Tool Capabilities: ").append(worker.toolCapabilities());
        if (!worker.toolScope().isEmpty()) {
            sb.append("\nWorker Tool Scope: ").append(worker.toolScope());
        }
        sb.append("\nMax Tool Rounds: ").append(resolveMaxToolRounds(context, toolStateBefore));
        sb.append("\nTool Chain Trace:\n").append(formatToolChainTrace(toolChain));
        appendGroundedOutputState(sb, toolStateBefore, toolStateAfter, 8000);
        sb.append("\nProduce the grounded result for this multi-step tool chain now.");
        return sb.toString();
    }

    private String formatToolChainTrace(List<ToolChainStep> toolChain) {
        if (toolChain == null || toolChain.isEmpty()) {
            return "(no tool chain)";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolChainStep step : toolChain) {
            sb.append("- Step ").append(step.stepIndex())
                .append(": ").append(step.selectedTool())
                .append(" success=").append(step.success())
                .append(" elapsedMs=").append(step.elapsedMs())
                .append("\n  Why Selected: ").append(firstNonBlank(step.whySelected(), "(empty)"))
                .append("\n  Args: ").append(truncate(JsonMapper.toJson(step.arguments()), 300))
                .append("\n  Result Summary: ").append(firstNonBlank(step.resultSummary(), "(empty)"));
            if (step.outputPreview() != null && !step.outputPreview().isBlank()) {
                sb.append("\n  Output Preview: ").append(truncate(step.outputPreview(), 500));
            }
            if (step.whyNextStep() != null && !step.whyNextStep().isBlank()) {
                sb.append("\n  Why Next Step: ").append(step.whyNextStep());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<ToolChainStep> updateLastWhyNextStep(List<ToolChainStep> toolChain, String whyNextStep) {
        if (toolChain == null || toolChain.isEmpty()) {
            return toolChain;
        }
        List<ToolChainStep> updated = new ArrayList<>(toolChain);
        ToolChainStep last = updated.get(updated.size() - 1);
        updated.set(updated.size() - 1, last.withWhyNextStep(whyNextStep));
        return updated;
    }

    private String repeatedToolGuardReason(ToolPlan plan, List<ToolChainStep> toolChain) {
        String nextFingerprint = toolPlanFingerprint(plan.toolName(), plan.toolArguments());
        for (ToolChainStep priorStep : toolChain) {
            String priorFingerprint = toolPlanFingerprint(priorStep.selectedTool(), priorStep.arguments());
            if (nextFingerprint.equals(priorFingerprint)) {
                return "stop: repeated_tool_guard, repeated " + plan.toolName() + " with the same arguments";
            }
        }
        return "";
    }

    private String noProgressGuardReason(ToolPlan plan,
                                         ToolExecutionOutcome outcome,
                                         TaskToolState toolStateBefore,
                                         TaskToolState toolStateAfter) {
        if (outcome == null || outcome.result() == null) {
            return "stop: no_progress_guard, missing tool result";
        }
        if (!outcome.result().success()) {
            return "stop: no_progress_guard, tool failed: " + firstNonBlank(outcome.result().summary(), plan.toolName());
        }
        if (outcome.result().summary().isBlank() && outcome.result().output().isBlank()) {
            return "stop: no_progress_guard, tool returned an empty result";
        }
        if (isGroundedWriteTool(plan.toolName())
            && toolStateBefore.outputRequired()
            && toolStateAfter.outputRequired()
            && !groundedOutputStateChanged(toolStateBefore, toolStateAfter)) {
            return "stop: no_progress_guard, " + plan.toolName() + " did not change the grounded output";
        }
        return "";
    }

    private String buildMultiToolGroundedPrefix(List<ToolChainStep> toolChain,
                                                TaskToolState toolState,
                                                String terminationReason,
                                                boolean groundedArtifact,
                                                int maxToolRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("The tool-aware worker completed ").append(toolChain.size())
            .append(" grounded tool step");
        if (toolChain.size() != 1) {
            sb.append("s");
        }
        sb.append(".");
        if ("max_tool_rounds_reached".equals(terminationReason)) {
            sb.append(" The round stopped because it reached max_tool_rounds=").append(maxToolRounds).append(".");
        } else if ("repeated_tool_guard".equals(terminationReason)) {
            sb.append(" The round stopped because the next planned tool call repeated an earlier step.");
        } else if ("no_progress_guard".equals(terminationReason)) {
            sb.append(" The round stopped because the latest tool step made no usable progress.");
        } else if ("planner_no_additional_tool".equals(terminationReason)) {
            sb.append(" The planner requested finalization after the current evidence.");
        }
        if (groundedArtifact) {
            sb.append(toolState.outputDirExists()
                ? " A grounded output directory is present."
                : " A grounded output file is present.");
        } else if (toolState.outputRequired() && !toolState.groundedOutputExists()) {
            sb.append(toolState.outputDirRequired()
                ? " The expected output directory is still missing or empty."
                : " The expected output file is still missing.");
        }
        return sb.toString();
    }

    private boolean hasSuccessfulWrite(List<ToolChainStep> toolChain) {
        if (toolChain == null || toolChain.isEmpty()) {
            return false;
        }
        for (ToolChainStep step : toolChain) {
            if (step.success() && isGroundedWriteTool(step.selectedTool())) {
                return true;
            }
        }
        return false;
    }

    private boolean groundedOutputStateChanged(TaskToolState before, TaskToolState after) {
        if (before == null || after == null) {
            return false;
        }
        return before.outputFileExists() != after.outputFileExists()
            || before.outputFileSize() != after.outputFileSize()
            || !before.outputFileFingerprint().equals(after.outputFileFingerprint())
            || !before.outputFilePath().equals(after.outputFilePath())
            || before.outputDirExists() != after.outputDirExists()
            || before.outputDirEntryCount() != after.outputDirEntryCount()
            || !before.outputDirFingerprint().equals(after.outputDirFingerprint())
            || !before.outputDirPath().equals(after.outputDirPath());
    }

    private List<Map<String, Object>> toToolChainTraceMetadata(List<ToolChainStep> toolChain) {
        List<Map<String, Object>> trace = new ArrayList<>();
        if (toolChain == null) {
            return trace;
        }
        for (ToolChainStep step : toolChain) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("step_index", step.stepIndex());
            item.put("selected_tool", step.selectedTool());
            item.put("args", step.arguments());
            item.put("result_summary", step.resultSummary());
            item.put("why_selected", step.whySelected());
            item.put("why_next_step", step.whyNextStep());
            item.put("success", step.success());
            item.put("elapsed_ms", step.elapsedMs());
            if (step.outputPreview() != null && !step.outputPreview().isBlank()) {
                item.put("output_preview", step.outputPreview());
            }
            trace.add(item);
        }
        return trace;
    }

    private String toolPlanFingerprint(String toolName, Map<String, Object> arguments) {
        return firstNonBlank(toolName, "(none)") + "::" + JsonMapper.toJson(arguments == null ? Map.of() : arguments);
    }

    private String normalizeMultiToolConfidence(String confidence, String terminationReason) {
        if ("no_progress_guard".equals(terminationReason) || "repeated_tool_guard".equals(terminationReason)) {
            return "low";
        }
        return firstNonBlank(confidence, "medium");
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
                json.path("execution_status").asText("completed"),
                readStringList(json.path("evidence_refs")),
                readStringList(json.path("unfinished_items")),
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

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.asText("").isBlank()) {
                items.add(item.asText());
            }
        }
        return List.copyOf(items);
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

    private AutoWriteFilesDraft parseAutoWriteFilesDraft(String raw, String defaultBasePath) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new AutoWriteFilesDraft("", firstNonBlank(defaultBasePath), List.of(), "", "low", safeRaw, "empty_response");
        }

        try {
            JsonNode json = MAPPER.readTree(safeRaw);
            String basePath = json.path("base_path").asText("");
            List<Map<String, Object>> files = new ArrayList<>();
            JsonNode filesNode = json.path("files");
            if (filesNode.isArray()) {
                for (JsonNode item : filesNode) {
                    String path = item.path("path").asText("");
                    if (path.isBlank()) {
                        continue;
                    }
                    LinkedHashMap<String, Object> file = new LinkedHashMap<>();
                    file.put("path", path);
                    file.put("content", item.path("content").asText(""));
                    files.add(file);
                }
            }
            return new AutoWriteFilesDraft(
                json.path("summary").asText(""),
                firstNonBlank(basePath, defaultBasePath),
                List.copyOf(files),
                json.path("suggested_next_step").asText(""),
                json.path("confidence").asText("medium"),
                safeRaw,
                files.isEmpty() ? "empty_files" : ""
            );
        } catch (Exception e) {
            log.warn("Failed to parse auto-write-files JSON output: {}", e.getMessage());
            return new AutoWriteFilesDraft(
                "",
                firstNonBlank(defaultBasePath),
                List.of(),
                "",
                "low",
                safeRaw,
                "parse_failed: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName())
            );
        }
    }

    private String buildAutoWriteSystemPrompt(Worker worker) {
        return "You are a grounded output-writing worker. Worker ID: " + worker.workerId() + ". "
            + "The planner failed to select a grounded write tool, but the current round still requires a grounded write to the expected output artifact. "
            + "Generate the exact full file content that should be written for the current round only. "
            + "Return a JSON object with exactly these fields: "
            + "summary (string), artifact_title (string), content (string), suggested_next_step (string), confidence (high|medium|low). "
            + "The content field must contain the exact text to write into the target file, with no markdown fences and no explanation outside JSON. "
            + "If this is the final round, suggested_next_step should be empty. "
            + "Preserve useful existing material, improve weak sections, and satisfy the explicit current round instruction.";
    }

    private String buildAutoWriteFilesSystemPrompt(Worker worker) {
        return "You are a grounded directory output-writing worker. Worker ID: " + worker.workerId() + ". "
            + "The planner stopped before selecting the final directory write, but the task still requires a grounded output directory. "
            + "Generate the concrete multi-file bundle that should now be written. "
            + "Return a JSON object with exactly these fields: "
            + "summary (string), base_path (string), files (array), suggested_next_step (string), confidence (high|medium|low). "
            + "Each files entry must be an object with path (relative string) and content (full exact text). "
            + "Use files only for the minimum runnable project structure needed to satisfy the task. "
            + "Do not include markdown fences or any explanation outside JSON.";
    }

    private String buildPlanningSystemPrompt(Worker worker) {
        List<String> registeredTools = registeredToolCapabilities(worker);
        StringBuilder sb = new StringBuilder();
        sb.append("You are a tool-planning worker. Worker ID: ").append(worker.workerId()).append(". ");
        sb.append("Decide the next grounded tool call for the current round only. ");
        sb.append("Available tools: ").append(toolRegistry.listToolNames()).append(". ");
        sb.append("Allowed registered tools for this worker: ").append(registeredTools).append(". ");
        sb.append("Allowed working scope: ").append(worker.toolScope()).append(". ");
        String toolGuide = toolRegistry.describeTools(registeredTools);
        String groundedWriteTools = groundedWriteToolHint(registeredTools);
        if (!toolGuide.isBlank()) {
            sb.append("Tool usage guide:\n").append(toolGuide).append("\n");
        }
        sb.append("If the task intent contains explicit Round N instructions, follow the current round instruction and do not skip future rounds. ");
        sb.append("If the current round instruction explicitly requires writing, overwriting, patching, or updating the expected grounded output, ");
        sb.append("you must return needs_tool=true and select ").append(groundedWriteTools).append(" for that grounded write. ");
        sb.append("If a grounded output is required but still missing and you need repository or file context first, ");
        sb.append("use a lightweight inspection tool such as list_files, read_file, or search_text before returning needs_tool=false. ");
        if (registeredTools.contains("write_file")) {
            sb.append("Use write_file when you already know the exact full content to write. ");
        }
        if (registeredTools.contains("write_files")) {
            sb.append("Use write_files when the task needs creating or updating multiple files under one allowed base directory. ");
        }
        if (registeredTools.contains("patch_file")) {
            sb.append("Use patch_file when you can anchor a targeted replacement with exact old_text and new_text. ");
        }
        sb.append("Do not return needs_tool=false for a round that still requires a grounded output write. ");
        sb.append("Return a JSON object with exactly these fields: ");
        sb.append("needs_tool (boolean), tool_name (string), tool_arguments (object), reason (string). ");
        sb.append("If no tool is required, set needs_tool to false, tool_name to empty string, and tool_arguments to {}. ");
        sb.append("Use at most one tool. Prefer relative paths inside the allowed scope. No markdown, no extra text.");
        return sb.toString();
    }

    private String buildPlanningUserPrompt(TaskRuntimeContext context,
                                           Worker worker,
                                           TaskToolState toolState,
                                           PromptRenderingMode renderingMode) {
        StringBuilder sb = new StringBuilder();
        List<String> registeredTools = registeredToolCapabilities(worker);
        sb.append(buildTaskPrompt(context, true, renderingMode));
        sb.append("\n\nWorker Tool Capabilities: ").append(registeredTools);
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
        sb.append("\nGrounded Output Present: ").append(toolState.groundedOutputExists());
        sb.append("\nPrior Successful Tool Rounds: ").append(toolState.successfulToolCount());
        List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
        sb.append("\nImage Inputs Available: ").append(imageInputs.size());
        if (!imageInputs.isEmpty()) {
            sb.append("\nImage Input Paths:");
            for (LlmImageInput imageInput : imageInputs) {
                sb.append("\n- ").append(firstNonBlank(imageInput.path(), "(inline image)"));
            }
        }
        sb.append("\nPrior Tool Trace:\n").append(toolState.recentToolTrace());
        appendExpectedGroundedOutput(sb, toolState);
        if (toolState.outputRequired() && !toolState.groundedOutputExists() && toolState.successfulToolCount() == 0) {
            sb.append("\nIf you still need repository context before writing, inspect it now with list_files, read_file, or search_text instead of returning needs_tool=false.");
        }
        sb.append("\nDecide whether you need exactly one tool call before producing the current-round answer.");
        if (registeredTools.contains("write_file")) {
            sb.append(" Use write_file only when you already know the exact full content to write.");
        }
        if (registeredTools.contains("write_files")) {
            sb.append(" Use write_files when one directory-backed artifact needs several files to be created together.");
        }
        if (registeredTools.contains("patch_file")) {
            sb.append(" Use patch_file only when you can make an exact targeted replacement.");
        }
        return sb.toString();
    }

    private String buildAutoWriteUserPrompt(TaskRuntimeContext context,
                                            Worker worker,
                                            TaskToolState toolState,
                                            ToolPlan originalPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context, false, PromptRenderingMode.resolve(context.task())));
        sb.append("\n\nWorker Tool Capabilities: ").append(registeredToolCapabilities(worker));
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
        appendExpectedGroundedOutput(sb, toolState);
        if (toolState.outputFileRequired()) {
            String existingFile = loadExistingOutputFile(toolState.outputFilePath(), 12000);
            if (!existingFile.isBlank()) {
                sb.append("\nExisting Output File Content:\n").append(existingFile);
            }
        }
        sb.append("\nWrite the exact full content that should overwrite the output file for the current round.");
        sb.append(" Do not describe what to write; provide the final text itself in content.");
        return sb.toString();
    }

    private String buildAutoWriteFilesUserPrompt(TaskRuntimeContext context,
                                                 Worker worker,
                                                 TaskToolState toolState,
                                                 ToolPlan originalPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTaskPrompt(context, false, PromptRenderingMode.resolve(context.task())));
        sb.append("\n\nWorker Tool Capabilities: ").append(registeredToolCapabilities(worker));
        if (!worker.toolScope().isEmpty()) {
            sb.append("\nWorker Tool Scope: ").append(worker.toolScope());
        }
        sb.append("\nPlanner Returned No Additional Tool: true");
        sb.append("\nOriginal Planning Reason: ").append(firstNonBlank(originalPlan.reason(), "(empty)"));
        sb.append("\nGrounded Output Directory Required: ").append(toolState.outputDirRequired());
        sb.append("\nPrior Tool Trace:\n").append(truncate(toolState.recentToolTrace(), 2000));
        String carryForwardNotes = extractCarryForwardNotes(context, 5000);
        if (!carryForwardNotes.isBlank()) {
            sb.append("\nCarry-Forward Notes From Prior Rounds:\n").append(carryForwardNotes);
        }
        appendExpectedGroundedOutput(sb, toolState);
        sb.append("\nCreate the minimal runnable directory-backed artifact now.");
        sb.append("\nUse base_path: ").append(toolState.outputDirPath());
        sb.append("\nAll file paths in files[] must be relative to base_path.");
        return sb.toString();
    }

    private String buildFinalizationSystemPrompt(Worker worker) {
        return "You are a tool-enabled execution worker. Worker ID: " + worker.workerId() + ". "
            + "You already received one tool result. Produce the current-round result, not the whole task unless the provided evidence proves the whole task is complete. "
            + "Return a JSON object containing exactly these fields: "
            + "summary (string), output_text (string), produced_artifact (boolean), "
            + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
            + "confidence (high|medium|low). "
            + "Ground every claim in the provided tool result and grounded output state. "
            + "Never claim that a grounded output was written unless the current tool is "
            + groundedWriteToolHint(registeredToolCapabilities(worker))
            + " and Tool Success is true. "
            + "If a declared future round remains, explicitly say the overall task is not complete yet and set suggested_next_step to that next round. "
            + "If a grounded output is required and it does not exist after this round, produced_artifact must be false. "
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
        sb.append(buildTaskPrompt(context, true, PromptRenderingMode.resolve(context.task())));
        sb.append("\n\nWorker Tool Capabilities: ").append(registeredToolCapabilities(worker));
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
        appendGroundedOutputState(sb, toolStateBefore, toolStateAfter, 6000);
        if (!outcome.result().output().isBlank()) {
            sb.append("\nTool Output:\n").append(truncate(outcome.result().output(), 6000));
        }
        sb.append("\n\nProduce the grounded current-round execution result now.");
        return sb.toString();
    }

    private String buildTaskPrompt(TaskRuntimeContext context) {
        return buildTaskPrompt(context, true, PromptRenderingMode.resolve(context.task()));
    }

    private String buildTaskPrompt(TaskRuntimeContext context, boolean includeFullActiveContext) {
        return buildTaskPrompt(context, includeFullActiveContext, PromptRenderingMode.resolve(context.task()));
    }

    private String buildTaskPrompt(TaskRuntimeContext context,
                                   boolean includeFullActiveContext,
                                   PromptRenderingMode renderingMode) {
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
        appendMountedContext(sb, context, renderingMode);
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
        if (context.recentMessages() != null && !context.recentMessages().isEmpty()) {
            sb.append("\nRecent Messages:\n");
            for (String line : formatRecentMessages(context.recentMessages(), 6)) {
                sb.append("- ").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void appendMountedContext(StringBuilder sb,
                                      TaskRuntimeContext context,
                                      PromptRenderingMode renderingMode) {
        if (renderingMode == null || !renderingMode.shouldInjectMountedPrompt()) {
            return;
        }
        String mountedPrompt = mountedContextPromptRenderer.render(context);
        if (mountedPrompt.isBlank()) {
            return;
        }
        sb.append("\n").append(mountedPrompt);
    }

    private WorkerExecutionResult withPromptRenderingMetadata(WorkerExecutionResult result,
                                                              TaskRuntimeContext context,
                                                              PromptRenderingMode renderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (result.metadata() != null) {
            metadata.putAll(result.metadata());
        }
        String mountedPrompt = renderingMode.shouldRenderMountedPrompt() ? mountedContextPromptRenderer.render(context) : "";
        metadata.putAll(MountedContextPromptMetrics.from(context, renderingMode, mountedPrompt).toMetadata());
        List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
        metadata.put("image_input_count", imageInputs.size());
        metadata.put("image_input_used", !imageInputs.isEmpty());
        return new WorkerExecutionResult(
            result.summary(),
            result.outputText(),
            result.producedArtifact(),
            result.artifactTitle(),
            result.artifactContent(),
            result.suggestedNextStep(),
            result.confidence(),
            result.executionStatus(),
            result.evidenceRefs(),
            result.unfinishedItems(),
            result.tokenUsage(),
            result.durationMs(),
            metadata
        );
    }

    private List<String> formatRecentMessages(List<SessionMessage> messages, int limit) {
        if (messages == null || messages.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = Math.max(0, messages.size() - limit);
        for (int index = start; index < messages.size(); index++) {
            SessionMessage message = messages.get(index);
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = message.role() == null || message.role().isBlank() ? "message" : message.role();
            String type = message.messageType() == null || message.messageType().isBlank() ? "" : " [" + message.messageType() + "]";
            String content = message.content().replaceAll("\\s+", " ").trim();
            if (content.length() > 240) {
                content = content.substring(0, 240) + "...";
            }
            lines.add(role + type + ": " + content);
        }
        return lines;
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

    private String autoWriteFilesGenerationMode(AutoWriteFilesDraft draft) {
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
        if (draft.files().isEmpty()) {
            return "empty_files";
        }
        return "generated";
    }

    private void appendGroundedOutputMetadata(Map<String, Object> metadata, TaskToolState toolState) {
        if (metadata == null || toolState == null) {
            return;
        }
        metadata.put("output_file_required", toolState.outputFileRequired());
        metadata.put("output_file_path", toolState.outputFilePath());
        metadata.put("output_file_exists", toolState.outputFileExists());
        metadata.put("output_file_size", toolState.outputFileSize());
        metadata.put("output_dir_required", toolState.outputDirRequired());
        metadata.put("output_dir_path", toolState.outputDirPath());
        metadata.put("output_dir_exists", toolState.outputDirExists());
        metadata.put("output_dir_entry_count", toolState.outputDirEntryCount());
    }

    private void appendDelegatedToolStateMetadata(Map<String, Object> metadata, TaskToolState toolState) {
        if (metadata == null || toolState == null) {
            return;
        }
        appendGroundedOutputMetadata(metadata, toolState);
        metadata.put("tool_round_index", toolState.totalToolCount());
        metadata.put("declared_round_count", toolState.declaredRoundCount());
        metadata.put("grounded_output_present", toolState.groundedOutputExists());
        metadata.put("file_backed_artifact", toolState.outputFileExists());
        metadata.put("directory_backed_artifact", toolState.outputDirExists() && toolState.outputDirEntryCount() > 0);
        if (!toolState.currentRoundInstruction().isBlank()) {
            metadata.put("current_round_instruction", toolState.currentRoundInstruction());
        }
        if (!toolState.nextRoundInstruction().isBlank()) {
            metadata.put("next_round_instruction", toolState.nextRoundInstruction());
        }
    }

    private boolean isGroundedArtifactProduced(String toolName, boolean toolSuccess, TaskToolState toolStateAfter) {
        return toolSuccess
            && isGroundedWriteTool(toolName)
            && toolStateAfter != null
            && toolStateAfter.groundedOutputExists();
    }

    private String groundedArtifactTitle(TaskToolState toolState) {
        if (toolState == null) {
            return "";
        }
        if (toolState.outputDirRequired()) {
            return fileName(toolState.outputDirPath());
        }
        if (toolState.outputFileRequired()) {
            return fileName(toolState.outputFilePath());
        }
        return "";
    }

    private String awaitingGroundedOutputSummary(TaskToolState toolState, boolean toolSuccess) {
        if (toolState == null) {
            return toolSuccess ? "尚未产生 grounded 输出。" : "工具执行失败，且尚未产生 grounded 输出。";
        }
        if (toolState.outputDirRequired()) {
            return toolSuccess
                ? "本轮已执行工具，但目标输出目录仍为空或尚未创建。"
                : "工具执行失败，目标输出目录仍为空或尚未创建。";
        }
        return toolSuccess
            ? "本轮已执行工具，但目标输出文件仍未生成。"
            : "工具执行失败，目标输出文件仍未生成。";
    }

    private TaskToolState taskToolStateFromOutcome(ToolExecutionOutcome outcome) {
        if (outcome == null || outcome.result() == null) {
            return new TaskToolState("", false, 0L, "", "", false, 0, "", 0, 0, 0, 0, "", "(no prior tool rounds)", 0, 0, "", "");
        }
        String outputFilePath = firstNonBlank(
            stringValue(outcome.result().metadata().get("output_file_path")),
            stringValue(outcome.result().metadata().get("path")),
            stringValue(outcome.request().arguments().get("path"))
        );
        String outputDirPath = firstNonBlank(
            stringValue(outcome.result().metadata().get("output_dir_path")),
            stringValue(outcome.request().arguments().get("base_path"))
        );
        return buildGroundedOutputState(outputFilePath, outputDirPath);
    }

    private TaskToolState buildGroundedOutputState(String outputFilePath, String outputDirPath) {
        boolean outputFileExists = false;
        long outputFileSize = 0L;
        String outputFileFingerprint = "";
        boolean outputDirExists = false;
        int outputDirEntryCount = 0;
        String outputDirFingerprint = "";
        if (outputFilePath != null && !outputFilePath.isBlank()) {
            try {
                Path outputPath = Path.of(outputFilePath).toAbsolutePath().normalize();
                outputFileExists = Files.exists(outputPath);
                if (outputFileExists) {
                    outputFileSize = Files.size(outputPath);
                    outputFileFingerprint = fileFingerprint(outputPath);
                }
                outputFilePath = outputPath.toString();
            } catch (InvalidPathException | IOException e) {
                log.warn("Failed to inspect grounded output file state. path={} reason={}", outputFilePath, e.getMessage());
            }
        }
        if (outputDirPath != null && !outputDirPath.isBlank()) {
            try {
                Path outputPath = Path.of(outputDirPath).toAbsolutePath().normalize();
                outputDirExists = Files.exists(outputPath) && Files.isDirectory(outputPath);
                if (outputDirExists) {
                    outputDirEntryCount = countDirectoryEntries(outputPath);
                    outputDirFingerprint = directoryFingerprint(outputPath);
                }
                outputDirPath = outputPath.toString();
            } catch (InvalidPathException | IOException e) {
                log.warn("Failed to inspect grounded output dir state. path={} reason={}", outputDirPath, e.getMessage());
            }
        }
        return new TaskToolState(
            firstNonBlank(outputFilePath),
            outputFileExists,
            outputFileSize,
            outputFileFingerprint,
            firstNonBlank(outputDirPath),
            outputDirExists,
            outputDirEntryCount,
            outputDirFingerprint,
            0,
            0,
            0,
            0,
            "",
            "(no prior tool rounds)",
            0,
            0,
            "",
            ""
        );
    }

    private void appendExpectedGroundedOutput(StringBuilder sb, TaskToolState toolState) {
        if (sb == null || toolState == null) {
            return;
        }
        if (toolState.outputFileRequired()) {
            sb.append("\nExpected Output File: ").append(toolState.outputFilePath());
            sb.append("\nOutput File Exists: ").append(toolState.outputFileExists());
            sb.append("\nOutput File Size: ").append(toolState.outputFileSize());
        }
        if (toolState.outputDirRequired()) {
            sb.append("\nExpected Output Directory: ").append(toolState.outputDirPath());
            sb.append("\nOutput Directory Exists: ").append(toolState.outputDirExists());
            sb.append("\nOutput Directory Entry Count: ").append(toolState.outputDirEntryCount());
            String existingDirectory = loadExistingOutputDirectory(toolState.outputDirPath(), 4000);
            if (!existingDirectory.isBlank()) {
                sb.append("\nExisting Output Directory Entries:\n").append(existingDirectory);
            }
        }
    }

    private void appendGroundedOutputState(StringBuilder sb,
                                           TaskToolState toolStateBefore,
                                           TaskToolState toolStateAfter,
                                           int maxChars) {
        if (sb == null) {
            return;
        }
        if (toolStateBefore != null) {
            appendExpectedGroundedOutput(sb, toolStateBefore);
        }
        if (toolStateAfter == null) {
            return;
        }
        if (toolStateAfter.outputFileRequired()) {
            sb.append("\nOutput File Exists After Tool: ").append(toolStateAfter.outputFileExists());
            sb.append("\nOutput File Size After Tool: ").append(toolStateAfter.outputFileSize());
            String existingFile = loadExistingOutputFile(toolStateAfter.outputFilePath(), maxChars);
            if (!existingFile.isBlank()) {
                sb.append("\nExisting Output File Content:\n").append(existingFile);
            }
        }
        if (toolStateAfter.outputDirRequired()) {
            sb.append("\nOutput Directory Exists After Tool: ").append(toolStateAfter.outputDirExists());
            sb.append("\nOutput Directory Entry Count After Tool: ").append(toolStateAfter.outputDirEntryCount());
            String existingDirectory = loadExistingOutputDirectory(toolStateAfter.outputDirPath(), maxChars);
            if (!existingDirectory.isBlank()) {
                sb.append("\nExisting Output Directory Entries:\n").append(existingDirectory);
            }
        }
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
            if (isGroundedWriteTool(invocation.toolName())) {
                successfulWriteCount++;
            }
        }

        String outputFilePath = metadataString(context.task().metadata(), "output_file");
        String outputDirPath = metadataString(context.task().metadata(), "output_dir");
        TaskToolState groundedOutputState = buildGroundedOutputState(outputFilePath, outputDirPath);

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
            groundedOutputState.outputFilePath(),
            groundedOutputState.outputFileExists(),
            groundedOutputState.outputFileSize(),
            groundedOutputState.outputFileFingerprint(),
            groundedOutputState.outputDirPath(),
            groundedOutputState.outputDirExists(),
            groundedOutputState.outputDirEntryCount(),
            groundedOutputState.outputDirFingerprint(),
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

    private String fileFingerprint(Path path) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Failed to compute file fingerprint. path={} reason={}", path, e.getMessage());
            return "";
        }
    }

    private String summaryForCurrentRound(ToolPlan plan,
                                          boolean groundedArtifact,
                                          TaskToolState toolStateBefore,
                                          TaskToolState toolStateAfter) {
        if ("read_file".equals(plan.toolName()) && instructionSuggestsFileWrite(toolStateBefore.currentRoundInstruction())) {
            return "本轮仅完成了读取或分析，尚未执行当前轮要求的 grounded 输出写入。";
        }
        if ("read_file".equals(plan.toolName())) {
            return "已完成当前轮参考资料读取与整理，整体任务仍需继续。";
        }
        if (groundedArtifact && toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
            return "已完成当前轮 grounded 输出写入，整体任务仍需继续下一轮。";
        }
        if (groundedArtifact) {
            return "已完成当前轮 grounded 输出写入。";
        }
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            return "已完成当前轮目标，整体任务仍需继续。";
        }
        return "";
    }

    private String buildGroundedPrefix(ToolPlan plan,
                                       TaskToolState toolStateBefore,
                                       TaskToolState toolStateAfter,
                                       boolean groundedArtifact,
                                       boolean moreDeclaredRoundsRemain,
                                       boolean missingRequiredCurrentRoundWrite) {
        StringBuilder sb = new StringBuilder();
        if (moreDeclaredRoundsRemain) {
            sb.append("Current round finished, but the overall task is not complete yet.");
            if (toolStateAfter.currentRoundInstruction() != null && !toolStateAfter.currentRoundInstruction().isBlank()) {
                sb.append(" Next step: ").append(toolStateAfter.currentRoundInstruction()).append(".");
            }
        } else if (missingRequiredCurrentRoundWrite) {
            sb.append("The current round has not performed the required grounded output write yet.");
            if ("read_file".equals(plan.toolName())) {
                sb.append(" This round only completed reading and analysis.");
            }
            if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
                sb.append(" Required current-round action: ").append(toolStateBefore.currentRoundInstruction()).append(".");
            }
        } else if (toolStateAfter.outputRequired() && !toolStateAfter.groundedOutputExists()) {
            sb.append(toolStateAfter.outputDirRequired()
                ? "The required output directory has not been created yet."
                : "The required output file has not been written yet.");
            if ("read_file".equals(plan.toolName())) {
                sb.append(" This round only completed reading and analysis.");
            }
        } else if (groundedArtifact) {
            sb.append(toolStateAfter.outputDirExists()
                ? "The output directory exists and is grounded by a successful grounded write tool call."
                : "The output file exists and is grounded by a successful grounded write tool call.");
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

    private String defaultNextStep(TaskToolState toolState) {
        if (toolState.currentRoundInstruction() != null && !toolState.currentRoundInstruction().isBlank()) {
            return toolState.currentRoundInstruction();
        }
        if (toolState.outputFileRequired() && !toolState.outputFileExists()) {
            return "Write the next article version to '" + toolState.outputFilePath() + "'.";
        }
        if (toolState.outputDirRequired() && !toolState.groundedOutputExists()) {
            return "Create or update the grounded output under '" + toolState.outputDirPath() + "'.";
        }
        return "";
    }

    private String requiredWriteNextStep(TaskToolState toolStateBefore, TaskToolState toolStateAfter) {
        if (toolStateBefore.currentRoundInstruction() != null && !toolStateBefore.currentRoundInstruction().isBlank()) {
            return toolStateBefore.currentRoundInstruction();
        }
        if (toolStateAfter.outputFileRequired()) {
            return "Use write_file or patch_file to update the required article version at '" + toolStateAfter.outputFilePath() + "'.";
        }
        if (toolStateAfter.outputDirRequired()) {
            return "Use write_files, write_file, or patch_file to create the required grounded output under '" + toolStateAfter.outputDirPath() + "'.";
        }
        return "Use a grounded write tool to complete the required current-round update.";
    }

    private boolean requiresCurrentRoundWrite(TaskToolState toolState) {
        return toolState != null
            && toolState.outputRequired()
            && instructionSuggestsFileWrite(toolState.currentRoundInstruction());
    }

    private boolean shouldAutoGroundDirectoryWrite(Worker worker, TaskToolState toolState) {
        if (worker == null || toolState == null) {
            return false;
        }
        if (!toolState.outputDirRequired() || toolState.groundedOutputExists()) {
            return false;
        }
        return registeredToolCapabilities(worker).contains("write_files");
    }

    private ToolPlan buildInitialProbePlan(Worker worker, TaskToolState toolState, ToolPlan originalPlan) {
        if (worker == null || toolState == null || !toolState.outputRequired() || toolState.groundedOutputExists()) {
            return null;
        }
        if (toolState.successfulToolCount() > 0) {
            return null;
        }
        List<String> registeredTools = registeredToolCapabilities(worker);
        if (!registeredTools.contains("list_files")) {
            return null;
        }
        String probePath = initialProbePath(worker, toolState);
        if (probePath.isBlank()) {
            return null;
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", probePath);
        arguments.put("recursive", false);
        arguments.put("max_entries", 80);
        return new ToolPlan(
            true,
            "list_files",
            arguments,
            "planner returned no tool before any successful scope inspection; run a lightweight directory probe first",
            firstNonBlank(originalPlan.rawResponse(), originalPlan.reason())
        );
    }

    private String initialProbePath(Worker worker, TaskToolState toolState) {
        if (worker == null || worker.toolScope() == null || worker.toolScope().isEmpty()) {
            return "";
        }
        try {
            Path scopeRoot = Path.of(worker.toolScope().get(0)).toAbsolutePath().normalize();
            Path candidate = scopeRoot;
            if (toolState.outputDirRequired()) {
                Path outputDir = Path.of(toolState.outputDirPath()).toAbsolutePath().normalize();
                Path preferred = outputDir.getParent();
                if (preferred != null && preferred.startsWith(scopeRoot)) {
                    candidate = preferred;
                } else if (outputDir.startsWith(scopeRoot)) {
                    candidate = outputDir;
                }
            } else if (toolState.outputFileRequired()) {
                Path outputFile = Path.of(toolState.outputFilePath()).toAbsolutePath().normalize();
                Path preferred = outputFile.getParent();
                if (preferred != null && preferred.startsWith(scopeRoot)) {
                    candidate = preferred;
                }
            }
            if (!candidate.startsWith(scopeRoot)) {
                candidate = scopeRoot;
            }
            Path relative = scopeRoot.relativize(candidate);
            return relative.toString().isBlank() ? "." : relative.toString();
        } catch (InvalidPathException e) {
            log.warn("Failed to resolve initial probe path. worker={} reason={}", worker.workerId(), e.getMessage());
            return ".";
        }
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

    private boolean isGroundedWriteTool(String toolName) {
        return "write_file".equals(toolName) || "write_files".equals(toolName) || "patch_file".equals(toolName);
    }

    private String groundedWriteToolHint(List<String> registeredTools) {
        if (registeredTools == null || registeredTools.isEmpty()) {
            return "a grounded write tool";
        }
        boolean supportsWrite = registeredTools.contains("write_file");
        boolean supportsWriteFiles = registeredTools.contains("write_files");
        boolean supportsPatch = registeredTools.contains("patch_file");
        if ((supportsWrite || supportsWriteFiles) && supportsPatch) {
            if (supportsWrite && supportsWriteFiles) {
                return "write_file, write_files, or patch_file";
            }
            return supportsWrite
                ? "write_file or patch_file"
                : "write_files or patch_file";
        }
        if (supportsWrite && supportsWriteFiles) {
            return "write_file or write_files";
        }
        if (supportsWrite) {
            return "write_file";
        }
        if (supportsWriteFiles) {
            return "write_files";
        }
        if (supportsPatch) {
            return "patch_file";
        }
        return "a grounded write tool";
    }

    private String resolvedGroundedArtifactContent(String currentArtifactContent,
                                                   ToolPlan plan,
                                                   ToolExecutionOutcome outcome,
                                                   TaskToolState toolState,
                                                   int maxChars) {
        String artifactContent = currentArtifactContent == null ? "" : currentArtifactContent;
        if (!artifactContent.isBlank()) {
            return artifactContent;
        }
        if (toolState.outputDirRequired()) {
            artifactContent = loadExistingOutputDirectory(toolState.outputDirPath(), maxChars);
        } else {
            String resolvedPath = firstNonBlank(
                toolState.outputFilePath(),
                stringValue(outcome.result().metadata().get("path")),
                stringValue(outcome.request().arguments().get("path"))
            );
            artifactContent = loadExistingOutputFile(resolvedPath, maxChars);
        }
        if (!artifactContent.isBlank()) {
            return artifactContent;
        }
        if ("write_file".equals(plan.toolName())) {
            return truncate(stringValue(outcome.request().arguments().get("content")), maxChars);
        }
        if ("write_files".equals(plan.toolName())) {
            return truncate(JsonMapper.toJson(outcome.request().arguments().get("files")), maxChars);
        }
        return "";
    }

    private List<String> registeredToolCapabilities(Worker worker) {
        if (worker == null || worker.toolCapabilities() == null || worker.toolCapabilities().isEmpty()) {
            return List.of();
        }
        return worker.toolCapabilities().stream()
            .filter(toolRegistry::contains)
            .toList();
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

    private String loadExistingOutputDirectory(String path, int maxChars) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path dir = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.walk(dir)) {
                List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .limit(20)
                    .toList();
                for (Path file : paths) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append("- ").append(dir.relativize(file)).append(" (").append(Files.size(file)).append(" bytes)");
                    if (sb.length() >= maxChars) {
                        break;
                    }
                }
            }
            return truncate(sb.toString(), maxChars);
        } catch (IOException | InvalidPathException e) {
            log.warn("Failed to load existing output directory. path={} reason={}", path, e.getMessage());
            return "";
        }
    }

    private int countDirectoryEntries(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }
        try (var stream = Files.walk(directory)) {
            return (int) stream
                .filter(Files::isRegularFile)
                .limit(Integer.MAX_VALUE)
                .count();
        }
    }

    private String directoryFingerprint(Path directory) {
        if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.walk(directory)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .limit(200)
                    .toList();
                for (Path file : files) {
                    String relative = directory.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                    String fingerprint = fileFingerprint(file);
                    if (!fingerprint.isBlank()) {
                        digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Failed to compute directory fingerprint. path={} reason={}", directory, e.getMessage());
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
        String outputFileFingerprint,
        String outputDirPath,
        boolean outputDirExists,
        int outputDirEntryCount,
        String outputDirFingerprint,
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
            if (outputFileFingerprint == null) outputFileFingerprint = "";
            if (outputDirPath == null) outputDirPath = "";
            if (outputDirFingerprint == null) outputDirFingerprint = "";
            if (lastToolName == null) lastToolName = "";
            if (recentToolTrace == null || recentToolTrace.isBlank()) recentToolTrace = "(no prior tool rounds)";
            if (currentRoundInstruction == null) currentRoundInstruction = "";
            if (nextRoundInstruction == null) nextRoundInstruction = "";
        }

        private boolean outputFileRequired() {
            return !outputFilePath.isBlank();
        }

        private boolean outputDirRequired() {
            return !outputDirPath.isBlank();
        }

        private boolean outputRequired() {
            return outputFileRequired() || outputDirRequired();
        }

        private boolean groundedOutputExists() {
            return outputFileExists || (outputDirExists && outputDirEntryCount > 0);
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

    private record AutoWriteFilesDraft(
        String summary,
        String basePath,
        List<Map<String, Object>> files,
        String suggestedNextStep,
        String confidence,
        String rawResponse,
        String failureReason
    ) {
        private AutoWriteFilesDraft {
            if (summary == null) summary = "";
            if (basePath == null) basePath = "";
            if (files == null) files = List.of();
            if (suggestedNextStep == null) suggestedNextStep = "";
            if (confidence == null || confidence.isBlank()) confidence = "medium";
            if (rawResponse == null) rawResponse = "";
            if (failureReason == null) failureReason = "";
        }
    }

    private List<String> extractTouchedPaths(Map<String, Object> arguments, Map<String, Object> traceMetadata) {
        List<String> touchedPaths = new ArrayList<>();
        addTouchedPath(touchedPaths, arguments == null ? null : arguments.get("path"));
        addTouchedPath(touchedPaths, arguments == null ? null : arguments.get("file_path"));
        addTouchedPath(touchedPaths, arguments == null ? null : arguments.get("output_path"));
        addTouchedPath(touchedPaths, arguments == null ? null : arguments.get("base_path"));
        addTouchedPath(touchedPaths, traceMetadata == null ? null : traceMetadata.get("output_file_path"));
        addTouchedPath(touchedPaths, traceMetadata == null ? null : traceMetadata.get("output_dir_path"));
        Object writtenPaths = traceMetadata == null ? null : traceMetadata.get("written_paths");
        if (writtenPaths instanceof List<?> paths) {
            for (Object candidate : paths) {
                addTouchedPath(touchedPaths, candidate);
            }
        }
        return touchedPaths;
    }

    private void addTouchedPath(List<String> touchedPaths, Object candidate) {
        if (candidate == null) {
            return;
        }
        String text = candidate.toString().trim();
        if (text.isBlank()) {
            return;
        }
        if (!touchedPaths.contains(text)) {
            touchedPaths.add(text);
        }
    }

    private record ToolChainStep(
        int stepIndex,
        String selectedTool,
        Map<String, Object> arguments,
        String resultSummary,
        String whySelected,
        String whyNextStep,
        boolean success,
        int elapsedMs,
        String outputPreview
    ) {
        private ToolChainStep {
            if (selectedTool == null) selectedTool = "";
            if (arguments == null) arguments = Map.of();
            if (resultSummary == null) resultSummary = "";
            if (whySelected == null) whySelected = "";
            if (whyNextStep == null) whyNextStep = "";
            if (outputPreview == null) outputPreview = "";
        }

        private ToolChainStep withWhyNextStep(String updatedWhyNextStep) {
            return new ToolChainStep(
                stepIndex,
                selectedTool,
                arguments,
                resultSummary,
                whySelected,
                updatedWhyNextStep,
                success,
                elapsedMs,
                outputPreview
            );
        }
    }
}
