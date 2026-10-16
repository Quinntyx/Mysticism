package io.github.mysticism.embedding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Production embedded -> lazy lifecycle -> session -> engine integration. Fixtures only;
 * the separate opt-in EmbeddedInferenceProbe exercises the exact approved real GGUF. */
public final class EmbeddedLifecycleIntegrationTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static void check(boolean value, String message) { checks.incrementAndGet(); if (!value) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(2, TimeUnit.SECONDS), "Gate did not open"); }
    private static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2);
        check(condition.getAsBoolean(), "State transition timed out");
    }
    private static class Asset implements EmbeddedEmbeddingService.Asset {
        final AtomicInteger closes = new AtomicInteger();
        public Path resolve() { return Path.of("fixture-NOT-a-model"); }
        public void close() { closes.incrementAndGet(); }
    }
    private static class Model implements EmbeddedEmbeddingService.Model {
        final AtomicInteger closes = new AtomicInteger(), inferences = new AtomicInteger();
        public int dimensions() { return 768; }
        public int tokens(String input) { return 5; }
        public float[] embed(String input) { inferences.incrementAndGet(); float[] v = new float[768]; v[767] = 1; return v; }
        public void close() { closes.incrementAndGet(); }
    }
    private static AsyncEmbeddingEngine engine(EmbeddedEmbeddingService provider, Semaphore lease) {
        return new AsyncEmbeddingEngine(new LifecycleEmbeddingProvider(() -> provider, lease), 4, 16, 16,
                Duration.ofSeconds(2));
    }
    private static void leaseCoversActualNativeClose() throws Exception {
        var gate = new Semaphore(1, true); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var closing = new AtomicBoolean(); var opens = new AtomicInteger();
        var asset = new Asset();
        var first = new Model() {
            public void close() {
                check(Thread.currentThread().getName().equals("Mysticism-EmbedCleanup"), "JNI close ran on lifecycle/worker thread");
                closing.set(true); entered.countDown(); uninterruptibly(release);
                super.close(); closing.set(false);
            }
        };
        var second = new Model();
        var old = engine(new EmbeddedEmbeddingService(asset, (path, cancelled) -> first), gate);
        AsyncEmbeddingEngine next = null;
        try {
            old.readiness().get(2, TimeUnit.SECONDS);
            long start = System.nanoTime(); old.close();
            check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250), "World exit waited for JNI teardown");
            await(entered);
            next = engine(new EmbeddedEmbeddingService(new Asset(), (path, cancelled) -> {
                check(!closing.get(), "Replacement opened before actual native close completed"); opens.incrementAndGet(); return second;
            }), gate);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!gate.hasQueuedThreads() && System.nanoTime() < end) Thread.sleep(2);
            check(gate.hasQueuedThreads(), "Replacement did not wait for old native lease");
            check(opens.get() == 0 && !next.readiness().isDone(), "Asynchronous native close released lease early");
            check(!old.awaitClosed(Duration.ofMillis(20)), "Drain latch claims a hung JNI close finished");
            release.countDown(); check(old.awaitClosed(Duration.ofSeconds(2)), "Native close did not complete");
            next.readiness().get(2, TimeUnit.SECONDS);
            check(opens.get() == 1 && first.closes.get() == 1 && asset.closes.get() == 1, "Lease handoff leaked or repeated teardown");
            check(next.embed("replacement").get(2, TimeUnit.SECONDS).data()[767] == 1, "Replacement inference unusable");
        } finally {
            release.countDown(); old.close(); if (next != null) next.close();
        }
        check(old.awaitClosed(Duration.ofSeconds(2)) && next.awaitClosed(Duration.ofSeconds(2)), "Repeated close did not settle");
        check(gate.availablePermits() == 1 && second.closes.get() == 1, "Successful native lifecycle retained/leaked lease");
    }
    private static void cancelledNativePreparation() throws Exception {
        var gate = new Semaphore(1, true); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cancelled = new AtomicReference<BooleanSupplier>(); var model = new Model(); var asset = new Asset();
        var slow = new EmbeddedEmbeddingService(asset, (path, signal) -> {
            cancelled.set(signal); entered.countDown(); uninterruptibly(release);
            // Simulate JNI which clears the interrupt flag. Volatile stop must still win.
            Thread.interrupted(); check(signal.getAsBoolean(), "Loader cancellation depended only on interrupt"); return model;
        });
        var e = new AsyncEmbeddingEngine(new LifecycleEmbeddingProvider(() -> slow, gate), 2, 4, 4,
                Duration.ofSeconds(1), Duration.ofMillis(200));
        try {
            await(entered);
            try { e.readiness().get(2, TimeUnit.SECONDS); throw new AssertionError("Expected preparation timeout"); }
            catch (ExecutionException failure) { check(failure.getCause() instanceof TimeoutException, "Wrong preparation failure"); }
            eventually(() -> cancelled.get().getAsBoolean());
            check(e.state().phase() == EmbeddingReadiness.Phase.FAILED, "Timeout disguised as normal world stop");
            check(gate.availablePermits() == 0 && asset.closes.get() == 0, "Active load lost its resource lease");
            release.countDown(); check(e.awaitClosed(Duration.ofSeconds(2)), "Cancelled native preparation leaked");
            check(model.inferences.get() == 0 && model.closes.get() == 1, "Late context was probed/published or leaked");
            check(asset.closes.get() == 1 && gate.availablePermits() == 1, "Cancelled preparation failed to release resources");
        } finally { release.countDown(); e.close(); }
    }
    private static void teardownFailurePoisonsLease(boolean candidateFailure, boolean verificationFailure) throws Exception {
        var gate = new Semaphore(1, true); var failure = new IllegalStateException("fixture native teardown failed");
        var model = new Model() {
            public int dimensions() { return candidateFailure ? 384 : 768; }
            public void close() { super.close(); throw failure; }
        };
        Asset asset = verificationFailure ? new Asset() {
            @Override public EmbeddedEmbeddingService.Model load(EmbeddedEmbeddingService.Loader loader, BooleanSupplier cancelled) throws Exception {
                // Like NomicV2ModelAssets.load: native construction succeeded but the
                // post-load asset verification failed and owns candidate cleanup itself.
                var loaded = loader.load(resolve(), cancelled);
                try { throw new IllegalStateException("fixture post-load verification failed"); }
                finally { loaded.close(); }
            }
        } : new Asset();
        var e = engine(new EmbeddedEmbeddingService(asset, (path, cancelled) -> model), gate);
        try {
            if (candidateFailure || verificationFailure) {
                try { e.readiness().get(2, TimeUnit.SECONDS); throw new AssertionError("Expected failed native preparation"); }
                catch (ExecutionException expected) { check(e.state().phase() == EmbeddingReadiness.Phase.FAILED, "Failed candidate marked ready/stopped"); }
            } else { e.readiness().get(2, TimeUnit.SECONDS); e.close(); }
            try { e.awaitClosed(Duration.ofSeconds(2)); throw new AssertionError("Native shutdown failure hidden"); }
            catch (IllegalStateException expected) { check(expected.getCause() == failure, "Wrong native cleanup cause"); }
            check(gate.availablePermits() == 0 && model.closes.get() == 1, "Failed teardown released lease or retried unsafe native close");
            check(asset.closes.get() == 1, "Native teardown failure skipped asset cleanup");
            var opened = new AtomicBoolean();
            var replacement = new AsyncEmbeddingEngine(new LifecycleEmbeddingProvider(() -> {
                opened.set(true); throw new AssertionError("Poisoned native lease reopened");
            }, gate), 1, 4, 4, Duration.ofSeconds(2));
            try {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!gate.hasQueuedThreads() && System.nanoTime() < end) Thread.sleep(2);
                check(gate.hasQueuedThreads() && !opened.get(), "Replacement bypassed failed native teardown");
            } finally { replacement.close(); }
            check(replacement.awaitClosed(Duration.ofSeconds(2)), "Unopened replacement did not cancel");
        } finally { e.close(); }
    }
    public static void main(String[] args) throws Exception {
        leaseCoversActualNativeClose(); cancelledNativePreparation();
        teardownFailurePoisonsLease(false, false); teardownFailurePoisonsLease(true, false); teardownFailurePoisonsLease(false, true);
        System.out.println("EmbeddedLifecycleIntegrationTest passed: " + checks + " checks (embedded lifecycle fixtures; no real model)");
    }
}
