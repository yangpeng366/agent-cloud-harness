package com.agentcloud.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 本地图片输入描述。
 * 目前仅要求可解析的本地路径，mediaType 可选。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmImageInput(
    String path,
    String mediaType
) {
    public LlmImageInput {
        if (path == null) {
            path = "";
        } else {
            path = path.trim();
        }
        if (mediaType != null) {
            mediaType = mediaType.trim();
            if (mediaType.isBlank()) {
                mediaType = null;
            }
        }
    }
}
