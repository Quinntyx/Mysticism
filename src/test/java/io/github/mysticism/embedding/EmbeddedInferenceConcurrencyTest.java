package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real engine/session ownership tests, not model inference. The deliberately unsafe
 * provider models a single native predictor and one reused mutable output buffer.
 */
public final class EmbeddedInferenceConcurrencyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(3, TimeUnit.SECONDS), "Gate did not open");
    }
    private static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }
    private static void failed(CompletableFuture<?> future, Class<? extends Throwable> type) throws Exception {
        try { get(future); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (ExecutionException error) { check(type.isInstance(error.getCause()), "Wrong failure: " + error); }
        catch (CancellationException error) { check(type.isInstance(error), "Wrong cancellation"); }
    }
    private static final class BorrowingProvider implements EmbeddingProvider {
        final AtomicInteger active = new AtomicInteger(), calls = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicBoolean overlap = new AtomicBoolean();
        final CountDownLatch started = new CountDownLatch(1), gate = new CountDownLatch(1), disposed = new CountDownLatch(1);
        final Vec384f output = Vec384f.ZERO();
        volatile boolean holdReady, holdFirst;
        volatile String closingThread;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        private void enter() { if (active.incrementAndGet() != 1) overlap.set(true); }
        private void leave() { active.decrementAndGet(); }
        public void checkReady() {
            enter();
            try { if (holdReady) { started.countDown(); uninterruptibly(gate); } }
            finally { leave(); }
        }
        public Vec384f getEmbedding(String descriptor) {
            enter();
            try {
                int call = calls.incrementAndGet();
                if (holdFirst && call == 1) { started.countDown(); uninterruptibly(gate); }
                if (descriptor.equals("throw")) throw new IllegalStateException("Native inference failure fixture");
                float[] values = new float[EmbeddingSpace.NATIVE_DIMENSIONS];
                if (descriptor.equals("wrong-profile")) return new Vec384f(values, "foreign-profile");
                if (!descriptor.equals("zero")) values[Integer.parseInt(descriptor)] = 7;
                output.mul(0).add(new Vec384f(values));
                return output; // The next call overwrites exactly this object.
            } finally { leave(); }
        }
        public void close() {
            closingThread = Thread.currentThread().getName();
            enter();
            try { closes.incrementAndGet(); output.mul(0); }
            finally { leave(); disposed.countDown(); }
        }
    }
    private static AsyncEmbeddingEngine engine(EmbeddingProvider provider, int workers, long deadlineMillis) {
        return new AsyncEmbeddingEngine(provider, workers, 64, 64, Duration.ofMillis(deadlineMillis));
    }
    private static void axis(Vec384f vector, int index) {
        float[] values = vector.data();
        check(values.length == 768 && values.length == EmbeddingSpace.NATIVE_DIMENSIONS, "Native v2 coordinates truncated");
        check(vector.fingerprint().equals(EmbeddingSpace.FINGERPRINT), "Profile changed");
        for (int i = 0; i < values.length; i++) check(values[i] == (i == index ? 1 : 0), "Mixed output at " + i);
    }
    private static void borrowedOutput() throws Exception {
        BorrowingProvider provider = new BorrowingProvider();
        try (var session = new EmbeddingProviderSession(provider)) {
            session.checkReady();
            Vec384f first = session.infer("767", () -> true);
            Vec384f second = session.infer("31", () -> true);
            axis(first, 767); axis(second, 31);
            check(session.infer("0", () -> false) == null, "Expired ownership was not skipped");
            check(provider.calls.get() == 2, "Skipped request touched native state");
        }
        await(provider.disposed);
        check(provider.closes.get() == 1 && !provider.overlap.get(), "Session disposal raced or repeated");
    }
    private static void concurrentRequests(boolean lifecycleManaged) throws Exception {
        BorrowingProvider provider = new BorrowingProvider(); provider.holdFirst = true;
        // Both adapters must retain output ownership through snapshot/normalization;
        // serializing getEmbedding alone does not protect a borrowed return buffer.
        EmbeddingProvider adapter = lifecycleManaged ? new LifecycleEmbeddingProvider(() -> provider) : provider;
        var e = engine(adapter, 8, 3000);
        try {
            get(e.readiness());
            List<CompletableFuture<Vec384f>> requests = new ArrayList<>();
            requests.add(e.embed("767")); await(provider.started);
            for (int i = 0; i < 23; i++) requests.add(e.embed(Integer.toString(i)));
            // All distinct requests are admitted while the sole predictor remains busy.
            check(!provider.overlap.get(), "Shared predictor called concurrently");
            check(e.inflightSize() == 24, "Concurrent admission lost requests");
            provider.gate.countDown();
            axis(get(requests.get(0)), 767);
            for (int i = 1; i < requests.size(); i++) axis(get(requests.get(i)), i - 1);
            check(provider.calls.get() == 24 && !provider.overlap.get(), "Shared predictor called concurrently");
            Vec384f cached = get(e.embed("767")); cached.mul(0);
            axis(get(e.embed("767")), 767);
            check(provider.calls.get() == 24, "Cache miss after concurrent inference");
            failed(e.embed("throw"), IllegalStateException.class);
            axis(get(e.embed("100")), 100);
            check(!provider.overlap.get(), "Failure recovery bypassed session ownership");
        } finally { provider.gate.countDown(); e.close(); }
        await(provider.disposed);
        check(e.awaitClosed(Duration.ofSeconds(2)), "Worker drain/cleanup did not finish");
        check(provider.closes.get() == 1 && "Mysticism-EmbedCleanup".equals(provider.closingThread),
                "Provider not closed exactly once on cleanup thread");
    }
    private static void invalidModelOutputs() throws Exception {
        for (String invalid : List.of("zero", "wrong-profile")) {
            var provider = new BorrowingProvider();
            var e = engine(new LifecycleEmbeddingProvider(() -> provider), 4, 3000);
            try {
                get(e.readiness());
                failed(e.embed(invalid), EmbeddingModelException.class);
                check(e.state().phase() == EmbeddingReadiness.Phase.FAILED, "Invalid native output retained readiness");
                failed(e.embed("1"), IllegalStateException.class);
                check(provider.calls.get() == 1, "Broken native generation ran another inference");
            } finally { e.close(); }
            check(e.awaitClosed(Duration.ofSeconds(2)), "Invalid generation did not drain");
            check(provider.closes.get() == 1 && !provider.overlap.get(), "Invalid generation leaked or raced disposal");
        }
    }
    private static void expiredWaiters() throws Exception {
        BorrowingProvider provider = new BorrowingProvider(); provider.holdFirst = true;
        var e = engine(provider, 4, 250);
        try {
            get(e.readiness());
            var first = e.embed("767"); await(provider.started);
            var duplicate = e.embed("767");
            var second = e.embed("1"); var third = e.embed("2"); var fourth = e.embed("3");
            failed(first, TimeoutException.class); failed(duplicate, TimeoutException.class);
            failed(second, TimeoutException.class); failed(third, TimeoutException.class); failed(fourth, TimeoutException.class);
            check(e.inflightSize() == 0 && e.cacheSize() == 0, "Expired requests published or retained");
            provider.gate.countDown();
            axis(get(e.embed("4")), 4);
            check(provider.calls.get() == 2, "Expired native-session waiter ran inference");
            check(e.cacheSize() == 1 && !provider.overlap.get(), "Late result cached or predictor overlapped");
        } finally { provider.gate.countDown(); e.close(); }
        await(provider.disposed);
    }
    private static void shutdownDuringCall(boolean readiness) throws Exception {
        BorrowingProvider provider = new BorrowingProvider();
        provider.holdReady = readiness; provider.holdFirst = !readiness;
        var e = engine(provider, 4, 3000);
        try {
            CompletableFuture<?> pending;
            CompletableFuture<?> waiter = null;
            if (readiness) pending = e.readiness();
            else { get(e.readiness()); pending = e.embed("767"); waiter = e.embed("1"); }
            await(provider.started);
            long before = System.nanoTime();
            e.close(); e.close();
            check(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "Shutdown waited for native inference");
            failed(pending, CancellationException.class);
            if (waiter != null) failed(waiter, CancellationException.class);
            check(provider.closes.get() == 0 && !provider.overlap.get(), "Shutdown freed active native state");
            failed(e.embed("2"), IllegalStateException.class);
            provider.gate.countDown(); await(provider.disposed);
            check(e.awaitClosed(Duration.ofSeconds(2)), "Deferred worker drain did not finish");
            check(provider.closes.get() == 1 && !provider.overlap.get()
                    && "Mysticism-EmbedCleanup".equals(provider.closingThread),
                    "Deferred native disposal raced, repeated, or ran on active worker");
            check(provider.calls.get() == (readiness ? 0 : 1), "Queued native call ran after shutdown");
            check(!e.isReady() && e.cacheSize() == 0, "Late native return revived closed engine");
        } finally { provider.gate.countDown(); e.close(); }
    }
    private static void independentSessions() throws Exception {
        BorrowingProvider stalled = new BorrowingProvider(); stalled.holdFirst = true;
        BorrowingProvider fresh = new BorrowingProvider();
        var oldEngine = engine(stalled, 4, 3000);
        try {
            get(oldEngine.readiness()); var pending = oldEngine.embed("767"); await(stalled.started);
            oldEngine.close(); failed(pending, CancellationException.class);
            // Restarting with a distinct provider must not wait for the old native owner.
            try (var replacement = engine(fresh, 4, 3000)) {
                get(replacement.readiness()); axis(get(replacement.embed("700")), 700);
                check(stalled.closes.get() == 0, "Restart disposed the old active provider");
            }
            await(fresh.disposed);
            stalled.gate.countDown(); await(stalled.disposed);
            check(fresh.closes.get() == 1 && stalled.closes.get() == 1, "Independent sessions leaked");
            check(!fresh.overlap.get() && !stalled.overlap.get(), "Restart mixed native sessions");
        } finally { stalled.gate.countDown(); oldEngine.close(); }
    }
    private static void cancelledSubscriber() throws Exception {
        BorrowingProvider provider = new BorrowingProvider(); provider.holdFirst = true;
        try (var e = engine(provider, 4, 3000)) {
            get(e.readiness()); var detached = e.embed("767"); await(provider.started);
            var sibling = e.embed("767"); detached.cancel(true);
            provider.gate.countDown(); axis(get(sibling), 767);
            check(detached.isCancelled() && provider.calls.get() == 1, "Subscriber cancellation corrupted shared work");
        }
        await(provider.disposed);
    }
    public static void main(String[] args) throws Exception {
        borrowedOutput(); concurrentRequests(false); concurrentRequests(true); invalidModelOutputs(); expiredWaiters();
        shutdownDuringCall(false); shutdownDuringCall(true); independentSessions(); cancelledSubscriber();
        System.out.println("EmbeddedInferenceConcurrencyTest passed: " + checks + " checks (unsafe provider fixtures; no model inference)");
    }
}
