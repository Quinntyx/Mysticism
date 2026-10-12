package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/** Supported-walking decisions shared by the live deep/shallow controller and runtime regressions.
 * Terrain still owns exact source proof and continuous collision/render acquisition. */
public final class WalkingModePolicy {
    private WalkingModePolicy() {}

    public enum Shallow { WALK, JUMP_GRACE, DEEP }

    /** Shallow per-tick mode decision. Real mesh support holds walking, and so does ground whose
     * exact octree owner is still resolving (entry/staleness window): that ground is real and
     * source-identical, so it must not reset the player into deep flight. Away from any ground an
     * ascending takeoff keeps the ordinary bounded jump grace; walking off an edge goes deep at once. */
    public static Shallow shallowSupport(boolean supportPresent, boolean groundPending, boolean rising,
            int unsupportedTicks, boolean jumping) {
        if (supportPresent || groundPending) return Shallow.WALK;
        boolean currentJumping = unsupportedTicks == 0 ? rising : jumping;
        if (unsupportedTicks >= 14 || !currentJumping) return Shallow.DEEP;
        return Shallow.JUMP_GRACE;
    }

    /** Shared server/client support prediction: only actual, bounded movement advances q.
     * A support coordinate is an inverse of the LIVE projected mesh, not a fixed attraction target. */
    public static void advanceSupport(Vec384f q, Basis384f basis, double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || dx * dx + dy * dy + dz * dz > 16) return;
        TraversalSteering.advance(q, basis, dx, dy, dz);
    }

    /** Bounded, progress-aware alignment state for one walk request. A changing support window or
     * basis re-bases the SAME request instead of failing it, and real convergence progress re-arms
     * the stall window; only a request that makes no progress for a full window expires. */
    public static final class Alignment {
        public static final int STALL_LIMIT = 200, ALIGN_STEPS = 40;
        public static final float DISCONTINUITY = .01f, ALIGNED = 1e-8f, BINDING = 1e-12f;

        public Basis384f from, grid;
        public long window;
        public String id = "", dimension = "";
        public int stall, alignTick;
        public float stallError = Float.MAX_VALUE;
        private boolean pending;

        public boolean pending() { return pending; }

        /** Duplicate flight-off packets cannot re-arm the deadline or restart the blend. */
        public boolean begin() {
            if (pending) return false;
            reset(); pending = true; return true;
        }

        public void reset() {
            pending = false;
            from = null; grid = null; window = 0; id = ""; dimension = "";
            stall = 0; alignTick = 0; stallError = Float.MAX_VALUE;
        }

        /** Bind (or re-base) this request onto the current support. True means the binding changed;
         * the request continues from the live basis rather than failing and requiring a new toggle. */
        public boolean bind(Basis384f current, Basis384f destination, long window, String id, String dimension) {
            if (from == null || this.window != window || !this.id.equals(id) || !this.dimension.equals(dimension)
                    || error(grid, destination) > BINDING) {
                from = current.clone(); grid = destination.clone(); alignTick = 0;
                this.window = window; this.id = id; this.dimension = dimension;
                return true;
            }
            return false;
        }

        public Basis384f proposed(Basis384f current, Basis384f destination) {
            if (error(current, destination) < ALIGNED) return current.clone();
            return TraversalSteering.blend(from, destination, (alignTick + 1) / (float) ALIGN_STEPS);
        }

        /** Restart the blend from the actual current basis. False only when even a single minimal
         * step toward the destination would be discontinuous, i.e. alignment is genuinely unreachable. */
        public boolean restartFrom(Basis384f current, Basis384f destination) {
            from = current.clone(); alignTick = 0;
            return !discontinuous(current, TraversalSteering.blend(from, destination, 1 / (float) ALIGN_STEPS));
        }

        public void applied() { if (alignTick < ALIGN_STEPS) ++alignTick; }

        /** Records this tick's source-grid alignment error. Real progress re-arms the bounded
         * stall window. @return true when the request expired with no progress for a full window. */
        public boolean stalled(float error) {
            // Track the best error, not the last sample: jitter/alternating contacts must not
            // masquerade as convergence and keep an unusable request alive forever.
            if (Float.isFinite(error) && error >= 0 && error < stallError) {
                stall = 0; stallError = error;
            }
            return ++stall > STALL_LIMIT;
        }

        public static float error(Basis384f a, Basis384f b) {
            return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
        }

        public static boolean discontinuous(Basis384f before, Basis384f proposed) {
            return before.i.squareDistance(proposed.i) > DISCONTINUITY || before.j.squareDistance(proposed.j) > DISCONTINUITY
                    || before.k.squareDistance(proposed.k) > DISCONTINUITY;
        }
    }
}
