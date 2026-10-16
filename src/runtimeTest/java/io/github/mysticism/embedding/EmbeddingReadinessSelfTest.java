package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Production engine/admission/HTTP protocol regressions. Synthetic provider fixtures
 * exercise failure/lifecycle only; these tests do not claim real model inference. */
public final class EmbeddingReadinessSelfTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(2, TimeUnit.SECONDS); }
    private static Throwable failed(CompletableFuture<?> f) throws Exception {
        try { get(f); throw new AssertionError("Expected failure"); }
        catch (ExecutionException e) { checks++; return EmbeddingReadiness.unwrap(e); }
        catch (CancellationException e) { checks++; return e; }
    }
    private static void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(5);
        check(condition.getAsBoolean(), "Timed out waiting for production state transition");
    }
    private static Vec384f vector() { float[] v = new float[EmbeddingSpace.DIMENSIONS]; v[767] = 1; return new Vec384f(v); }
    private static abstract class Provider implements EmbeddingProvider {
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() throws Exception {}
        public void close() {}
    }
    private static AsyncEmbeddingEngine engine(EmbeddingProvider p, int threads, long millis) {
        return new AsyncEmbeddingEngine(p, threads, 8, 8, Duration.ofMillis(millis));
    }
    private static EmbeddingReadiness pending() { return new EmbeddingReadiness(EmbeddingReadiness.Phase.INITIALIZING, null); }
    private static EmbeddingReadiness ready() { return new EmbeddingReadiness(EmbeddingReadiness.Phase.READY, null); }
    private static void offer(EmbeddingWorkQueue<String> q, String work, boolean model, CompletableFuture<?> f, EmbeddingReadiness state) {
        q.offer(work, model, !model, f, f::completeExceptionally, state);
    }
    private static void admission() throws Exception {
        var q = new EmbeddingWorkQueue<String>(4);
        var discovery = new CompletableFuture<Void>(); var read = new CompletableFuture<Void>();
        offer(q, "discovery", true, discovery, pending()); offer(q, "source-read", false, read, pending());
        check(q.poll(pending()).equals("source-read"), "Physical source reads must bypass pending model requests");
        check(q.poll(pending()) == null && !discovery.isDone(), "Initialization is pending, not a failed or ready discovery");
        check(q.poll(ready()).equals("discovery"), "Real readiness admits pending discovery");
        offer(q, "discover-again", true, discovery, ready()); offer(q, "priority-read", false, read, ready());
        check(q.poll(ready()).equals("priority-read"), "Preserve near-source read priority even when model ready");
        q.cancelWhere(work -> true, new CancellationException("unload")); failed(discovery);

        var failure = new EmbeddingModelException("missing pinned v2 model");
        var unavailable = new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED, failure);
        var rejected = new CompletableFuture<Void>(); offer(q, "after-failure", true, rejected, unavailable);
        check(failed(rejected) == failure && q.size() == 0, "Immediate failure must retain actual model cause and consume no queue slot");
        var first = new CompletableFuture<Void>(); var second = new CompletableFuture<Void>(); var independent = new CompletableFuture<Void>();
        offer(q, "first", true, first, pending()); offer(q, "second", true, second, pending());
        // Future callbacks can reenter admission; iteration must finish before delivery.
        first.whenComplete((v, error) -> offer(q, "read-after-callback", false, independent, unavailable));
        q.settle(unavailable);
        check(failed(first) == failure && failed(second) == failure, "All pending dependent requests terminate, not only the queue head");
        check(q.poll(unavailable).equals("read-after-callback"), "Terminal model errors must not block physical work/reentrant admission");
        var cancelled = new CompletableFuture<Void>(); offer(q, "cancelled", true, cancelled, pending()); cancelled.cancel(false);
        q.settle(pending()); check(q.size() == 0, "Cancelled callers do not occupy discovery budget");
        var bound = new EmbeddingWorkQueue<String>(1); var slot = new CompletableFuture<Void>(); var excess = new CompletableFuture<Void>();
        offer(bound, "slot", true, slot, pending()); offer(bound, "excess", false, excess, pending());
        check(failed(excess) instanceof RejectedExecutionException && bound.size() == 1, "Admission remains bounded");
    }
    private static void modelLoss() throws Exception {
        var badStarted = new CountDownLatch(1); var badGate = new CountDownLatch(1);
        var failure = new EmbeddingModelException("pinned model revision changed"); var queuedCalls = new AtomicInteger();
        Provider provider = new Provider() {
            public Vec384f getEmbedding(String text) throws Exception {
                if (text.equals("bad")) { badStarted.countDown(); badGate.await(); throw failure; }
                if (text.equals("queued") || text.equals("held")) queuedCalls.incrementAndGet();
                return vector();
            }
            public void close() { badGate.countDown(); }
        };
        try (var e = engine(provider, 2, 1500)) {
            get(e.readiness()); check(e.state().ready(), "Real health probe publishes readiness");
            check(get(e.embed("cached")).data()[767] == 1, "Keep all native v2 coordinates");
            var bad = e.embed("bad"); check(badStarted.await(1, TimeUnit.SECONDS), "Failure worker started");
            // One JNI predictor is exclusive. The second active worker waits at the outer
            // ownership gate; the rest are queued, not simultaneous model calls.
            var held = e.embed("held"); var queued = e.embed("queued"); var shared = e.embed("held"); badGate.countDown();
            check(failed(bad) == failure && failed(held) == failure && failed(queued) == failure && failed(shared) == failure,
                    "Model loss terminates active/queued/shared callers with original cause");
            check(e.state().phase() == EmbeddingReadiness.Phase.FAILED && !e.isReady(), "Startup success cannot mask model loss");
            check(failed(e.readiness()) == failure, "Later readiness subscribers see operational failure");
            check(e.cacheSize() == 0 && e.inflightSize() == 0 && queuedCalls.get() == 0, "No old cached vectors, leaked work or invalidated queue execution");
            check(failed(e.embed("cached")).getCause() == failure, "Cache cannot mask a missing/changed model");
            eventually(() -> e.inflightSize() == 0);
        }
        Provider invalid = new Provider() { public Vec384f getEmbedding(String text) { return Vec384f.ZERO(); } };
        try (var e = engine(invalid, 1, 1500)) {
            get(e.readiness()); check(failed(e.embed("zero")) instanceof EmbeddingModelException, "Invalid model output is a model error");
            check(e.state().unavailable(), "Invalid output revokes discovery readiness");
        }
        Provider overflow = new Provider() { public Vec384f getEmbedding(String text) { throw new IllegalArgumentException("512 token context overflow"); } };
        try (var e = engine(overflow, 1, 1500)) {
            get(e.readiness()); failed(e.embed("too-long")); check(e.state().ready(), "Per-descriptor errors do not pretend the model disappeared");
        }
    }
    private static void startupAndLateError() throws Exception {
        var readyGate = new CountDownLatch(1); var failure = new EmbeddingModelException("model absent");
        Provider absent = new Provider() {
            public void checkReady() throws Exception { readyGate.await(); throw failure; }
            public Vec384f getEmbedding(String text) { throw new AssertionError("No inference before readiness"); }
            public void close() { readyGate.countDown(); }
        };
        try (var e = engine(absent, 1, 1500)) {
            var q = new EmbeddingWorkQueue<String>(2); var capture = new CompletableFuture<Void>();
            check(e.state().phase() == EmbeddingReadiness.Phase.INITIALIZING, "Nonblocking startup snapshot");
            offer(q, "capture", true, capture, e.state()); check(q.poll(e.state()) == null, "No premature discovery");
            readyGate.countDown(); check(failed(e.readiness()) == failure, "Probe failure survives publication");
            q.settle(e.state()); check(failed(capture) == failure, "Failed startup drains real admission instead of waiting indefinitely");
        }
        var gate = new CountDownLatch(1); var started = new CountDownLatch(1);
        Provider late = new Provider() {
            public Vec384f getEmbedding(String text) throws Exception { started.countDown(); gate.await(); throw failure; }
            public void close() { gate.countDown(); }
        };
        try (var e = engine(late, 1, 200)) {
            get(e.readiness()); var timedOut = e.embed("late"); check(started.await(1, TimeUnit.SECONDS), "Late model work started");
            check(failed(timedOut) instanceof TimeoutException, "Request deadline still terminates caller");
            gate.countDown(); eventually(() -> e.state().unavailable());
            check(e.state().failure() == failure && e.cacheSize() == 0, "Late definitive model error must revoke readiness without redelivery");
        }
        var e = engine(new Provider() { public Vec384f getEmbedding(String text) { return vector(); } }, 1, 1500);
        get(e.readiness()); e.close(); check(e.state().phase() == EmbeddingReadiness.Phase.STOPPED, "Shutdown is distinct from initializing/ready"); failed(e.readiness());
    }
    private static void httpModelErrors() throws Exception {
        // Only loopback test fixtures, never a real service or weight download.
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var digest = new AtomicReference<>(EmbeddingSpace.REVISION); var response = new AtomicReference<>(nativeResponse());
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = ("{\"models\":[{\"name\":\"" + EmbeddingSpace.MODEL + "\",\"digest\":\"" + digest.get() + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/api/embed", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/");
            try (var e = engine(new EmbeddingService(endpoint, Duration.ofMillis(1000), Duration.ofMillis(1000)), 1, 1500)) {
                get(e.readiness()); get(e.embed("cached")); digest.set("0".repeat(64));
                check(failed(e.embed("new")) instanceof EmbeddingModelException && e.state().unavailable(), "Actual HTTP digest mismatch revokes readiness");
                failed(e.embed("cached"));
            }
            digest.set(EmbeddingSpace.REVISION);
            try (var e = engine(new EmbeddingService(endpoint, Duration.ofMillis(1000), Duration.ofMillis(1000)), 1, 1500)) {
                get(e.readiness()); response.set("{\"model\":\"" + EmbeddingSpace.MODEL + "\",\"embeddings\":[[1,2,3]]}");
                check(failed(e.embed("short")) instanceof EmbeddingModelException && e.state().unavailable(), "Actual non-native HTTP output cannot stay ready");
            }
        } finally { server.stop(0); }
    }
    private static String nativeResponse() {
        return "{\"model\":\"" + EmbeddingSpace.MODEL + "\",\"embeddings\":[[" + "0,".repeat(767) + "1]]}";
    }
    public static void main(String[] args) throws Exception {
        admission(); modelLoss(); startupAndLateError(); httpModelErrors();
        System.out.println("EmbeddingReadinessSelfTest passed: " + checks + " checks (no real model inference)");
    }
}
