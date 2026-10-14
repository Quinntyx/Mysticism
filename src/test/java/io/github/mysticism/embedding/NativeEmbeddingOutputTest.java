package io.github.mysticism.embedding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Output-math fixtures only: no tokenizer, model weights, network or game launch. */
public final class NativeEmbeddingOutputTest {
    private static final int D = 768;
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected) {
        check(Math.abs(actual - expected) < 1e-6, "Expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected malformed/zero output rejection");
    }
    private static Vec384f pool(float[] values, long... mask) {
        return NativeEmbeddingOutput.fromTensor(values, new long[]{1, mask.length, D}, mask);
    }
    private static void pooling() {
        float[] hidden = new float[4 * D];
        hidden[0] = 9; // Prompt/special-token rows remain attended, not discarded as CLS.
        hidden[D] = 3;
        hidden[2 * D + D - 1] = 16;
        Arrays.fill(hidden, 3 * D, 4 * D, Float.NaN); // Unattended padding is not semantic data.
        float[] before = hidden.clone();
        long[] mask = {1, 1, 1, 0};
        Vec384f output = pool(hidden, mask);
        near(output.data()[0], .6);
        near(output.data()[D - 1], .8);
        near(output.length(), 1);
        check(Arrays.equals(before, hidden) && Arrays.equals(mask, new long[]{1, 1, 1, 0}), "Inputs remain owned by runtime");
        check(output.fingerprint().equals(EmbeddingSpace.FINGERPRINT), "Fresh native v2 provenance");
        output.data()[0] = 100;
        near(output.data()[0], .6);
        var landmark = LandmarkProfiles.wrap(output);
        check(landmark.profile().dimensions() == D, "Landmarks retain native profile");

        // Mean raw states FIRST, then normalize. Per-token normalization gives the wrong angle.
        float[] unequal = new float[2 * D];
        unequal[0] = 3; unequal[D + D - 1] = 4;
        near(pool(unequal, 1, 1).data()[0], .6);
        near(pool(unequal, 1, 1).data()[D - 1], .8);

        float[] cancelled = new float[3 * D];
        cancelled[0] = Float.MAX_VALUE; cancelled[D] = 3; cancelled[2 * D] = -Float.MAX_VALUE;
        cancelled[D + D - 1] = 4;
        near(pool(cancelled, 1, 1, 1).data()[0], .6);
        near(pool(cancelled, 1, 1, 1).data()[D - 1], .8);

        float[] maximum = new float[512 * D]; Arrays.fill(maximum, Float.MAX_VALUE);
        near(pool(maximum, ones(512)).data()[767], 1 / Math.sqrt(D));
    }
    private static long[] ones(int count) {
        long[] result = new long[count]; Arrays.fill(result, 1); return result;
    }
    private static void shapesAndFailures() {
        float[] values = new float[D]; values[D - 1] = 2;
        near(NativeEmbeddingOutput.fromTensor(values, new long[]{1, D}, null).data()[D - 1], 1);
        near(pool(values, 1).data()[D - 1], 1);
        rejects(() -> NativeEmbeddingOutput.fromTensor(values, new long[]{1, D}, new long[]{1}));
        for (long[] shape : List.of(new long[]{D}, new long[]{2, D}, new long[]{D, 1},
                new long[]{1, 1, 256}, new long[]{1, 1, 384}, new long[]{2, 1, D},
                new long[]{1, 0, D}, new long[]{1, 513, D}, new long[]{1, Long.MAX_VALUE, D},
                new long[]{1, 1, D, 1})) {
            rejects(() -> NativeEmbeddingOutput.fromTensor(values, shape, new long[]{1}));
        }
        rejects(() -> NativeEmbeddingOutput.fromTensor(values, null, null));
        rejects(() -> NativeEmbeddingOutput.fromTensor(null, new long[]{1, D}, null));
        rejects(() -> NativeEmbeddingOutput.fromTensor(values, new long[]{1, 1, D}, null));
        rejects(() -> NativeEmbeddingOutput.fromTensor(values, new long[]{1, 1, D}, new long[0]));
        rejects(() -> pool(new float[D - 1], 1));
        rejects(() -> pool(new float[2 * D], 1));
        for (long invalid : new long[]{-1, 2, Long.MAX_VALUE}) rejects(() -> pool(values, invalid));
        rejects(() -> pool(values, 0));
        rejects(() -> pool(new float[D], 1));
        float[] cancellation = new float[2 * D]; cancellation[0] = 1; cancellation[D] = -1;
        rejects(() -> pool(cancellation, 1, 1));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] bad = values.clone(); bad[767] = invalid;
            rejects(() -> pool(bad, 1));
            rejects(() -> NativeEmbeddingOutput.normalize(bad));
        }
        rejects(() -> NativeEmbeddingOutput.normalize(new float[256]));
        rejects(() -> NativeEmbeddingOutput.normalize(new double[384]));
        rejects(() -> NativeEmbeddingOutput.normalize(new double[D]));
    }
    private static void normalization() {
        for (double scale : new double[]{Double.MIN_VALUE, 1e-300, 1e-40, 1, 1e30, Double.MAX_VALUE / 4}) {
            double[] input = new double[D]; input[0] = 3 * scale; input[767] = -4 * scale;
            Vec384f result = NativeEmbeddingOutput.normalize(input);
            near(result.data()[0], .6); near(result.data()[767], -.8); near(result.length(), 1);
            check(input[0] == 3 * scale && input[767] == -4 * scale, "No mutation during normalization");
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double[] bad = new double[D]; bad[767] = invalid;
            rejects(() -> NativeEmbeddingOutput.normalize(bad));
        }
        float[] tiny = new float[D]; tiny[767] = Float.MIN_VALUE;
        near(NativeEmbeddingOutput.normalize(tiny).data()[767], 1);
        float[] huge = new float[D]; Arrays.fill(huge, Float.MAX_VALUE);
        near(NativeEmbeddingOutput.normalize(huge).data()[767], 1 / Math.sqrt(D));

        // Active HTTP decoder must not round a finite nonzero vector to zero BEFORE L2.
        JsonArray coordinates = new JsonArray();
        for (int i = 0; i < D; i++) coordinates.add(i == 0 ? 3e-300 : i == 767 ? 4e-300 : 0);
        JsonArray batch = new JsonArray(); batch.add(coordinates);
        JsonObject response = new JsonObject(); response.add("embeddings", batch);
        near(EmbeddingService.decodeEmbedding(response).data()[0], .6);
        near(EmbeddingService.decodeEmbedding(response).data()[767], .8);
    }
    private static void landmarkTextPaddingFixtures() {
        // Production descriptor text as fixture labels; token rows below are synthetic.
        // This checks output packing/padding invariance, NOT tokenization or model quality.
        List<String> descriptions = List.of(
                CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs", "minecraft:burnable")),
                CanonicalDescriptors.biome("minecraft:dripstone_caves", List.of("minecraft:is_overworld")),
                CanonicalDescriptors.region("minecraft:the_nether", "minecraft:basalt_deltas", List.of()),
                "cave minecraft:lush_caves. moss clay water glow berries. cueva 洞窟 Höhle");
        int[] lengths = {8, 24, 64, 511};
        for (int fixture = 0; fixture < descriptions.size(); fixture++) {
            check(!descriptions.get(fixture).isBlank(), "Nonempty real landmark text label");
            int tokens = lengths[fixture];
            float[] hidden = new float[tokens * D];
            for (int token = 0; token < tokens; token++) {
                hidden[token * D] = token % 2 == 0 ? 3 : -1;
                hidden[token * D + 767] = 4;
            }
            Vec384f alone = pool(hidden, ones(tokens));
            for (int offset : new int[]{0, 512 - tokens}) {
                float[] padded = new float[512 * D]; Arrays.fill(padded, Float.POSITIVE_INFINITY);
                System.arraycopy(hidden, 0, padded, offset * D, hidden.length);
                long[] mask = new long[512]; Arrays.fill(mask, offset, offset + tokens, 1);
                Vec384f together = pool(padded, mask);
                check(Arrays.equals(alone.toBits(), together.toBits()), "Left/right padding independent of other landmark lengths");
            }
            near(alone.length(), 1);
            check(alone.data()[767] > .9, "Native tail remains in real-text-sized outputs");
        }
    }
    private static void referenceAgreement() {
        var random = new java.util.Random(0x768512L);
        for (int tokens : new int[]{1, 7, 128, 512}) {
            float[] hidden = new float[tokens * D];
            long[] mask = new long[tokens];
            double[] mean = new double[D];
            int attended = 0;
            for (int token = 0; token < tokens; token++) {
                mask[token] = token % 4 == 3 ? 0 : 1;
                attended += (int) mask[token];
                for (int dimension = 0; dimension < D; dimension++) {
                    float value = (random.nextFloat() - .5f) * 20;
                    hidden[token * D + dimension] = value;
                    if (mask[token] == 1) mean[dimension] += value;
                }
            }
            double squared = 0;
            for (int dimension = 0; dimension < D; dimension++) {
                mean[dimension] /= attended;
                squared += mean[dimension] * mean[dimension];
            }
            double length = Math.sqrt(squared);
            float[] actual = pool(hidden, mask).data();
            double largestError = 0;
            for (int dimension = 0; dimension < D; dimension++)
                largestError = Math.max(largestError, Math.abs(actual[dimension] - mean[dimension] / length));
            check(largestError < 1e-7, "All native coordinates agree with independent masked-mean/L2 reference");
        }
    }
    private static void asyncAndComposition() throws Exception {
        float[] hidden = new float[2 * D]; hidden[0] = 3; hidden[D + 767] = 4;
        EmbeddingProvider provider = new EmbeddingProvider() {
            public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
            public void checkReady() {}
            public Vec384f getEmbedding(String descriptor) { return pool(hidden, 1, 1); }
            public void close() {}
        };
        try (var engine = new AsyncEmbeddingEngine(provider, 1, 4, 4, Duration.ofSeconds(2))) {
            engine.readiness().get(2, TimeUnit.SECONDS);
            String descriptor = CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs"));
            Vec384f first = engine.embed(descriptor).get(2, TimeUnit.SECONDS); first.mul(0);
            Vec384f cached = engine.embed(descriptor).get(2, TimeUnit.SECONDS);
            near(cached.data()[0], .6); near(cached.data()[767], .8);
            Vec384f composed = DescriptorVectors.compose(List.of(new DescriptorVectors.WeightedVector(cached, 4)));
            near(composed.data()[767], .8); near(composed.length(), 1);
            check(engine.cacheSize() == 1, "Pooled result cached without aliases");
        }
    }
    public static void main(String[] args) throws Exception {
        check(EmbeddingSpace.DIMENSIONS == D && EmbeddingSpace.NATIVE_DIMENSIONS == D, "Fresh native v2 only");
        pooling(); shapesAndFailures(); normalization(); landmarkTextPaddingFixtures(); referenceAgreement(); asyncAndComposition();
        System.out.println("NativeEmbeddingOutputTest passed: " + checks + " checks (synthetic tensors; no model inference)");
    }
}
