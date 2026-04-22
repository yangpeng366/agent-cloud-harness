package com.agentcloud.store;

import com.agentcloud.model.Skill;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillDao extends SqlObject {

    @SqlUpdate("INSERT INTO skills (id, name, description, capability_tags_json, input_schema_json, output_schema_json, dependencies_json, risk_level, installed, ready, last_checked_at, version, metadata_json, created_at, updated_at) " +
               "VALUES (:id, :name, :description, :capabilityTags, :inputSchema, :outputSchema, :dependencies, :riskLevel, :installed, :ready, :lastCheckedAt, :version, :metadata, :createdAt, :updatedAt)")
    void insert(@Bind("id") String id, @Bind("name") String name, @Bind("description") String description,
                @Bind("capabilityTags") String capabilityTags, @Bind("inputSchema") String inputSchema,
                @Bind("outputSchema") String outputSchema, @Bind("dependencies") String dependencies,
                @Bind("riskLevel") String riskLevel, @Bind("installed") int installed, @Bind("ready") int ready,
                @Bind("lastCheckedAt") Instant lastCheckedAt, @Bind("version") String version,
                @Bind("metadata") String metadata, @Bind("createdAt") Instant createdAt, @Bind("updatedAt") Instant updatedAt);

    default void insert(Skill s) {
        insert(s.id(), s.name(), s.description(), JsonMapper.toJson(s.capabilityTags()),
               JsonMapper.toJson(s.inputSchema()), JsonMapper.toJson(s.outputSchema()),
               JsonMapper.toJson(s.dependencies()), s.riskLevel(), s.installed() ? 1 : 0,
               s.ready() ? 1 : 0, s.lastCheckedAt(), s.version(),
               JsonMapper.toJson(s.metadata()), s.createdAt(), s.updatedAt());
    }

    @SqlQuery("SELECT * FROM skills WHERE id = :id")
    Optional<Skill> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM skills ORDER BY updated_at DESC")
    List<Skill> listAll();

    @SqlQuery("SELECT * FROM skills WHERE ready = 1 ORDER BY updated_at DESC")
    List<Skill> listReady();

    @SqlUpdate("UPDATE skills SET ready = :ready, installed = :installed, last_checked_at = :lastCheckedAt, updated_at = :updatedAt WHERE id = :id")
    int updateState(@Bind("id") String id, @Bind("ready") int ready, @Bind("installed") int installed,
                    @Bind("lastCheckedAt") Instant lastCheckedAt, @Bind("updatedAt") Instant updatedAt);
}
