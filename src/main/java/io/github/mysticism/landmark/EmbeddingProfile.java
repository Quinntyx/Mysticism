package io.github.mysticism.landmark;

import java.util.Objects;
import io.github.mysticism.vector.Vec384f;

/** Exact embedding compatibility key. No fallback model or implicit dimension migration. */
public record EmbeddingProfile(String model, String revision, String tokenizer, String prefixPolicy,
                               int dimensions, Normalization normalization, String descriptorSchema) {
    public enum Normalization { NONE, UNIT }
    public EmbeddingProfile {
        for (String s : new String[]{model,revision,tokenizer,prefixPolicy,descriptorSchema})
            if (s == null || s.isBlank()) throw new IllegalArgumentException("incomplete embedding profile");
        Objects.requireNonNull(normalization);
        if (dimensions != Vec384f.ZERO().data().length) throw new IllegalArgumentException("unsupported vector dimension");
    }
    public void requireCompatible(EmbeddingProfile other) {
        if (!equals(other)) throw new IllegalArgumentException("mixed embedding profiles");
    }
}
