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
        if (args.length != 2) throw new IllegalArgumentException("Usage: EmbeddedInferenceProbe <exact-GGUF-path> <provision:true|false>");
        Path base = Path.of("build/embedding-inference-probe").toAbsolutePath(); Files.createDirectories(base);
        System.setProperty("net.ladenthin.llama.tmpdir", Files.createTempDirectory(base, "jni-").toString());
        Path path = Path.of(args[0]).toAbsolutePath();
        try (var pinned = new PinnedEmbeddingModel(path, PinnedEmbeddingModel.SOURCE, PinnedEmbeddingModel.SHA256,
                PinnedEmbeddingModel.BYTES, Boolean.parseBoolean(args[1]), Duration.ofMinutes(10))) {
            System.out.println("Verifying/provisioning exact v2 GGUF: " + path);
            pinned.resolve();
        }
        System.setProperty("mysticism.embedding.provider", "embedded");
        System.setProperty("mysticism.embedding.modelPath", path.toString());
        // Endpoint deliberately unusable; it must have no effect on the default local path.
        System.setProperty("mysticism.embedding.endpoint", "http://127.0.0.1:1/");
        try (var model = new LlamaEmbeddingModel(path, () -> false)) {
            check(model.dimensions() == 768, "Wrong native model dimension");
            String shortInput = "search_document: oak log";
            check(model.tokens(shortInput) > 0 && model.tokens(shortInput) < 512, "Real GGUF tokenizer unavailable");
            check(model.tokens("search_document: " + "oak ".repeat(600)) > 512, "Tokenizer cannot detect overflow");
            Vec384f a = EmbeddedEmbeddingService.decodeEmbedding(model.embed(shortInput));
            Vec384f b = EmbeddedEmbeddingService.decodeEmbedding(model.embed(shortInput));
            check(a.dot(b) > .99999f, "Repeated native inference unstable");
            check(a.data()[767] != 0, "Native tail missing");
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
    }
}
