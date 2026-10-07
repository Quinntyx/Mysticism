package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded work/deadlines and individually reserved callback delivery. Neither engine
 * monitors nor deadline/inference threads execute subscriber callbacks. Cancellation is detached. */
public final class AsyncEmbeddingEngine implements AutoCloseable {
    // Process-wide bounds survive engines being restarted while foreign callbacks ignore interruption.
    // One permit per accepted subscriber, held THROUGH synchronous callback execution. Thus every
    // admitted subscriber has a worker available even if all other subscribers block forever.
    static final int CALLBACK_CAPACITY = 32;
    private static final Semaphore CALLBACK_SLOTS = new Semaphore(CALLBACK_CAPACITY);
    private static final ThreadPoolExecutor DELIVERIES = deliveries();
    private static ThreadPoolExecutor deliveries() {
        var pool = new ThreadPoolExecutor(CALLBACK_CAPACITY, CALLBACK_CAPACITY, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CALLBACK_CAPACITY), r -> daemon(r, "Mysticism-EmbedDelivery"),
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true); return pool;
    }
    private static final class Subscriber<T> { final CompletableFuture<T> future = new CompletableFuture<>(); }
    private static <T> Subscriber<T> admit() { return CALLBACK_SLOTS.tryAcquire() ? new Subscriber<>() : null; }
    private static <T> void publish(Subscriber<T> subscriber, T value, Throwable error) {
        // Dispatch cannot saturate: queued + active publications <= reserved slots <= workers.
        DELIVERIES.execute(() -> {
            try {
                if (error == null) subscriber.future.complete(value);
                else subscriber.future.completeExceptionally(error);
            } finally { CALLBACK_SLOTS.release(); }
        });
    }
    private static <T> CompletableFuture<T> overloaded() {
        // Already terminal BEFORE exposure: any attached callback runs on its caller, not our scheduler.
        return CompletableFuture.failedFuture(new RejectedExecutionException("Embedding callback capacity exhausted; retry later"));
    }

    private final EmbeddingProvider provider;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final long deadlineMillis;
    private final int cacheCapacity;
    private final Map<String, Vec384f> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Request> pending = new HashMap<>();
    private final List<Subscriber<Void>> readySubscribers = new ArrayList<>();
    private ScheduledFuture<?> readyDeadline;
    private Throwable readinessError;
    private boolean closed, ready, readinessTerminal;
    private static final class Request {
        final List<Subscriber<Vec384f>> subscribers = new ArrayList<>();
        Runnable task;
        ScheduledFuture<?> deadline;
    }

    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline) {
        if (threads < 1 || threads > 32 || queueCapacity < 1 || queueCapacity > 4096
                || cacheCapacity < 1 || cacheCapacity > 65536 || deadline.isZero() || deadline.isNegative()
                || deadline.toMillis() < 1 || deadline.toMillis() > 120000)
            throw new IllegalArgumentException("Invalid embedding bounds");
        if (!provider.profile().equals(EmbeddingProfile.current())) throw new IllegalArgumentException("Provider profile mismatch");
        this.provider = provider; this.deadlineMillis = deadline.toMillis(); this.cacheCapacity = cacheCapacity;
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> daemon(r, "Mysticism-EmbedWorker"),
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "Mysticism-EmbedDeadline"));
        timer.setRemoveOnCancelPolicy(true);
        readyDeadline = timer.schedule(() -> finishReadiness(new TimeoutException("Embedding readiness deadline")), deadlineMillis, TimeUnit.MILLISECONDS);
        workers.execute(() -> {
            try { provider.checkReady(); finishReadiness(null); }
            catch (Throwable error) { finishReadiness(error); }
        });
    }
    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name); thread.setDaemon(true); return thread;
    }
    private void finishReadiness(Throwable error) {
        List<Subscriber<Void>> subscribers;
        synchronized (this) {
            if (readinessTerminal) return;
            readinessTerminal = true; readinessError = error; ready = error == null && !closed;
            if (readyDeadline != null) readyDeadline.cancel(false);
            subscribers = List.copyOf(readySubscribers); readySubscribers.clear();
        }
        for (var subscriber : subscribers) publish(subscriber, null, error);
    }
    public CompletableFuture<Void> readiness() {
        Subscriber<Void> subscriber = null; boolean terminal; Throwable error;
        synchronized (this) {
            terminal = readinessTerminal; error = readinessError;
            if (!terminal) { subscriber = admit(); if (subscriber != null) readySubscribers.add(subscriber); }
        }
        if (terminal) return error == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(error);
        return subscriber == null ? overloaded() : subscriber.future;
    }
    public synchronized boolean isReady() { return ready && !closed; }

    public CompletableFuture<Vec384f> embed(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.length() > 8192)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid descriptor"));
        String key = EmbeddingSpace.FINGERPRINT + ":" + descriptor;
        Subscriber<Vec384f> subscriber = null; Vec384f cached = null; Throwable rejection = null;
        synchronized (this) {
            if (!ready || closed) rejection = new IllegalStateException("Embeddings unavailable; inspect readiness");
            else {
                cached = cache.get(key); if (cached != null) cached = cached.clone();
                if (cached == null) {
                    subscriber = admit();
                    if (subscriber != null) {
                        Request request = pending.get(key);
                        if (request != null) request.subscribers.add(subscriber);
                        else {
                            Request work = new Request(); work.subscribers.add(subscriber);
                            work.task = () -> run(key, descriptor, work); pending.put(key, work);
                            try {
                                work.deadline = timer.schedule(() -> finish(key, work, null,
                                        new TimeoutException("Embedding request deadline")), deadlineMillis, TimeUnit.MILLISECONDS);
                                workers.execute(work.task);
                            } catch (RejectedExecutionException error) {
                                pending.remove(key, work);
                                if (work.deadline != null) work.deadline.cancel(false);
                                rejection = error;
                            }
                        }
                    }
                }
            }
        }
        if (cached != null) return CompletableFuture.completedFuture(cached);
        if (subscriber == null) return rejection == null ? overloaded() : CompletableFuture.failedFuture(rejection);
        if (rejection != null) publish(subscriber, null, rejection);
        return subscriber.future;
    }
    private void run(String key, String descriptor, Request request) {
        synchronized (this) { if (closed || pending.get(key) != request) return; }
        try {
            Vec384f value = provider.getEmbedding(descriptor); EmbeddingSpace.requireCurrent(value);
            if (value.length() == 0) throw new IllegalArgumentException("Provider returned zero embedding");
            finish(key, request, new Vec384f(value.norm()), null);
        } catch (Throwable error) { finish(key, request, null, error); }
    }
    private void finish(String key, Request request, Vec384f value, Throwable error) {
        synchronized (this) {
            if (pending.get(key) != request) return;
            pending.remove(key);
            if (request.deadline != null) request.deadline.cancel(false);
            workers.remove(request.task);
            if (error == null) {
                cache.put(key, value.clone());
                while (cache.size() > cacheCapacity) cache.remove(cache.keySet().iterator().next());
            }
        }
        for (var subscriber : request.subscribers) publish(subscriber, error == null ? value.clone() : null, error);
    }
    public synchronized int cacheSize() { return cache.size(); }
    public synchronized int inflightSize() { return pending.size(); }
    public void close() {
        List<Request> cancelled; List<Subscriber<Void>> readyCancelled;
        CancellationException error = new CancellationException("Embedding shutdown");
        synchronized (this) {
            if (closed) return;
            closed = true; ready = false;
            readyCancelled = List.copyOf(readySubscribers); readySubscribers.clear();
            if (!readinessTerminal) { readinessTerminal = true; readinessError = error; }
            if (readyDeadline != null) readyDeadline.cancel(false);
            cancelled = List.copyOf(pending.values()); pending.clear(); cache.clear();
            for (Request request : cancelled) if (request.deadline != null) request.deadline.cancel(false);
        }
        workers.shutdownNow(); timer.shutdownNow();
        for (var subscriber : readyCancelled) publish(subscriber, null, error);
        for (Request request : cancelled) for (var subscriber : request.subscribers) publish(subscriber, null, error);
        provider.close();
    }
}
