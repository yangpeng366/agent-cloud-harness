package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;

import java.util.List;
import java.util.Map;

/**
 * 内置 provider 清单，优先对齐 multica 的常见 agent/LLM CLI 命名约定。
 */
public final class BuiltinAgentProviders {
    private BuiltinAgentProviders() {
    }

    public static List<AgentProvider> defaults() {
        return List.of(
            new OpenClawProvider(),
            new LocalCliAgentProvider(
                "claude",
                "Claude Code",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "claude",
                "MULTICA_CLAUDE_PATH",
                "MULTICA_CLAUDE_MODEL"
            ),
            new CodexProvider(),
            new LocalCliAgentProvider(
                "copilot",
                "GitHub Copilot",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "copilot",
                "MULTICA_COPILOT_PATH",
                "MULTICA_COPILOT_MODEL"
            ),
            new LocalCliAgentProvider(
                "deepseek",
                "DeepSeek TUI",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "deepseek",
                "MULTICA_DEEPSEEK_PATH",
                "MULTICA_DEEPSEEK_MODEL"
            ),
            new LocalCliAgentProvider(
                "opencode",
                "OpenCode",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "opencode",
                "MULTICA_OPENCODE_PATH",
                "MULTICA_OPENCODE_MODEL"
            ),
            new LocalCliAgentProvider(
                "hermes",
                "Hermes",
                List.of("chat", "code", "session"),
                Map.of("model_tier", "small"),
                "hermes",
                "MULTICA_HERMES_PATH",
                "MULTICA_HERMES_MODEL"
            ),
            new LocalCliAgentProvider(
                "gemini",
                "Gemini CLI",
                List.of("chat", "code", "research", "session"),
                Map.of("model_tier", "strong"),
                "gemini",
                "MULTICA_GEMINI_PATH",
                "MULTICA_GEMINI_MODEL"
            ),
            new LocalCliAgentProvider(
                "pi",
                "Pi",
                List.of("chat", "research", "session"),
                Map.of("model_tier", "small"),
                "pi",
                "MULTICA_PI_PATH",
                "MULTICA_PI_MODEL"
            ),
            new LocalCliAgentProvider(
                "cursor",
                "Cursor Agent",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "cursor-agent",
                "MULTICA_CURSOR_PATH",
                "MULTICA_CURSOR_MODEL"
            ),
            new LocalCliAgentProvider(
                "kimi",
                "Kimi",
                List.of("chat", "code", "research", "session"),
                Map.of("model_tier", "small"),
                "kimi",
                "MULTICA_KIMI_PATH",
                "MULTICA_KIMI_MODEL"
            ),
            new LocalCliAgentProvider(
                "kiro",
                "Kiro",
                List.of("chat", "code", "session"),
                Map.of("model_tier", "small"),
                "kiro-cli",
                "MULTICA_KIRO_PATH",
                "MULTICA_KIRO_MODEL"
            ),
            new LocalCliAgentProvider(
                "codebuddy",
                "CodeBuddy",
                List.of("chat", "code", "patch", "session"),
                Map.of("model_tier", "strong"),
                "codebuddy",
                "MULTICA_CODEBUDDY_PATH",
                "MULTICA_CODEBUDDY_MODEL"
            ),
            new LocalCliAgentProvider(
                "trae",
                "Trae CN",
                List.of("chat", "code", "session"),
                Map.of("model_tier", "strong"),
                "trae",
                "MULTICA_TRAE_PATH",
                "MULTICA_TRAE_MODEL"
            ),
            new LocalCliAgentProvider(
                "reasonix",
                "Reasonix Code",
                List.of("chat", "code", "patch", "research", "session"),
                Map.of("model_tier", "strong"),
                "reasonix",
                "MULTICA_REASONIX_PATH",
                "MULTICA_REASONIX_MODEL"
            )
        );
    }
}
