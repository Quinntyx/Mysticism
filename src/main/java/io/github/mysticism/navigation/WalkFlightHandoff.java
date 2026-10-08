package io.github.mysticism.navigation;

import net.minecraft.util.math.Vec3d;

/** Deterministic walk/flight transition policy, shared by the navigation controller and its regressions.
 * Every mode switch must hand off a stable, USABLE velocity instead of whatever stale state the previous
 * mode happened to leave behind: an unbounded falling/sprint burst becomes rubber banding on the freshly
 * published mesh, and a descent clip at walk acquisition re-triggers depenetration jitter every step.
 * Server-authoritative bookkeeping also stays inside the integrable band the semantic steering accepts. */
public final class WalkFlightHandoff {
    /** Matches the evolver/steering integrable band: per-tick deltas above this are discarded as
     * teleport-scale movement, so a handoff faster than this would freeze semantic travel. */
    public static final double MAX_HANDOFF_SPEED = 4.0;
    /** Vanilla jump ascent produces a clearly positive per-tick Y delta at takeoff. */
    public static final double JUMP_TAKEOFF_DELTA = 0.01;
    /** Gravity's first unsupported per-tick displacement (~-0.0784) is far past this epsilon. */
    public static final double FALLING_DELTA = -1e-4;
    public static final int TAKEOFF_GRACE_TICKS = 14;
    private WalkFlightHandoff() {}

    /** walk→flight: keep the player's momentum for continuity (a jump takeoff keeps rising), but bound it
     * so no stale burst, collision push or teleport residue enters the new flight mode. */
    public static Vec3d toFlight(Vec3d velocity) {
        if (!finite(velocity)) return Vec3d.ZERO;
        double speed = velocity.length();
        if (speed <= MAX_HANDOFF_SPEED) return velocity;
        return velocity.multiply(MAX_HANDOFF_SPEED / speed);
    }

    /** flight→walk: validated ground contact means a stable standing pose — zero residual descent so the
     * first walking tick cannot clip into the newly acquired mesh and bounce out via depenetration.
     * Airborne handoffs keep bounded momentum and fall normally; no fabricated floor. */
    public static Vec3d toWalk(Vec3d velocity, boolean grounded) {
        if (!finite(velocity)) return Vec3d.ZERO;
        return grounded ? Vec3d.ZERO : toFlight(velocity);
    }

    /** True when this first unsupported tick is a genuine ascending takeoff. A settled/stationary pose
     * (entering on supported ground whose ownership is still publishing) is NOT a takeoff: it must keep
     * waiting in shallow instead of spontaneously starting flight. */
    public static boolean takeoffIntent(double deltaY) {
        return Double.isFinite(deltaY) && deltaY > JUMP_TAKEOFF_DELTA;
    }

    /** Mode decision while shallow and unsupported. Ascending takeoffs keep the ordinary jump grace.
     * Falling without takeoff intent (walked off an edge, floor vanished) converts immediately. A
     * stationary pose never auto-converts: gravity would only produce a falling delta if there were
     * genuinely no floor, and standing on a real-but-unowned floor must not start flight. */
    public static boolean leavesGround(boolean jumping, int unsupportedTicks, double deltaY) {
        if (!Double.isFinite(deltaY)) return true;
        if (jumping) return unsupportedTicks > TAKEOFF_GRACE_TICKS;
        return deltaY < FALLING_DELTA;
    }

    private static boolean finite(Vec3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
