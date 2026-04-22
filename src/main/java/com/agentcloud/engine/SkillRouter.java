package com.agentcloud.engine;

import com.agentcloud.model.Skill;
import com.agentcloud.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Skill Router - 技能路由选择
 */
public class SkillRouter {
    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);
    private final SkillRegistry registry;

    public SkillRouter(SkillRegistry registry) {
        this.registry = registry;
    }

    public RouteResult selectSkill(Task task, String capabilityTag) {
        List<Skill> candidates = registry.findByCapability(capabilityTag);
        if (candidates.isEmpty()) {
            return new RouteResult(task.id(), null, List.of(), "no skill found for capability: " + capabilityTag);
        }

        // 策略：优先 ready 的，然后按风险等级排序（低风险优先）
        Skill selected = candidates.stream()
            .filter(Skill::ready)
            .filter(s -> !"critical".equals(s.riskLevel()))
            .min((a, b) -> {
                int riskA = riskOrdinal(a.riskLevel());
                int riskB = riskOrdinal(b.riskLevel());
                return Integer.compare(riskA, riskB);
            })
            .orElse(candidates.stream().filter(Skill::ready).findFirst().orElse(null));

        if (selected == null) {
            return new RouteResult(task.id(), null, List.of(), "no ready skill found");
        }

        List<String> fallbacks = candidates.stream()
            .filter(s -> !s.id().equals(selected.id()) && s.ready())
            .map(Skill::id)
            .limit(2)
            .toList();

        log.info("Skill selected for task={} capability={}: skill={}", task.id(), capabilityTag, selected.name());
        return new RouteResult(task.id(), selected.id(), fallbacks, "selected by capability and risk level");
    }

    private int riskOrdinal(String level) {
        return switch (level != null ? level : "medium") {
            case "low" -> 0;
            case "medium" -> 1;
            case "high" -> 2;
            case "critical" -> 3;
            default -> 1;
        };
    }

    public record RouteResult(String taskId, String selectedSkill, List<String> fallbackSkills, String routeReason) {}
}
