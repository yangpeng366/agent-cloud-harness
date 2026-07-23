package com.agentcloud.agent.providers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDefaultProfileTest {

    @Test
    void defaultProfileWithNoConfigIsNotSubstantive() {
        ProviderDefaultProfile profile = new ProviderDefaultProfile("", "", "", Map.of());
        assertFalse(profile.hasSubstantiveConfig());
    }

    @Test
    void defaultProfileWithModelProviderIsSubstantive() {
        ProviderDefaultProfile profile = new ProviderDefaultProfile("OpenAI", "", "", Map.of());
        assertTrue(profile.hasSubstantiveConfig());
    }

    @Test
    void toProfileConfigConvertsCorrectly() {
        ProviderDefaultProfile profile = new ProviderDefaultProfile("xfyun", "xopglm51", "prod", Map.of());
        ProviderProfileConfig config = profile.toProfileConfig();
        assertEquals("provider_default", config.providerProfileId());
        assertEquals("xfyun", config.modelProvider());
        assertEquals("xopglm51", config.model());
        assertEquals("prod", config.cliProfile());
    }

    @Test
    void toMetadataExportsNonBlankFields() {
        ProviderDefaultProfile profile = new ProviderDefaultProfile("OpenAI", "", "", Map.of());
        Map<String, Object> metadata = profile.toMetadata();
        assertEquals("OpenAI", metadata.get("default_model_provider"));
        assertFalse(metadata.containsKey("default_model"));
    }
}
