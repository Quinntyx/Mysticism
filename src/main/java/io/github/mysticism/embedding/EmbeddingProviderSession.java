package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Exclusive ownership of a provider's tokenizer, predictor and borrowed output buffers.
 * No engine/lifecycle monitor is held during provider calls. Shutdown refuses new calls
 * immediately, but never frees native state while a call still owns it.
 */
final class EmbeddingProviderSession implements AutoCloseable {
    private final EmbeddingProvider provider;
    private boolean busy, closing, disposed;

    EmbeddingProviderSession(EmbeddingProvider provider) { this.provider = provider; }

    private synchronized void acquire() throws InterruptedException {
        while (busy && !closing) wait();
        if (closing) throw new CancellationException("Embedding provider session closed");
        busy = true;
    }

    private void release() {
        boolean dispose;
        synchronized (this) {
            busy = false;
            dispose = closing && !disposed;
            if (dispose) disposed = true;
            notifyAll();
        }
        if (dispose) provider.close();
    }

    void checkReady() throws Exception {
        acquire();
        try { provider.checkReady(); }
        finally { release(); }
    }

    /** Recheck deadline/engine ownership AFTER waiting for the native session. A timed-out
     * waiter must not launch expensive inference when a prior native call finally returns.
     * Detach and normalize the complete native vector BEFORE another call can reuse it.
     */
    Vec384f infer(String descriptor, BooleanSupplier current) throws Exception {
        acquire();
        try {
            if (!current.getAsBoolean()) return null;
            Vec384f borrowed = provider.getEmbedding(descriptor);
            EmbeddingSpace.requireCurrent(borrowed);
            Vec384f owned = borrowed.clone();
            if (owned.length() == 0) throw new IllegalArgumentException("Provider returned zero embedding");
            return new Vec384f(owned.norm());
        } finally { release(); }
    }

    @Override public void close() {
        boolean dispose;
        synchronized (this) {
            closing = true;
            dispose = !busy && !disposed;
            if (dispose) disposed = true;
            notifyAll();
        }
        // An active owner performs this cleanup on return, even if it ignores interruption.
        // Neither shutdown nor deadline delivery waits for that owner.
        if (dispose) provider.close();
    }
}
