package com.alz.assistant;

import com.alz.dto.AssistantModelSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRequestConfigTest {

    @Test
    void acceptsSupportedProviderAndMasksApiKey() {
        LlmRequestConfig config = LlmRequestConfig.from(
                new AssistantModelSettings("kimi", "kimi-k2.6", "secret-key"));

        assertEquals(LlmProvider.KIMI, config.provider());
        assertEquals("kimi-k2.6", config.model());
        assertFalse(config.toString().contains("secret-key"));
    }

    @Test
    void treatsSystemSelectionAsServerDefault() {
        assertNull(LlmRequestConfig.from(new AssistantModelSettings("SYSTEM", "", "")));
        assertNull(LlmRequestConfig.from(null));
    }

    @Test
    void rejectsUnsupportedProviderOrMissingKey() {
        assertThrows(IllegalArgumentException.class, () -> LlmRequestConfig.from(
                new AssistantModelSettings("OTHER", "model", "key")));
        assertThrows(IllegalArgumentException.class, () -> LlmRequestConfig.from(
                new AssistantModelSettings("GLM", "glm-5.2", "")));
    }
}
