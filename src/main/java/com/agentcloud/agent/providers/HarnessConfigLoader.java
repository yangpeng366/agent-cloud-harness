package com.agentcloud.agent.providers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * harness-config.yml 加载器。
 * 搜索路径：./harness-config.yml, ./config/harness-config.yml,
 * ~/.agentcloud/harness-config.yml, -Dagentcloud.config.path。
 */
public final class HarnessConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(HarnessConfigLoader.class);
    private static final String CONFIG_PATH_PROPERTY = "agentcloud.config.path";

    private HarnessConfigLoader() {
    }

    /**
     * 搜索并加载 harness-config.yml。如果文件不存在，返回 empty。
     */
    public static Optional<HarnessConfig> load() {
        return load(defaultSearchPaths());
    }

    /**
     * 从指定搜索路径加载。测试用。
     */
    public static Optional<HarnessConfig> load(List<Path> searchPaths) {
        if (searchPaths == null || searchPaths.isEmpty()) {
            return Optional.empty();
        }
        for (Path path : searchPaths) {
            if (Files.exists(path)) {
                try {
                    HarnessConfig config = parse(path);
                    log.info("Harness config loaded from: {} ({} worker lanes)",
                        path, config.workers().size());
                    return Optional.of(config);
                } catch (IOException e) {
                    log.warn("Harness config ignored. path={} reason={}", path, e.getMessage());
                }
            }
        }
        log.info("No harness-config.yml found; using builtin defaults");
        return Optional.empty();
    }

    /**
     * 解析单个 YAML 文件为 HarnessConfig。
     */
    static HarnessConfig parse(Path path) throws IOException {
        String content = Files.readString(path);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = mapper.readValue(content, Map.class);
        return fromMap(root);
    }

    @SuppressWarnings("unchecked")
    private static HarnessConfig fromMap(Map<String, Object> root) {
        if (root == null) {
            return new HarnessConfig(null, null, null, null);
        }
        Map<String, Object> harness = (Map<String, Object>) root.getOrDefault("harness", Map.of());
        Map<String, Object> defaultsMap = (Map<String, Object>) harness.getOrDefault("defaults", Map.of());
        Map<String, Object> ccxMap = (Map<String, Object>) harness.getOrDefault("ccx", Map.of());
        List<Object> workersList = (List<Object>) harness.getOrDefault("workers", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> workspaceAliasesMap = (Map<String, Object>) harness.getOrDefault("workspace-aliases", Map.of());
        Map<String, String> workspaceAliases = new LinkedHashMap<>();
        for (var entry : workspaceAliasesMap.entrySet()) {
            if (entry.getValue() != null) {
                workspaceAliases.put(entry.getKey(), entry.getValue().toString());
            }
        }

        HarnessConfig.HarnessDefaults defaults = new HarnessConfig.HarnessDefaults(
            stringFromMap(defaultsMap, "provider_model_provider"),
            stringFromMap(defaultsMap, "provider_base_url"),
            stringFromMap(defaultsMap, "provider_wire_api"),
            stringFromMap(defaultsMap, "provider_bearer_token")
        );

        HarnessConfig.HarnessCcxConfig ccx = new HarnessConfig.HarnessCcxConfig(
            stringFromMap(ccxMap, "base_url"),
            stringFromMap(ccxMap, "admin_key"),
            booleanFromMap(ccxMap, "health_check_on_startup"),
            booleanFromMap(ccxMap, "channel_sync_on_startup")
        );

        List<WorkerLaneConfig> workers = new ArrayList<>();
        for (Object item : workersList) {
            if (item instanceof Map) {
                workers.add(workerLaneFromMap((Map<String, Object>) item));
            }
        }

        return new HarnessConfig(defaults, ccx, List.copyOf(workers), Map.copyOf(workspaceAliases));
    }

    @SuppressWarnings("unchecked")
    private static WorkerLaneConfig workerLaneFromMap(Map<String, Object> map) {
        String id = stringFromMap(map, "id");
        String provider = stringFromMap(map, "provider");
        String modelTier = stringFromMap(map, "model_tier");
        String costClass = stringFromMap(map, "cost_class");
        Integer selectionPriority = integerFromMap(map, "selection_priority");

        List<String> capabilities = new ArrayList<>();
        Object capsObj = map.get("capabilities");
        if (capsObj instanceof List) {
            for (Object c : (List<?>) capsObj) {
                if (c != null) capabilities.add(c.toString());
            }
        }

        Map<String, Object> profileMap = (Map<String, Object>) map.getOrDefault("profile", Map.of());
        WorkerLaneProfileConfig profile = new WorkerLaneProfileConfig(
            stringFromMap(profileMap, "model"),
            stringFromMap(profileMap, "model_provider")
        );

        Map<String, Object> metadataMap = (Map<String, Object>) map.getOrDefault("metadata", Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>(metadataMap);
        // 将 selection_priority 也写入 metadata，与内置 worker 一致
        if (selectionPriority != null) {
            metadata.put("selection_priority", selectionPriority);
        }
        if (!modelTier.isBlank()) {
            metadata.put("model_tier", modelTier);
        }
        if (!costClass.isBlank()) {
            metadata.put("provider_cost_class", costClass);
        }
        // 将 profile 信息写入 metadata，供 ProviderProfileConfig 消费
        if (!profile.model().isBlank()) {
            metadata.put("provider_model", profile.model());
        }
        if (!profile.modelProvider().isBlank()) {
            metadata.put("provider_model_provider", profile.modelProvider());
        }

        return new WorkerLaneConfig(
            id, provider, modelTier, costClass, selectionPriority,
            List.copyOf(capabilities), profile, Map.copyOf(metadata)
        );
    }

    static List<Path> defaultSearchPaths() {
        List<Path> paths = new ArrayList<>();
        paths.add(Paths.get("harness-config.yml"));
        paths.add(Paths.get("config", "harness-config.yml"));
        paths.add(Paths.get(System.getProperty("user.home"), ".agentcloud", "harness-config.yml"));
        String explicit = System.getProperty(CONFIG_PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            paths.add(0, Paths.get(explicit));
        }
        return paths;
    }

    private static String stringFromMap(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integerFromMap(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean booleanFromMap(Map<String, Object> map, String key) {
        if (map == null || key == null) return false;
        Object value = map.get(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}