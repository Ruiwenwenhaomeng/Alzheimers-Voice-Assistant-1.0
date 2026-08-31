package com.alz.service;

import com.alz.dto.AssistantChatResponse;
import com.alz.dto.ScreeningGuideResponse;

import java.util.List;

public interface AssistantService {

    AssistantChatResponse chat(String message);

    ScreeningGuideResponse screeningGuide();

    List<String> supportedTopics();
}
