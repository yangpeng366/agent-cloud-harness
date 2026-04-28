package com.agentcloud.worker;

import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认 Worker 执行器。
 * 基于 task + packet + recent events/decisions/artifacts 组装 prompt，调用 LLM 返回文本结果。
 */
public class DefaultWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmClient llmClient;

    public DefaultWorkerExecutor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        long startMs = System.currentTimeMillis();

        String systemPrompt = buildSystemPrompt(context, workerId);
        String userPrompt = buildUserPrompt(context);

        String raw = llmClient.chat(systemPrompt, userPrompt);
        long durationMs = System.currentTimeMillis() - startMs;
        WorkerExecutionResult result = parseExecutionResult(raw, durationMs);

        log.info("Worker round completed. task={}, worker={}, outputLength={}, durationMs={}",
            context.task().id(), workerId, result.outputText().length(), durationMs);

        return result;
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

    private String buildUserPrompt(TaskRuntimeContext context) {
        var t = context.task();
        StringBuilder sb = new StringBuilder();
        sb.append("Task Title: ").append(t.title()).append("\n");
        if (t.goal() != null && !t.goal().isBlank()) {
            sb.append("Goal: ").append(t.goal()).append("\n");
        }
        if (t.metadata() != null && t.metadata().get("intent") != null) {
            sb.append("Intent: ").append(t.metadata().get("intent")).append("\n");
        }
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
        if (context.activeContext() != null && !context.activeContext().synthesizedContext().isBlank()) {
            sb.append("\nActive Context:\n");
            sb.append(context.activeContext().synthesizedContext()).append("\n");
        } else {
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

        sb.append("\nPlease execute the task and provide your output.");
        return sb.toString();
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

    private WorkerExecutionResult parseExecutionResult(String raw, long durationMs) {
        String safeRaw = raw == null ? "" : raw.trim();
        if (safeRaw.isBlank()) {
            return new WorkerExecutionResult("", "", false, "", "", "", "low", 0, durationMs, Map.of("parser", "empty"));
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
                0,
                durationMs,
                metadata
            );
        } catch (Exception e) {
            log.warn("Failed to parse worker JSON output, falling back to raw text: {}", e.getMessage());
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
}
