package io.github.mysticism.navigation;

import net.minecraft.util.math.Vec3d;

/** Packet acceptance after Entity.move has resolved the observer's visible mesh.
 * Vanilla's carrier-block tests cannot authorize overwriting this swept result: vertical
 * residuals are discarded and horizontal residuals below .25 blocks are normally accepted.
 * Keep even sub-correction-epsilon clipping; the epsilon limits packets, not collision. */
public final class SpiritMovementAcceptance {
    private SpiritMovementAcceptance() {}
    private static final double CORRECTION_DISTANCE_SQUARED = 1e-8;
    public record Accepted(Vec3d position, boolean correctionRequired) {}

    public static Accepted accept(Vec3d requested, Vec3d resolved) {
        return new Accepted(resolved, requested.squaredDistanceTo(resolved) > CORRECTION_DISTANCE_SQUARED);
    }
}
