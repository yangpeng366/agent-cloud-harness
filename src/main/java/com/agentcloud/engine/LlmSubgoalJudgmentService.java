package com.agentcloud.engine;

import com.agentcloud.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * LLM 辅助 subgoal 状态判断服务。
 * 当规则判断无法确定 subgoal 状态迁移时（ambiguous 场景），
 * 通过 LLM 判断 subgoal 是否完成。
 *
 * 规则优先：completed/failed 仍用规则判断。
 * LLM 只在 ambiguous 场景介入（如 worker 返回 partial progress）。
 */
public class LlmSubgoalJudgmentService {
    private static final Logger log = LoggerFactory.getLogger(LlmSubgoalJudgmentService.class);

    private final LlmClient llmClient;
    private final boolean enabled;

    public LlmSubgoalJudgmentService(LlmClient llmClient) {
        this(llmClient, true);
    }

    public LlmSubgoalJudgmentService(LlmClient llmClient, boolean enabled) {
        this.llmClient = llmClient;
        this.enabled = enabled;
    }

    /**
     * 判断 subgoal 是否完成。
     * 返回 "done" / "blocked" / "in_progress" / null（无法判断）。
     */
    public String judgeSubgoalStatus(String subgoalDescription, String workerOutputSummary,
                                      String currentSubgoalStatus, Map<String, Object> context) {
        if (!enabled || llmClient == null) {
            return null;
        }

        String systemPrompt = """
            你是任务进度判断助手。根据 worker 执行结果判断当前子目标是否完成。
            只能回答以下三种状态之一：
            - done: 子目标已完全达成
            - blocked: 子目标遇到阻塞，无法继续
            - in_progress: 子目标正在进行中，尚未完成也未阻塞
            
            只回答状态词，不要解释。
            """;

        String userPrompt = "子目标: " + safeStr(subgoalDescription) + "\n"
            + "当前状态: " + safeStr(currentSubgoalStatus) + "\n"
            + "Worker 执行摘要: " + safeStr(workerOutputSummary) + "\n"
            + "请判断子目标状态:";

        try {
            String response = llmClient.chat(systemPrompt, userPrompt);
            if (response == null || response.isBlank()) {
                return null;
            }
            String normalized = response.trim().toLowerCase()
                .replaceAll("[^a-z_]", "");
            return switch (normalized) {
                case "done", "completed", "finished" -> "done";
                case "blocked", "failed", "stuck" -> "blocked";
                case "in_progress", "running", "ongoing", "partial" -> "in_progress";
                default -> {
                    log.warn("LLM subgoal judgment returned unrecognized status: '{}'", response.trim());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("LLM subgoal judgment failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断 worker 执行结果是否为 ambiguous（规则无法确定）。
     * ambiguous 条件：executionStatus 不是 completed/failed/running，但有 outputText。
     */
    public static boolean isAmbiguousExecution(String executionStatus, String outputText) {
        if (executionStatus == null || outputText == null || outputText.isBlank()) {
            return false;
        }
        // "unknown" / "partial" / "timeout" 等非明确状态 + 有输出 = ambiguous
        return !"completed".equals(executionStatus)
            && !"failed".equals(executionStatus)
            && !"running".equals(executionStatus);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }
}