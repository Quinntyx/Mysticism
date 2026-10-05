package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;

/** Blocking provider behind bounded asynchronous workers. Implementations never download models. */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingProfile profile();
    void checkReady() throws Exception;
    Vec384f getEmbedding(String descriptor) throws Exception;
    @Override void close();
}
