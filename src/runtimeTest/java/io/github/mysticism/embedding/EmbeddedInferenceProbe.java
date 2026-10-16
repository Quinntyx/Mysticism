package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
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
        System.out.println("CPU platform preflight passed: " + EmbeddedNativeRuntime.prepare());
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
        System.clearProperty("mysticism.embedding.provider"); // exercise normal default, not an opt-in override
        if (args[0].isEmpty()) System.clearProperty("mysticism.embedding.modelPath");
        else System.setProperty("mysticism.embedding.modelPath", path.toString());
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
        helperCacheRoundTrip(base);
    }

    /** Real native preparation in each generation; disk hits do not authorize a dead model. */
    private static void helperCacheRoundTrip(Path base) throws Exception {
        Path cache = Files.createTempDirectory(base, "helper-cache-").resolve("native-v2.bin");
        System.setProperty("mysticism.embedding.cacheFile", cache.toString());
        System.setProperty("mysticism.embedding.preparationDeadlineMillis", "180000");
        ExecutorService client = Executors.newSingleThreadExecutor(r -> new Thread(r, "Mysticism-ProbeClient"));
        List<String> descriptors = List.of(
                CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs")),
                CanonicalDescriptors.biome("minecraft:lush_caves", List.of("minecraft:is_overworld")));
        List<DescriptorVectors.WeightedDescriptor> weighted = List.of(
                new DescriptorVectors.WeightedDescriptor(descriptors.get(0), 3),
                new DescriptorVectors.WeightedDescriptor(descriptors.get(1), 4));
        List<Vec384f> fresh = new ArrayList<>();
        try {
            for (int generation = 0; generation < 2; generation++) {
                long start = System.nanoTime();
                EmbeddingHelper.initializeServer(2);
                System.out.println("Default helper generation " + generation + " initialization returned in "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "ms; preparation continues on workers");
                try {
                    EmbeddingHelper.readiness().thenRunAsync(() -> {
                        check(Thread.currentThread().getName().equals("Mysticism-ProbeClient"), "Readiness did not marshal to owning executor");
                        check(EmbeddingHelper.isReady(), "Readiness callback sees unavailable provider");
                    }, client).get(3, TimeUnit.MINUTES);
                    check(EmbeddingHelper.getCacheSize() == (generation == 0 ? 0 : 2), "Cache did not restore before real readiness");
                    for (int i = 0; i < descriptors.size(); i++) {
                        var future = EmbeddingHelper.getEmbedding(descriptors.get(i));
                        if (generation == 1) check(future.isDone(), "Persisted descriptor scheduled inference instead of hitting restored cache");
                        Vec384f vector = future.get(30, TimeUnit.SECONDS);
                        check(vector.data().length == 768 && vector.data()[767] != 0, "Helper lost real native tail");
                        if (generation == 0) fresh.add(vector.clone());
                        else check(Arrays.equals(vector.toBits(), fresh.get(i).toBits()), "Persisted native coordinates changed");
                        vector.mul(0);
                    }
                    Vec384f reference = DescriptorVectors.compose(List.of(
                            new DescriptorVectors.WeightedVector(fresh.get(0), 3),
                            new DescriptorVectors.WeightedVector(fresh.get(1), 4)));
                    EmbeddingHelper.composeDescriptors(weighted).thenAcceptAsync(actual -> {
                        check(Thread.currentThread().getName().equals("Mysticism-ProbeClient"), "Composition callback ran on wrong executor");
                        for (int i = 0; i < 768; i++) check(Math.abs(actual.data()[i] - reference.data()[i]) < 1e-7,
                                "Real helper composition changed native coordinate " + i);
                    }, client).get(30, TimeUnit.SECONDS);
                } finally { EmbeddingHelper.shutdown(); }
                check(EmbeddingHelper.state().phase() == EmbeddingReadiness.Phase.STOPPED, "Helper lifecycle did not stop");
                check(EmbeddingCacheFile.read(cache, 16).size() == 2, "Helper did not persist complete native cache");
            }
            System.out.println("REAL default helper/cache restart/composition/client-executor callbacks passed; "
                    + "both generations executed native readiness, all 768 cache coordinates retained, HTTP endpoint unusable");
        } finally { EmbeddingHelper.shutdown(); client.shutdownNow(); }
    }
}
