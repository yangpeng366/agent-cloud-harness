package com.agentcloud.worker;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderTaskPromptBuilderTest {

    @Test
    void promptIncludesLocalExecutionContractWithoutHarnessReadingFiles() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("repo_path", "D:\\gitAll\\agent-cloud-harness");
        metadata.put("reference_paths", List.of("docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md"));
        metadata.put("desired_output_dir", "docs");
        metadata.put("validation_commands", List.of("mvn -Dtest=ProviderTaskPromptBuilderTest test"));
        metadata.put("acceptance_criteria", "worker inspects local files before answering");
        metadata.put("write_scope", List.of("src/main/java/com/agentcloud/worker", "docs"));
        Task task = new Task(
            "task_prompt_contract",
            "session_prompt_contract",
            null,
            "provider prompt contract",
            "active",
            "high",
            Instant.parse("2026-05-22T00:00:00Z"),
            Instant.parse("2026-05-22T00:00:00Z"),
            null,
            null,
            null,
            "summary",
            "strengthen provider prompt",
            "continue",
            "codex",
            "continue",
            null,
            metadata
        );

        String prompt = ProviderTaskPromptBuilder.build(
            new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), null)
        );

        assertTrue(prompt.contains("Workspaces:"));
        assertTrue(prompt.contains("D:\\gitAll\\agent-cloud-harness"));
        assertTrue(prompt.contains("Reference Inputs:"));
        assertTrue(prompt.contains("docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md"));
        assertTrue(prompt.contains("Expected Deliverables:"));
        assertTrue(prompt.contains("Validation Commands:"));
        assertTrue(prompt.contains("mvn -Dtest=ProviderTaskPromptBuilderTest test"));
        assertTrue(prompt.contains("Acceptance Criteria:"));
        assertTrue(prompt.contains("Allowed Modification Scope:"));
        assertTrue(prompt.contains("The harness passes local paths and execution boundaries, not file contents."));
        assertTrue(prompt.contains("Inspect the listed local paths directly"));
    }
}
