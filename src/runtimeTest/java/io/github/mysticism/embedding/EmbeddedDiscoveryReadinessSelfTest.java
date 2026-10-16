package io.github.mysticism.embedding;

import io.github.mysticism.navigation.DiscoveryReadiness;
import io.github.mysticism.vector.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** The production embedded provider -> engine -> discovery/navigation admission path.
 * Injected models are failure fixtures only, never proof of real weight inference. */
public final class EmbeddedDiscoveryReadinessSelfTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
    private static Throwable failed(CompletableFuture<?> future) throws Exception {
        try { get(future); throw new AssertionError("Expected failure"); }
        catch (ExecutionException failure) { checks++; return EmbeddingReadiness.unwrap(failure); }
    }
    private static void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), "Timed out waiting for embedded cleanup/state");
    }
    private static float[] vector() { float[] values = new float[768]; values[0] = 3; values[767] = 4; return values; }
    private static class Model implements EmbeddedEmbeddingService.Model {
        volatile int tokenCount = 20;
        volatile float[] output = vector();
        final AtomicInteger calls = new AtomicInteger(), closes = new AtomicInteger();
        public int dimensions() { return 768; }
        public int tokens(String input) { return tokenCount; }
        public float[] embed(String input) { calls.incrementAndGet(); return output; }
        public void close() { closes.incrementAndGet(); }
    }
    private static final class Asset implements EmbeddedEmbeddingService.Asset {
        final CountDownLatch closed = new CountDownLatch(1);
        public Path resolve() { return Path.of("fixture-NOT-model-weights"); }
        public void close() { closed.countDown(); }
    }
    private static AsyncEmbeddingEngine engine(Model model, Asset asset, long millis) {
        return new AsyncEmbeddingEngine(new EmbeddedEmbeddingService(asset, (path, cancelled) -> model),
                1, 8, 8, Duration.ofMillis(millis), Duration.ofSeconds(3));
    }
    private static CompletableFuture<Void> offer(EmbeddingWorkQueue<String> queue, String name, boolean needsModel,
                                               EmbeddingReadiness readiness) {
        var result = new CompletableFuture<Void>();
        queue.offer(name, needsModel, !needsModel, result, result::completeExceptionally, readiness);
        return result;
    }
    private static void discoveryFailure(EmbeddingReadiness state, Throwable cause) throws Exception {
        var queue = new EmbeddingWorkQueue<String>(4);
        var waiting = offer(queue, "new-discovery", true, new EmbeddingReadiness(EmbeddingReadiness.Phase.INITIALIZING, null));
        var physical = offer(queue, "source-read", false, state);
        queue.settle(state);
        check(failed(waiting) == cause && queue.poll(state).equals("source-read") && !physical.isDone(),
                "Model failure must terminate discovery, not block independent physical reads");
        check(failed(offer(queue, "another-discovery", true, state)) == cause && queue.size() == 0,
                "Unavailable model must reject fresh discovery without occupying the queue");
        var unanchored = new DiscoveryReadiness(false, state);
        check(!unanchored.navigationReady() && unanchored.terminallyBlocked(), "Model loss must block unanchored walking");
        var anchored = new DiscoveryReadiness(true, state);
        check(anchored.navigationReady() && !anchored.terminallyBlocked(), "Current published ownership remains navigable without new inference");
    }
    private static void nativeFailuresRevokeReadiness() throws Exception {
        // Wrong width, zero/non-finite output, malformed tokenizer, native execution/ABI loss.
        for (int scenario = 0; scenario < 6; scenario++) {
            int mode = scenario; var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var nativeFailure = new IllegalStateException("native context disappeared");
            var abiFailure = new UnsatisfiedLinkError("native symbol unavailable");
            Model model = new Model() {
                public int tokens(String input) {
                    if (input.endsWith("broken") && mode == 3) throw new IllegalArgumentException("Invalid tokenizer response");
                    return super.tokens(input);
                }
                public float[] embed(String input) {
                    if (!input.endsWith("broken")) return super.embed(input);
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Fixture gate timed out"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    return switch (mode) {
                        case 0 -> new float[384];
                        case 1 -> new float[768];
                        case 2 -> { float[] values = vector(); values[767] = Float.NaN; yield values; }
                        case 4 -> throw nativeFailure;
                        case 5 -> throw abiFailure;
                        default -> throw new AssertionError("Tokenizer fixture should not run inference");
                    };
                }
            };
            Asset asset = new Asset();
            try (var engine = engine(model, asset, 2000)) {
                get(engine.readiness()); Vec384f cached = get(engine.embed("cached"));
                check(engine.state().ready() && cached.data().length == 768 && cached.data()[767] > .79f,
                        "Successful native-profile probe authorizes full-width inference, not yet navigation");
                check(!new DiscoveryReadiness(false, engine.state()).navigationReady(), "Model success is not source ownership");
                var broken = engine.embed("broken");
                CompletableFuture<Vec384f> queued = null;
                if (mode != 3) {
                    check(entered.await(1, TimeUnit.SECONDS), "Broken native inference did not enter");
                    queued = engine.embed("queued"); release.countDown();
                }
                Throwable failure = failed(broken);
                check(failure instanceof EmbeddingModelException && failure.getCause() != null, "Embedded failure must be a definitive model error with original cause");
                if (mode == 4) check(failure.getCause() == nativeFailure, "Native error cause lost");
                if (mode == 5) check(failure.getCause() == abiFailure, "Native ABI error cause lost");
                if (queued != null) check(failed(queued) == failure, "Queued inference not drained with the definitive error");
                check(engine.state().phase() == EmbeddingReadiness.Phase.FAILED && failed(engine.readiness()) == failure,
                        "Successful startup must not mask later embedded failure");
                check(engine.cacheSize() == 0 && engine.inflightSize() == 0 && failed(engine.embed("cached")).getCause() == failure,
                        "No old cached vector may mask model loss");
                discoveryFailure(engine.state(), failure);
                check(asset.closed.await(2, TimeUnit.SECONDS), "Failed native generation did not cancel its asset");
                eventually(() -> model.closes.get() == 1);
            } finally { release.countDown(); }
            check(model.closes.get() == 1, "Engine shutdown must not double-close failed native generation");
        }
    }
    /** Merge regression: stable pooling/cache math must coexist with terminal model loss.
     * These canonical labels exercise production plumbing, not model semantic quality. */
    private static void landmarkPoolingThenModelLoss() throws Exception {
        String block = CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs"));
        String biome = CanonicalDescriptors.biome("minecraft:dripstone_caves", List.of("minecraft:is_overworld"));
        String region = CanonicalDescriptors.region("minecraft:the_nether", "minecraft:basalt_deltas", List.of());
        Model model = new Model() {
            private String tokenized;
            public int tokens(String input) { tokenized = input; return 24; }
            public float[] embed(String input) {
                check(input.equals(tokenized), "Tokenizer and inference must consume identical prefixed text");
                if (input.equals("search_document: " + region)) return new float[768];
                if (!input.equals("search_document: " + block) && !input.equals("search_document: " + biome))
                    return super.embed(input); // readiness probe
                float[] values = new float[768];
                if (input.equals("search_document: " + block)) {
                    values[0] = 3 * Float.MIN_VALUE; values[767] = 4 * Float.MIN_VALUE;
                } else {
                    values[0] = Float.MAX_VALUE; values[767] = Float.MAX_VALUE;
                }
                return values;
            }
        };
        try (var engine = engine(model, new Asset(), 2000)) {
            get(engine.readiness());
            Vec384f a = get(engine.embed(block)), b = get(engine.embed(biome));
            Vec384f pooled = DescriptorVectors.compose(List.of(
                    new DescriptorVectors.WeightedVector(a, Double.MAX_VALUE),
                    new DescriptorVectors.WeightedVector(b, Double.MAX_VALUE)));
            double x = .6 + 1 / Math.sqrt(2), y = .8 + 1 / Math.sqrt(2);
            double length = Math.hypot(x, y);
            for (int i = 0; i < 768; i++) {
                double expected = i == 0 ? x / length : i == 767 ? y / length : 0;
                check(Math.abs(pooled.data()[i] - expected) < 1e-7, "Landmark pooled coordinate changed: " + i);
            }
            check(engine.state().ready() && engine.cacheSize() == 2,
                    "Finite extreme output/weights must not fail readiness or lose native coordinates");
            a.mul(0); pooled.mul(0);
            check(get(engine.embed(block)).data()[767] > .79f, "Composed/caller storage must not alias native cache");
            Throwable failure = failed(engine.embed(region));
            check(failure instanceof EmbeddingModelException && engine.state().failure() == failure,
                    "Invalid landmark output must revoke the previously successful generation");
            check(engine.cacheSize() == 0 && failed(engine.embed(block)).getCause() == failure,
                    "Normalized cached landmarks must not hide subsequent model failure");
            discoveryFailure(engine.state(), failure);
        }
    }
    private static void descriptorFailuresStayLocal() throws Exception {
        Model model = new Model(); Asset asset = new Asset();
        try (var engine = engine(model, asset, 2000)) {
            get(engine.readiness()); int before = model.calls.get(); model.tokenCount = 513;
            check(failed(engine.embed("oversized")) instanceof IllegalArgumentException && engine.state().ready(),
                    "Descriptor token overflow must not revoke readiness or truncate input");
            check(model.calls.get() == before, "Overflow reached native execution");
            model.tokenCount = 20;
            failed(engine.embed("unpaired \uD800")); check(engine.state().ready(), "Malformed descriptor is not model disappearance");
            check(get(engine.embed("valid after rejection")).data()[767] > .79f, "Descriptor rejection poisoned later valid work");
            model.tokenCount = 0;
            check(failed(engine.embed("no token ids")) instanceof EmbeddingModelException && engine.state().unavailable(),
                    "An empty tokenizer result is model failure, not input overflow");
        }
    }
    private static void startupFailureNeverAdmitsDiscovery() throws Exception {
        Model model = new Model(); model.output = new float[768]; Asset asset = new Asset();
        try (var engine = engine(model, asset, 2000)) {
            Throwable failure = failed(engine.readiness());
            check(failure instanceof EmbeddingModelException && engine.state().unavailable(), "Readiness must execute and validate actual provider output");
            discoveryFailure(engine.state(), failure);
            check(asset.closed.await(2, TimeUnit.SECONDS), "Startup failure did not release asset");
            eventually(() -> model.closes.get() == 1);
        }
    }
    private static void lateNativeFailure() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new IllegalStateException("late native model failure");
        Model model = new Model() {
            public float[] embed(String input) {
                if (!input.endsWith("late")) return super.embed(input);
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                throw failure;
            }
        };
        Asset asset = new Asset();
        try (var engine = engine(model, asset, 150)) {
            get(engine.readiness()); var result = engine.embed("late");
            check(entered.await(1, TimeUnit.SECONDS), "Late native fixture did not start");
            check(failed(result) instanceof TimeoutException && engine.state().ready(), "Request deadline is not evidence of model loss");
            release.countDown(); eventually(() -> engine.state().unavailable());
            check(engine.state().failure() instanceof EmbeddingModelException && engine.state().failure().getCause() == failure,
                    "Definitive late native failure must still revoke discovery readiness");
            check(failed(result) instanceof TimeoutException, "Late model error must not redeliver expired subscribers");
            check(engine.cacheSize() == 0 && engine.inflightSize() == 0, "Late failure leaked work/cache");
            discoveryFailure(engine.state(), engine.state().failure());
            eventually(() -> model.closes.get() == 1);
        } finally { release.countDown(); }
    }
    private static void defaultMissingLocalAsset() throws Exception {
        String[] names = {"mysticism.embedding.provider", "mysticism.embedding.modelPath", "mysticism.embedding.endpoint", "mysticism.embedding.download"};
        Map<String, String> old = new HashMap<>(); for (String name : names) old.put(name, System.getProperty(name));
        Path missing = Path.of("build", "readiness-absent-" + UUID.randomUUID() + ".gguf");
        EmbeddingHelper.shutdown();
        try {
            System.clearProperty(names[0]); System.setProperty(names[1], missing.toString());
            System.setProperty(names[2], "deliberately not a valid HTTP endpoint"); System.setProperty(names[3], "false");
            for (int launch = 0; launch < 2; launch++) {
                EmbeddingHelper.initializeServer(1);
                Throwable failure = failed(EmbeddingHelper.readiness());
                check(failure instanceof NomicV2ModelAssets.AssetException
                                && failure.getMessage().contains("Missing native v2 GGUF (including tokenizer)")
                                && failure.getMessage().contains(missing.toAbsolutePath().toString()),
                        "Default lifecycle must report the selected missing local asset, not an endpoint or provisioning failure");
                discoveryFailure(EmbeddingHelper.state(), failure);
                String status = EmbeddingHelper.getInitializationStatus();
                check(!Files.exists(missing) && status.contains(NomicV2ModelAssets.MODEL_PATH_PROPERTY)
                                && status.contains("No download, server connection or fallback was attempted"),
                        "Missing-model status must explain local configuration and the absence of network provisioning");
                EmbeddingHelper.shutdown(); check(EmbeddingHelper.state().phase() == EmbeddingReadiness.Phase.STOPPED, "Stopped generation revived readiness");
            }
        } finally {
            EmbeddingHelper.shutdown();
            for (String name : names) { if (old.get(name) == null) System.clearProperty(name); else System.setProperty(name, old.get(name)); }
        }
    }
    public static void main(String[] args) throws Exception {
        nativeFailuresRevokeReadiness(); descriptorFailuresStayLocal(); startupFailureNeverAdmitsDiscovery();
        lateNativeFailure(); landmarkPoolingThenModelLoss(); defaultMissingLocalAsset();
        System.out.println("EmbeddedDiscoveryReadinessSelfTest passed: " + checks + " checks (failure fixtures; no real model inference)");
    }
}
