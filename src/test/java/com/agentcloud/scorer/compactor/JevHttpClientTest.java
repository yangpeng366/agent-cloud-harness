package com.agentcloud.scorer.compactor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JevHttpClientTest {

    @Test
    @DisplayName("renderRequest uses the configured model")
    void renderRequestUsesConfiguredModel() {
        Map<String, String> questions = new LinkedHashMap<>();
        questions.put("call_t1", "Keep the tool call?");

        String actual = JevHttpClient.renderRequest(
            "jev-custom", "{\"k\":\"v\"}", questions);

        assertEquals(
            "{\"model\":\"jev-custom\",\"state\":{\"k\":\"v\"},"
                + "\"questions\":{\"call_t1\":{\"type\":\"noul\","
                + "\"instructions\":\"Keep the tool call?\"}}}",
            actual);
    }

    @Test
    @DisplayName("constructor rejects blank API keys")
    void constructorRejectsBlankApiKey() {
        assertThrows(IllegalStateException.class, () -> new JevHttpClient(" "));
    }
}