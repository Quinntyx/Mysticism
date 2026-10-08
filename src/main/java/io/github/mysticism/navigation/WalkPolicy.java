package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/** Pure shallow-walking and walk-request decisions. No entity, world or terrain access, so the
 * attachment and usability rules stay unit-testable and identical between call sites. */
public final class WalkPolicy {
    private WalkPolicy() {}

    /** Physical vs semantic support classification for one shallow-walking tick. */
    public enum SupportPhase {
        /** Terrain reports a fully owned, landmark-matched floor under the player. */
        SUPPORTED,
        /** The mesh has real ground below the player but ownership/anchoring is still publishing.
         * Walking stays attached to the source locality instead of flapping into free flight. */
        PENDING,
        /** No physical ground or a genuinely unowned floor: ordinary takeoff/edge handling applies. */
        AIRBORNE
    }

    /** Ascending takeoff keeps ordinary jump grace; walking off an edge is immediate freeflight. */
    public static final int JUMP_GRACE_TICKS = 14;
    /** Ticks without any current support candidate before a walk request cancels itself. */
    public static final int STALL_CANCEL_TICKS = 200;
    /** Total walk-request budget backstop; generous so slow real validations finish. */
    public static final int MAX_PENDING_TICKS = 1200;
    /** Bounded retry pressure: failed acquisitions re-attempt at most every 10 ticks, 60 times. */
    public static final int ACQUIRE_RETRY_TICKS = 10;
    public static final int MAX_ACQUIRE_FAILURES = 60;
    /** Fraction of the remaining q/coordinate residual closed per validated slide tick. */
    public static final float SLIDE_FRACTION = 0.2f;

    public static SupportPhase phase(boolean semanticSupport, boolean meshGround, boolean ownershipPending,
                                     boolean blankSourceId) {
        if (semanticSupport) return SupportPhase.SUPPORTED;
        if (meshGround && (ownershipPending || blankSourceId)) return SupportPhase.PENDING;
        return SupportPhase.AIRBORNE;
    }

    /** unsupportedTicks is the count AFTER this tick's increment; ascending is the latched takeoff motion. */
    public static boolean takeoffDeep(int unsupportedTicks, boolean ascending) {
        return unsupportedTicks > JUMP_GRACE_TICKS || !ascending;
    }

    /** World-scale squared error of a semantic residual expressed through the current basis. */
    public static double worldErrorSquared(Vec384f residual, Basis384f basis) {
        double x = residual.dot(basis.i) * SpiritTerrainService.SCALE;
        double y = residual.dot(basis.j) * SpiritTerrainService.SCALE;
        double z = residual.dot(basis.k) * SpiritTerrainService.SCALE;
        return x * x + y * y + z * z;
    }

    /** At or below this squared world error the acquisition gate (1mm) is already satisfied. */
    public static boolean slideNeeded(double worldErrorSquared) { return worldErrorSquared > 1e-8; }

    /** Beyond 16 blocks of q/coordinate displacement this is a pathological scene/roundoff state,
     * not drift; smaller residuals (including multi-block movement during alignment) slide shut. */
    public static boolean displacementFatal(double worldErrorSquared) { return worldErrorSquared > 256.0; }

    /** Bounded per-tick slide candidate; never overshoots because converge clamps the fraction. */
    public static Vec384f slidToward(Vec384f q, Vec384f target) {
        return q.clone().converge(target, SLIDE_FRACTION);
    }

    /** During a walk request q integrates along the destination source grid once known. */
    public static Basis384f integrationBasis(Basis384f current, Basis384f destination) {
        return destination != null ? destination : current;
    }
}
