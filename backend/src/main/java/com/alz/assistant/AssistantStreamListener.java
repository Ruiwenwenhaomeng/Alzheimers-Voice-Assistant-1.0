package com.alz.assistant;

import com.alz.dto.AssistantSource;

/**
 * Receives user-visible streaming updates. Analysis messages are concise process
 * summaries and must never contain a model's private chain of thought.
 */
public interface AssistantStreamListener {

    default void onStatus(String message) { }

    default void onAnalysis(String summary) { }

    default void onSource(AssistantSource source) { }

    default void onAnswerDelta(String content) { }
}
