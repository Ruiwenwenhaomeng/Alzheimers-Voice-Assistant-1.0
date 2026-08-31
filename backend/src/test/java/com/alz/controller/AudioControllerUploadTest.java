package com.alz.controller;

import com.alz.config.JwtUtil;
import com.alz.config.ScreeningConsentPolicy;
import com.alz.entity.User;
import com.alz.service.AudioService;
import com.alz.service.DiagnosisReportService;
import com.alz.service.PdfReportService;
import com.alz.service.PythonService;
import com.alz.service.UserService;
import com.alz.validation.AudioUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioControllerUploadTest {

    @Mock private AudioService audioService;
    @Mock private UserService userService;
    @Mock private PythonService pythonService;
    @Mock private DiagnosisReportService reportService;
    @Mock private PdfReportService pdfReportService;
    @Mock private AudioUploadValidator audioUploadValidator;

    @InjectMocks
    private AudioController controller;

    private MockHttpServletRequest request;

    @BeforeEach
    void authenticateUser() {
        String token = JwtUtil.generateToken("alice", "USER");
        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        when(userService.findByUsername("alice")).thenReturn(user);
    }

    @Test
    void rejectsUploadWithoutExplicitConsent() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.upload(dummyFile(), 30, "natural-speech", false,
                        ScreeningConsentPolicy.CURRENT_VERSION,
                        ScreeningConsentPolicy.DEFAULT_TASK_TYPE, request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(audioUploadValidator, audioService);
    }

    @Test
    void rejectsAStaleConsentVersion() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.upload(dummyFile(), 30, "natural-speech", true,
                        "voice-screening-consent-old",
                        ScreeningConsentPolicy.DEFAULT_TASK_TYPE, request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(audioUploadValidator, audioService);
    }

    private MockMultipartFile dummyFile() {
        return new MockMultipartFile("file", "sample.wav", "audio/wav", new byte[]{1});
    }
}
