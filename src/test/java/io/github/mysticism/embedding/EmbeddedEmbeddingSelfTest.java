package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fixture inference is intentionally confined to tests; production has no fake fallback. */
public final class EmbeddedEmbeddingSelfTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    private static void failed(CompletableFuture<?> future) throws Exception { rejects(() -> future.get(3, TimeUnit.SECONDS)); }
    private static final class Asset implements EmbeddedEmbeddingService.Asset {
        final AtomicInteger reads = new AtomicInteger(); final CountDownLatch closed = new CountDownLatch(1);
        public Path resolve() { reads.incrementAndGet(); return Path.of("fixture-NOT-a-model"); }
        public void close() { closed.countDown(); }
    }
    private static class Model implements EmbeddedEmbeddingService.Model {
        final List<String> inputs = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger closes = new AtomicInteger(), calls = new AtomicInteger();
        volatile int tokenCount = 20, dimensions = 768;
        volatile float[] output = vector();
        public int dimensions() { return dimensions; }
        public int tokens(String input) { inputs.add(input); return tokenCount; }
        public float[] embed(String input) { calls.incrementAndGet(); return output; }
        public void close() { closes.incrementAndGet(); }
    }
    private static float[] vector() { float[] output = new float[768]; output[0] = 3; output[767] = 4; return output; }
    private static void selection() throws Exception {
        String[] names = {"mysticism.embedding.provider", "mysticism.embedding.modelPath", "mysticism.embedding.endpoint"};
        Map<String, String> old = new HashMap<>(); for (String name : names) old.put(name, System.getProperty(name));
        try {
            System.clearProperty(names[0]); System.setProperty(names[1], "build/no-weights-should-be-created.gguf");
            System.setProperty(names[2], "not an HTTP URL");
            try (EmbeddingProvider provider = EmbeddingProviders.create()) {
                check(provider instanceof EmbeddedEmbeddingService, "Normal path is not local");
                check(provider.profile().equals(EmbeddingProfile.current()), "Profile changed");
                check(!Files.exists(Path.of(System.getProperty(names[1]))), "Constructor provisioned weights");
            }
            System.setProperty(names[1], "\u0000");
            try (EmbeddingProvider provider = EmbeddingProviders.create()) {
                // Invalid path parsing must be deferred along with file/cache IO.
                rejects(provider::checkReady);
            }
            System.setProperty(names[0], "ollama"); System.setProperty(names[2], "http://127.0.0.1:1/");
            try (EmbeddingProvider provider = EmbeddingProviders.create()) { check(provider instanceof EmbeddingService, "Explicit HTTP opt-in lost"); }
            System.setProperty(names[0], "typo"); rejects(EmbeddingProviders::create);
        } finally { for (String name : names) { if (old.get(name) == null) System.clearProperty(name); else System.setProperty(name, old.get(name)); } }
    }
    private static void inferenceContract() throws Exception {
        Asset asset = new Asset(); Model model = new Model();
        try (var provider = new EmbeddedEmbeddingService(asset, (path, cancelled) -> model)) {
            rejects(() -> provider.getEmbedding("before readiness"));
            check(asset.reads.get() == 0 && model.calls.get() == 0, "Unexpected eager preparation");
            provider.checkReady(); provider.checkReady();
            check(asset.reads.get() == 1 && model.calls.get() == 1, "Readiness must prepare once and actually infer");
            Vec384f result = provider.getEmbedding("oak log");
            check(result.data().length == 768 && Math.abs(result.data()[767] - .8f) < 1e-6, "Native tail truncated");
            check(Math.abs(result.length() - 1) < 1e-6 && result.fingerprint().equals(EmbeddingSpace.FINGERPRINT), "Wrong normalization/provenance");
            check(model.inputs.equals(List.of("search_document: readiness probe", "search_document: oak log")), "Wrong task/tokenizer input");
            result.mul(0); check(model.output[767] == 4, "Model output aliases result");
            model.tokenCount = 512; provider.getEmbedding("fits");
            int calls = model.calls.get(); model.tokenCount = 513; rejects(() -> provider.getEmbedding("overflow"));
            check(model.calls.get() == calls, "Token overflow silently truncated/executed");
            rejects(() -> provider.getEmbedding(" ")); rejects(() -> provider.getEmbedding("x".repeat(8193)));
        }
        check(asset.closed.await(2, TimeUnit.SECONDS), "Asset cancellation lost");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (model.closes.get() == 0 && System.nanoTime() < end) Thread.sleep(5);
        check(model.closes.get() == 1, "Native model not released exactly once");
        for (int size : new int[]{256, 384, 767, 769}) rejects(() -> EmbeddedEmbeddingService.decodeEmbedding(new float[size]));
        rejects(() -> EmbeddedEmbeddingService.decodeEmbedding(new float[768]));
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] output = vector(); output[400] = value; rejects(() -> EmbeddedEmbeddingService.decodeEmbedding(output));
        }
        Model invalid = new Model(); invalid.dimensions = 384;
        try (var provider = new EmbeddedEmbeddingService(new Asset(), (path, cancelled) -> invalid)) { rejects(provider::checkReady); }
        check(invalid.closes.get() == 1 && invalid.calls.get() == 0, "Incompatible model leaked/executed");
        Model zero = new Model(); zero.output = new float[768];
        try (var provider = new EmbeddedEmbeddingService(new Asset(), (path, cancelled) -> zero)) { rejects(provider::checkReady); }
        check(zero.closes.get() == 1, "Failed readiness inference leaked native context");
    }
    private static void preparationDeadline() throws Exception {
        Asset asset = new Asset(); Model model = new Model();
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        var provider = new EmbeddedEmbeddingService(asset, (path, cancelled) -> {
            loading.countDown(); release.await(); return model;
        });
        try (var engine = new AsyncEmbeddingEngine(provider, 2, 4, 4, Duration.ofMillis(50), Duration.ofSeconds(2))) {
            check(loading.await(1, TimeUnit.SECONDS), "Preparation did not run on worker");
            check(!engine.readiness().isDone(), "Readiness was only an executor flag");
            Thread.sleep(100); check(!engine.readiness().isDone(), "Inference deadline incorrectly applies to preparation");
            release.countDown(); engine.readiness().get(2, TimeUnit.SECONDS);
            engine.embed("ready").get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        // A JNI-like load which ignores interruption must not hold shutdown or revive readiness.
        Asset slowAsset = new Asset(); Model late = new Model();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        var slow = new EmbeddedEmbeddingService(slowAsset, (path, cancelled) -> {
            started.countDown();
            while (finish.getCount() > 0) { try { finish.await(); } catch (InterruptedException ignored) { /* native-like */ } }
            check(cancelled.getAsBoolean(), "Timed-out preparation did not cancel loader");
            return late;
        });
        var engine = new AsyncEmbeddingEngine(slow, 1, 4, 4, Duration.ofMillis(50), Duration.ofMillis(150));
        try {
            check(started.await(1, TimeUnit.SECONDS), "Slow loader did not start"); failed(engine.readiness());
            check(slowAsset.closed.await(2, TimeUnit.SECONDS), "Readiness timeout did not close provider");
            long start = System.nanoTime(); engine.close(); slow.close();
            check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100), "Shutdown waits for native load");
            finish.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (late.closes.get() == 0 && System.nanoTime() < end) Thread.sleep(5);
            check(late.closes.get() == 1 && late.calls.get() == 0, "Late JNI context leaked/published");
            check(!engine.isReady(), "Late preparation revived failed readiness"); failed(engine.embed("late"));
        } finally { finish.countDown(); engine.close(); }
    }
    private static void serialNativeLifetime() throws Exception {
        Asset asset = new Asset(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        Model model = new Model() {
            @Override public float[] embed(String input) {
                if (input.endsWith("readiness probe")) return super.embed(input);
                int concurrent = active.incrementAndGet(); maximum.accumulateAndGet(concurrent, Math::max);
                entered.countDown();
                try {
                    while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { /* native-like */ } }
                    if (closes.get() != 0) throw new AssertionError("Freed context underneath native inference");
                    return super.embed(input);
                } finally { active.decrementAndGet(); }
            }
        };
        var provider = new EmbeddedEmbeddingService(asset, (path, cancelled) -> model);
        var engine = new AsyncEmbeddingEngine(provider, 2, 4, 4, Duration.ofSeconds(2));
        try {
            engine.readiness().get(2, TimeUnit.SECONDS);
            var one = engine.embed("first"); check(entered.await(1, TimeUnit.SECONDS), "Native inference did not start");
            var two = engine.embed("second"); Thread.sleep(100);
            check(maximum.get() == 1, "Concurrent calls entered one JNI context");
            long start = System.nanoTime(); engine.close();
            check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100), "Shutdown blocks on JNI inference");
            failed(one); failed(two); check(model.closes.get() == 0, "Context freed while inference is active");
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (model.closes.get() == 0 && System.nanoTime() < end) Thread.sleep(5);
            check(model.closes.get() == 1 && maximum.get() == 1, "Native lifetime cleanup/serialization broken");
        } finally { release.countDown(); engine.close(); }
    }
    public static void main(String[] args) throws Exception {
        check(LlamaEmbeddingModel.decodeTokenCount("{\"tokens\":[101,1234,102]}") == 3, "Special tokens not counted");
        rejects(() -> LlamaEmbeddingModel.decodeTokenCount("{}"));
        rejects(() -> LlamaEmbeddingModel.decodeTokenCount("{\"tokens\":[]}"));
        rejects(() -> LlamaEmbeddingModel.decodeTokenCount("{\"tokens\":1}"));
        rejects(() -> LlamaEmbeddingModel.decodeTokenCount("x".repeat(262145)));
        selection(); inferenceContract(); preparationDeadline(); serialNativeLifetime();
        System.out.println("EmbeddedEmbeddingSelfTest passed: " + checks + " checks (fixture inference, no weights)");
    }
}
