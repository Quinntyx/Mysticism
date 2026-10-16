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

    // Stop admission immediately, but dispose only after ALL workers have returned.
    // The session owns serialization AND snapshots of the provider's borrowed output.
    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(
            r -> daemon(r, "Mysticism-EmbedCleanup"));
    private final CountDownLatch providerClosed = new CountDownLatch(1);
    private volatile Throwable closeFailure;
    private final EmbeddingProviderSession provider;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final long deadlineMillis;
    private final int cacheCapacity;
    private final Map<String, Vec384f> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Request> pending = new HashMap<>();
    private final List<Subscriber<Void>> readySubscribers = new ArrayList<>();
    private ScheduledFuture<?> readyDeadline;
    private Throwable readinessError;
    private boolean closed, stopped, ready, readinessTerminal;
    private static final class Request {
        final List<Subscriber<Vec384f>> subscribers = new ArrayList<>();
        Runnable task;
        ScheduledFuture<?> deadline;
    }

    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline) {
        this(provider, threads, queueCapacity, cacheCapacity, deadline, deadline);
    }
    /** Separate bounded preparation budget for first-run local asset provisioning/loading.
     * Queue + inference deadlines remain short and retain their original semantics. */
    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline, Duration preparationDeadline) {
        if (preparationDeadline.toMillis() < 1 || preparationDeadline.toMillis() > 900000)
            throw new IllegalArgumentException("Invalid embedding preparation deadline");
        if (threads < 1 || threads > 32 || queueCapacity < 1 || queueCapacity > 4096
                || cacheCapacity < 1 || cacheCapacity > 65536 || deadline.isZero() || deadline.isNegative()
                || deadline.toMillis() < 1 || deadline.toMillis() > 120000)
            throw new IllegalArgumentException("Invalid embedding bounds");
        if (!provider.profile().equals(EmbeddingProfile.current())) throw new IllegalArgumentException("Provider profile mismatch");
        this.provider = new EmbeddingProviderSession(provider);
        this.deadlineMillis = deadline.toMillis(); this.cacheCapacity = cacheCapacity;
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> daemon(r, "Mysticism-EmbedWorker"),
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() {
                if (AsyncEmbeddingEngine.this.provider.closeUnopened()) {
                    providerClosed.countDown();
                    return;
                }
                CLEANUP.execute(() -> {
                    try { AsyncEmbeddingEngine.this.provider.close(); }
                    catch (Throwable error) {
                        closeFailure = error;
                        System.getLogger("Mysticism").log(System.Logger.Level.ERROR,
                                "Embedding resource shutdown failed; restart the process before reopening a native model", error);
                    }
                    finally { providerClosed.countDown(); }
                });
            }
        };
        timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "Mysticism-EmbedDeadline"));
        timer.setRemoveOnCancelPolicy(true);
        readyDeadline = timer.schedule(() -> finishReadiness(new TimeoutException("Embedding readiness deadline")), preparationDeadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            workers.execute(() -> {
                try { this.provider.checkReady(); finishReadiness(null); }
                catch (Throwable error) { finishReadiness(error); }
            });
        } catch (RejectedExecutionException error) {
            // A very short readiness deadline may close the engine before submission.
            finishReadiness(error);
        }
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
        // Failed/expired preparation still owns real native resources. Drain before disposal,
        // preserving FAILED (with its cause) rather than disguising it as an explicit stop.
        if (error != null) shutdown(error);
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
        if (stopped) return new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED, new CancellationException("Embedding shutdown"));
        if (!readinessTerminal) return new EmbeddingReadiness(EmbeddingReadiness.Phase.INITIALIZING, null);
        return ready ? new EmbeddingReadiness(EmbeddingReadiness.Phase.READY, null)
                : new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED, readinessError);
    }
    synchronized String initializationStatus() {
        var snapshot = state();
        return switch (snapshot.phase()) {
            case READY -> "Ready";
            case INITIALIZING -> "Initializing";
            case STOPPED -> "Stopped";
            case FAILED -> "Unavailable: " + EmbeddingReadiness.describe(snapshot.failure());
        };
    }

    public CompletableFuture<Vec384f> embed(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.length() > 8192)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid descriptor"));
        String key = EmbeddingSpace.FINGERPRINT + ":" + descriptor;
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
    private synchronized boolean isCurrent(String key, Request request) {
        return !closed && pending.get(key) == request;
    }
    private void run(String key, String descriptor, Request request) {
        if (!isCurrent(key, request)) return;
        try {
            Vec384f value = provider.infer(descriptor, () -> isCurrent(key, request));
            if (value != null) finish(key, request, value, null);
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
        if (modelFailed) shutdown(error);
    }
    public synchronized int cacheSize() { return cache.size(); }
    public synchronized int inflightSize() { return pending.size(); }
    public void close() { shutdown(null); }
    private void shutdown(Throwable failure) {
        List<Request> cancelled; List<Subscriber<Void>> readyCancelled;
        CancellationException error = new CancellationException("Embedding shutdown");
        synchronized (this) {
            if (failure == null) stopped = true;
            if (closed) return;
            closed = true; ready = false;
            readyCancelled = List.copyOf(readySubscribers); readySubscribers.clear();
            readinessTerminal = true; readinessError = failure == null ? error : failure;
            if (readyDeadline != null) readyDeadline.cancel(false);
            cancelled = List.copyOf(pending.values()); pending.clear(); cache.clear();
            for (Request request : cancelled) if (request.deadline != null) request.deadline.cancel(false);
        }
        provider.requestStop();
        workers.shutdownNow(); timer.shutdownNow();
        for (var subscriber : readyCancelled) publish(subscriber, null, error);
        for (Request request : cancelled) for (var subscriber : request.subscribers) publish(subscriber, null, error);
    }
    /** Package-private bounded observation for lifecycle regressions; close itself never waits.
     * False means native work/cleanup still owns the resource, not that it is safe to reopen. */
    boolean awaitClosed(Duration timeout) throws InterruptedException {
        if (!providerClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) return false;
        if (closeFailure != null) throw new IllegalStateException("Embedding resource shutdown failed", closeFailure);
        return true;
    }
}
