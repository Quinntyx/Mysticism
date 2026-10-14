package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Provider/output integration fixtures. No weights, JNI forward pass or substitute runtime model. */
public final class EmbeddedNativeOutputTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        checks++;
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected explicit invalid inference rejection");
    }
    private static final class Model implements EmbeddedEmbeddingService.Model {
        float[] output = coordinates(1);
        int tokenCount = 17, inferenceCalls;
        String tokenInput, inferenceInput;
        public int dimensions() { return 768; }
        public int tokens(String input) { tokenInput = input; return tokenCount; }
        public float[] embed(String input) { inferenceCalls++; inferenceInput = input; return output; }
        public void close() {}
    }
    private static EmbeddedEmbeddingService provider(Model model) {
        return new EmbeddedEmbeddingService(new EmbeddedEmbeddingService.Asset() {
            public Path resolve() { return Path.of("test-fixture-not-a-model.gguf"); }
            public void close() {}
        }, (path, cancelled) -> model);
    }
    private static float[] coordinates(float scale) {
        float[] values = new float[768];
        for (int i = 0; i < values.length; i++) values[i] = ((i % 13) - 6) * scale;
        return values;
    }
    private static void fullOutput() throws Exception {
        Model model = new Model();
        try (var provider = provider(model)) {
            provider.checkReady();
            for (float scale : new float[]{Float.MIN_VALUE, Float.MIN_NORMAL, 1, Float.MAX_VALUE / 8}) {
                model.output = coordinates(scale);
                float[] snapshot = model.output.clone();
                Vec384f actual = provider.getEmbedding("cave moss clay water 洞窟");
                double squared = 0;
                for (float value : snapshot) squared += (double) value * value;
                double length = Math.sqrt(squared);
                float[] coordinates = actual.data();
                for (int i = 0; i < snapshot.length; i++)
                    check(Math.abs(coordinates[i] - snapshot[i] / length) < 1e-7,
                            "Native coordinate lost or altered at " + i + " scale " + scale);
                check(actual.fingerprint().equals(EmbeddingSpace.FINGERPRINT), "Wrong profile");
                check(Math.abs(actual.length() - 1) < 1e-6, "Non-unit output");
                check(Arrays.equals(snapshot, model.output), "Native output mutated");
                check(model.tokenInput.equals("search_document: cave moss clay water 洞窟") &&
                        model.tokenInput.equals(model.inferenceInput), "Tokenizer and inference must use identical prefixed input");
                actual.mul(0);
                check(Arrays.equals(snapshot, model.output), "Result aliases the runtime array");
            }
            model.tokenCount = 512;
            provider.getEmbedding("exact limit");
            for (int count : new int[]{0, -1, 513, Integer.MAX_VALUE}) {
                model.tokenCount = count;
                int before = model.inferenceCalls;
                rejects(() -> provider.getEmbedding("invalid token count"));
                check(model.inferenceCalls == before, "Invalid token count reached inference/truncation");
            }
            model.tokenCount = 17;
            for (String invalid : new String[]{"unpaired \uD800", "unpaired \uDC00"}) {
                int before = model.inferenceCalls;
                rejects(() -> provider.getEmbedding(invalid));
                check(model.inferenceCalls == before, "Invalid UTF-16 was silently replaced");
            }
            provider.getEmbedding("valid supplementary \uD83C\uDF33");
        }
    }
    private static void tokenizerResponse() throws Exception {
        check(LlamaEmbeddingModel.decodeTokenCount("{\"tokens\":[0,101,2147483647]}") == 3, "Valid token IDs rejected");
        for (String response : List.of("null", "[]", "{}", "{\"tokens\":[]}", "{\"tokens\":null}",
                "{\"tokens\":[null]}", "{\"tokens\":[true]}", "{\"tokens\":[\"101\"]}",
                "{\"tokens\":[-1]}", "{\"tokens\":[1.5]}", "{\"tokens\":[2147483648]}", "broken"))
            rejects(() -> LlamaEmbeddingModel.decodeTokenCount(response));
    }
    private static void noInvalidPublication() throws Exception {
        Model model = new Model();
        try (var provider = provider(model);
             var engine = new AsyncEmbeddingEngine(provider, 1, 8, 8, Duration.ofSeconds(2))) {
            engine.readiness().get(2, TimeUnit.SECONDS);
            float[] nan = coordinates(1); nan[767] = Float.NaN;
            float[] infinite = coordinates(1); infinite[767] = Float.POSITIVE_INFINITY;
            for (float[] invalid : new float[][]{new float[256], new float[384], new float[767],
                    new float[769], new float[768], nan, infinite, null}) {
                model.output = invalid;
                try {
                    engine.embed("same descriptor").get(2, TimeUnit.SECONDS);
                    throw new AssertionError("Invalid native output published/cached");
                } catch (ExecutionException expected) {
                    check(expected.getCause() instanceof IllegalArgumentException, "Wrong failure: " + expected.getCause());
                }
                check(engine.cacheSize() == 0 && engine.inflightSize() == 0, "Invalid output left a cache/pending entry");
            }
            model.output = coordinates(1);
            Vec384f vector = engine.embed("same descriptor").get(2, TimeUnit.SECONDS);
            check(vector.data().length == 768 && vector.data()[767] != 0, "Recovery truncated native tail");
            check(engine.cacheSize() == 1, "Successful inference not cached");
            Vec384f composed = DescriptorVectors.compose(List.of(new DescriptorVectors.WeightedVector(vector, 1)));
            check(composed.data().length == 768 && Math.abs(composed.dot(vector) - 1) < 1e-6, "Landmark composition changed native output");
            vector.mul(0);
            check(engine.embed("same descriptor").get(2, TimeUnit.SECONDS).length() > .99f, "Mutable cache alias");
        }
        Model zero = new Model(); zero.output = new float[768];
        try (var provider = provider(zero);
             var engine = new AsyncEmbeddingEngine(provider, 1, 8, 8, Duration.ofSeconds(2))) {
            try { engine.readiness().get(2, TimeUnit.SECONDS); throw new AssertionError("Zero readiness output accepted"); }
            catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalArgumentException, "Wrong readiness error"); }
            check(!engine.isReady() && engine.cacheSize() == 0, "Failed native readiness exposed success/placeholder");
        }
    }
    public static void main(String[] args) throws Exception {
        fullOutput(); tokenizerResponse(); noInvalidPublication();
        System.out.println("EmbeddedNativeOutputTest passed: " + checks + " checks (provider fixtures, not real model inference)");
    }
}
