package com.agentcloud.agent.providers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从 provider CLI help 输出中提取的轻量参数能力画像。
 * 画像只用于裁剪高风险可选参数；没有证据时 executor 保持原有命令形态。
 */
public record CliCapabilityProfile(
    boolean evidenceAvailable,
    Boolean supportsYolo,
    Boolean supportsModel,
    Boolean supportsJsonOutput,
    Boolean supportsResume,
    Boolean supportsWorkspaceArg,
    Boolean supportsWorkDirArg,
    Boolean supportsOutputFile
) {
    public Map<String, Object> metadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cli_profile_evidence_available", evidenceAvailable);
        put(metadata, "supports_yolo", supportsYolo);
        put(metadata, "supports_model", supportsModel);
        put(metadata, "supports_json_output", supportsJsonOutput);
        put(metadata, "supports_resume", supportsResume);
        put(metadata, "supports_workspace_arg", supportsWorkspaceArg);
        put(metadata, "supports_work_dir_arg", supportsWorkDirArg);
        put(metadata, "supports_output_file", supportsOutputFile);
        return Map.copyOf(metadata);
    }

    public static CliCapabilityProfile fromHelpOutput(String providerId, String output) {
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        boolean hasEvidence = !normalizedOutput.isBlank();
        return new CliCapabilityProfile(
            hasEvidence,
            containsAny(normalizedOutput, "--yolo", "dangerously-skip", "skip permissions"),
            containsAny(normalizedOutput, "--model", "-m, --model", "-m <"),
            containsAny(normalizedOutput, "--json", "stream-json", "--output-format", "-o "),
            containsAny(normalizedOutput, "--resume", "-r,", " resume ", "resume <"),
            containsAny(normalizedOutput, "--workspace"),
            containsAny(normalizedOutput, "--work-dir", "--workdir", "--working-directory"),
            containsAny(normalizedOutput, "-o ", "--output", "--output-file")
        );
    }

    public static CliCapabilityProfile fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object marker = metadata.get("cli_profile_evidence_available");
        if (marker == null && !metadata.containsKey("supports_yolo")) {
            return null;
        }
        return new CliCapabilityProfile(
            booleanValue(marker),
            booleanObject(metadata.get("supports_yolo")),
            booleanObject(metadata.get("supports_model")),
            booleanObject(metadata.get("supports_json_output")),
            booleanObject(metadata.get("supports_resume")),
            booleanObject(metadata.get("supports_workspace_arg")),
            booleanObject(metadata.get("supports_work_dir_arg")),
            booleanObject(metadata.get("supports_output_file"))
        );
    }

    public boolean explicitlyUnsupported(String capability) {
        Boolean value = switch (capability == null ? "" : capability) {
            case "yolo" -> supportsYolo;
            case "model" -> supportsModel;
            case "json_output" -> supportsJsonOutput;
            case "resume" -> supportsResume;
            case "workspace_arg" -> supportsWorkspaceArg;
            case "work_dir_arg" -> supportsWorkDirArg;
            case "output_file" -> supportsOutputFile;
            default -> null;
        };
        return Boolean.FALSE.equals(value);
    }

    private static void put(Map<String, Object> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Boolean containsAny(String output, String... needles) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && output.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static Boolean booleanObject(Object value) {
        return value == null ? null : Boolean.parseBoolean(value.toString());
    }
}
