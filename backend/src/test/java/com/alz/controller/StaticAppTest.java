package com.alz.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaticAppTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesAccessibleAssistantHomePageWithoutLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        byte[] htmlBytes = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String html = new String(htmlBytes, StandardCharsets.UTF_8);
        assertTrue(html.contains("忆声守护"));
        assertTrue(html.contains("不能诊断、排除或治疗阿尔茨海默病"));
        assertTrue(html.contains("看图说话"));
        assertTrue(html.contains("回答模型"));
        assertTrue(html.contains("Kimi"));
        assertTrue(html.contains("智谱 GLM"));
        assertTrue(html.contains("通义千问"));
        assertTrue(html.contains("class=\"app-shell\""));
        assertTrue(html.contains("class=\"app-sidebar\""));
        assertTrue(html.contains("id=\"currentSectionName\""));
    }

    @Test
    void servesBrowserRecordingApplicationScript() throws Exception {
        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String script = new String(
                            result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
                    assertTrue(script.contains("encodeWav"));
                    assertTrue(script.contains("/audio/screening/"));
                    assertTrue(script.contains("/user/detect"));
                    assertTrue(script.contains("/admin/detect"));
                    assertTrue(script.contains("playProtectedAudio"));
                    assertTrue(script.contains("Authorization: `Bearer ${state.token}`"));
                    assertTrue(script.contains("message.event === \"source\""));
                    assertTrue(script.contains("message.event === \"analysis\""));
                    assertTrue(script.contains("stream-progress"));
                    assertTrue(script.contains("查看检索与分析摘要"));
                    assertTrue(script.contains("alzAssistantModelSettings:"));
                    assertTrue(script.contains("modelSettings"));
                    assertTrue(script.contains("document.body.dataset.activeSection"));
                    assertTrue(script.contains("ArrowRight"));
                    assertTrue(script.contains("retryScreeningTask"));
                    assertTrue(script.contains("重新筛查"));
                });
    }

    @Test
    void servesDistinctStreamingProgressStyles() throws Exception {
        mockMvc.perform(get("/app.css"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String css = new String(
                            result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
                    assertTrue(css.contains(".stream-progress"));
                    assertTrue(css.contains(".stream-sources"));
                    assertTrue(css.contains(".stream-answer"));
                    assertTrue(css.contains(".stream-process-summary"));
                    assertTrue(css.contains(".app-shell"));
                    assertTrue(css.contains(".app-sidebar"));
                    assertTrue(css.contains("@keyframes panelReveal"));
                    assertTrue(css.contains("@media (max-width: 680px)"));
                    assertTrue(css.contains("prefers-reduced-motion: reduce"));
                });
    }

    @Test
    void servesPictureTaskImagesWithoutLogin() throws Exception {
        mockMvc.perform(get("/test.jpg"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test1.jpg"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test2.jpg"))
                .andExpect(status().isOk());
    }
}
