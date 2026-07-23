package com.agentcloud.agent.providers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderProfileConfigTest {

    @Test
    void defaultConfigHasNoSubstantiveConfig() {
        ProviderProfileConfig config = new ProviderProfileConfig("", "", "", "", Map.of());
        assertFalse(config.hasSubstantiveConfig());
    }

    @Test
    void configWithModelProviderIsSubstantive() {
        ProviderProfileConfig config = new ProviderProfileConfig("test", "OpenAI", "", "", Map.of());
        assertTrue(config.hasSubstantiveConfig());
    }

    @Test
    void configWithModelIsSubstantive() {
        ProviderProfileConfig config = new ProviderProfileConfig("test", "", "gpt-5.4", "", Map.of());
        assertTrue(config.hasSubstantiveConfig());
    }

    @Test
    void configWithCliProfileIsSubstantive() {
        ProviderProfileConfig config = new ProviderProfileConfig("test", "", "", "my-profile", Map.of());
        assertTrue(config.hasSubstantiveConfig());
    }

    @Test
    void configWithOverridesIsSubstantive() {
        ProviderProfileConfig config = new ProviderProfileConfig("test", "", "", "", Map.of("key", "value"));
        assertTrue(config.hasSubstantiveConfig());
    }

    @Test
    void mergeOverridesBlankFields() {
        ProviderProfileConfig base = new ProviderProfileConfig("base", "", "", "", Map.of());
        ProviderProfileConfig override = new ProviderProfileConfig("", "OpenAI", "gpt-5.4", "", Map.of());
        ProviderProfileConfig merged = base.merge(override);
        assertEquals("base", merged.providerProfileId());
        assertEquals("OpenAI", merged.modelProvider());
        assertEquals("gpt-5.4", merged.model());
    }

    @Test
    void mergeKeepsBaseWhenOverrideIsBlank() {
        ProviderProfileConfig base = new ProviderProfileConfig("base", "xfyun", "xopglm51", "prod", Map.of());
        ProviderProfileConfig override = new ProviderProfileConfig("", "", "", "", Map.of());
        ProviderProfileConfig merged = base.merge(override);
        assertEquals("base", merged.providerProfileId());
        assertEquals("xfyun", merged.modelProvider());
        assertEquals("xopglm51", merged.model());
        assertEquals("prod", merged.cliProfile());
    }

    @Test
    void mergeCombinesConfigOverrides() {
        ProviderProfileConfig base = new ProviderProfileConfig("", "", "", "", Map.of("a", "1"));
        ProviderProfileConfig override = new ProviderProfileConfig("", "", "", "", Map.of("b", "2"));
        ProviderProfileConfig merged = base.merge(override);
        assertEquals("1", merged.configOverrides().get("a"));
        assertEquals("2", merged.configOverrides().get("b"));
    }

    @Test
    void fromWorkerMetadataExtractsProfileFields() {
        Map<String, Object> metadata = Map.of(
            "provider_profile_id", "codex_openai_strong",
            "provider_model_provider", "OpenAI",
            "provider_model", "gpt-5.4",
            "provider_cli_profile", ""
        );
        ProviderProfileConfig config = ProviderProfileConfig.fromWorkerMetadata(metadata);
        assertEquals("codex_openai_strong", config.providerProfileId());
        assertEquals("OpenAI", config.modelProvider());
        assertEquals("gpt-5.4", config.model());
    }

    @Test
    void fromTaskMetadataExtractsProfileFields() {
        Map<String, Object> metadata = Map.of(
            "preferred_provider_profile", "codex_xfyun_execute",
            "provider_model_provider", "xfyun",
            "provider_model", "xopglm51"
        );
        ProviderProfileConfig config = ProviderProfileConfig.fromTaskMetadata(metadata);
        assertEquals("codex_xfyun_execute", config.providerProfileId());
        assertEquals("xfyun", config.modelProvider());
        assertEquals("xopglm51", config.model());
    }

    @Test
    void toMetadataExportsNonBlankFields() {
        ProviderProfileConfig config = new ProviderProfileConfig("test_id", "OpenAI", "gpt-5.4", "", Map.of());
        Map<String, Object> metadata = config.toMetadata();
        assertEquals("test_id", metadata.get("provider_profile_id"));
        assertEquals("OpenAI", metadata.get("configured_model_provider"));
        assertEquals("gpt-5.4", metadata.get("configured_model"));
        assertFalse(metadata.containsKey("configured_cli_profile"));
    }

    @Test
    void mergeWithNullReturnsThis() {
        ProviderProfileConfig config = new ProviderProfileConfig("test", "OpenAI", "gpt-5.4", "", Map.of());
        ProviderProfileConfig merged = config.merge(null);
        assertEquals(config, merged);
    }

    @Test
    void fromNullMetadataReturnsEmpty() {
        ProviderProfileConfig config = ProviderProfileConfig.fromWorkerMetadata(null);
        assertFalse(config.hasSubstantiveConfig());
    }
}
