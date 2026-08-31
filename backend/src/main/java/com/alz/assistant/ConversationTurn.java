package com.alz.assistant;

public record ConversationTurn(int turnNo, String userMessage, String assistantMessage) {
}
