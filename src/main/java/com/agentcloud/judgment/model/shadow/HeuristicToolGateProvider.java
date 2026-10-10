package com.agentcloud.judgment.model.shadow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3 Tool Gate 启发式 Provider。
 * <p>
 * 按 JudgmentRequest.normalizedInput 中的稳定字段做确定性映射：
 *   destructive       (rm / drop / truncate / delete / force)  → block
 *   network_egress    (curl / wget / http / ssh / ftp)         → approval_required
 *   credential_use    (env.PASSWORD / env.TOKEN / env.AKIA)    → approval_required
 *   reversible=false                                             → approval_required
 *   destructive_side_effect=true                                 → block
 *   out_of_workspace=true                                        → block
 * <p>
 * 异常一律 JudgmentDecision.fallback("approval_required")；脱敏失败一律视为最严（approval）。
 */
public class HeuristicToolGateProvider implements ToolGateProvider {

    public static final String PROVIDER_REF = "heuristic-shadow-v1";

    private static final List<String> DESTRUCTIVE_PATTERNS = List.of(
            "rm ", "rm\t", "delete", "drop ", "truncate", "wipe",
            "format ", "mkfs", "shutdown", "reboot", "reset ", "destroy");
    private static final List<String> NETWORK_EGRESS_PATTERNS = List.of(
            "curl ", "wget ", "http://", "https://", "ftp://", "scp ", "ssh ", "rsync ");
    private static final List<String> CREDENTIAL_KEYS = List.of(
            "password", "passwd", "secret", "token", "bearer",
            "api_key", "apikey", "ak_", "akia",
            "credential",
            "private_key", "priv_key", "ssh_key",
            "access_key", "secret_key", "key_id");

    @Override
    public JudgmentDecision decide(JudgmentRequest request) {
        long started = System.nanoTime();
        try {
            Map<String, Object> input = request.normalizedInput() == null ? Map.of() : request.normalizedInput();
            String toolName = stringOrEmpty(input.get("tool_name"));
            String argsBlob = stringOrEmpty(input.get("args_blob"));
            List<String> envKeys = stringList(input.get("env_keys"));
            boolean destructiveSideEffect = Boolean.TRUE.equals(input.get("destructive_side_effect"));
            boolean outOfWorkspace = Boolean.TRUE.equals(input.get("out_of_workspace"));
            boolean reversible = !Boolean.FALSE.equals(input.get("reversible"));
            boolean needsHuman = Boolean.TRUE.equals(input.get("needs_human"));

            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("destructive_side_effect", destructiveSideEffect ? 1.0 : 0.0);
            scores.put("out_of_workspace", outOfWorkspace ? 1.0 : 0.0);

            Map<String, String> reasons = new LinkedHashMap<>();
            String action;
            if (needsHuman) {
                action = "approval_required";
                reasons.put("policy", "needs_human_flag_set");
            } else if (destructiveSideEffect || matchesAny(argsBlob, DESTRUCTIVE_PATTERNS) || isDestructiveTool(toolName)) {
                action = "block";
                reasons.put("policy", "destructive_detected");
            } else if (outOfWorkspace || !reversible) {
                action = "block";
                reasons.put("policy", "out_of_workspace_or_irreversible");
            } else if (matchesAny(argsBlob, NETWORK_EGRESS_PATTERNS) || envKeyHitsCredential(envKeys)) {
                action = "approval_required";
                reasons.put("policy", "network_egress_or_credential_use");
            } else {
                action = "allow";
                reasons.put("policy", "read_only_no_egress");
            }

            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return new JudgmentDecision(
                    request.judgmentId(),
                    action,
                    scores,
                    0.0,
                    request.policyVersion(),
                    false,
                    providerRef(),
                    latencyMs,
                    0.0,
                    reasons
            );
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return JudgmentDecision.fallback(request.judgmentId(), "approval_required",
                    "heuristic_error:" + e.getClass().getSimpleName(), providerRef(), latencyMs);
        }
    }

    @Override
    public String providerRef() {
        return PROVIDER_REF;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static boolean matchesAny(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (lower.contains(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isDestructiveTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) return false;
        String lower = toolName.toLowerCase(Locale.ROOT);
        return lower.equals("delete_file") || lower.equals("rm") || lower.equals("drop_table")
                || lower.equals("wipe_workspace") || lower.equals("reset_session");
    }

    private static boolean envKeyHitsCredential(List<String> envKeys) {
        for (String k : envKeys) {
            if (k == null) continue;
            String lower = k.toLowerCase(Locale.ROOT);
            for (String pat : CREDENTIAL_KEYS) {
                if (lower.contains(pat)) return true;
            }
        }
        return false;
    }
}