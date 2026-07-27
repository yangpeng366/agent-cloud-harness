package com.agentcloud.engine;

import com.agentcloud.llm.LlmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessStateWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void discoverReturnsStateWithNullLlmConfig() {
        HarnessState state = HarnessStateWriter.discover(null);
        assertNotNull(state);
        assertFalse(state.ccxReachable());
        assertTrue(state.workers().containsKey("codex"));
    }

    @Test
    void discoverReturnsStateWithUnreachableCcx() {
        LlmConfig config = new LlmConfig(
            "test-key",
            "http://127.0.0.1:9999/v1",
            "codex",
            "codex",
            "chat_completions",
            5, 1, null
        );
        HarnessState state = HarnessStateWriter.discover(config);
        assertNotNull(state);
        assertFalse(state.ccxReachable());
        assertTrue(state.ccxModels().isEmpty());
    }

    @Test
    void writeCreatesFile() throws Exception {
        HarnessState state = new HarnessState(
            java.time.Instant.now(),
            false,
            List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            0
        );
        Path path = tempDir.resolve("harness-state.json");
        HarnessStateWriter.write(state, path);
        assertTrue(Files.exists(path));
        String content = Files.readString(path);
        assertTrue(content.contains("lastUpdated"));
        assertTrue(content.contains("ccxReachable"));
    }

    @Test
    void writeCreatesParentDirectory() throws Exception {
        HarnessState state = new HarnessState(
            java.time.Instant.now(), false, List.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0
        );
        Path path = tempDir.resolve("subdir").resolve("harness-state.json");
        HarnessStateWriter.write(state, path);
        assertTrue(Files.exists(path));
    }

    @Test
    void stateContainsProviderAvailability() {
        HarnessState state = HarnessStateWriter.discover(null);
        assertNotNull(state.providers());
        assertTrue(state.providers().containsKey("codex"));
        assertTrue(state.providers().containsKey("codex-free"));
        assertTrue(state.providers().containsKey("deepseek"));
        assertTrue(state.providers().containsKey("trae"));
        // deepseek and trae should be userEnabled=false
        assertFalse(state.providers().get("deepseek").userEnabled());
        assertFalse(state.providers().get("trae").userEnabled());
    }

    @Test
    void stateContainsWorkerAvailability() {
        HarnessState state = HarnessStateWriter.discover(null);
        assertNotNull(state.workers());
        assertTrue(state.workers().containsKey("codex"));
        assertTrue(state.workers().containsKey("claude"));
        assertTrue(state.workers().containsKey("pi"));
        assertTrue(state.workers().containsKey("kimi"));
        assertTrue(state.workers().containsKey("deepseek"));
    }
}