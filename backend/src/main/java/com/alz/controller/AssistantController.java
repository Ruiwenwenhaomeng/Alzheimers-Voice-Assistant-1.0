package com.alz.controller;

import com.alz.assistant.ConversationContext;
import com.alz.assistant.LlmRequestConfig;
import com.alz.dto.AssistantChatRequest;
import com.alz.dto.AssistantChatResponse;
import com.alz.dto.ScreeningGuideResponse;
import com.alz.service.AssistantService;
import com.alz.service.impl.RagAssistantServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody AssistantChatRequest request) {
        if (assistantService instanceof RagAssistantServiceImpl ragService) {
            return ragService.chat(
                    request == null ? null : request.message(),
                    ConversationContext.empty(),
                    LlmRequestConfig.from(request == null ? null : request.modelSettings()));
        }
        return assistantService.chat(request == null ? null : request.message());
    }

    @GetMapping("/screening-guide")
    public ScreeningGuideResponse screeningGuide() {
        return assistantService.screeningGuide();
    }

    @GetMapping("/topics")
    public Map<String, List<String>> topics() {
        return Map.of("topics", assistantService.supportedTopics());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}
