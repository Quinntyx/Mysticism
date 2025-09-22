package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async embedding helper with:
 *  - per-worker EmbeddingService (ThreadLocal)
 *  - deduped in-flight requests
 *  - bounded executor queue for backpressure
 *  - zero-overhead cache hits (completed futures)
 */
public class EmbeddingHelper {
    public static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-EmbeddingHelper");

    // --- config ---
    private static final int DEFAULT_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 3);
    private static final int QUEUE_CAPACITY = 4096; // bound the queue to avoid OOM on bursts
    private static final boolean PREWARM_SERVICES = true; // create one EmbeddingService per worker on init

    // --- runtime ---
    private static final ConcurrentHashMap<String, Vec384f> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<Vec384f>> INFLIGHT = new ConcurrentHashMap<>();

    private static volatile ThreadPoolExecutor EXECUTOR = null;
    private static final ThreadLocal<EmbeddingService> WORKER_SERVICE =
            ThreadLocal.withInitial(() -> {
                try {
                    return new EmbeddingService();
                } catch (Exception e) {
                    // Make init errors loud and fail the task cleanly
                    throw new RuntimeException("Failed to construct EmbeddingService for worker thread", e);
                }
            });

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static CompletableFuture<Void> initializationFuture;

    // ===== Lifecycle =====

    /** Initialize for server environment (no toast). Safe to call multiple times. */
    public static synchronized void initializeServer() {
        initializeServer(DEFAULT_THREADS);
    }

    /** Overload with explicit thread count. */
    public static synchronized void initializeServer(int threads) {
        if (started.get()) {
            LOGGER.info("EmbeddingHelper already initialized ({} threads).", EXECUTOR != null ? EXECUTOR.getCorePoolSize() : -1);
            return;
        }
        started.set(true);

        LOGGER.info("Starting EmbeddingHelper initialization with {} worker threads…", threads);

        initializationFuture = CompletableFuture.runAsync(() -> {
            try {
                // BOUNDED queue + custom thread factory
                BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
                ThreadFactory tf = r -> {
                    Thread t = new Thread(r, "Mysticism-EmbedWorker");
                    t.setDaemon(true);
                    return t;
                };

                EXECUTOR = new ThreadPoolExecutor(
                        threads,              // core
                        threads,              // max
                        30L, TimeUnit.SECONDS,
                        queue,
                        tf,
                        new ThreadPoolExecutor.AbortPolicy() // reject instead of unbounded growth
                );

                if (PREWARM_SERVICES) {
                    LOGGER.info("Prewarming per-thread EmbeddingService instances…");
                    List<CompletableFuture<Void>> warms = new ArrayList<>();
                    for (int i = 0; i < threads; i++) {
                        warms.add(CompletableFuture.runAsync(() -> {
                            // Touch the ThreadLocal inside the pool to instantiate the service
                            WORKER_SERVICE.get();
                        }, EXECUTOR));
                    }
                    // Block until all workers created their service (fail fast if any throws)
                    warms.forEach(CompletableFuture::join);
                }

                LOGGER.info("EmbeddingHelper initialized.");
            } catch (Throwable t) {
                LOGGER.error("EmbeddingHelper failed to initialize!", t);
                throw t;
            }
        }).whenComplete((ok, err) -> {
            if (err != null) LOGGER.error("EmbeddingHelper init completed with error", err);
            else LOGGER.info("EmbeddingHelper init completed successfully.");
        });
    }

    public static boolean isReady() {
        return initializationFuture != null
                && initializationFuture.isDone()
                && !initializationFuture.isCompletedExceptionally()
                && EXECUTOR != null
                && !EXECUTOR.isShutdown();
    }

    public static void awaitReady() {
        if (initializationFuture != null) {
            initializationFuture.join();
        }
    }

    /** Optional: graceful shutdown for dedicated server stop. */
    public static synchronized void shutdown() {
        if (EXECUTOR != null) {
            LOGGER.info("Shutting down EmbeddingHelper executor…");
            EXECUTOR.shutdown();
            try { EXECUTOR.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            EXECUTOR.shutdownNow();
            EXECUTOR = null;
        }
        started.set(false);
        INFLIGHT.clear();
        // Keep CACHE across restarts of the helper within same JVM if you want; otherwise:
        // CACHE.clear();
    }

    // ===== Public API =====

    /**
     * Async get: immediately returns a future.
     *  - If cached: returns a completed future.
     *  - Else: dedupes concurrent requests and schedules one worker task.
     */
    public static CompletableFuture<Vec384f> getEmbedding(String text) {
        if (text == null) return failedFuture(new IllegalArgumentException("text is null"));
        if (!isReady()) return failedFuture(new IllegalStateException("EmbeddingHelper not ready"));

        // Fast path: cache hit
        Vec384f cached = CACHE.get(text);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        // Deduplicate: one job per unique text
        return INFLIGHT.computeIfAbsent(text, key -> {
            CompletableFuture<Vec384f> promise = new CompletableFuture<>();
            try {
                EXECUTOR.submit(() -> {
                    try {
                        // Double-check cache after scheduling (another thread may have filled it)
                        Vec384f c2 = CACHE.get(key);
                        if (c2 != null) {
                            promise.complete(c2);
                            return;
                        }

                        // One EmbeddingService per worker thread (ThreadLocal)
                        EmbeddingService svc = WORKER_SERVICE.get();
                        Vec384f vec = svc.getEmbedding(key);
                        if (vec == null) {
                            throw new IllegalStateException("EmbeddingService returned null embedding");
                        }
                        CACHE.put(key, vec);
                        promise.complete(vec);
                    } catch (Throwable t) {
                        throw new IllegalStateException("EmbeddingService completed exceptionally", t);
//                        promise.completeExceptionally(t);
                    } finally {
                        // remove only if still mapping to this promise
                        INFLIGHT.remove(key, promise);
                    }
                });
            } catch (RejectedExecutionException rex) {
                INFLIGHT.remove(key, promise);
                throw new IllegalStateException("EmbeddingHelper executor rejected execution", rex);
//                promise.completeExceptionally(rex);
            }
            return promise;
        });
    }

    /**
     * Compatibility helper: synchronous call that blocks on the async future.
     * Prefer {@link #getEmbedding(String)} in new code.
     */
    public static Optional<Vec384f> getEmbeddingBlocking(String text) {
        try {
            return Optional.ofNullable(getEmbedding(text).get());
        } catch (Exception e) {
            LOGGER.warn("getEmbeddingBlocking failed for '{}': {}", text, e.toString());
            return Optional.empty();
        }
    }

    // ===== Introspection =====

    public static String getInitializationStatus() {
        if (initializationFuture == null) return "Not started";
        if (!initializationFuture.isDone()) return "In progress";
        if (initializationFuture.isCompletedExceptionally()) return "Failed";
        if (EXECUTOR == null) return "Completed but executor unavailable";
        return "Ready";
    }

    public static int getCacheSize() { return CACHE.size(); }
    public static int getInflightSize() { return INFLIGHT.size(); }

    // ===== Utilities =====

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }
}
