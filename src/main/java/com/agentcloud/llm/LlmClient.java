package com.agentcloud.llm;

import java.util.List;

/**
 * 最小 LLM 调用接口。
 *
 * <p>两套签名并存：
 * <ul>
 *   <li>{@code chat}/{@code review}：历史签名，返回纯文本，行为不变；</li>
 *   <li>{@code chatResult}/{@code reviewResult}：带 token 计量的签名，默认退化为
 *       {@link LlmUsage#unavailable()}，因此既有实现与测试替身无需改动即可编译。</li>
 * </ul>
 */
public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);

    default String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
        return chat(systemPrompt, userPrompt);
    }

    default String review(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt);
    }

    /**
     * 带计量的 chat。默认实现只回文本、usage 标记 unavailable，保证旧实现零改动兼容。
     */
    default LlmCallResult chatResult(String systemPrompt, String userPrompt) {
        return new LlmCallResult(chat(systemPrompt, userPrompt), LlmUsage.unavailable(), null, "default");
    }

    default LlmCallResult chatResult(String systemPrompt,
                                     String userPrompt,
                                     List<LlmImageInput> imageInputs) {
        return new LlmCallResult(chat(systemPrompt, userPrompt, imageInputs), LlmUsage.unavailable(), null, "default");
    }

    default LlmCallResult reviewResult(String systemPrompt, String userPrompt) {
        return new LlmCallResult(review(systemPrompt, userPrompt), LlmUsage.unavailable(), null, "review");
    }
}
