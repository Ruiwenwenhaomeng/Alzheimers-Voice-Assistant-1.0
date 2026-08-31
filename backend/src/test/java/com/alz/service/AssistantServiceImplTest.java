package com.alz.service;

import com.alz.dto.AssistantChatResponse;
import com.alz.dto.ScreeningGuideResponse;
import com.alz.service.impl.AssistantServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantServiceImplTest {

    private final AssistantService service = new AssistantServiceImpl();

    @Test
    void identifiesSpeechScreeningQuestionAndStatesBoundary() {
        AssistantChatResponse response = service.chat("语音筛查可以确诊吗？");

        assertEquals("speech_screening", response.intent());
        assertFalse(response.urgent());
        assertTrue(response.answer().contains("不能确诊"));
        assertFalse(response.sources().isEmpty());
    }

    @Test
    void escalatesPossibleStrokeSymptoms() {
        AssistantChatResponse response = service.chat("老人突然说不清话而且一侧无力");

        assertEquals("emergency", response.intent());
        assertTrue(response.urgent());
        assertTrue(response.answer().contains("120"));
    }

    @Test
    void providesActionableSymptomGuidanceWithoutDiagnosis() {
        AssistantChatResponse response = service.chat("最近总忘事，是阿尔茨海默病吗？");

        assertEquals("symptoms", response.intent());
        assertFalse(response.actionSuggestions().isEmpty());
        assertTrue(response.medicalDisclaimer().contains("不能诊断"));
    }

    @Test
    void rejectsBlankQuestion() {
        assertThrows(IllegalArgumentException.class, () -> service.chat("  "));
    }

    @Test
    void screeningGuideIncludesConsentPrivacyAndEmergencyRules() {
        ScreeningGuideResponse guide = service.screeningGuide();

        assertTrue(guide.beforeRecording().stream().anyMatch(item -> item.contains("知情同意")));
        assertTrue(guide.privacyNotice().contains("敏感健康信息"));
        assertTrue(guide.stopAndSeekHelpWhen().stream().anyMatch(item -> item.contains("120")));
    }
}
