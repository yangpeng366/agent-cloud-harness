package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void patchFileToolReplacesSingleOccurrence() throws Exception {
        Path draft = tempDir.resolve("draft.txt");
        Files.writeString(draft, "alpha TODO omega");

        PatchFileTool tool = new PatchFileTool(createWorkerRegistry(), new ToolPolicy());
        ToolResult result = tool.invoke(new ToolRequest(
            "session-patch",
            "task-patch",
            "patch-worker",
            "patch_file",
            Map.of(
                "path", "draft.txt",
                "old_text", "TODO",
                "new_text", "done"
            )
        ));

        assertTrue(result.success());
        assertEquals("alpha done omega", Files.readString(draft));
        assertEquals(1, ((Number) result.metadata().get("replacements")).intValue());
        assertEquals(draft.toString(), result.metadata().get("path"));
    }

    @Test
    void patchFileToolRejectsAmbiguousMultipleMatches() throws Exception {
        Path draft = tempDir.resolve("draft.txt");
        Files.writeString(draft, "TODO one\nTODO two\n");

        PatchFileTool tool = new PatchFileTool(createWorkerRegistry(), new ToolPolicy());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> tool.invoke(new ToolRequest(
            "session-patch",
            "task-patch",
            "patch-worker",
            "patch_file",
            Map.of(
                "path", "draft.txt",
                "old_text", "TODO",
                "new_text", "done"
            )
        )));

        assertTrue(error.getMessage().contains("matched multiple occurrences"));
    }

    @Test
    void patchFileToolAllowsEmptyReplacementForDeletion() throws Exception {
        Path draft = tempDir.resolve("draft.txt");
        Files.writeString(draft, "keep remove-me keep");

        PatchFileTool tool = new PatchFileTool(createWorkerRegistry(), new ToolPolicy());
        ToolResult result = tool.invoke(new ToolRequest(
            "session-patch",
            "task-patch",
            "patch-worker",
            "patch_file",
            Map.of(
                "path", "draft.txt",
                "old_text", "remove-me ",
                "new_text", ""
            )
        ));

        assertTrue(result.success());
        assertEquals("keep keep", Files.readString(draft));
    }

    @Test
    void readFileAllowsTaskWorkspaceOutsideStaticWorkerScope() throws Exception {
        Path harnessScope = tempDir.resolve("harness");
        Path taskWorkspace = tempDir.resolve("articleeditor");
        Files.createDirectories(harnessScope);
        Files.createDirectories(taskWorkspace);
        Path source = taskWorkspace.resolve("MchSyncTaskServiceImpl.java");
        Files.writeString(source, "class MchSyncTaskServiceImpl {}");

        WorkerRegistry workerRegistry = new WorkerRegistry();
        workerRegistry.register(new Worker(
            "deepseek",
            "deepseek",
            List.of("coding"),
            List.of("read_file"),
            List.of(harnessScope.toString()),
            Map.of("api_key", true),
            Map.of("execution_backend", "provider_native_cli"),
            false,
            true
        ));
        ReadFileTool tool = new ReadFileTool(workerRegistry, new ToolPolicy());

        ToolResult result = tool.invoke(new ToolRequest(
            "session-read",
            "task-read",
            "deepseek",
            "read_file",
            Map.of("path", source.toString()),
            Map.of("workspace_root", taskWorkspace.toString())
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("MchSyncTaskServiceImpl"));
    }

    private WorkerRegistry createWorkerRegistry() {
        WorkerRegistry workerRegistry = new WorkerRegistry();
        workerRegistry.register(new Worker(
            "patch-worker",
            "codex",
            List.of("coding"),
            List.of("patch_file"),
            List.of(tempDir.toString()),
            Map.of("api_key", true),
            Map.of("model_tier", "strong"),
            false,
            true
        ));
        return workerRegistry;
    }
}
