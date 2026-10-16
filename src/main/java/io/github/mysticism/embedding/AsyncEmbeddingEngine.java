package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-EmbeddingCache");

    /** Reserve completion delivery BEFORE starting composite work. Even a fully cached operation
     * runs on the supplied worker; publication uses the reserved delivery pool, not that worker.
     * Standard CompletableFuture semantics (late attachment, cancellation, waiter assistance)
     * still apply; critical-thread callback consumers must choose an explicit async executor.
     * Cancellation does not cancel shared inference. The private composite pipeline can stop
     * scheduling further work when the supplied cancellation token becomes true. */
    static <T> CompletableFuture<T> offThread(Supplier<CompletableFuture<T>> operation, Executor executor) {
        return offThread(ignored -> operation.get(), executor);
    }
    static <T> CompletableFuture<T> offThread(Function<BooleanSupplier, CompletableFuture<T>> operation, Executor executor) {
        Subscriber<T> subscriber = admit();
        if (subscriber == null) return overloaded();
        try {
            executor.execute(() -> {
                try {
                    Objects.requireNonNull(operation.apply(subscriber.future::isCancelled))
                            .whenComplete((value, error) -> publish(subscriber, value, error));
                } catch (Throwable error) { publish(subscriber, null, error); }
            });
        } catch (RejectedExecutionException error) { publish(subscriber, null, error); }
        return subscriber.future;
    }

    private final EmbeddingProvider provider;
    private final Path cacheFile;
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
    private final java.util.concurrent.atomic.AtomicBoolean providerClosed = new java.util.concurrent.atomic.AtomicBoolean();
    private static final class Request {
        final List<Subscriber<Vec384f>> subscribers = new ArrayList<>();
        Runnable task;
        ScheduledFuture<?> deadline;
    }

    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline) {
        this(provider, threads, queueCapacity, cacheCapacity, deadline, deadline, null);
    }
    /** Optional disposable current-profile cache; provider health is still mandatory. */
    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline, Path cacheFile) {
        this(provider, threads, queueCapacity, cacheCapacity, deadline, deadline, cacheFile);
    }
    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline, Duration preparationDeadline) {
        this(provider, threads, queueCapacity, cacheCapacity, deadline, preparationDeadline, null);
    }
    /** Verified local assets and JNI preparation have a separate bounded worker-only budget.
     * The short queue/inference deadline must not abort a healthy first model load. */
    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline, Duration preparationDeadline, Path cacheFile) {
        if (preparationDeadline.toMillis() < 1 || preparationDeadline.toMillis() > 900000)
            throw new IllegalArgumentException("Invalid embedding preparation deadline");
        if (threads < 1 || threads > 32 || queueCapacity < 1 || queueCapacity > 4096
                || cacheCapacity < 1 || cacheCapacity > 65536 || deadline.isZero() || deadline.isNegative()
                || deadline.toMillis() < 1 || deadline.toMillis() > 120000)
            throw new IllegalArgumentException("Invalid embedding bounds");
        if (!provider.profile().equals(EmbeddingProfile.current())) throw new IllegalArgumentException("Provider profile mismatch");
        this.provider = provider; this.deadlineMillis = deadline.toMillis(); this.cacheCapacity = cacheCapacity;
        this.cacheFile = cacheFile == null ? null : cacheFile.toAbsolutePath();
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> daemon(r, "Mysticism-EmbedWorker"),
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "Mysticism-EmbedDeadline"));
        timer.setRemoveOnCancelPolicy(true);
        readyDeadline = timer.schedule(() -> finishReadiness(new TimeoutException("Embedding readiness deadline")), preparationDeadline.toMillis(), TimeUnit.MILLISECONDS);
        workers.execute(() -> {
            try { provider.checkReady(); finishReadiness(null, loadCache()); }
            catch (Throwable error) { finishReadiness(error); }
        });
    }
    private Map<String, Vec384f> loadCache() {
        if (cacheFile == null) return Map.of();
        try { return EmbeddingCacheFile.read(cacheFile, cacheCapacity); }
        catch (IOException | RuntimeException error) {
            LOGGER.warn("Discarding unavailable/stale embedding cache {}; fresh inference required", cacheFile, error);
            return Map.of();
        }
    }
    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name); thread.setDaemon(true); return thread;
    }
    private void finishReadiness(Throwable error) { finishReadiness(error, Map.of()); }
    private void finishReadiness(Throwable error, Map<String, Vec384f> restored) {
        List<Subscriber<Void>> subscribers;
        synchronized (this) {
            if (readinessTerminal) return;
            readinessTerminal = true; readinessError = error; ready = error == null && !closed;
            // Cache activation and readiness have one terminal claim: late IO never populates a
            // failed/stopped engine, even when the timer races immediately after a successful read.
            if (ready) cache.putAll(restored);
            if (readyDeadline != null) readyDeadline.cancel(false);
            subscribers = List.copyOf(readySubscribers); readySubscribers.clear();
        }
        for (var subscriber : subscribers) publish(subscriber, null, error);
        if (error != null) {
            workers.shutdownNow(); timer.shutdown();
            // Cancel failed preparation without running cleanup on the deadline/delivery thread.
            CompletableFuture.runAsync(this::closeProvider);
        }
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
    public synchronized EmbeddingReadiness state() {
        if (closed) return new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED, readinessError);
        if (!readinessTerminal) return new EmbeddingReadiness(EmbeddingReadiness.Phase.INITIALIZING, null);
        return ready ? new EmbeddingReadiness(EmbeddingReadiness.Phase.READY, null)
                : new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED, readinessError);
    }

    public CompletableFuture<Vec384f> embed(String descriptor) {
        if (!EmbeddingCacheFile.validDescriptor(descriptor))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid descriptor"));
        // Engine profile is immutable/current; disk headers validate that same complete profile.
        String key = descriptor;
        Subscriber<Vec384f> subscriber = null; Vec384f cached = null; Throwable rejection = null;
        synchronized (this) {
            if (!ready || closed) rejection = new IllegalStateException("Embeddings unavailable; inspect readiness", state().failure());
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
    private synchronized boolean current(String key, Request request) {
        return ready && !closed && pending.get(key) == request;
    }
    private void run(String key, String descriptor, Request request) {
        synchronized (this) { if (closed || pending.get(key) != request) return; }
        try {
            Vec384f value = provider.getEmbedding(descriptor, () -> current(key, request));
            Vec384f normalized;
            try {
                EmbeddingSpace.requireCurrent(value);
                normalized = NativeEmbeddingOutput.normalize(value.data());
            } catch (IllegalArgumentException invalid) {
                throw new EmbeddingModelException("Provider returned an invalid current-model vector", invalid);
            }
            finish(key, request, normalized, null);
        } catch (Throwable error) { finish(key, request, null, EmbeddingReadiness.unwrap(error)); }
    }
    private void finish(String key, Request request, Vec384f value, Throwable error) {
        List<Request> invalidated = List.of();
        boolean deliver, modelFailed = false;
        synchronized (this) {
            deliver = pending.get(key) == request;
            if (deliver) {
                pending.remove(key);
                if (request.deadline != null) request.deadline.cancel(false);
                workers.remove(request.task);
            }
            // A late definitive model error still revokes readiness after its request
            // deadline. Late successes cannot repopulate caches or redeliver subscribers.
            if (!closed && ready && error instanceof EmbeddingModelException) {
                modelFailed = true;
                // A successful startup probe cannot authorize a missing/changed model forever.
                // Fail the generation atomically; no cached result or queued old-model success
                // can make a fresh discovery look ready. Current persisted anchors are separate.
                ready = false; readinessError = error; cache.clear();
                invalidated = List.copyOf(pending.values()); pending.clear();
                for (var work : invalidated) {
                    if (work.deadline != null) work.deadline.cancel(false);
                    workers.remove(work.task);
                }
            } else if (deliver && error == null) {
                cache.put(key, value.clone());
                while (cache.size() > cacheCapacity) cache.remove(cache.keySet().iterator().next());
            }
        }
        if (deliver) for (var subscriber : request.subscribers) publish(subscriber, error == null ? value.clone() : null, error);
        for (var work : invalidated) for (var subscriber : work.subscribers) publish(subscriber, null, error);
        if (modelFailed) {
            workers.shutdownNow(); timer.shutdown();
            // Revoke the native generation, not merely its status. As with failed startup,
            // cleanup cannot wait on JNI or run on a Minecraft/deadline delivery thread.
            CompletableFuture.runAsync(this::closeProvider);
        }
    }
    public synchronized int cacheSize() { return cache.size(); }
    public synchronized int inflightSize() { return pending.size(); }
    public void close() {
        List<Request> cancelled; List<Subscriber<Void>> readyCancelled;
        Map<String, Vec384f> saved = null;
        CancellationException error = new CancellationException("Embedding shutdown");
        synchronized (this) {
            if (closed) return;
            if (ready && cacheFile != null) {
                saved = new LinkedHashMap<>();
                for (var entry : cache.entrySet()) saved.put(entry.getKey(), entry.getValue().clone());
            }
            closed = true; ready = false;
            readyCancelled = List.copyOf(readySubscribers); readySubscribers.clear();
            readinessTerminal = true; readinessError = error;
            if (readyDeadline != null) readyDeadline.cancel(false);
            cancelled = List.copyOf(pending.values()); pending.clear(); cache.clear();
            for (Request request : cancelled) if (request.deadline != null) request.deadline.cancel(false);
        }
        workers.shutdownNow(); timer.shutdownNow();
        for (var subscriber : readyCancelled) publish(subscriber, null, error);
        for (Request request : cancelled) for (var subscriber : request.subscribers) publish(subscriber, null, error);
        try { closeProvider(); }
        finally {
            // Outside engine/helper monitors. Failure to save a derived cache never replaces model
            // failure or blocks callbacks; the next startup simply computes fresh vectors.
            if (saved != null) try { EmbeddingCacheFile.write(cacheFile, saved); }
            catch (IOException | RuntimeException saving) { LOGGER.warn("Unable to save embedding cache {}", cacheFile, saving); }
        }
    }
    private void closeProvider() {
        if (providerClosed.compareAndSet(false, true)) provider.close();
    }
}
