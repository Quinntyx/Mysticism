package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises production lazy factory, ownership and drain gates; no game/model/network. */
public final class EmbeddedLifecycleTest {
    private static int checks;
    private static void check(boolean ok, String text) { checks++; if (!ok) throw new AssertionError(text); }
    private static void await(CountDownLatch gate) throws Exception { check(gate.await(2, TimeUnit.SECONDS), "Gate did not open"); }
    private static void uninterruptibly(CountDownLatch gate) {
        boolean interrupted = false;
        while (true) {
            try { gate.await(); break; } catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void failed(CompletableFuture<?> future) throws Exception {
        try { future.get(2, TimeUnit.SECONDS); throw new AssertionError("Expected failure"); }
        catch (ExecutionException | CancellationException expected) { checks++; }
    }
    private static AsyncEmbeddingEngine current() throws Exception {
        Field field = EmbeddingHelper.class.getDeclaredField("engine"); field.setAccessible(true);
        return (AsyncEmbeddingEngine) field.get(null);
    }
    private static class Provider implements EmbeddingProvider {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger(), closes = new AtomicInteger(), probes = new AtomicInteger();
        volatile String closingThread;
        volatile boolean holdReady, holdWork, unsafeClose, lockedClose;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() {
            probes.incrementAndGet(); active.incrementAndGet();
            try { if (holdReady) { entered.countDown(); uninterruptibly(release); } }
            finally { active.decrementAndGet(); }
        }
        public Vec384f getEmbedding(String text) {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                entered.countDown(); if (holdWork) uninterruptibly(release);
                float[] v = new float[EmbeddingSpace.DIMENSIONS]; v[700] = 1;
                return new Vec384f(v);
            } finally { active.decrementAndGet(); }
        }
        public void close() {
            closingThread = Thread.currentThread().getName();
            unsafeClose = active.get() != 0;
            lockedClose = Thread.holdsLock(EmbeddingHelper.class);
            closes.incrementAndGet();
        }
    }
    private static void start(Object owner, java.util.function.Supplier<EmbeddingProvider> factory, long deadline) {
        EmbeddingHelper.initializeServer(owner, 2, factory, Duration.ofMillis(deadline));
    }
    private static void stoppedDuringFactory() throws Exception {
        Object old = new Object(), next = new Object(); Provider p = new Provider(), q = new Provider();
        CountDownLatch constructing = new CountDownLatch(1), release = new CountDownLatch(1), opened = new CountDownLatch(1);
        AtomicBoolean workerFactory = new AtomicBoolean();
        try {
            start(old, () -> {
                workerFactory.set(Thread.currentThread().getName().equals("Mysticism-EmbedWorker") && !Thread.holdsLock(EmbeddingHelper.class));
                constructing.countDown(); uninterruptibly(release);
                Thread.interrupted(); // Emulate native code swallowing Java cancellation.
                return p;
            }, 1500);
            AsyncEmbeddingEngine oldEngine = current(); await(constructing);
            var oldReady = EmbeddingHelper.readiness();
            long before = System.nanoTime(); EmbeddingHelper.shutdown(old);
            check(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(500), "Server exit blocked on constructor");
            failed(oldReady); failed(oldEngine.readiness());
            check(!oldEngine.awaitClosed(Duration.ofMillis(50)), "Closed a still-constructing model");
            start(next, () -> { opened.countDown(); return q; }, 1500);
            var nextReady = EmbeddingHelper.readiness(); AsyncEmbeddingEngine nextEngine = current();
            check(!opened.await(80, TimeUnit.MILLISECONDS), "New launch overlapped old constructor");
            EmbeddingHelper.shutdown(old); check(current() == nextEngine, "Old stop removed new owner");
            release.countDown(); nextReady.get(2, TimeUnit.SECONDS);
            check(oldEngine.awaitClosed(Duration.ofSeconds(2)), "Old constructor did not drain");
            check(p.closes.get() == 1 && p.probes.get() == 0 && !p.unsafeClose && !p.lockedClose && workerFactory.get(), "Unsafe constructor lifecycle");
            check(EmbeddingHelper.isReady(), "Old readiness overwrote replacement");
            EmbeddingHelper.shutdown(next); check(nextEngine.awaitClosed(Duration.ofSeconds(2)), "New owner did not close");
        } finally { release.countDown(); EmbeddingHelper.shutdown(); }
    }
    private static void inferenceDrain() throws Exception {
        Object owner = new Object(); Provider p = new Provider(); p.holdWork = true;
        start(owner, () -> p, 1500); var e = current();
        try {
            EmbeddingHelper.readiness().get(2, TimeUnit.SECONDS);
            var pending = EmbeddingHelper.getEmbedding("native tail"); await(p.entered);
            EmbeddingHelper.shutdown(owner); failed(pending); failed(e.readiness());
            check(!e.awaitClosed(Duration.ofMillis(50)) && p.closes.get() == 0, "Resources freed under native inference");
            p.release.countDown(); check(e.awaitClosed(Duration.ofSeconds(2)), "Native inference did not drain");
            check(!p.unsafeClose && p.closes.get() == 1 && e.cacheSize() == 0, "Late inference published/leaked");
            e.close(); check(p.closes.get() == 1, "Double close");
        } finally { p.release.countDown(); EmbeddingHelper.shutdown(); }
    }
    private static void readinessExpiry() throws Exception {
        Object owner = new Object(); Provider p = new Provider(); p.holdReady = true;
        start(owner, () -> p, 100); var e = current();
        try {
            await(p.entered); failed(EmbeddingHelper.readiness());
            check(!e.awaitClosed(Duration.ofMillis(50)), "Readiness timeout freed active model");
            p.release.countDown(); check(e.awaitClosed(Duration.ofSeconds(2)), "Failed readiness leaked resources");
            check(!e.isReady() && p.closes.get() == 1 && !p.unsafeClose, "Expired readiness revived");
        } finally { p.release.countDown(); EmbeddingHelper.shutdown(); }
    }
    private static void failuresAndRelaunches() throws Exception {
        Object owner = new Object();
        start(owner, () -> { throw new IllegalStateException("factory failure"); }, 1000);
        var broken = current(); failed(EmbeddingHelper.readiness());
        check(broken.awaitClosed(Duration.ofSeconds(2)), "Failed factory retained process lease"); EmbeddingHelper.shutdown(owner);
        Provider mismatch = new Provider() {
            public EmbeddingProfile profile() { return new EmbeddingProfile("other", "other", 768, "other", "other"); }
        };
        start(owner, () -> mismatch, 1000); broken = current(); failed(EmbeddingHelper.readiness());
        check(broken.awaitClosed(Duration.ofSeconds(2)) && mismatch.closes.get() == 1, "Profile failure leaked model"); EmbeddingHelper.shutdown(owner);
        AtomicInteger opens = new AtomicInteger();
        for (int i = 0; i < 30; i++) {
            Object launch = new Object(); Provider p = new Provider();
            start(launch, () -> { opens.incrementAndGet(); return p; }, 1500); var e = current();
            EmbeddingHelper.readiness().get(2, TimeUnit.SECONDS);
            start(launch, () -> { throw new AssertionError("Duplicate startup opened a model"); }, 1500);
            var copy = EmbeddingHelper.readiness(); copy.cancel(false); check(EmbeddingHelper.isReady(), "Caller cancelled shared readiness");
            check(EmbeddingHelper.getEmbedding("fresh native v2").get(2, TimeUnit.SECONDS).data()[700] == 1, "Native tail lost");
            EmbeddingHelper.shutdown(launch); check(e.awaitClosed(Duration.ofSeconds(2)), "Repeated launch leaked");
            check(p.closes.get() == 1 && !p.unsafeClose && !p.lockedClose && p.closingThread.equals("Mysticism-EmbedCleanup"), "Repeated launch unsafe close");
        }
        check(opens.get() == 30, "Duplicate model construction");
    }
    private static void replacementWithoutStop() throws Exception {
        Object oldOwner = new Object(), nextOwner = new Object(); Provider p = new Provider(), q = new Provider();
        p.holdWork = true; start(oldOwner, () -> p, 1500); var old = current();
        try {
            EmbeddingHelper.readiness().get(2, TimeUnit.SECONDS);
            var request = EmbeddingHelper.getEmbedding("old world"); await(p.entered);
            AtomicBoolean safeOpen = new AtomicBoolean();
            long before = System.nanoTime();
            start(nextOwner, () -> { safeOpen.set(p.active.get() == 0 && p.closes.get() == 1); return q; }, 1500);
            check(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(500), "Replacement startup blocked on old work");
            var next = current(); var nextReady = EmbeddingHelper.readiness(); failed(request);
            check(!nextReady.isDone(), "Replacement did not wait for old work");
            EmbeddingHelper.shutdown(oldOwner); check(current() == next, "Late stop discarded replacement");
            p.release.countDown(); nextReady.get(2, TimeUnit.SECONDS);
            check(old.awaitClosed(Duration.ofSeconds(2)) && safeOpen.get(), "Replacement overlapped old runtime");
            check(EmbeddingHelper.getInitializationStatus().equals("Ready"), "Old shutdown changed current status");
            EmbeddingHelper.shutdown(nextOwner); check(next.awaitClosed(Duration.ofSeconds(2)), "Replacement did not clean up");
        } finally { p.release.countDown(); EmbeddingHelper.shutdown(); }
    }
    private static void serializedPredictor() throws Exception {
        Provider p = new Provider(); p.holdWork = true;
        var provider = new LifecycleEmbeddingProvider(() -> p);
        var e = new AsyncEmbeddingEngine(provider, 2, 8, 2, Duration.ofSeconds(2));
        try {
            e.readiness().get(2, TimeUnit.SECONDS); var a = e.embed("one"); await(p.entered); var b = e.embed("two");
            Field calls = LifecycleEmbeddingProvider.class.getDeclaredField("calls"); calls.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) calls.get(provider);
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!lock.hasQueuedThreads() && System.nanoTime() < limit) Thread.sleep(1);
            check(lock.hasQueuedThreads() && p.active.get() == 1, "Second inference did not wait for predictor");
            p.release.countDown(); a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS);
            check(p.maxActive.get() == 1, "Predictor used concurrently");
        } finally { p.release.countDown(); e.close(); check(e.awaitClosed(Duration.ofSeconds(2)), "Predictor cleanup"); }
    }
    private static void blockedCloseAndCancelledLaunches() throws Exception {
        Object owner = new Object(); CountDownLatch closing = new CountDownLatch(1), closeRelease = new CountDownLatch(1);
        AtomicInteger prematureOpens = new AtomicInteger();
        Provider p = new Provider() {
            public void close() { super.close(); closing.countDown(); uninterruptibly(closeRelease); }
        };
        start(owner, () -> p, 1500); var old = current();
        try {
            EmbeddingHelper.readiness().get(2, TimeUnit.SECONDS);
            long before = System.nanoTime(); EmbeddingHelper.shutdown(owner);
            check(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(500), "Server exit blocked on native close");
            await(closing); check(!old.awaitClosed(Duration.ofMillis(50)), "Native close reported complete early");
            for (int i = 0; i < 12; i++) {
                Object cancelled = new Object(); start(cancelled, () -> { prematureOpens.incrementAndGet(); return new Provider(); }, 1500);
                var empty = current(); EmbeddingHelper.shutdown(cancelled);
                check(empty.awaitClosed(Duration.ofSeconds(1)), "Empty launch accumulated behind blocked native close");
            }
            Object next = new Object(); Provider q = new Provider(); start(next, () -> q, 1500);
            var nextReady = EmbeddingHelper.readiness(); var nextEngine = current();
            check(!nextReady.isDone() && prematureOpens.get() == 0, "Launch opened before native close completed");
            closeRelease.countDown(); check(old.awaitClosed(Duration.ofSeconds(2)), "Native close leaked");
            nextReady.get(2, TimeUnit.SECONDS);
            EmbeddingHelper.shutdown(next); check(nextEngine.awaitClosed(Duration.ofSeconds(2)), "Replacement cleanup");
        } finally { closeRelease.countDown(); EmbeddingHelper.shutdown(); }
    }
    private static void helperCallbackIsolation() throws Exception {
        Object owner = new Object(); Provider p = new Provider(); p.holdReady = true;
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        start(owner, () -> p, 1500); var e = current();
        try {
            await(p.entered);
            var listener = EmbeddingHelper.readiness().thenRun(() -> { blocked.countDown(); uninterruptibly(release); });
            var independent = EmbeddingHelper.readiness(); p.release.countDown(); await(blocked);
            independent.get(2, TimeUnit.SECONDS);
            check(EmbeddingHelper.getInitializationStatus().equals("Ready"), "Foreign listener blocked lifecycle status");
            release.countDown(); listener.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); p.release.countDown(); EmbeddingHelper.shutdown(owner); check(e.awaitClosed(Duration.ofSeconds(2)), "Listener session cleanup"); }
    }
    private static void closeFailureVisible() throws Exception {
        Provider p = new Provider() { public void close() { throw new IllegalStateException("native close failed"); } };
        Semaphore lease = new Semaphore(1, true);
        var e = new AsyncEmbeddingEngine(new LifecycleEmbeddingProvider(() -> p, lease), 1, 8, 2, Duration.ofSeconds(1));
        e.readiness().get(2, TimeUnit.SECONDS); e.close();
        try { e.awaitClosed(Duration.ofSeconds(2)); throw new AssertionError("Close failure swallowed"); }
        catch (IllegalStateException expected) { check(expected.getCause() != null, "Close failure lost cause"); }
        AtomicInteger reopened = new AtomicInteger();
        var blocked = new AsyncEmbeddingEngine(new LifecycleEmbeddingProvider(() -> {
            reopened.incrementAndGet(); return new Provider();
        }, lease), 1, 8, 2, Duration.ofMillis(100));
        try {
            failed(blocked.readiness()); check(blocked.awaitClosed(Duration.ofSeconds(2)), "Failed reopen leaked");
            check(reopened.get() == 0 && lease.availablePermits() == 0, "Reopened native runtime after failed teardown");
        } finally { blocked.close(); }
    }
    public static void main(String[] args) throws Exception {
        stoppedDuringFactory(); inferenceDrain(); readinessExpiry(); failuresAndRelaunches(); replacementWithoutStop(); serializedPredictor();
        blockedCloseAndCancelledLaunches(); helperCallbackIsolation(); closeFailureVisible();
        System.out.println("EmbeddedLifecycleTest passed: " + checks + " checks");
    }
}
