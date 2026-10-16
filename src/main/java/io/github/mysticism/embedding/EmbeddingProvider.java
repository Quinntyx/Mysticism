package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;

/** Blocking provider behind bounded asynchronous workers. Implementations never download models. */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingProfile profile();
    void checkReady() throws Exception;
    Vec384f getEmbedding(String descriptor) throws Exception;
    /** Recheck request ownership after waiting for a serialized native context. A timed-out
     * waiter must not launch expensive inference when the previous call finally returns. */
    default Vec384f getEmbedding(String descriptor, java.util.function.BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException("Embedding request expired");
        return getEmbedding(descriptor);
    }
    @Override void close();
}
