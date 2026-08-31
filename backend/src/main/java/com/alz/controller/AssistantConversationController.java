package com.alz.controller;

import com.alz.assistant.AssistantStreamListener;
import com.alz.assistant.LlmRequestConfig;
import com.alz.assistant.memory.AssistantConversationService;
import com.alz.assistant.memory.ConversationOwner;
import com.alz.assistant.memory.ConversationOwnerResolver;
import com.alz.assistant.memory.TurnPreparation;
import com.alz.dto.AssistantChatRequest;
import com.alz.dto.AssistantChatResponse;
import com.alz.dto.AssistantConversationDetail;
import com.alz.dto.AssistantConversationSummary;
import com.alz.dto.CreateAssistantConversationRequest;
import com.alz.service.impl.RagAssistantServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant/conversations")
public class AssistantConversationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantConversationController.class);

    private final AssistantConversationService conversationService;
    private final ConversationOwnerResolver ownerResolver;
    private final RagAssistantServiceImpl ragAssistantService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor memoryExecutor;

    public AssistantConversationController(AssistantConversationService conversationService,
                                           ConversationOwnerResolver ownerResolver,
                                           RagAssistantServiceImpl ragAssistantService,
                                           ObjectMapper objectMapper,
                                           @Qualifier("assistantMemoryExecutor") TaskExecutor memoryExecutor) {
        this.conversationService = conversationService;
        this.ownerResolver = ownerResolver;
        this.ragAssistantService = ragAssistantService;
        this.objectMapper = objectMapper;
        this.memoryExecutor = memoryExecutor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantConversationSummary create(@RequestBody(required = false) CreateAssistantConversationRequest body,
                                               HttpServletRequest request) {
        return conversationService.create(ownerResolver.resolve(request), body == null ? null : body.title());
    }

    @GetMapping
    public List<AssistantConversationSummary> list(HttpServletRequest request) {
        return conversationService.list(ownerResolver.resolve(request));
    }

    @GetMapping("/{conversationId}")
    public AssistantConversationDetail detail(@PathVariable String conversationId, HttpServletRequest request) {
        return conversationService.detail(ownerResolver.resolve(request), conversationId);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId, HttpServletRequest request) {
        conversationService.delete(ownerResolver.resolve(request), conversationId);
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String conversationId,
                                                        @RequestBody AssistantChatRequest body,
                                                        HttpServletRequest request) {
        ConversationOwner owner = ownerResolver.resolve(request);
        LlmRequestConfig modelConfig = LlmRequestConfig.from(
                body == null ? null : body.modelSettings());
        TurnPreparation turn = conversationService.prepareTurn(owner, conversationId,
                body == null ? null : body.message());

        StreamingResponseBody stream = output -> {
            try {
                writeEvent(output, "start", Map.of(
                        "conversationId", turn.conversationId(),
                        "turnNo", turn.turnNo(),
                        "maxTurns", turn.maxTurns()
                ));
                AssistantChatResponse response = ragAssistantService.streamChat(
                        turn.question(), turn.context(), modelConfig, new AssistantStreamListener() {
                            @Override
                            public void onStatus(String message) {
                                emit(output, "status", Map.of("message", message));
                            }

                            @Override
                            public void onAnalysis(String summary) {
                                emit(output, "analysis", Map.of("content", summary));
                            }

                            @Override
                            public void onSource(com.alz.dto.AssistantSource source) {
                                emit(output, "source", source);
                            }

                            @Override
                            public void onAnswerDelta(String content) {
                                if (content != null && !content.isEmpty()) {
                                    emit(output, "delta", Map.of("content", content));
                                }
                            }
                        });
                AssistantConversationSummary conversation = conversationService.completeTurn(owner, turn, response);
                try {
                    memoryExecutor.execute(() -> {
                        try {
                            conversationService.refreshMemoryAfterTurn(owner, turn.conversationId(), turn.turnNo());
                        } catch (RuntimeException ignored) { }
                    });
                } catch (RuntimeException ignored) {
                    // A later completed turn retries any summary block that is still missing.
                }
                writeEvent(output, "complete", Map.of(
                        "response", response,
                        "conversation", conversation
                ));
            } catch (Exception exception) {
                LOGGER.warn("Assistant response stream failed: conversationId={}, turnNo={}",
                        turn.conversationId(), turn.turnNo(), exception);
                try {
                    conversationService.failTurn(owner, turn, "回答生成失败，请稍后重试。");
                } catch (RuntimeException ignored) { }
                try {
                    writeEvent(output, "error", Map.of("message", safeMessage(exception)));
                } catch (IOException ignored) { }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private void writeEvent(OutputStream output, String event, Object data) throws IOException {
        String payload = "event: " + event + "\n" +
                "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void emit(OutputStream output, String event, Object data) {
        try {
            writeEvent(output, event, data);
        } catch (IOException exception) {
            throw new StreamWriteException(exception);
        }
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof StreamWriteException) return "客户端已断开连接";
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "回答生成失败，请稍后重试" : message;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException exception) {
        return Map.of("message", exception.getMessage());
    }

    private static final class StreamWriteException extends RuntimeException {
        private StreamWriteException(IOException cause) { super(cause); }
    }
}
