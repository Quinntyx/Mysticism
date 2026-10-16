package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;

/** Nomic v2 output math, independent of the native inference library.
 * No CLS selection, layer normalization, dimension reduction or legacy migration.
 */
public final class NativeEmbeddingOutput {
    public static final int MAX_TOKENS = 512;

    private NativeEmbeddingOutput() {}

    /** Decode one explicitly shaped, row-major output tensor. A local runtime must
     * select last_hidden_state [1,tokens,768] OR sentence_embedding [1,768], not
     * the first arbitrarily named output. Hidden states require the tokenizer's
     * exact binary attention mask (including prompt and special tokens).
     * Already pooled sentence outputs must use a null mask: never pool twice.
     */
    public static Vec384f fromTensor(float[] values, long[] shape, long[] attentionMask) {
        requireNativeSpace();
        if (values == null || shape == null) throw new IllegalArgumentException("Missing model output tensor");
        if (shape.length == 2 && shape[0] == 1 && shape[1] == EmbeddingSpace.NATIVE_DIMENSIONS) {
            if (attentionMask != null) throw new IllegalArgumentException("Sentence output must not be token-pooled again");
            return normalize(values);
        }
        if (shape.length != 3 || shape[0] != 1 || shape[2] != EmbeddingSpace.NATIVE_DIMENSIONS
                || shape[1] < 1 || shape[1] > MAX_TOKENS)
            throw new IllegalArgumentException("Expected [1,tokens,768] hidden states or [1,768] sentence output");
        int tokens = (int) shape[1];
        int dimensions = EmbeddingSpace.NATIVE_DIMENSIONS;
        if (values.length != tokens * dimensions || attentionMask == null || attentionMask.length != tokens)
            throw new IllegalArgumentException("Output and attention mask shape mismatch");
        int attended = 0;
        for (long mask : attentionMask) {
            if (mask != 0 && mask != 1) throw new IllegalArgumentException("Attention mask must be binary");
            attended += (int) mask;
        }
        if (attended == 0) throw new IllegalArgumentException("No attended tokens");
        double[] sums = new double[dimensions];
        double[] compensation = new double[dimensions];
        for (int token = 0; token < tokens; token++) {
            // Padding cannot affect the result, even when its unused output is nonfinite.
            if (attentionMask[token] == 0) continue;
            for (int dimension = 0; dimension < dimensions; dimension++) {
                float value = values[token * dimensions + dimension];
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite attended hidden state");
                double previous = sums[dimension];
                double next = previous + value;
                compensation[dimension] += Math.abs(previous) >= Math.abs(value)
                        ? (previous - next) + value : (value - next) + previous;
                sums[dimension] = next;
            }
        }
        for (int i = 0; i < dimensions; i++) sums[i] = (sums[i] + compensation[i]) / attended;
        return normalize(sums);
    }

    /** Normalize a native sentence vector without a float cast before normalization. */
    public static Vec384f normalize(double[] values) {
        requireNativeSpace();
        if (values == null || values.length != EmbeddingSpace.NATIVE_DIMENSIONS)
            throw new IllegalArgumentException("Expected all 768 native coordinates");
        double scale = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite embedding");
            scale = Math.max(scale, Math.abs(value));
        }
        if (scale == 0) throw new IllegalArgumentException("Zero embedding");
        // Scaling first avoids squaring overflow/underflow for finite native outputs.
        double squaredLength = 0;
        for (double value : values) {
            double scaled = value / scale;
            squaredLength += scaled * scaled;
        }
        double length = Math.sqrt(squaredLength);
        float[] normalized = new float[values.length];
        for (int i = 0; i < normalized.length; i++) normalized[i] = (float) ((values[i] / scale) / length);
        return new Vec384f(normalized);
    }

    public static Vec384f normalize(float[] values) {
        if (values == null || values.length != EmbeddingSpace.NATIVE_DIMENSIONS)
            throw new IllegalArgumentException("Expected all 768 native coordinates");
        double[] precise = new double[values.length];
        for (int i = 0; i < values.length; i++) precise[i] = values[i];
        return normalize(precise);
    }

    private static void requireNativeSpace() {
        if (EmbeddingSpace.DIMENSIONS != EmbeddingSpace.NATIVE_DIMENSIONS || EmbeddingSpace.NATIVE_DIMENSIONS != 768)
            throw new IllegalStateException("Native Nomic v2 space must be active");
    }
}
