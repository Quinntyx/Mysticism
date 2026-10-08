package io.github.mysticism.lifecycle;

import io.github.mysticism.navigation.MotionAlignment;
import net.minecraft.util.math.Vec3d;

/**
 * Regression for repeated-entry/mode-change/reconnect movement coherence: the ONE shared rule that
 * a position delta crossing a prediction-epoch change is re-anchored, never integrated. The server
 * evolver (SpiritBasisEvolver) and the client predictor (ClientLatentPredictor) must both consume
 * exactly this decision, or every mode commit leaves a one-tick q divergence for the periodic
 * authoritative sync to snap back (the reported movement rubber banding).
 */
public final class MotionAlignmentTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void zero(Vec3d delta, String message) { check(delta.equals(Vec3d.ZERO), message); }

    public static void main(String[] args) {
        Vec3d a = new Vec3d(1, 2, 3), b = new Vec3d(1.5, 2, 3.25), step = b.subtract(a);

        // Bounded PHYSICAL movement is epoch-agnostic: navigation decisions that consume real
        // movement (jump takeoff grace, blend cancellation) must use it, never the filtered delta.
        check(MotionAlignment.boundedDelta(a, b).equals(step), "bounded physical delta is real movement");
        zero(MotionAlignment.boundedDelta(null, b), "tracking start re-anchors physical movement");
        zero(MotionAlignment.boundedDelta(Vec3d.ZERO, new Vec3d(64, 0, 0)), "teleport is not physical movement");
        zero(MotionAlignment.boundedDelta(Vec3d.ZERO, new Vec3d(Double.NaN, 0, 0)), "nonfinite is not physical movement");
        check(MotionAlignment.boundedDelta(a, b).equals(MotionAlignment.alignedDelta(a, b, 7L, 7L)),
                "within one epoch physical and semantic deltas coincide");
        // ...and the epoch filter remains purely semantic.
        zero(MotionAlignment.alignedDelta(a, b, 6L, 7L), "epoch change still drops the semantic delta");

        // Tracking start (fresh visit, reconnect, first tracked tick): re-anchor, never integrate.
        zero(MotionAlignment.alignedDelta(null, b, null, 7), "null last position must re-anchor");
        zero(MotionAlignment.alignedDelta(a, b, null, 7), "null recorded epoch must re-anchor");

        // Epoch change (mode commit, semantic anchor transition, reconnect): crossing delta dropped.
        zero(MotionAlignment.alignedDelta(a, b, 6L, 7L), "epoch change must drop the crossing delta");
        zero(MotionAlignment.alignedDelta(a, b, 8L, 7L), "stale recorded epoch must re-anchor");

        // Continuous tracked movement within one epoch integrates normally.
        check(MotionAlignment.alignedDelta(a, b, 7L, 7L).equals(step), "same-epoch movement must integrate");

        // Teleports are never semantic movement (shared 16-block guard of both former call sites).
        zero(MotionAlignment.alignedDelta(Vec3d.ZERO, new Vec3d(64, 0, 0), 7L, 7L), "oversized delta must re-anchor");
        zero(MotionAlignment.alignedDelta(Vec3d.ZERO, new Vec3d(10, 10, 10), 7L, 7L), "just above the guard must re-anchor");
        check(MotionAlignment.alignedDelta(Vec3d.ZERO, new Vec3d(4, 0, 0), 7L, 7L).equals(new Vec3d(4, 0, 0)),
                "movement inside the guard integrates");

        // Nonfinite positions can never drive semantic movement.
        zero(MotionAlignment.alignedDelta(Vec3d.ZERO, new Vec3d(Double.NaN, 0, 0), 7L, 7L), "NaN delta must re-anchor");

        // Stationary continuous tracking integrates a zero delta (a no-op, not a re-anchor rejection).
        check(MotionAlignment.alignedDelta(a, a, 7L, 7L).equals(Vec3d.ZERO), "stationary delta is zero");

        System.out.println("MotionAlignmentTest passed: " + checks + " checks");
    }
}
