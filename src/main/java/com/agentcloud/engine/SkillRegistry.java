package com.agentcloud.engine;

import com.agentcloud.model.Skill;
import com.agentcloud.store.SkillDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill Registry - 技能注册与就绪检查
 */
public class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private final SkillDao skillDao;
    private final Map<String, Skill> cache = new ConcurrentHashMap<>();

    public SkillRegistry(SkillDao skillDao) {
        this.skillDao = skillDao;
        preload();
    }

    private void preload() {
        List<Skill> skills = skillDao.listAll();
        skills.forEach(s -> cache.put(s.id(), s));
        log.info("SkillRegistry preloaded {} skills", skills.size());
    }

    public void register(Skill skill) {
        skillDao.insert(skill);
        cache.put(skill.id(), skill);
        log.info("Skill registered: {} (tags={}, ready={})", skill.name(), skill.capabilityTags(), skill.ready());
    }

    public Skill get(String id) {
        return cache.get(id);
    }

    public List<Skill> listAll() {
        return List.copyOf(cache.values());
    }

    public List<Skill> listReady() {
        return cache.values().stream().filter(Skill::ready).collect(Collectors.toList());
    }

    public List<Skill> findByCapability(String tag) {
        return cache.values().stream()
            .filter(s -> s.capabilityTags() != null && s.capabilityTags().contains(tag))
            .collect(Collectors.toList());
    }

    public ReadinessCheck checkReadiness(String skillId) {
        Skill s = cache.get(skillId);
        if (s == null) return new ReadinessCheck(skillId, false, Map.of(), "skill not found");
        Map<String, Boolean> checks = new ConcurrentHashMap<>();
        if (s.dependencies() != null) {
            s.dependencies().forEach((k, v) -> checks.put(k, v));
        }
        boolean allOk = checks.values().stream().allMatch(Boolean::booleanValue);
        return new ReadinessCheck(skillId, allOk && s.ready(), checks, allOk ? "ready" : "dependency not satisfied");
    }

    public void updateReadiness(String skillId, boolean ready) {
        Skill s = cache.get(skillId);
        if (s == null) return;
        Skill updated = new Skill(s.id(), s.name(), s.description(), s.capabilityTags(),
            s.inputSchema(), s.outputSchema(), s.dependencies(), s.riskLevel(),
            s.installed(), ready, Instant.now(), s.version(), s.metadata(), s.createdAt(), Instant.now());
        skillDao.updateState(skillId, ready ? 1 : 0, s.installed() ? 1 : 0, Instant.now(), Instant.now());
        cache.put(skillId, updated);
        log.info("Skill {} readiness updated: ready={}", skillId, ready);
    }

    public record ReadinessCheck(String skillId, boolean ready, Map<String, Boolean> checks, String reason) {}
}
