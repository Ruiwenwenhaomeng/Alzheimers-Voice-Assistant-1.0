package com.alz.screening.api;

import com.alz.config.JwtUtil;
import com.alz.config.RequireRole;
import com.alz.entity.User;
import com.alz.screening.application.ScreeningTaskService;
import com.alz.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequireRole("USER")
public class ScreeningTaskController {

    private final ScreeningTaskService screeningTaskService;
    private final UserService userService;

    public ScreeningTaskController(ScreeningTaskService screeningTaskService, UserService userService) {
        this.screeningTaskService = screeningTaskService;
        this.userService = userService;
    }

    @PostMapping("/audios/{audioId}/screenings")
    public ResponseEntity<ScreeningTaskResponse> create(
            @PathVariable Long audioId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        User user = currentUser(request);
        ScreeningTaskResponse response = screeningTaskService.createForAudioId(
                audioId, user.getId(), idempotencyKey);
        return accepted(response);
    }

    @GetMapping("/screenings/{taskId}")
    public ScreeningTaskResponse get(@PathVariable String taskId, HttpServletRequest request) {
        return screeningTaskService.getOwned(taskId, currentUser(request).getId());
    }

    @GetMapping("/screenings")
    public List<ScreeningTaskResponse> list(
            @RequestParam(value = "status", defaultValue = "all") String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        return screeningTaskService.listOwned(currentUser(request).getId(),
                "active".equalsIgnoreCase(status), page, size);
    }

    @DeleteMapping("/screenings/{taskId}")
    public ResponseEntity<ScreeningTaskResponse> cancel(
            @PathVariable String taskId, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(screeningTaskService.cancel(taskId, currentUser(request).getId()));
    }

    @PostMapping("/screenings/{taskId}/retry")
    public ResponseEntity<ScreeningTaskResponse> retry(
            @PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return accepted(screeningTaskService.retry(
                taskId, currentUser(request).getId(), idempotencyKey));
    }

    private ResponseEntity<ScreeningTaskResponse> accepted(ScreeningTaskResponse response) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/screenings/" + response.taskId()))
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(response);
    }

    private User currentUser(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        User user = userService.findByUsername(JwtUtil.getUsername(authorization.substring(7)));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }
}
