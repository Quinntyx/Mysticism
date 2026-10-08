package io.github.mysticism.navigation;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * One shared server/client rule for semantic movement integration: a position delta that spans a
 * prediction-epoch change (mode deep/shallow commit, semantic anchor transition, reconnect) is
 * NEVER integrated as chosen semantic movement. Both sides re-anchor instead, so the server
 * evolver and the client predictor drop exactly the same crossing tick and no per-mode-change
 * divergence is left for the periodic authoritative sync to correct (the visible rubber band).
 */
public final class MotionAlignment {
    /** Same bounded-delta guard both call sites used before this rule was shared. */
    private static final double MAX_INTEGRATED_MOVEMENT_SQUARED = 16;

    private MotionAlignment() {}

    /**
     * @param lastPos previous tracked position, or null when tracking starts (fresh visit, reconnect)
     * @param recordedEpoch epoch recorded with lastPos, or null when tracking starts
     * @param currentEpoch the player's current navigation motionEpoch
     * @return the delta to integrate this tick; ZERO means re-anchor without semantic movement
     */
    public static Vec3d alignedDelta(@Nullable Vec3d lastPos, Vec3d nowPos,
                                     @Nullable Long recordedEpoch, long currentEpoch) {
        if (lastPos == null || recordedEpoch == null || recordedEpoch != currentEpoch) return Vec3d.ZERO;
        Vec3d delta = nowPos.subtract(lastPos);
        if (!Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)
                || delta.lengthSquared() > MAX_INTEGRATED_MOVEMENT_SQUARED) return Vec3d.ZERO;
        return delta;
    }
}
