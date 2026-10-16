package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.util.Arrays;
import java.util.List;

/** Numeric composition fixtures labelled with production text, not semantic model inference. */
public final class LandmarkEmbeddingPoolingTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Vec384f axes(float first, float last) {
        float[] values = new float[768]; values[0] = first; values[767] = last;
        return new Vec384f(values);
    }
    private static DescriptorVectors.WeightedVector part(Vec384f vector, double weight) {
        return new DescriptorVectors.WeightedVector(vector, weight);
    }
    private static void near(float actual, double expected) {
        check(Math.abs(actual - expected) < 1e-7, "Expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable operation) {
        checks++;
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected exact zero/invalid composition rejection");
    }
    public static void main(String[] args) {
        // The numerical output contract should not depend on descriptor length or language.
        var texts = List.of(CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs")),
                CanonicalDescriptors.biome("minecraft:lush_caves", List.of("minecraft:is_overworld")),
                "cave minecraft:lush_caves. moss clay water glow berries. cueva 洞窟 Höhle 🌳");
        for (String text : texts) check(!text.isBlank(), "Production landmark text fixture");
        Vec384f a = axes(1, 0), b = axes(0, 1);
        // Equivalent weighted distributions, including finite totals that overflow naïve summation.
        int[] expected = DescriptorVectors.compose(List.of(part(a, 3), part(b, 4))).toBits();
        for (double scale : new double[]{Double.MIN_VALUE, 1e-300, 1, 1e300, Double.MAX_VALUE / 4}) {
            Vec384f output = DescriptorVectors.compose(List.of(part(a, 3 * scale), part(b, 4 * scale)));
            check(Arrays.equals(output.toBits(), expected), "Common weight scaling changed native output: " + scale);
            near(output.data()[0], .6); near(output.data()[767], .8);
            near(output.length(), 1);
        }
        // Do not reject a nonzero mean simply because it is very small before L2.
        Vec384f almostOpposite = axes(-1, Float.MIN_VALUE);
        Vec384f tinyResidual = DescriptorVectors.compose(List.of(part(a, 1), part(almostOpposite, 1)));
        near(tinyResidual.data()[0], 0); near(tinyResidual.data()[767], 1);
        // Keep low-weight evidence during cancellation of large opposite contributors.
        for (List<DescriptorVectors.WeightedVector> order : List.of(
                List.of(part(a, 1), part(b, 1e-100), part(axes(-1, 0), 1)),
                List.of(part(axes(-1, 0), 1), part(a, 1), part(b, 1e-100)))) {
            Vec384f residual = DescriptorVectors.compose(order);
            near(residual.data()[0], 0); near(residual.data()[767], 1);
        }
        // Compensation must preserve small contributors in the SAME coordinate as
        // large opposite inputs, regardless of deterministic descriptor iteration order.
        for (List<DescriptorVectors.WeightedVector> order : List.of(
                List.of(part(a, 1), part(axes(1, 1), 1e-100), part(axes(-1, 0), 1)),
                List.of(part(axes(-1, 0), 1), part(a, 1), part(axes(1, 1), 1e-100)))) {
            Vec384f residual = DescriptorVectors.compose(order);
            near(residual.data()[0], 1 / Math.sqrt(2)); near(residual.data()[767], 1 / Math.sqrt(2));
        }
        // Independently computed full-coordinate reference, rather than only axis fixtures.
        var random = new java.util.Random(768123L);
        var parts = new java.util.ArrayList<DescriptorVectors.WeightedVector>();
        double[] mean = new double[768];
        for (int j = 0; j < 29; j++) {
            float[] values = new float[768]; double normSquared = 0;
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextFloat() - .5f; normSquared += (double) values[i] * values[i];
            }
            double norm = Math.sqrt(normSquared), weight = 1 + j;
            // The contract normalizes each constituent to an owned float sentence vector.
            for (int i = 0; i < values.length; i++) mean[i] += (float) (values[i] / norm) * weight;
            parts.add(part(new Vec384f(values), weight));
        }
        double squared = 0; for (double value : mean) squared += value * value;
        double length = Math.sqrt(squared);
        Vec384f combined = DescriptorVectors.compose(parts);
        for (int i = 0; i < 768; i++) near(combined.data()[i], mean[i] / length);
        int[] snapshot = combined.toBits(); combined.mul(0);
        check(Arrays.equals(snapshot, DescriptorVectors.compose(parts).toBits()), "Output aliases composition inputs");
        check(a.data()[0] == 1 && b.data()[767] == 1, "Caller vectors mutated");
        rejects(() -> DescriptorVectors.compose(List.of()));
        rejects(() -> DescriptorVectors.compose(List.of(part(a, 0))));
        rejects(() -> DescriptorVectors.compose(List.of(part(Vec384f.ZERO(), 1))));
        rejects(() -> DescriptorVectors.compose(List.of(part(a, 1), part(axes(-1, 0), 1))));
        // Zero-weight empty observations have no semantic contribution.
        near(DescriptorVectors.compose(List.of(part(Vec384f.ZERO(), 0), part(b, 1))).data()[767], 1);
        System.out.println("LandmarkEmbeddingPoolingTest passed: " + checks + " checks (numeric fixtures, not model inference)");
    }
}
