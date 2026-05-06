package com.agentcloud.store;

import com.agentcloud.model.ToolInvocationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseManagerCompatibilityMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void addsMissingColumnsToLegacyToolInvocationsTable() throws Exception {
        Path dbPath = tempDir.resolve("legacy-tool-invocations.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            connection.createStatement().execute("""
                CREATE TABLE tool_invocations (
                  id TEXT PRIMARY KEY,
                  session_id TEXT NOT NULL,
                  task_id TEXT NOT NULL,
                  worker_id TEXT NOT NULL,
                  tool_name TEXT NOT NULL,
                  arguments_json TEXT,
                  result_summary TEXT,
                  success INTEGER NOT NULL,
                  elapsed_ms INTEGER,
                  created_at TEXT NOT NULL,
                  metadata_json TEXT
                )
                """);
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            ToolInvocationDao dao = db.jdbi().onDemand(ToolInvocationDao.class);
            dao.insert(new ToolInvocationRecord(
                "tool_1",
                "session_1",
                "task_1",
                "codex",
                "exec_1",
                "write_file",
                Map.of("path", ".tmp/demo.txt"),
                "ok",
                "succeeded",
                true,
                12,
                List.of(".tmp/demo.txt"),
                Instant.now(),
                Map.of("source", "test")
            ));

            ToolInvocationRecord inserted = dao.listByTask("task_1", 10).getFirst();
            assertEquals("exec_1", inserted.executionId());
            assertEquals("succeeded", inserted.status());
            assertEquals(List.of(".tmp/demo.txt"), inserted.touchedPaths());
        }
    }
}
