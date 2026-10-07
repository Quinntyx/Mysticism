package io.github.mysticism.client.spiritworld;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Projection384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Render-thread session placement, independent of camera motion and later CCA/predictor updates. */
final class SpiritGlyphFrame {
    private final Basis384f basis;
    private final Vec384f origin;
    private final Vec3d anchor;
    private final Map<String, Vec3d> positions = new HashMap<>();

    SpiritGlyphFrame(Basis384f liveBasis, Vec384f liveOrigin, Vec3d realmAnchor) {
        EmbeddingSpace.requireCurrent(liveOrigin);
        EmbeddingSpace.requireCurrent(liveBasis.i);
        EmbeddingSpace.requireCurrent(liveBasis.j);
        EmbeddingSpace.requireCurrent(liveBasis.k);
        requireFinite(realmAnchor);
        basis = liveBasis.clone();
        origin = liveOrigin.clone();
        anchor = realmAnchor;
    }

    Vec3d position(String id, Vec384f embedding) {
        // Validate even a retained ID: a new incompatible payload must never inherit cached placement.
        EmbeddingSpace.requireCurrent(embedding);
        Vec3d retained = positions.get(id);
        if (retained != null) return retained;
        if (positions.size() >= SpiritRenderSettings.MAX_GLYPHS)
            throw new IllegalArgumentException("Glyph placement budget exceeded");
        Vec3d projected = Projection384f.projectToWorld(embedding, origin, basis, anchor, 30.0f);
        requireFinite(projected);
        positions.put(id, projected);
        return projected;
    }

    float scale(Vec384f embedding) {
        return (float) Math.max(0.25, Math.min(1.5,
                1.0 / Math.sqrt(Math.max(0.01, embedding.squareDistance(origin)))));
    }

    void retain(Set<String> visible) {
        if (visible.size() > SpiritRenderSettings.MAX_GLYPHS)
            throw new IllegalArgumentException("Glyph visibility budget exceeded");
        positions.keySet().removeIf(id -> !visible.contains(id)); // scans <=128 retained IDs, never a world/catalog
    }

    int size() { return positions.size(); }

    private static void requireFinite(Vec3d position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Nonfinite glyph placement");
    }
}
