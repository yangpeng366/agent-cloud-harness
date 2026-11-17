package com.agentcloud.llm;

/**
 * 带计量的 LLM 调用结果：文本 + 真实 token 用量 + 实际生效的模型/通道。
 *
 * <p>用于 B 向 Step1「只记账不改路由」：既有 {@link LlmClient#chat} 等返回字符串的签名保持不变，
 * 需要记账的调用方改用 {@code chatResult}/{@code reviewResult}。
 */
public record LlmCallResult(String text, LlmUsage usage, String model, String channel) {

    public LlmCallResult {
        if (text == null) {
            text = "";
        }
        if (usage == null) {
            usage = LlmUsage.unavailable();
        }
        if (channel == null) {
            channel = "default";
        }
    }

    public static LlmCallResult of(String text) {
        return new LlmCallResult(text, LlmUsage.unavailable(), null, "default");
    }
}
