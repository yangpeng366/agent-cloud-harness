package com.agentcloud.worker;

import com.agentcloud.llm.LlmClient;
import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.llm.LlmImageInputResolver;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.runtime.RuntimeFactPromptFormatter;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.MountedContextPromptRenderResult;
import com.agentcloud.runtime.context.MountedContextPromptMetrics;
import com.agentcloud.runtime.context.MountedContextPromptRenderer;
import com.agentcloud.runtime.context.PromptRenderingMode;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 Worker 执行器。
 * 基于 task + packet + recent events/decisions/artifacts 组装 prompt，调用 LLM 返回文本结果。
 */
public class DefaultWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmClient llmClient;
    private final MountedContextPromptRenderer mountedContextPromptRenderer;
    private final RuntimeFactSetAssembler runtimeFactSetAssembler;
    private final RuntimeFactPromptFormatter runtimeFactPromptFormatter;

    public DefaultWorkerExecutor(LlmClient llmClient) {
        this(llmClient, new MountedContextPromptRenderer(), new RuntimeFactSetAssembler(), new RuntimeFactPromptFormatter());
    }

    DefaultWorkerExecutor(LlmClient llmClient, MountedContextPromptRenderer mountedContextPromptRenderer) {
        this(llmClient, mountedContextPromptRenderer, new RuntimeFactSetAssembler(), new RuntimeFactPromptFormatter());
    }

    public DefaultWorkerExecutor(LlmClient llmClient,
                                 MountedContextPromptRenderer mountedContextPromptRenderer,
                                 RuntimeFactSetAssembler runtimeFactSetAssembler,
                                 RuntimeFactPromptFormatter runtimeFactPromptFormatter) {
        this.llmClient = llmClient;
        this.mountedContextPromptRenderer = mountedContextPromptRenderer == null
            ? new MountedContextPromptRenderer()
            : mountedContextPromptRenderer;
        this.runtimeFactSetAssembler = runtimeFactSetAssembler == null
            ? new RuntimeFactSetAssembler()
            : runtimeFactSetAssembler;
        this.runtimeFactPromptFormatter = runtimeFactPromptFormatter == null
            ? new RuntimeFactPromptFormatter()
            : runtimeFactPromptFormatter;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        long startMs = System.currentTimeMillis();
        PromptRenderingMode renderingMode = PromptRenderingMode.resolve(context);
        MountedContextPromptRenderResult mountedRenderResult = renderingMode.shouldRenderMountedPrompt()
            ? mountedContextPromptRenderer.renderResult(context)
            : MountedContextPromptRenderResult.empty();

        String systemPrompt = buildSystemPrompt(context, workerId);
        String userPrompt = buildUserPrompt(context, workerId, renderingMode, mountedRenderResult);
        List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);

        String raw = llmClient.chat(systemPrompt, userPrompt, imageInputs);
        long durationMs = System.currentTimeMillis() - startMs;
        WorkerExecutionResult result = parseExecutionResult(raw, durationMs);
        WorkerExecutionResult enriched = attachRenderingMetadata(result, renderingMode, context, imageInputs, mountedRenderResult);
        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(
            context,
            renderingMode,
            mountedRenderResult
        );

        log.info("Worker round completed. task={}, worker={}, outputLength={}, durationMs={}, promptMode={}, mountedRenderUsed={}, mountedPanelCount={}, mountedActiveCount={}, mountedEvidenceCount={}, mountedArchiveCount={}",
            context.task().id(),
            workerId,
            enriched.outputText().length(),
            durationMs,
            metrics.promptMode(),
            metrics.mountedRenderUsed(),
            metrics.panelCount(),
            metrics.activeCount(),
            metrics.evidenceCount(),
            metrics.archiveCount());

        return enriched;
    }

    private String buildSystemPrompt(TaskRuntimeContext context, String workerId) {
        String modelMode = metadataString(context.task().metadata(), "model_mode");
        String orchestrationStage = metadataString(context.task().metadata(), "orchestration_stage");
        if ("orchestrated".equalsIgnoreCase(modelMode) && isPlannerStage(orchestrationStage)) {
            return "You are the strong planning worker in an orchestration runtime. Worker ID: " + workerId
                + ". Do not try to finish the entire task if it can be delegated. "
                + "Produce a compact execution brief for a smaller executor and clearly state the immediate next step. "
                + "Respond with a JSON object containing exactly these fields: "
                + "summary (string), output_text (string), produced_artifact (boolean), "
                + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
                + "confidence (high|medium|low). Keep summary concise, keep output_text under 1200 characters, "
                + "keep artifact_content under 1600 characters, and use suggested_next_step for the executor handoff instruction. "
                + "No markdown, no extra text.";
        }
        if ("orchestrated".equalsIgnoreCase(modelMode) && isExecutionStage(orchestrationStage)) {
            return "You are the delegated execution worker in an orchestration runtime. Worker ID: " + workerId
                + ". Follow the current next step and planning brief instead of replanning the whole task. "
                + "Respond with a JSON object containing exactly these fields: "
                + "summary (string), output_text (string), produced_artifact (boolean), "
                + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
                + "confidence (high|medium|low). Keep summary concise, keep output_text under 1200 characters, "
                + "keep artifact_content under 1600 characters, and focus on concrete execution progress. "
                + "No markdown, no extra text.";
        }
        return "You are a task execution worker. Worker ID: " + workerId
            + ". Execute the assigned task to the best of your ability. "
            + "Respond with a JSON object containing exactly these fields: "
            + "summary (string), output_text (string), produced_artifact (boolean), "
            + "artifact_title (string), artifact_content (string), suggested_next_step (string), "
            + "confidence (high|medium|low). Keep summary concise, keep output_text under 1200 characters, "
            + "keep artifact_content under 1600 characters, and prefer a compact actionable answer over a long draft. "
            + "No markdown, no extra text.";
    }

    private String buildUserPrompt(TaskRuntimeContext context,
                                   String workerId,
                                   PromptRenderingMode renderingMode,
                                   MountedContextPromptRenderResult mountedRenderResult) {
        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(context, renderingMode, mountedRenderResult);
        var t = context.task();
        StringBuilder sb = new StringBuilder();
        WorkerPromptHeaderBuilder.appendTaskHeader(sb, t, false);
        String modelMode = metadataString(t.metadata(), "model_mode");
        String orchestrationStage = metadataString(t.metadata(), "orchestration_stage");
        if (modelMode != null) {
            sb.append("Model Mode: ").append(modelMode).append("\n");
        }
        if (orchestrationStage != null) {
            sb.append("Orchestration Stage: ").append(orchestrationStage).append("\n");
        }
        if (t.nextStep() != null && !t.nextStep().isBlank()) {
            sb.append("Next Step: ").append(t.nextStep()).append("\n");
        }
        if ("orchestrated".equalsIgnoreCase(modelMode) && isPlannerStage(orchestrationStage)) {
            sb.append("Execution Contract: produce a delegation brief for a small executor, not a final closeout.\n");
        } else if ("orchestrated".equalsIgnoreCase(modelMode) && isExecutionStage(orchestrationStage)) {
            sb.append("Execution Contract: execute the delegated next step before proposing broader replans.\n");
        }
        appendMountedContext(sb, renderingMode, mountedRenderResult);
        if (context.activeContext() != null && !context.activeContext().synthesizedContext().isBlank()) {
            sb.append("\nActive Context:\n");
            sb.append(context.activeContext().synthesizedContext()).append("\n");
        }
        if (context.recentMessages() != null && !context.recentMessages().isEmpty()) {
            sb.append("\nRecent Messages:\n");
            for (String line : formatRecentMessages(context.recentMessages(), 6)) {
                sb.append("- ").append(line).append("\n");
            }
        }
        if (context.activeContext() == null || context.activeContext().synthesizedContext().isBlank()) {
            if (context.latestPacket() != null && context.latestPacket().activeTaskSummary() != null) {
                sb.append("Context Summary: ").append(context.latestPacket().activeTaskSummary()).append("\n");
            }

            if (context.recentEvents() != null && !context.recentEvents().isEmpty()) {
                sb.append("\nRecent Events:\n");
                for (Event e : context.recentEvents()) {
                    sb.append("- [").append(e.eventType()).append("] ").append(e.summary()).append("\n");
                }
            }

            if (context.recentDecisions() != null && !context.recentDecisions().isEmpty()) {
                sb.append("\nRecent Decisions:\n");
                for (Decision d : context.recentDecisions()) {
                    sb.append("- ").append(d.summary()).append("\n");
                }
            }

            if (context.recentArtifacts() != null && !context.recentArtifacts().isEmpty()) {
                sb.append("\nRecent Artifacts:\n");
                for (Artifact a : context.recentArtifacts()) {
                    sb.append("- ").append(a.title() != null ? a.title() : "artifact");
                    if (a.summary() != null) sb.append(": ").append(a.summary());
                    sb.append("\n");
                }
            }

        }
        appendRuntimeFactSurface(sb, context, workerId, metrics);

        sb.append("\nPlease execute the task and provide your output.");
        return sb.toString();
    }

    private void appendMountedContext(StringBuilder sb,
                                      PromptRenderingMode renderingMode,
                                      MountedContextPromptRenderResult mountedRenderResult) {
        if (renderingMode == null || !renderingMode.shouldInjectMountedPrompt()) {
            return;
        }
        String mountedPrompt = mountedRenderResult == null ? "" : mountedRenderResult.prompt();
        if (mountedPrompt.isBlank()) {
            return;
        }
        sb.append("\n").append(mountedPrompt);
    }

    private void appendRuntimeFactSurface(StringBuilder sb,
                                          TaskRuntimeContext context,
                                          String workerId,
                                          MountedContextPromptMetrics metrics) {
        RuntimeFactSet factSet = resolveRuntimeFactSet(context, workerId, metrics);
        if (factSet == null) {
            return;
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append("\n");
        }
        runtimeFactPromptFormatter.append(sb, factSet);
    }

    private RuntimeFactSet resolveRuntimeFactSet(TaskRuntimeContext context,
                                                 String workerId,
                                                 MountedContextPromptMetrics metrics) {
        if (context == null || context.task() == null) {
            return null;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (metrics != null) {
            metadata.putAll(metrics.toMetadata());
        }
        if (workerId != null && !workerId.isBlank()) {
            metadata.put("selected_worker", workerId);
        }
        return runtimeFactSetAssembler.assemble(
            context.task(),
            context,
            12,
            metadata
        );
    }

    private WorkerExecutionResult attachRenderingMetadata(WorkerExecutionResult result,
                                                          PromptRenderingMode renderingMode,
                                                          TaskRuntimeContext context,
                                                          List<LlmImageInput> imageInputs,
                                                          MountedContextPromptRenderResult mountedRenderResult) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (result.metadata() != null) {
            metadata.putAll(result.metadata());
        }
        metadata.putAll(MountedContextPromptMetrics.from(context, renderingMode, mountedRenderResult).toMetadata());
        metadata.put("image_input_count", imageInputs == null ? 0 : imageInputs.size());
        metadata.put("image_input_used", imageInputs != null && !imageInputs.isEmpty());
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

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private boolean isPlannerStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return false;
        }
        return "plan_pending".equalsIgnoreCase(stage) || "planner_active".equalsIgnoreCase(stage);
    }

    private boolean isExecutionStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return false;
        }
        return stage.toLowerCase().startsWith("execution");
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
            String scope = "";
            if (message.metadata() != null && message.metadata().get("continuity_scope") != null) {
                scope = " {" + message.metadata().get("continuity_scope") + "}";
            }
            String content = message.content().replaceAll("\\s+", " ").trim();
            if (content.length() > 240) {
                content = content.substring(0, 240) + "...";
            }
            lines.add(role + type + scope + ": " + content);
        }
        return lines;
    }

    private WorkerExecutionResult parseExecutionResult(String raw, long durationMs) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new WorkerExecutionResult("", "", false, "", "", "", "low", "empty", List.of(), List.of(), 0, durationMs, Map.of("parser", "empty"));
        }

        try {
            JsonNode json = MAPPER.readTree(safeRaw);
            String summary = json.path("summary").asText("");
            String outputText = json.path("output_text").asText("");
            boolean producedArtifact = json.path("produced_artifact").asBoolean(false);
            String artifactTitle = json.path("artifact_title").asText("");
            String artifactContent = json.path("artifact_content").asText("");
            String suggestedNextStep = json.path("suggested_next_step").asText("");
            String confidence = json.path("confidence").asText("medium");
            String executionStatus = json.path("execution_status").asText("completed");
            List<String> evidenceRefs = readStringList(json.path("evidence_refs"));
            List<String> unfinishedItems = readStringList(json.path("unfinished_items"));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parser", "json");
            return new WorkerExecutionResult(
                summary,
                outputText,
                producedArtifact,
                artifactTitle,
                artifactContent,
                suggestedNextStep,
                confidence,
                executionStatus,
                evidenceRefs,
                unfinishedItems,
                0,
                durationMs,
                metadata
            );
        } catch (Exception e) {
            log.warn("Failed to parse worker JSON output, falling back to raw text: {}", e.getMessage());
            return new WorkerExecutionResult(
                safeRaw,
                safeRaw,
                false,
                "",
                "",
                "",
                "medium",
                "unknown",
                List.of(),
                List.of(),
                0,
                durationMs,
                Map.of("parser", "raw_text")
            );
        }
    }
}
