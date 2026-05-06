package com.agentcloud.llm;

/**
 * 最小 LLM 调用接口。
 */
public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);

    default String review(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt);
    }
}
