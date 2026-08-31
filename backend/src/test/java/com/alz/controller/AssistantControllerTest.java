package com.alz.controller;

import com.alz.service.impl.AssistantServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssistantController(new AssistantServiceImpl()))
                .build();
    }

    @Test
    void returnsStructuredChatResponse() throws Exception {
        mockMvc.perform(post("/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"语音筛查可以确诊吗？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("speech_screening"))
                .andExpect(jsonPath("$.urgent").value(false))
                .andExpect(jsonPath("$.medicalDisclaimer").isNotEmpty())
                .andExpect(jsonPath("$.sources[0].url").isNotEmpty());
    }

    @Test
    void returnsBadRequestForBlankQuestion() throws Exception {
        mockMvc.perform(post("/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }

    @Test
    void exposesScreeningGuide() throws Exception {
        mockMvc.perform(get("/assistant/screening-guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(3))
                .andExpect(jsonPath("$.privacyNotice").isNotEmpty());
    }
}
