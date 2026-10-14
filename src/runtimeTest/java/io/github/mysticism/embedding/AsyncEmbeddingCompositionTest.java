package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production composition/engine tests; synthetic fixture vectors, no game/model/network. */
public final class AsyncEmbeddingCompositionTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    private static void await(CountDownLatch gate) {
        try { if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture gate timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static Vec384f axis() {
        float[] values = new float[EmbeddingSpace.DIMENSIONS]; values[values.length - 1] = 2;
        return new Vec384f(values);
    }
    private static void failed(CompletableFuture<?> future, Class<? extends Throwable> type) throws Exception {
        try { get(future); throw new AssertionError("Expected " + type); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            check(type.isInstance(cause), "Wrong failure: " + cause);
        }
    }
    private static List<DescriptorVectors.WeightedDescriptor> parts(int count) {
        List<DescriptorVectors.WeightedDescriptor> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new DescriptorVectors.WeightedDescriptor("descriptor-" + i, i + 1));
        return result;
    }
    private static void cachedAndCallbacks() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean onCaller = new AtomicBoolean(), onDelivery = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        List<DescriptorVectors.WeightedDescriptor> input = parts(256);
        CompletableFuture<Vec384f> result = DescriptorVectors.embed(input, descriptor -> {
            onCaller.compareAndSet(false, Thread.currentThread() == caller);
            onDelivery.compareAndSet(false, Thread.currentThread().getName().equals("Mysticism-EmbedDelivery"));
            calls.incrementAndGet(); started.countDown(); await(release);
            return CompletableFuture.completedFuture(axis()); // all-cache-hit path
        });
        try {
            check(started.await(2, TimeUnit.SECONDS), "Composition worker never entered");
            check(!result.isDone(), "Submission waited for cached composition");
            input.clear(); // API must have captured a stable descriptor list
            CountDownLatch callbackEntered = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
            AtomicReference<String> callbackThread = new AtomicReference<>();
            var callback = result.thenAccept(value -> {
                callbackThread.set(Thread.currentThread().getName()); callbackEntered.countDown(); await(callbackRelease);
            });
            try {
                release.countDown();
                // CompletableFuture.get may help fire dependent callbacks on the waiter. Wait
                // for the reserved publisher to enter this blocking fixture before reading it.
                check(callbackEntered.await(2, TimeUnit.SECONDS), "Completion not delivered");
                Vec384f composed = get(result);
                check(calls.get() == 256 && !onCaller.get() && !onDelivery.get(), "Cache work ran on caller/delivery or snapshot changed");
                check(composed.data().length == 768 && Math.abs(composed.data()[767] - 1) < 1e-6,
                        "Composition lost native v2 coordinates/normalization");
                check(callbackThread.get().equals("Mysticism-EmbedDelivery"), "Foreign callback occupies composition worker");
                var sibling = DescriptorVectors.embed(parts(2), ignored -> CompletableFuture.completedFuture(axis()));
                check(get(sibling).length() == 1, "Blocked user callback starved independent composition");
            } finally { callbackRelease.countDown(); get(callback); }
        } finally { release.countDown(); }
        failed(DescriptorVectors.embed(List.of(), ignored -> CompletableFuture.completedFuture(axis())), IllegalArgumentException.class);
        failed(DescriptorVectors.embed(parts(1), ignored -> { throw new IllegalStateException("Fixture inference failure"); }), IllegalStateException.class);
        failed(AsyncEmbeddingEngine.offThread(() -> CompletableFuture.completedFuture(axis()),
                ignored -> { throw new RejectedExecutionException("Fixture executor full"); }), RejectedExecutionException.class);
        check(get(DescriptorVectors.embed(parts(1), ignored -> CompletableFuture.completedFuture(axis()))).length() == 1,
                "Failure/rejection leaked admission");
    }
    private static void boundedAndCancellation() throws Exception {
        int capacity = DescriptorVectors.COMPOSITION_CAPACITY;
        CountDownLatch entered = new CountDownLatch(capacity);
        CompletableFuture<Vec384f> inference = new CompletableFuture<>();
        List<CompletableFuture<Vec384f>> results = new ArrayList<>();
        try {
            for (int i = 0; i < capacity; i++) results.add(DescriptorVectors.embed(parts(1), ignored -> {
                entered.countDown(); return inference;
            }));
            check(entered.await(2, TimeUnit.SECONDS), "Accepted compositions did not start");
            failed(DescriptorVectors.embed(parts(1), ignored -> CompletableFuture.completedFuture(axis())), RejectedExecutionException.class);
            results.getFirst().cancel(false);
            failed(DescriptorVectors.embed(parts(1), ignored -> CompletableFuture.completedFuture(axis())), RejectedExecutionException.class);
            check(!inference.isCancelled(), "Subscription cancelled shared descriptor inference");
        } finally { inference.complete(axis()); }
        for (int i = 1; i < results.size(); i++) check(get(results.get(i)).length() == 1, "Sibling cancelled");
        for (int i = 0; i < 50; i++) check(get(DescriptorVectors.embed(parts(1), ignored -> CompletableFuture.completedFuture(axis()))).length() == 1,
                "Composition slots not recovered");
    }
    private static void cancelRemaining() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Vec384f> shared = new CompletableFuture<>();
        var result = DescriptorVectors.embed(parts(128), ignored -> {
            calls.incrementAndGet(); entered.countDown(); return shared;
        });
        try {
            check(entered.await(2, TimeUnit.SECONDS), "Cancellable composition never entered");
            check(result.cancel(false), "Cannot cancel private subscription");
            check(!shared.isCancelled(), "Cancellation reached shared model request");
        } finally { shared.complete(axis()); }
        Field field = DescriptorVectors.class.getDeclaredField("COMPOSITION_SLOTS"); field.setAccessible(true);
        Semaphore slots = (Semaphore) field.get(null);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (slots.availablePermits() != DescriptorVectors.COMPOSITION_CAPACITY && System.nanoTime() < until) Thread.sleep(1);
        check(slots.availablePermits() == DescriptorVectors.COMPOSITION_CAPACITY, "Cancelled composition leaked private work");
        check(calls.get() == 1, "Cancelled private composition scheduled remaining descriptors");
    }
    private static final class Provider implements EmbeddingProvider {
        final CountDownLatch started = new CountDownLatch(1), work = new CountDownLatch(1), ready = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        boolean holdReady;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() { if (holdReady) await(ready); }
        public Vec384f getEmbedding(String descriptor) { calls.incrementAndGet(); started.countDown(); await(work); return axis(); }
        public void close() { work.countDown(); ready.countDown(); }
    }
    private static AsyncEmbeddingEngine engine(Provider provider) {
        return new AsyncEmbeddingEngine(provider, 1, 16, 16, Duration.ofSeconds(4));
    }
    private static void helperGenerationAndReadiness() throws Exception {
        Field field = EmbeddingHelper.class.getDeclaredField("engine"); field.setAccessible(true);
        check(field.get(null) == null, "Refusing to overwrite a live helper engine");
        Provider original = new Provider(), replacement = new Provider();
        try (var first = engine(original); var second = engine(replacement)) {
            get(first.readiness()); get(second.readiness()); field.set(null, first);
            var composed = EmbeddingHelper.composeDescriptors(parts(2));
            check(original.started.await(2, TimeUnit.SECONDS), "Original generation never entered");
            field.set(null, second); original.work.countDown(); get(composed);
            check(original.calls.get() == 2 && replacement.calls.get() == 0, "Composition switched provider generations");
        } finally { field.set(null, null); }
        Provider pending = new Provider(); pending.holdReady = true;
        CountDownLatch callbackEntered = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
        try (var e = engine(pending)) {
            field.set(null, e);
            var blocked = EmbeddingHelper.readiness().thenRun(() -> { callbackEntered.countDown(); await(callbackRelease); });
            var independent = EmbeddingHelper.readiness();
            try {
                pending.ready.countDown();
                check(callbackEntered.await(2, TimeUnit.SECONDS), "First helper readiness callback did not enter");
                get(independent); check(!blocked.isDone(), "Fixture callback unexpectedly released");
            } finally { callbackRelease.countDown(); get(blocked); }
        } finally { field.set(null, null); callbackRelease.countDown(); pending.ready.countDown(); }
    }
    /** Merge regression: actual restored engine cache, helper generation and isolated composition
     * must work together. Synthetic native-v2 vectors are fixtures, not model inference evidence. */
    private static void persistedHelperComposition() throws Exception {
        Field field = EmbeddingHelper.class.getDeclaredField("engine"); field.setAccessible(true);
        check(field.get(null) == null, "Refusing to replace a live helper engine");
        Path root = Path.of("build", "embedding-composition-tests"); Files.createDirectories(root);
        Path file = Files.createTempDirectory(root, "run-").resolve("vectors.bin");
        Provider original = new Provider(); original.work.countDown();
        try (var first = new AsyncEmbeddingEngine(original, 1, 16, 16, Duration.ofSeconds(4), file)) {
            get(first.readiness()); get(first.embed("restored"));
            check(original.calls.get() == 1, "Initial miss must really call fixture provider");
        }
        check(Files.isRegularFile(file), "Orderly close must persist cache for integration fixture");
        Provider restored = new Provider(); restored.holdReady = true;
        Thread caller = Thread.currentThread();
        CountDownLatch callbackEntered = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        List<DescriptorVectors.WeightedDescriptor> input = new ArrayList<>();
        for (int i = 0; i < 256; i++) input.add(new DescriptorVectors.WeightedDescriptor("restored", i + 1));
        try (var second = new AsyncEmbeddingEngine(restored, 1, 16, 16, Duration.ofSeconds(4), file)) {
            field.set(null, second);
            check(second.cacheSize() == 0 && !EmbeddingHelper.isReady(), "Disk cache cannot advertise readiness before provider health");
            failed(EmbeddingHelper.composeDescriptors(input), IllegalStateException.class);
            restored.ready.countDown(); get(EmbeddingHelper.readiness());
            check(second.cacheSize() == 1, "Cache must activate before helper readiness completion");
            // Cache clone isolation survives both the engine API and asynchronous weighted API.
            get(EmbeddingHelper.getEmbedding("restored")).mul(0);
            CompletableFuture<Vec384f> composed;
            CompletableFuture<Void> blocked;
            // Prevent the first cache lookup from finishing before the callback is attached;
            // late callbacks legitimately execute on the attaching caller in CompletableFuture.
            synchronized (second) {
                composed = EmbeddingHelper.composeDescriptors(input);
                blocked = composed.thenAccept(value -> {
                    callbackThread.set(Thread.currentThread()); callbackEntered.countDown(); await(callbackRelease);
                });
            }
            try {
                check(callbackEntered.await(2, TimeUnit.SECONDS), "Restored composition never published");
                Vec384f value = get(composed);
                check(value.data().length == 768 && value.data()[767] == 1 && value.length() == 1,
                        "Restored composition lost normalized native-v2 tail");
                check(callbackThread.get() != caller && callbackThread.get().getName().equals("Mysticism-EmbedDelivery"),
                        "Restored composition must publish off caller/worker");
                value.mul(0);
                for (int i = 0; i < 20; i++) {
                    Vec384f sibling = get(EmbeddingHelper.composeDescriptors(input));
                    check(sibling.data()[767] == 1, "Blocked callback or caller mutation poisoned restored sibling");
                }
                check(restored.calls.get() == 0, "Current-profile restored hits must not repeat inference");
            } finally { callbackRelease.countDown(); get(blocked); }
        } finally { field.set(null, null); restored.close(); callbackRelease.countDown(); }
        check(EmbeddingCacheFile.read(file, 16).get("restored").data()[767] == 1,
                "Merged composition must leave persisted native tail intact");
    }
    public static void main(String[] args) throws Exception {
        cachedAndCallbacks(); boundedAndCancellation(); cancelRemaining(); helperGenerationAndReadiness(); persistedHelperComposition();
        System.out.println("AsyncEmbeddingCompositionTest passed: " + checks + " checks");
    }
}
