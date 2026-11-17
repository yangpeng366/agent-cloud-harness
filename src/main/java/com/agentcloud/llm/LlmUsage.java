package com.agentcloud.llm;

/**
 * 单次 LLM 调用的真实 token 计量。
 *
 * <p>口径约定（B 向 Step1）：
 * <ul>
 *   <li>{@link #SOURCE_PROVIDER}：响应体 usage 字段直接给出的真实值；</li>
 *   <li>{@link #SOURCE_ESTIMATED}：provider 未返回 usage 时的估算值（口径与 {@code JevContextCompactor#estimateTokens} 一致，偏差 2%~18%）；</li>
 *   <li>{@link #SOURCE_UNAVAILABLE}：既无 usage 也无估算，全零。</li>
 * </ul>
 * 下游记账必须保留 source，禁止把 estimated 与 provider 混算成同一个数。
 */
public record LlmUsage(int inputTokens, int outputTokens, int cachedTokens, String source) {

    public static final String SOURCE_PROVIDER = "provider";
    public static final String SOURCE_ESTIMATED = "estimated";
    public static final String SOURCE_UNAVAILABLE = "unavailable";

    public LlmUsage {
        if (inputTokens < 0) {
            inputTokens = 0;
        }
        if (outputTokens < 0) {
            outputTokens = 0;
        }
        if (cachedTokens < 0) {
            cachedTokens = 0;
        }
        if (source == null || source.isBlank()) {
            source = SOURCE_UNAVAILABLE;
        }
    }

    public static LlmUsage provider(int inputTokens, int outputTokens, int cachedTokens) {
        return new LlmUsage(inputTokens, outputTokens, cachedTokens, SOURCE_PROVIDER);
    }

    public static LlmUsage estimated(int inputTokens, int outputTokens) {
        return new LlmUsage(inputTokens, outputTokens, 0, SOURCE_ESTIMATED);
    }

    public static LlmUsage unavailable() {
        return new LlmUsage(0, 0, 0, SOURCE_UNAVAILABLE);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /** 是否有可用计量（provider 真实值或估算值）。 */
    public boolean present() {
        return totalTokens() > 0;
    }

    public boolean fromProvider() {
        return SOURCE_PROVIDER.equals(source);
    }
}
