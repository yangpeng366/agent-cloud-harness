package com.agentcloud.llm;

import java.util.List;

/**
 * 最小 LLM 调用接口。
 */
public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);

    default String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
        return chat(systemPrompt, userPrompt);
    }

    default String review(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt);
    }
}
