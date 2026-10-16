package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;

/** Blocking provider behind bounded asynchronous workers. Implementations never download models.
 * Ownership transfers to one AsyncEmbeddingEngine: callers must not share it with another
 * engine or invoke it directly after transfer. The engine serializes readiness/inference
 * and snapshots the complete borrowed vector before the next call can reuse its buffer.
 * Native adapters must finish native work and release per-call tensors before returning.
 * Factories must unwind partially acquired resources if construction throws. The lazy
 * lifecycle wrapper holds its process lease through factory, inference and completed close.
 * Engine close is invoked once, off the server thread, only after all workers have drained.
 * A failed native close prevents another lifecycle-managed runtime opening.
 * profile() is metadata-only and must not initialize/load a model.
 */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingProfile profile();
    void checkReady() throws Exception;
    Vec384f getEmbedding(String descriptor) throws Exception;
    /** Cancellation signal only: bounded, lock/IO-free, idempotent; never free native state.
     * Needed when a native loader ignores or clears the worker's interrupt flag. */
    default void requestStop() {}
    /** Returns only after teardown completes. The engine supplies an off-thread, drained
     * owner; asynchronous close would release the process lease before JNI is safe. */
    @Override void close();
}
