package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.EmbeddingProfile;
import io.github.mysticism.landmark.LandmarkEmbedding;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;

/** Exact bridge to the pinned, native-768 -> first-256 Nomic document space. */
public final class LandmarkProfiles {
    private LandmarkProfiles() {}
    public static EmbeddingProfile current() {
        var p = io.github.mysticism.embedding.EmbeddingProfile.current();
        return new EmbeddingProfile(p.model(), p.revision(), "model-manifest:" + p.revision(),
                p.semantics(), p.dimensions(), EmbeddingProfile.Normalization.UNIT, p.fingerprint());
    }
    public static LandmarkEmbedding wrap(Vec384f vector) {
        EmbeddingSpace.requireCurrent(vector);
        return new LandmarkEmbedding(current(), vector);
    }
}
