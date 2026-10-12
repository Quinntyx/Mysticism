package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;

/** Blocking provider behind bounded asynchronous workers. Implementations never download models.
 * Ownership transfers to one AsyncEmbeddingEngine: callers must not share it with another
 * engine or invoke it directly after transfer. The engine serializes readiness/inference,
 * snapshots borrowed vectors before the next call, and defers close until an active call
 * returns. Native adapters must release per-call tensors before returning; session-owned
 * tokenizer/predictor state may be reused only within these serialized calls.
 */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingProfile profile();
    void checkReady() throws Exception;
    Vec384f getEmbedding(String descriptor) throws Exception;
    @Override void close();
}
