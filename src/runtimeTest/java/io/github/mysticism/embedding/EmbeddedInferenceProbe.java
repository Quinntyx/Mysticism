package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import io.github.mysticism.navigation.DiscoveryReadiness;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.ladenthin.llama.LlamaModel;

/** Explicit opt-in integration probe. Not named *Test: ordinary build never fetches weights. */
public final class EmbeddedInferenceProbe {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: EmbeddedInferenceProbe <exact-GGUF-path-or-empty-for-local-caches> <provision:true|false>");
        NomicV2ModelAssets.requireApprovedProfile();
        Path base = Path.of("build/embedding-inference-probe").toAbsolutePath(); Files.createDirectories(base);
        System.setProperty("net.ladenthin.llama.tmpdir", Files.createTempDirectory(base, "jni-").toString());
        Path path;
        if (args[0].isEmpty()) {
            if (Boolean.parseBoolean(args[1])) throw new IllegalArgumentException("Provisioning requires an explicit probe path");
            System.out.println("Verifying normal read-only runtime local/cache assets (no download)");
            try (var asset = new LocalNomicV2ModelAsset()) { path = asset.resolve(); }
        } else {
            path = Path.of(args[0]).toAbsolutePath();
            try (var pinned = new PinnedEmbeddingModel(path, PinnedEmbeddingModel.SOURCE, PinnedEmbeddingModel.SHA256,
                    PinnedEmbeddingModel.BYTES, Boolean.parseBoolean(args[1]), Duration.ofMinutes(10))) {
                System.out.println("Verifying/provisioning exact v2 GGUF: " + path);
                pinned.resolve();
            }
        }
        // Exercise the actual default selection, not an explicit embedded override.
        System.clearProperty("mysticism.embedding.provider");
        if (!args[0].isEmpty()) System.setProperty("mysticism.embedding.modelPath", path.toString());
        // Endpoint deliberately unusable; it must have no effect on the default local path.
        System.setProperty("mysticism.embedding.endpoint", "http://127.0.0.1:1/");
        try (var model = new LlamaEmbeddingModel(path, () -> false)) {
            check(model.dimensions() == 768, "Wrong native model dimension");
            check(model.tokens("search_document: " + "oak ".repeat(600)) > 512, "Tokenizer cannot detect overflow");
            // Production canonical text and multilingual cave text, at differing actual lengths.
            // The single-sequence native MEAN output is already pooled, not a hidden-state batch.
            List<String> corpus = List.of(
                    CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs", "minecraft:burnable")),
                    CanonicalDescriptors.biome("minecraft:dripstone_caves", List.of("minecraft:is_overworld")),
                    CanonicalDescriptors.region("minecraft:the_nether", "minecraft:basalt_deltas", List.of()),
                    "cave minecraft:lush_caves. moss clay water glow berries. cueva 洞窟 Höhle 🌳");
            var firstPass = new ArrayList<Vec384f>();
            for (String descriptor : corpus) {
                String input = "search_document: " + descriptor;
                int tokens = model.tokens(input);
                check(tokens > 0 && tokens <= 512, "Real landmark tokenizer length out of bounds: " + tokens);
                float[] sentence = model.embed(input);
                check(sentence != null && sentence.length == 768, "Native sentence output shape changed");
                double squared = 0;
                for (float value : sentence) {
                    check(Float.isFinite(value), "Nonfinite real native output");
                    squared += (double) value * value;
                }
                check(squared > 0, "Zero real native output");
                double length = Math.sqrt(squared);
                Vec384f decoded = EmbeddedEmbeddingService.decodeEmbedding(sentence);
                float[] coordinates = decoded.data();
                for (int i = 0; i < 768; i++)
                    check(Math.abs(coordinates[i] - sentence[i] / length) < 1e-7,
                            "Real native coordinate differs from independent L2 reference: " + i);
                check(coordinates[767] != 0, "Native tail missing");
                firstPass.add(decoded);
            }
            // Revisit after different-length/text requests to expose stale pooling/context state.
            for (int i = corpus.size() - 1; i >= 0; i--) {
                Vec384f repeated = EmbeddedEmbeddingService.decodeEmbedding(model.embed("search_document: " + corpus.get(i)));
                check(firstPass.get(i).dot(repeated) > .99999f, "Repeated real landmark inference unstable");
            }
            System.out.println("Real landmark tokenizer/pooled-output checks passed for " + corpus.size() +
                    " texts (all 768 coordinates compared; no semantic-quality claim)");
        }
        try (var provider = EmbeddingProviders.create();
             var engine = new AsyncEmbeddingEngine(provider, 2, 16, 32, Duration.ofSeconds(30), Duration.ofMinutes(3))) {
            check(provider instanceof EmbeddedEmbeddingService, "Probe is not default embedded selection");
            engine.readiness().get(3, TimeUnit.MINUTES); check(engine.isReady(), "Real readiness failed");
            List<String> descriptors = List.of("block minecraft:oak_log. minecraft oak log.",
                    "block minecraft:birch_log. minecraft birch log.", "block minecraft:lava. minecraft lava.",
                    "biome minecraft:forest. minecraft forest.");
            var futures = descriptors.stream().map(engine::embed).toList();
            List<Vec384f> vectors = new ArrayList<>();
            for (var future : futures) {
                Vec384f vector = future.get(30, TimeUnit.SECONDS); EmbeddingSpace.requireCurrent(vector);
                check(vector.data().length == 768 && Math.abs(vector.length() - 1) < 1e-5, "Wrong native shape/norm");
                check(vector.data()[767] != 0, "Native output was truncated"); vectors.add(vector);
            }
            check(vectors.get(0).dot(vectors.get(2)) < .999f, "Different text returned identical vectors");
            Vec384f cached = engine.embed(descriptors.getFirst()).get(5, TimeUnit.SECONDS); cached.mul(0);
            check(engine.embed(descriptors.getFirst()).get(5, TimeUnit.SECONDS).length() > .99, "Mutable cache alias");
            try { engine.embed("oak ".repeat(600)).get(30, TimeUnit.SECONDS); throw new AssertionError("Actual token overflow truncated"); }
            catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalArgumentException, "Unexpected overflow failure: " + expected); }
            System.out.println("REAL in-process inference passed; JNI=" + LlamaModel.getLlamaCppBuildInfo() +
                    ", SHA256=" + PinnedEmbeddingModel.SHA256 + ", dimensions=768, all coordinates retained, no HTTP service");
            System.out.println("Observed cosine oak/birch=" + vectors.get(0).dot(vectors.get(1)) +
                    ", oak/lava=" + vectors.get(0).dot(vectors.get(2)) + " (not a corpus-quality claim)");
        }
        sharedDiscoveryLifecycle();
    }

    /** Real shared server lifecycle and production landmark composition, not an injected model.
     * Queue/navigation policies are exercised without bootstrapping a Minecraft world; only
     * actual source ownership (not simulated here) may authorize semantic navigation.
     */
    private static void sharedDiscoveryLifecycle() throws Exception {
        var descriptors = List.of(
                new DescriptorVectors.WeightedDescriptor(
                        CanonicalDescriptors.region("minecraft:overworld", "minecraft:dripstone_caves"), 2),
                new DescriptorVectors.WeightedDescriptor(
                        "source landmark cave connected underground air and rock walls, unknown frontiers", 2),
                new DescriptorVectors.WeightedDescriptor("source material item minecraft:stone", 3),
                new DescriptorVectors.WeightedDescriptor("source material item minecraft:water", 1));
        var queue = new EmbeddingWorkQueue<String>(4);
        var discovery = new CompletableFuture<Void>();
        var physical = new CompletableFuture<Void>();
        EmbeddingHelper.shutdown();
        try {
            EmbeddingHelper.initializeServer(2);
            EmbeddingReadiness starting = EmbeddingHelper.state();
            check(!starting.unavailable(), "Default shared provider failed at initialization: " + starting.description());
            queue.offer("discovery", true, false, discovery, discovery::completeExceptionally, starting);
            queue.offer("physical", false, true, physical, physical::completeExceptionally, starting);
            check("physical".equals(queue.poll(starting)), "Physical source reads cannot wait for model initialization");
            if (!starting.ready()) check(queue.poll(starting) == null, "New discovery admitted before real readiness");
            // Stage before waiting; like real discovery, infer only after readiness succeeds.
            var composed = EmbeddingHelper.readiness()
                    .thenCompose(ignored -> EmbeddingHelper.composeDescriptors(descriptors));
            EmbeddingHelper.readiness().get(3, TimeUnit.MINUTES);
            check(EmbeddingHelper.isReady(), "Shared readiness did not publish successful native inference");
            check("discovery".equals(queue.poll(EmbeddingHelper.state())), "Real readiness did not release discovery work");
            var unanchored = new DiscoveryReadiness(false, EmbeddingHelper.state());
            check(!unanchored.navigationReady() && !unanchored.terminallyBlocked(),
                    "A ready real model must allow discovery but cannot invent a source navigation anchor");
            Vec384f pooled = composed.get(1, TimeUnit.MINUTES);
            EmbeddingSpace.requireCurrent(pooled);
            check(pooled.data().length == 768 && Math.abs(pooled.length() - 1) < 1e-5, "Shared composition lost native profile/norm");
            // Independent double-precision weighted/L2 reference for every real coordinate.
            // Do not use the production normalization/composition helper as its own oracle.
            double[] sums = new double[768];
            for (var part : descriptors) {
                float[] values = EmbeddingHelper.getEmbedding(part.descriptor()).get(30, TimeUnit.SECONDS).data();
                double squared = 0;
                for (float value : values) squared += (double) value * value;
                double norm = Math.sqrt(squared);
                for (int i = 0; i < sums.length; i++) sums[i] += values[i] / norm * part.weight();
            }
            double squared = 0;
            for (double value : sums) squared += value * value;
            double norm = Math.sqrt(squared);
            float[] coordinates = pooled.data();
            for (int i = 0; i < sums.length; i++)
                check(Math.abs(coordinates[i] - sums[i] / norm) < 1e-7, "Real composed landmark coordinate mismatch: " + i);
            pooled.mul(0);
            Vec384f repeated = EmbeddingHelper.composeDescriptors(descriptors).get(30, TimeUnit.SECONDS);
            check(repeated.length() > .99 && repeated.data()[767] != 0, "Shared landmark/cache storage aliases caller result");
            check(EmbeddingHelper.isReady(), "Successful real landmark work lost discovery readiness");
            System.out.println("REAL shared EmbeddingHelper readiness -> discovery admission -> cave landmark composition passed; " +
                    "all 768 coordinates checked against independent weighted/L2 reference; no HTTP service or source anchor fabricated");
        } finally {
            EmbeddingHelper.shutdown();
        }
        check(EmbeddingHelper.state().phase() == EmbeddingReadiness.Phase.STOPPED, "Shared shutdown retained stale readiness");
        check(new DiscoveryReadiness(false, EmbeddingHelper.state()).terminallyBlocked(), "Stopped shared model admits unanchored navigation");
        var afterStop = new CompletableFuture<Void>();
        queue.offer("stopped-discovery", true, false, afterStop, afterStop::completeExceptionally, EmbeddingHelper.state());
        check(afterStop.isCompletedExceptionally(), "Stopped shared model admits fresh discovery");
        try {
            EmbeddingHelper.getEmbedding(descriptors.getFirst().descriptor()).get(5, TimeUnit.SECONDS);
            throw new AssertionError("Stopped shared model served a previously cached landmark");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IllegalStateException, "Unexpected shutdown error: " + expected);
        }
        System.out.println("REAL shared lifecycle shutdown revoked fresh discovery/inference readiness (not a simulated native fault)");
    }
}
