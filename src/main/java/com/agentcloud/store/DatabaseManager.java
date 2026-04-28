package com.agentcloud.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class DatabaseManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    public DatabaseManager(Path dbPath) {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception ignored) {}

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("journal_mode", "wal");
        config.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        this.jdbi.registerColumnMapper(new JsonMapper());
        this.jdbi.registerArgument(new InstantArgumentFactory());
        // 注册显式 RowMapper
        this.jdbi.registerRowMapper(com.agentcloud.model.Session.class, Mappers.SESSION);
        this.jdbi.registerRowMapper(com.agentcloud.model.SessionMessage.class, Mappers.SESSION_MESSAGE);
        this.jdbi.registerRowMapper(com.agentcloud.model.Task.class, Mappers.TASK);
        this.jdbi.registerRowMapper(com.agentcloud.model.Decision.class, Mappers.DECISION);
        this.jdbi.registerRowMapper(com.agentcloud.model.Artifact.class, Mappers.ARTIFACT);
        this.jdbi.registerRowMapper(com.agentcloud.model.Event.class, Mappers.EVENT);
        this.jdbi.registerRowMapper(com.agentcloud.model.ResumePacket.class, Mappers.RESUME_PACKET);
        this.jdbi.registerRowMapper(com.agentcloud.model.Relation.class, Mappers.RELATION);
        this.jdbi.registerRowMapper(com.agentcloud.model.Skill.class, Mappers.SKILL);
        this.jdbi.registerRowMapper(com.agentcloud.model.Checkpoint.class, Mappers.CHECKPOINT);
        this.jdbi.registerRowMapper(com.agentcloud.model.LearningMemory.class, Mappers.LEARNING_MEMORY);
        this.jdbi.registerRowMapper(com.agentcloud.model.ToolInvocationRecord.class, Mappers.TOOL_INVOCATION);
        this.jdbi.registerRowMapper(com.agentcloud.model.ExperimentRunRecord.class, Mappers.EXPERIMENT_RUN);

        initSchema();
        log.info("Database initialized at {}", dbPath);
    }

    private void initSchema() {
        try (var is = getClass().getResourceAsStream("/schema.sql")) {
            if (is == null) throw new IllegalStateException("schema.sql not found");
            String sql = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
            // SQLite 不支持多语句 execute，拆分执行
            String[] statements = sql.split(";");
            jdbi.useHandle(handle -> {
                for (String stmt : statements) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) {
                        handle.execute(trimmed);
                    }
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to init schema", e);
        }
    }

    public Jdbi jdbi() { return jdbi; }
    public DataSource dataSource() { return dataSource; }
    @Override
    public void close() { dataSource.close(); }
}
