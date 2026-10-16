package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import net.minecraft.util.math.Vec3d;

/** Packet acceptance after Entity.move has resolved the observer's visible mesh.
 * Vanilla's carrier-block tests cannot authorize overwriting this swept result: vertical
 * residuals are discarded and horizontal residuals below .25 blocks are normally accepted.
 * Keep even sub-correction-epsilon clipping; the epsilon limits packets, not collision. */
public final class SpiritMovementAcceptance {
    private SpiritMovementAcceptance() {}
    private static final double CORRECTION_DISTANCE_SQUARED = 1e-8;
    public record Accepted(Vec3d position, boolean correctionRequired) {}

    /** A validated published-frame replay supersedes the provisional current-frame sweep. Match
     * the exact packet; absent/rejected/foreign decisions never bypass ordinary swept acceptance. */
    public static Accepted accept(Vec3d requested, Vec3d resolved, MeshMovementValidation.Decision decision) {
        return accept(requested, decision!=null && decision.replays(requested) ? decision.replayedFeet() : resolved);
    }

    public static Accepted accept(Vec3d requested, Vec3d resolved) {
        return new Accepted(resolved, requested.squaredDistanceTo(resolved) > CORRECTION_DISTANCE_SQUARED);
    }
}
