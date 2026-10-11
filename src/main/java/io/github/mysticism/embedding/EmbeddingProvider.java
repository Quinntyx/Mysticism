package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;

/** Blocking provider behind bounded asynchronous workers. Implementations never download models.
 * Factories must unwind partially acquired resources if construction throws. The lifecycle wrapper
 * serializes predictor calls and owns a returned provider until all calls have drained; close is
 * invoked once, off the server thread. A failed native close prevents another runtime opening.
 * profile() is metadata-only and must not initialize/load a model.
 */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingProfile profile();
    void checkReady() throws Exception;
    Vec384f getEmbedding(String descriptor) throws Exception;
    @Override void close();
}
