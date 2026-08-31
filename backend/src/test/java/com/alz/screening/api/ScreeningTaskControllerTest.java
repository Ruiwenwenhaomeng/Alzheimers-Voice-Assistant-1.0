package com.alz.screening.api;

import com.alz.config.JwtUtil;
import com.alz.entity.User;
import com.alz.screening.application.ScreeningTaskService;
import com.alz.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreeningTaskControllerTest {

    @Test
    void acceptsTaskAndReturnsLocation() {
        ScreeningTaskService taskService = mock(ScreeningTaskService.class);
        UserService userService = mock(UserService.class);
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        when(userService.findByUsername("alice")).thenReturn(user);
        ScreeningTaskResponse task = new ScreeningTaskResponse(
                "62f7d621-295d-4d43-83f1-09ea40196a1a", 8L, "sample.wav",
                "QUEUED", "QUEUED", 0, "已受理", null, null,
                null, null, null, Map.of());
        when(taskService.createForAudioId(8L, 7L, "request-1")).thenReturn(task);
        ScreeningTaskController controller = new ScreeningTaskController(taskService, userService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + JwtUtil.generateToken("alice", "USER"));

        ResponseEntity<ScreeningTaskResponse> response = controller.create(8L, "request-1", request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("/api/v1/screenings/" + task.taskId(), response.getHeaders().getLocation().toString());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }
}
