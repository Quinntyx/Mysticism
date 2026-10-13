package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Lazy, process-exclusive model ownership. Factory/model code runs only on inference
 * workers, never in a server lifecycle event or under the helper monitor.
 * A new world cannot open another native runtime until the old one has drained and closed.
 */
final class LifecycleEmbeddingProvider implements EmbeddingProvider {
    private static final Semaphore MODEL_LEASE = new Semaphore(1, true);
    private final Supplier<? extends EmbeddingProvider> factory;
    private final Semaphore modelLease;
    private final ReentrantLock calls = new ReentrantLock();
    private EmbeddingProvider delegate;
    private boolean leased, closed;
    private volatile boolean stopRequested;

    LifecycleEmbeddingProvider(Supplier<? extends EmbeddingProvider> factory) {
        this(factory, MODEL_LEASE);
    }
    // Isolated gate for failure-path tests (a failed native teardown intentionally poisons its gate).
    LifecycleEmbeddingProvider(Supplier<? extends EmbeddingProvider> factory, Semaphore modelLease) {
        this.factory = Objects.requireNonNull(factory);
        this.modelLease = Objects.requireNonNull(modelLease);
    }

    public EmbeddingProfile profile() { return EmbeddingProfile.current(); }

    public void checkReady() throws Exception {
        calls.lockInterruptibly();
        try {
            requireRunning();
            if (delegate == null) {
                modelLease.acquire();
                leased = true;
                requireRunning();
                delegate = Objects.requireNonNull(factory.get(), "Embedding factory returned null");
                requireRunning(); // Native factories may swallow interruption; exit still wins.
                if (!profile().equals(delegate.profile())) throw new IllegalArgumentException("Provider profile mismatch");
            }
            requireRunning();
            delegate.checkReady();
        } finally { calls.unlock(); }
    }

    public Vec384f getEmbedding(String descriptor) throws Exception {
        // One predictor need not be thread-safe. Waiting callers are interruptible on shutdown.
        calls.lockInterruptibly();
        try {
            requireRunning();
            if (delegate == null) throw new IllegalStateException("Embedding provider unavailable");
            return delegate.getEmbedding(descriptor);
        } finally { calls.unlock(); }
    }

    // Lifecycle signalling is a volatile write only: no model code or locks on the server thread.
    void requestStop() { stopRequested = true; }

    private void requireRunning() throws InterruptedException {
        if (closed || stopRequested || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Embedding session stopped");
    }

    /** Only called after worker termination. Avoid queuing empty sessions behind a hung native
     * close; repeated cancelled launches must not accumulate cleanup jobs. */
    boolean closeUnopened() {
        calls.lock();
        try {
            if (leased || delegate != null) return false;
            closed = true; stopRequested = true; return true;
        } finally { calls.unlock(); }
    }

    public void close() {
        calls.lock();
        try {
            if (closed) return;
            closed = true; stopRequested = true;
            // The engine invokes this only after ALL provider calls have returned. If a native
            // close fails, retain the process lease: opening another runtime is not demonstrably safe.
            if (delegate != null) delegate.close();
            delegate = null;
            if (leased) { leased = false; modelLease.release(); }
        } finally { calls.unlock(); }
    }
}
