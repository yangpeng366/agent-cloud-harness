package com.agentcloud.agent.providers;

import java.util.List;
import java.util.Map;

public class CodexProvider extends LocalCliAgentProvider {
    public CodexProvider() {
        this("codex");
    }

    public CodexProvider(String binary) {
        super(
            "codex",
            "Codex",
            "local_cli",
            "pty",
            List.of("chat", "code", "patch", "session"),
            Map.of("model_tier", "strong"),
            binary,
            "MULTICA_CODEX_PATH",
            "MULTICA_CODEX_MODEL",
            "MULTICA_CODEX_MODEL_PROVIDER",
            "MULTICA_CODEX_PROFILE",
            "MULTICA_CODEX_CONFIG_JSON"
        );
    }
}
