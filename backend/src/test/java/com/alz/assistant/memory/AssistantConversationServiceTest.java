package com.alz.assistant.memory;

import com.alz.assistant.DeepSeekClient;
import com.alz.dto.AssistantChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationServiceTest {

    private AssistantConversationMapper conversationMapper;
    private AssistantMessageMapper messageMapper;
    private AssistantMemoryMapper memoryMapper;
    private DeepSeekClient deepSeekClient;
    private AssistantConversationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationMapper = mock(AssistantConversationMapper.class);
        messageMapper = mock(AssistantMessageMapper.class);
        memoryMapper = mock(AssistantMemoryMapper.class);
        deepSeekClient = mock(DeepSeekClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new AssistantConversationService(conversationMapper, messageMapper, memoryMapper,
                deepSeekClient, redis, new ObjectMapper(), new AssistantMemoryProperties());
    }

    @Test
    void rejectsTheOneHundredAndFirstTurn() {
        ConversationOwner owner = new ConversationOwner("ANONYMOUS", "client");
        when(conversationMapper.findOwned("c1", owner.type(), owner.key()))
                .thenReturn(conversation(100, 100, "摘要"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.prepareTurn(owner, "c1", "继续提问"));

        assertEquals("该对话已达到 100 轮上限，请新建对话", exception.getMessage());
    }

    @Test
    void summarizesAndPersistsMemoryAfterEveryTenTurns() {
        ConversationOwner owner = new ConversationOwner("ANONYMOUS", "client");
        AssistantConversation before = conversation(9, 0, "");
        AssistantConversation tenth = conversation(10, 0, "");
        when(conversationMapper.findOwned("c1", owner.type(), owner.key()))
                .thenReturn(before, tenth);
        when(conversationMapper.acquireTurn(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(1);
        when(messageMapper.listAfterTurn("c1", 0)).thenReturn(List.of());

        TurnPreparation turn = service.prepareTurn(owner, "c1", "第十个问题");
        assertEquals(10, turn.turnNo());

        when(conversationMapper.findOwned("c1", owner.type(), owner.key()))
                .thenReturn(tenth, tenth);
        AssistantChatResponse response = new AssistantChatResponse(
                "rag_intro", "科普", "第十个回答", List.of(), "边界", List.of(), false);
        service.completeTurn(owner, turn, response);

        AssistantMessage user = message(10, "USER", "第十个问题");
        AssistantMessage assistant = message(10, "ASSISTANT", "第十个回答");
        AssistantConversation summarized = conversation(10, 10, "新的滚动摘要");
        when(conversationMapper.findOwned("c1", owner.type(), owner.key()))
                .thenReturn(tenth, summarized);
        when(messageMapper.listRange("c1", 1, 10)).thenReturn(List.of(user, assistant));
        when(deepSeekClient.summarize(anyString(), any())).thenReturn(Optional.of("新的滚动摘要"));
        when(messageMapper.listAfterTurn("c1", 10)).thenReturn(List.of());

        var result = service.refreshMemoryAfterTurn(owner, "c1", 10);

        assertEquals(10, result.summarizedThroughTurn());
        verify(memoryMapper).insert(anyString(), anyInt(), anyInt(), anyString());
        verify(conversationMapper).updateSummary("c1", "新的滚动摘要", 10);
    }

    private static AssistantConversation conversation(int turns, int summarizedThrough, String summary) {
        AssistantConversation value = new AssistantConversation();
        value.setId("c1");
        value.setOwnerType("ANONYMOUS");
        value.setOwnerKey("client");
        value.setTitle("新对话");
        value.setUserTurnCount(turns);
        value.setSummaryUpToTurn(summarizedThrough);
        value.setRollingSummary(summary);
        value.setGenerationStatus("IDLE");
        return value;
    }

    private static AssistantMessage message(int turn, String role, String content) {
        AssistantMessage value = new AssistantMessage();
        value.setConversationId("c1");
        value.setTurnNo(turn);
        value.setRole(role);
        value.setContent(content);
        return value;
    }
}
