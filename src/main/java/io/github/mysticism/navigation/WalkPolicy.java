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
    /** How far below the last supported feet height a jump/step-down may fall and still land back
     * on its supporting projected terrain (one carrier step height). */
    public static final double LANDING_ENVELOPE = .6;
    /** Bounded air budget for any continuous unsupported shallow interval, however it arose. */
    public static final int AIR_GRACE_TICKS = 60;
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

    /** Physical landing-envelope hold: keep shallow only while the carrier is still inside its last
     * support's landing envelope or the collision mesh still physically holds it. A momentary
     * ground-contact detection miss while standing/clamped, an ordinary jump ascent and its
     * landing, and small step-downs all hold instead of alternating shallow/deep flight states.
     * This is the single airborne shallow decision; takeoffDeep above documents only the legacy
     * takeoff boundary and must not be layered on top of it (two competing decisions re-couple
     * shallow support with recovery retention — see EntryFailureRecoveryTest). */
    public static boolean landingEnvelopeHold(boolean onGround, double feetY, double supportY,
                                              int unsupportedTicks) {
        boolean envelope = !Double.isNaN(supportY) && feetY >= supportY - LANDING_ENVELOPE;
        return (envelope || onGround) && unsupportedTicks <= AIR_GRACE_TICKS;
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
