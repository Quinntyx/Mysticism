package io.github.mysticism.navigation;

import net.minecraft.util.math.Vec3d;

/** Tick-invariant movement classification shared by the server evolver and the client predictor.
 *  Integration must be robust to tick variation and accumulated frame timing: a lag burst is still
 *  real movement and integrates in full, while teleport/correction displacement of ANY size is
 *  excluded by provenance rather than by a magnitude guess. */
public final class MovementIntegration {
    /** Displacement beyond this per-tick-window bound cannot be chosen motion (vanilla movement is
     *  far below it); treat as repositioning. Chosen bursts below the bound always integrate. */
    public static final double REPOSITION_LIMIT = 8.0;
    private static final double REPOSITION_LIMIT_SQUARED = REPOSITION_LIMIT * REPOSITION_LIMIT;

    /** One measured movement window: state-eligible displacement and semantically integrable displacement. */
    public record Sample(Vec3d stateDelta, Vec3d semanticDelta) {
        public Sample {
            if (stateDelta == null || semanticDelta == null) throw new IllegalArgumentException("movement sample");
        }
        public static final Sample ZERO = new Sample(Vec3d.ZERO, Vec3d.ZERO);
    }
    private MovementIntegration() {}
    private static boolean finite(Vec3d v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }

    /** Server window: the claimed trajectory plus the server frame's body-overlap signal.
     *  A correction means the accepted body overlaps server-owned geometry; the window's
     *  displacement is a collision response in dispute, not chosen movement, so semantic
     *  advance pauses instead of chasing the disagreement (repeated overshoot correction).
     *  State machines still see the measured movement so mode/safety logic stays physical. */
    public static Sample server(Vec3d measured, Vec3d correction, boolean teleported) {
        if (measured == null || correction == null || !finite(measured) || !finite(correction)) return Sample.ZERO;
        if (teleported || measured.lengthSquared() > REPOSITION_LIMIT_SQUARED) return Sample.ZERO;
        if (correction.lengthSquared() > 0) return new Sample(measured, Vec3d.ZERO);
        return new Sample(measured, measured);
    }

    /** Client prediction window: the recorded depenetration was applied to the body by our own
     *  collision, so the chosen movement is exactly the remainder. A server position correction
     *  is authoritative repositioning, not the player's chosen movement, and skips the window. */
    public static Vec3d clientSemantic(Vec3d measured, Vec3d correction, boolean repositioned) {
        if (measured == null || correction == null || !finite(measured) || !finite(correction)) return Vec3d.ZERO;
        if (repositioned || measured.lengthSquared() > REPOSITION_LIMIT_SQUARED) return Vec3d.ZERO;
        return measured.subtract(correction);
    }
}
