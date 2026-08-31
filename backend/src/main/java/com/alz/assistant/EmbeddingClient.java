package com.alz.assistant;

import java.util.List;
import java.util.Optional;

public interface EmbeddingClient {

    Optional<List<List<Double>>> embedAll(List<String> texts);

    default Optional<List<Double>> embed(String text) {
        return embedAll(List.of(text))
                .filter(vectors -> vectors.size() == 1)
                .map(vectors -> vectors.get(0));
    }
}
