package com.alz.assistant;

import java.util.List;

/** Bounded memory sent to the model, never the complete conversation. */
public record ConversationContext(String rollingSummary, List<ConversationTurn> recentTurns) {

    public ConversationContext {
        rollingSummary = rollingSummary == null ? "" : rollingSummary.trim();
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public static ConversationContext empty() {
        return new ConversationContext("", List.of());
    }

    public boolean isEmpty() {
        return rollingSummary.isBlank() && recentTurns.isEmpty();
    }
}
