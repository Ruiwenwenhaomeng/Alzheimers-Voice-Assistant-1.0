package com.alz.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagVectorWarmupTest {

    @Test
    void warmsEnabledRetrieverDuringApplicationStartup() {
        QdrantKnowledgeRetriever retriever = mock(QdrantKnowledgeRetriever.class);
        when(retriever.isEnabled()).thenReturn(true);
        when(retriever.warmUp()).thenReturn(true);

        assertDoesNotThrow(() -> new RagVectorWarmup(retriever, true)
                .run(new DefaultApplicationArguments(new String[0])));

        verify(retriever).warmUp();
    }

    @Test
    void failsStartupWhenRequiredVectorIndexCannotWarm() {
        QdrantKnowledgeRetriever retriever = mock(QdrantKnowledgeRetriever.class);
        when(retriever.isEnabled()).thenReturn(true);
        when(retriever.warmUp()).thenReturn(false);

        RagVectorWarmup warmup = new RagVectorWarmup(retriever, true);

        assertThrows(IllegalStateException.class,
                () -> warmup.run(new DefaultApplicationArguments(new String[0])));
    }
}
