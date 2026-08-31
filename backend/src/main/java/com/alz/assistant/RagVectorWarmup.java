package com.alz.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RagVectorWarmup implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagVectorWarmup.class);

    private final QdrantKnowledgeRetriever retriever;
    private final boolean enabled;

    public RagVectorWarmup(
            QdrantKnowledgeRetriever retriever,
            @Value("${app.rag.vector.warmup-enabled:true}") boolean enabled) {
        this.retriever = retriever;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !retriever.isEnabled()) {
            LOGGER.info("RAG vector startup warm-up is disabled");
            return;
        }
        long startedAt = System.nanoTime();
        LOGGER.info("Starting RAG vector warm-up before application readiness");
        if (!retriever.warmUp()) {
            throw new IllegalStateException("RAG vector warm-up failed; Qdrant knowledge index is not ready");
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOGGER.info("RAG vector warm-up completed in {} ms", elapsedMillis);
    }
}
