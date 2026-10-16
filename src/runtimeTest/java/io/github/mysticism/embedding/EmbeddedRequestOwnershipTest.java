package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Serialized JNI lifetime fixtures: no real weights, native allocation or service. */
public final class EmbeddedRequestOwnershipTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void fails(CompletableFuture<?> future, Class<? extends Throwable> type) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("Unexpected success"); }
        catch (ExecutionException expected) { check(type.isInstance(expected.getCause()), "Wrong failure: " + expected); }
    }
    private static final class Model implements EmbeddedEmbeddingService.Model {
        final AtomicInteger expiredCalls = new AtomicInteger(), recoveryCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        volatile boolean fatal;
        public int dimensions() { return 768; }
        public int tokens(String text) { return 16; }
        public float[] embed(String text) {
            if (text.endsWith("blocking")) {
                entered.countDown(); await(release);
                if (fatal) throw new IllegalStateException("fixture lost native context");
            }
            if (text.endsWith("expired")) expiredCalls.incrementAndGet();
            if (text.endsWith("recovery")) recoveryCalls.incrementAndGet();
            float[] values = new float[768]; values[0] = 3; values[767] = 4; return values;
        }
        public void close() { closed.countDown(); }
    }
    private static EmbeddedEmbeddingService nativeProvider(Model model) {
        return new EmbeddedEmbeddingService(new EmbeddedEmbeddingService.Asset() {
            public Path resolve() { return Path.of("fixture-not-a-model.gguf"); }
            public void close() {}
        }, (path, cancelled) -> model);
    }
    private static void expiredWaiter() throws Exception {
        Model model = new Model(); var nativeProvider = nativeProvider(model);
        CountDownLatch dispatched = new CountDownLatch(1);
        EmbeddingProvider observed = new EmbeddingProvider() {
            public EmbeddingProfile profile() { return nativeProvider.profile(); }
            public void checkReady() throws Exception { nativeProvider.checkReady(); }
            public Vec384f getEmbedding(String text) throws Exception { return nativeProvider.getEmbedding(text); }
            public Vec384f getEmbedding(String text, BooleanSupplier current) throws Exception {
                if (text.equals("expired")) dispatched.countDown();
                return nativeProvider.getEmbedding(text, current);
            }
            public void close() { nativeProvider.close(); }
        };
        try (var engine = new AsyncEmbeddingEngine(observed, 2, 8, 8, Duration.ofMillis(300), Duration.ofSeconds(3))) {
            engine.readiness().get(3, TimeUnit.SECONDS);
            var first = engine.embed("blocking");
            check(model.entered.await(2, TimeUnit.SECONDS), "Blocking native call never entered");
            var second = engine.embed("expired");
            check(dispatched.await(2, TimeUnit.SECONDS), "Second request never reached serialized provider");
            fails(first, TimeoutException.class); fails(second, TimeoutException.class);
            check(engine.isReady() && engine.inflightSize() == 0, "Request deadlines must not revoke healthy model");
            model.release.countDown();
            Vec384f recovered = engine.embed("recovery").get(3, TimeUnit.SECONDS);
            check(recovered.data()[767] == .8f && model.recoveryCalls.get() == 1, "Healthy generation cannot recover after deadline");
            check(model.expiredCalls.get() == 0, "Expired waiter launched unnecessary JNI inference");
            check(engine.cacheSize() == 1, "Late success repopulated expired cache");
        } finally { model.release.countDown(); }
        check(model.closed.await(2, TimeUnit.SECONDS), "Native context leaked after timeout/recovery");
    }
    private static void lateModelFailure() throws Exception {
        Field field = EmbeddingHelper.class.getDeclaredField("engine"); field.setAccessible(true);
        check(field.get(null) == null, "Cannot replace live helper");
        Model model = new Model(); model.fatal = true;
        try (var engine = new AsyncEmbeddingEngine(nativeProvider(model), 2, 8, 8,
                Duration.ofMillis(300), Duration.ofSeconds(3))) {
            field.set(null, engine); EmbeddingHelper.readiness().get(3, TimeUnit.SECONDS);
            EmbeddingHelper.getEmbedding("cached").get(3, TimeUnit.SECONDS);
            check(EmbeddingHelper.isReady() && EmbeddingHelper.getCacheSize() == 1, "Initial native readiness/cache missing");
            var blocked = EmbeddingHelper.getEmbedding("blocking");
            check(model.entered.await(2, TimeUnit.SECONDS), "Fatal native request never entered");
            fails(blocked, TimeoutException.class);
            model.release.countDown();
            check(model.closed.await(3, TimeUnit.SECONDS), "Late definitive native failure failed to revoke context");
            check(!EmbeddingHelper.isReady() && EmbeddingHelper.state().unavailable(), "Completed startup probe hid native failure");
            check(EmbeddingHelper.getInitializationStatus().contains("fixture lost native context"), "Helper lost actionable native failure");
            fails(EmbeddingHelper.readiness(), EmbeddingModelException.class);
            fails(EmbeddingHelper.getEmbedding("cached"), IllegalStateException.class);
            fails(EmbeddingHelper.composeDescriptors(java.util.List.of(new DescriptorVectors.WeightedDescriptor("cached", 1))), IllegalStateException.class);
            check(EmbeddingHelper.getCacheSize() == 0, "Failed generation served old cached readiness");
        } finally { field.set(null, null); model.release.countDown(); }
    }
    public static void main(String[] args) throws Exception {
        expiredWaiter(); lateModelFailure();
        System.out.println("EmbeddedRequestOwnershipTest passed: " + checks + " checks (native lifetime fixtures, not real inference)");
    }
}
