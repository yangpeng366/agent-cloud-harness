package com.agentcloud.scorer;

import java.util.Objects;

/**
 * JevContextScorer configuration (production-shape, immutable).
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.1 (defaults from fast-jev-compaction v0.2.0):
 *   - enabled:               feature flag, default false (off); controlled by harness-config.yml `feature_flags.jev.context_scoring`
 *   - keepThreshold:         default 0.5 (probability threshold; 官方更推荐 confidence 3 段阈值, see §1.1a)
 *   - preserveRecentMessages: default 6 (last N messages always KEEP_VERBATIM)
 *   - truncateHeadChars:     default 300 (chars kept when action=TRUNCATE_HEAD)
 *   - requestTimeoutMs:      default 5000 (HTTP timeout for /v1/systemone)
 *   - baseUrl:               default https://api.typesafe.ai (env override via TYPESAFE_BASE_URL)
 *   - apiKeyEnvVar:          default TYPESAFE_API_KEY (alternative OPENROUTER_API_KEY)
 *   - maxRequestTokens:      default 30000 (request token cap per docs/JEV_OFFICIAL_SKILL_ABSORPTION.md)
 *   - maxStateTokens:        default 25000 (Jev state token cap)
 *
 * All thresholds/questions are centralized here per official SKILL.md
 * "common issues" #4:改 policy 是 constant edit under code review.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public final class JevContextScorerConfig {

    public static final boolean DEFAULT_ENABLED = false;
    public static final double DEFAULT_KEEP_THRESHOLD = 0.5;
    public static final int DEFAULT_PRESERVE_RECENT_MESSAGES = 6;
    public static final int DEFAULT_TRUNCATE_HEAD_CHARS = 300;
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_API_KEY_ENV_VAR = "TYPESAFE_API_KEY";
    public static final int DEFAULT_MAX_REQUEST_TOKENS = 30000;
    public static final int DEFAULT_MAX_STATE_TOKENS = 25000;

    private final boolean enabled;
    private final double keepThreshold;
    private final int preserveRecentMessages;
    private final int truncateHeadChars;
    private final int requestTimeoutMs;
    private final String baseUrl;
    private final String apiKeyEnvVar;
    private final int maxRequestTokens;
    private final int maxStateTokens;

    public JevContextScorerConfig(
            boolean enabled,
            double keepThreshold,
            int preserveRecentMessages,
            int truncateHeadChars,
            int requestTimeoutMs,
            String baseUrl,
            String apiKeyEnvVar,
            int maxRequestTokens,
            int maxStateTokens) {
        if (keepThreshold < 0.0 || keepThreshold > 1.0) {
            throw new IllegalArgumentException(
                "keepThreshold must be in [0.0, 1.0]: " + keepThreshold);
        }
        if (preserveRecentMessages < 0) {
            throw new IllegalArgumentException(
                "preserveRecentMessages must be >= 0: " + preserveRecentMessages);
        }
        if (truncateHeadChars < 0) {
            throw new IllegalArgumentException(
                "truncateHeadChars must be >= 0: " + truncateHeadChars);
        }
        if (requestTimeoutMs < 0) {
            throw new IllegalArgumentException(
                "requestTimeoutMs must be >= 0: " + requestTimeoutMs);
        }
        if (maxRequestTokens < 0 || maxStateTokens < 0) {
            throw new IllegalArgumentException("token caps must be >= 0");
        }
        this.enabled = enabled;
        this.keepThreshold = keepThreshold;
        this.preserveRecentMessages = preserveRecentMessages;
        this.truncateHeadChars = truncateHeadChars;
        this.requestTimeoutMs = requestTimeoutMs;
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKeyEnvVar = Objects.requireNonNull(apiKeyEnvVar, "apiKeyEnvVar");
        this.maxRequestTokens = maxRequestTokens;
        this.maxStateTokens = maxStateTokens;
    }

    /** Default constructor: all defaults from plan §1.1. */
    public static JevContextScorerConfig defaults() {
        return new JevContextScorerConfig(
            DEFAULT_ENABLED,
            DEFAULT_KEEP_THRESHOLD,
            DEFAULT_PRESERVE_RECENT_MESSAGES,
            DEFAULT_TRUNCATE_HEAD_CHARS,
            DEFAULT_REQUEST_TIMEOUT_MS,
            DEFAULT_BASE_URL,
            DEFAULT_API_KEY_ENV_VAR,
            DEFAULT_MAX_REQUEST_TOKENS,
            DEFAULT_MAX_STATE_TOKENS
        );
    }

    /** Runtime defaults with context scoring enabled for Main.java wiring. */
    public static JevContextScorerConfig enabledDefaults() {
        return new JevContextScorerConfig(
            true,
            DEFAULT_KEEP_THRESHOLD,
            DEFAULT_PRESERVE_RECENT_MESSAGES,
            DEFAULT_TRUNCATE_HEAD_CHARS,
            DEFAULT_REQUEST_TIMEOUT_MS,
            System.getenv().getOrDefault("TYPESAFE_BASE_URL", DEFAULT_BASE_URL),
            DEFAULT_API_KEY_ENV_VAR,
            DEFAULT_MAX_REQUEST_TOKENS,
            DEFAULT_MAX_STATE_TOKENS
        );
    }

    /** Short-circuit: enabled=false 时 JevContextScorer 不联外网，直接返回 KEEP_VERBATIM。 */
    public boolean isEnabled() { return enabled; }
    public double keepThreshold() { return keepThreshold; }
    public int preserveRecentMessages() { return preserveRecentMessages; }
    public int truncateHeadChars() { return truncateHeadChars; }
    public int requestTimeoutMs() { return requestTimeoutMs; }
    public String baseUrl() { return baseUrl; }
    public String apiKeyEnvVar() { return apiKeyEnvVar; }
    public int maxRequestTokens() { return maxRequestTokens; }
    public int maxStateTokens() { return maxStateTokens; }
}
