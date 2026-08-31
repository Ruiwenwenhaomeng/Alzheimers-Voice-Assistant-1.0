package com.alz.assistant.memory;

import com.alz.assistant.ConversationContext;

public record TurnPreparation(String conversationId, int turnNo, int maxTurns,
                              String question, ConversationContext context) {
}
