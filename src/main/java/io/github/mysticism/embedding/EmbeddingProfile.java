package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;

/** Persist fingerprint, not just dimensionality. The manifest includes model/tokenizer revision. */
public record EmbeddingProfile(String model, String revision, int dimensions, String semantics, String fingerprint) {
    public static EmbeddingProfile current() {
        return new EmbeddingProfile(EmbeddingSpace.MODEL, EmbeddingSpace.REVISION, EmbeddingSpace.DIMENSIONS, EmbeddingSpace.SEMANTICS, EmbeddingSpace.FINGERPRINT);
    }
}
