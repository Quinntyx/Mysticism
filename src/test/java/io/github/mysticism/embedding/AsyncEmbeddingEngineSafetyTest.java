package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real production engine; deterministic gates, no models/network/framework. */
public final class AsyncEmbeddingEngineSafetyTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static Vec384f axis() { float[] v = new float[EmbeddingSpace.DIMENSIONS]; v[0] = 3; return new Vec384f(v); }
    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(2, TimeUnit.SECONDS); }
    private static void failed(CompletableFuture<?> f) throws Exception {
        try { get(f); throw new AssertionError("Expected terminal failure"); }
        catch (ExecutionException | CancellationException expected) { checks++; }
    }
    private static class Provider implements EmbeddingProvider {
        final CountDownLatch readyGate = new CountDownLatch(1), start = new CountDownLatch(1), gate = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean holdReady, holdWork = true, closed;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() throws Exception { if (holdReady) readyGate.await(); }
        public Vec384f getEmbedding(String text) throws Exception { calls.incrementAndGet(); start.countDown(); if (holdWork) gate.await(); return axis(); }
        public void close() { closed = true; readyGate.countDown(); gate.countDown(); }
    }
    private static AsyncEmbeddingEngine engine(Provider p, int queue, long millis) {
        return new AsyncEmbeddingEngine(p, 1, queue, 2, Duration.ofMillis(millis));
    }
    private static void callbacks() throws Exception {
        Provider p = new Provider(); p.holdReady = true;
        try (var e = engine(p, 8, 1500)) {
            var ready = e.readiness().thenRun(() -> check(!Thread.holdsLock(e), "Readiness callback owns monitor"));
            p.readyGate.countDown(); get(ready);
            Object stateLock = new Object();
            var callbackStarted = new CountDownLatch(1);
            var f = e.embed("cross-lock"); check(p.start.await(1, TimeUnit.SECONDS), "Inference started");
            var completed = f.thenAccept(v -> {
                check(!Thread.holdsLock(e), "Success callback owns monitor");
                callbackStarted.countDown();
                synchronized (stateLock) { /* matches persistent state/service callback lock */ }
            });
            var actor = new CompletableFuture<Void>();
            Thread t = new Thread(() -> {
                try {
                    synchronized (stateLock) {
                        p.gate.countDown();
                        check(callbackStarted.await(1, TimeUnit.SECONDS), "Callback entered");
                        e.cacheSize(); // old code deadlocked here against callback's stateLock
                    }
                    actor.complete(null);
                } catch (Throwable failure) { actor.completeExceptionally(failure); }
            });
            t.setDaemon(true); t.start(); get(actor); get(completed);
            get(e.embed("cross-lock").thenRun(() -> check(!Thread.holdsLock(e), "Cache callback owns monitor")));
            var cached = get(e.embed("cross-lock")); cached.mul(0);
            check(get(e.embed("cross-lock")).length() == 1, "Cache must clone");
        }
        Provider waiting = new Provider(); waiting.holdReady = true;
        var e = engine(waiting, 8, 1000);
        var callback = e.readiness().handle((v, error) -> { check(!Thread.holdsLock(e), "Shutdown readiness owns monitor"); return null; });
        e.close(); get(callback); check(!e.isReady(), "Closed engine ready");
        Provider work = new Provider();
        var closing = engine(work, 8, 1000); get(closing.readiness());
        var outstanding = closing.embed("shutdown").handle((v, error) -> { check(!Thread.holdsLock(closing), "Shutdown request owns monitor"); return null; });
        closing.close(); get(outstanding); failed(closing.embed("after"));
    }
    private static void bounded() throws Exception {
        Provider p = new Provider();
        try (var e = engine(p, 1, 150)) {
            get(e.readiness()); var a = e.embed("same"); check(p.start.await(1, TimeUnit.SECONDS), "Started");
            var duplicate = e.embed("same"); var detached = e.embed("same"); detached.cancel(false);
            var queued = e.embed("queued"); failed(e.embed("overflow"));
            get(a.handle((v, error) -> { check(!Thread.holdsLock(e), "Timeout owns monitor"); return null; }));
            failed(a); failed(duplicate); failed(queued);
            check(e.inflightSize() == 0 && e.cacheSize() == 0, "Expired requests leaked");
            var next = e.embed("after-queue-expiry"); // queue slot recovered although provider remains blocked
            check(!next.isCompletedExceptionally(), "Expired queued task still occupies queue");
            p.gate.countDown(); get(next);
            check(p.calls.get() == 2, "Expired queued work ran or dedupe failed");
        }
        Provider p2 = new Provider(); p2.holdWork = false;
        try (var e = engine(p2, 4, 1000)) {
            get(e.readiness()); get(e.embed("a")); get(e.embed("b")); get(e.embed("c"));
            check(e.cacheSize() == 2, "LRU bound"); get(e.embed("a")); check(p2.calls.get() == 4, "LRU eviction");
        }
        Provider slowReady = new Provider(); slowReady.holdReady = true;
        try (var e = engine(slowReady, 4, 80)) {
            failed(e.readiness()); slowReady.readyGate.countDown();
            failed(e.embed("must-not-revive")); check(!e.isReady(), "Late readiness revived");
        }
    }
    private static void races() throws Exception {
        for (int i = 0; i < 80; i++) {
            Provider p = new Provider(); p.holdWork = false;
            var e = engine(p, 8, 1000); get(e.readiness());
            var start = new CountDownLatch(1); var futures = new CopyOnWriteArrayList<CompletableFuture<Vec384f>>();
            Thread submit = new Thread(() -> {
                try { start.await(); for (int j = 0; j < 20; j++) futures.add(e.embed("race-" + j)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            });
            submit.start(); start.countDown(); e.close(); submit.join(2000);
            check(!submit.isAlive(), "Submission/shutdown deadlocked");
            for (var f : futures) get(f.handle((v, error) -> null));
            check(e.inflightSize() == 0 && e.cacheSize() == 0 && p.closed, "Shutdown race leaked");
        }
    }
    public static void main(String[] args) throws Exception {
        callbacks(); bounded(); races();
        System.out.println("AsyncEmbeddingEngineSafetyTest passed: " + checks + " checks");
    }
}
