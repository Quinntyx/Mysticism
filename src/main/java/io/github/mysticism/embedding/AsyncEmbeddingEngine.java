package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded requests and total deadlines. State transitions happen under the monitor;
 * arbitrary future callbacks NEVER do. Caller cancellation cannot cancel shared work. */
public final class AsyncEmbeddingEngine implements AutoCloseable {
    private final EmbeddingProvider provider;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final long deadlineMillis;
    private final int cacheCapacity;
    private final Map<String, Vec384f> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Request> pending = new HashMap<>();
    private final CompletableFuture<Void> readiness = new CompletableFuture<>();
    private ScheduledFuture<?> readyDeadline;
    private boolean closed, ready, readinessTerminal;

    private static final class Request {
        final CompletableFuture<Vec384f> future = new CompletableFuture<>();
        Runnable task;
        ScheduledFuture<?> deadline;
    }

    public AsyncEmbeddingEngine(EmbeddingProvider provider, int threads, int queueCapacity,
                                int cacheCapacity, Duration deadline) {
        if (threads < 1 || threads > 32 || queueCapacity < 1 || cacheCapacity < 1
                || deadline.isZero() || deadline.isNegative() || deadline.toMillis() < 1
                || deadline.toMillis() > 120000) throw new IllegalArgumentException("Invalid embedding bounds");
        if (!provider.profile().equals(EmbeddingProfile.current())) throw new IllegalArgumentException("Provider profile mismatch");
        this.provider = provider;
        this.deadlineMillis = deadline.toMillis();
        this.cacheCapacity = cacheCapacity;
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> daemon(r, "Mysticism-EmbedWorker"),
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "Mysticism-EmbedDeadline"));
        timer.setRemoveOnCancelPolicy(true);
        readyDeadline = timer.schedule(() -> finishReadiness(new TimeoutException("Embedding readiness deadline")),
                deadlineMillis, TimeUnit.MILLISECONDS);
        workers.execute(() -> {
            try { provider.checkReady(); finishReadiness(null); }
            catch (Throwable error) { finishReadiness(error); }
        });
    }

    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name); thread.setDaemon(true); return thread;
    }

    private void finishReadiness(Throwable error) {
        synchronized (this) {
            if (readinessTerminal) return;
            readinessTerminal = true;
            ready = error == null && !closed;
            if (readyDeadline != null) readyDeadline.cancel(false);
        }
        if (error == null) readiness.complete(null); else readiness.completeExceptionally(error);
    }

    public CompletableFuture<Void> readiness() { return readiness.copy(); }
    public synchronized boolean isReady() { return ready && !closed; }

    public CompletableFuture<Vec384f> embed(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.length() > 8192)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid descriptor"));
        String key = EmbeddingSpace.FINGERPRINT + ":" + descriptor;
        Request request = null;
        Vec384f cached = null;
        Throwable rejection = null;
        synchronized (this) {
            if (!ready || closed) {
                rejection = new IllegalStateException("Embeddings unavailable; inspect readiness");
            } else {
                cached = cache.get(key);
                if (cached != null) cached = cached.clone();
                request = pending.get(key);
                if (cached == null && request == null) {
                    request = new Request();
                    Request work = request;
                    work.task = () -> run(key, descriptor, work);
                    pending.put(key, work);
                    try {
                        work.deadline = timer.schedule(() -> finish(key, work, null,
                                new TimeoutException("Embedding request deadline")), deadlineMillis, TimeUnit.MILLISECONDS);
                        workers.execute(work.task);
                    } catch (RejectedExecutionException error) {
                        // Claim terminal state atomically; publish the failure only after unlocking.
                        pending.remove(key, work);
                        if (work.deadline != null) work.deadline.cancel(false);
                        rejection = error;
                    }
                }
            }
        }
        if (cached != null) return CompletableFuture.completedFuture(cached);
        if (request == null) return CompletableFuture.failedFuture(rejection);
        if (rejection != null) request.future.completeExceptionally(rejection);
        return request.future.thenApply(Vec384f::clone);
    }

    private void run(String key, String descriptor, Request request) {
        synchronized (this) { if (closed || pending.get(key) != request) return; }
        try {
            Vec384f value = provider.getEmbedding(descriptor);
            EmbeddingSpace.requireCurrent(value);
            if (value.length() == 0) throw new IllegalArgumentException("Provider returned zero embedding");
            finish(key, request, new Vec384f(value.norm()), null);
        } catch (Throwable error) { finish(key, request, null, error); }
    }

    private void finish(String key, Request request, Vec384f value, Throwable error) {
        synchronized (this) {
            if (pending.get(key) != request) return; // Timeout/shutdown already won.
            pending.remove(key);
            if (request.deadline != null) request.deadline.cancel(false);
            workers.remove(request.task); // Expired queued work must not monopolize queue capacity.
            if (error == null) {
                cache.put(key, value.clone());
                while (cache.size() > cacheCapacity) cache.remove(cache.keySet().iterator().next());
            }
        }
        if (error == null) request.future.complete(value); else request.future.completeExceptionally(error);
    }

    public synchronized int cacheSize() { return cache.size(); }
    public synchronized int inflightSize() { return pending.size(); }

    public void close() {
        List<Request> cancelled;
        boolean cancelReadiness;
        synchronized (this) {
            if (closed) return;
            closed = true; ready = false;
            cancelReadiness = !readinessTerminal;
            readinessTerminal = true;
            if (readyDeadline != null) readyDeadline.cancel(false);
            cancelled = List.copyOf(pending.values());
            pending.clear(); cache.clear();
            for (Request request : cancelled) if (request.deadline != null) request.deadline.cancel(false);
        }
        workers.shutdownNow(); timer.shutdownNow();
        CancellationException error = new CancellationException("Embedding shutdown");
        if (cancelReadiness) readiness.completeExceptionally(error);
        for (Request request : cancelled) request.future.completeExceptionally(error);
        provider.close();
    }
}
