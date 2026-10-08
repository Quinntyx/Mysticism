package io.github.mysticism.navigation;

/**
 * Pure decision gate for natural landing: a deep-flying player who settles onto available
 * owned custom terrain lands into ordinary shallow walking instead of hovering above support.
 *
 * <p>Arming requires the player to have actually been airborne since entering deep flight, so an
 * explicit takeoff from standing ground is never immediately reversed. A sustained low-rest contact
 * (strongly upward ground normal, no ascending motion) then counts down to the landing trigger.
 * Callers still require real current {@code currentSupport} availability before committing; unknown
 * or unowned geometry below keeps free flight.</p>
 */
public final class NaturalLandingPolicy {
    /** Consecutive resting-contact ticks before a natural landing trigger (10 ticks = half a second). */
    public static final int REST_TICKS = 10;
    /** Resting contact requires near-horizontal ground, matching walk acquisition's support gate. */
    public static final double MIN_GROUND_NORMAL_Y = .99;
    /** Settled/rested means not ascending; any real upward motion re-arms takeoff. */
    public static final double MAX_REST_VERTICAL = 1e-3;
    public enum Action { NONE, LAND }
    private NaturalLandingPolicy() {}
    /** Pure per-tick evaluation. restingContact is the caller's measured ground state this tick. */
    public static Action evaluate(boolean deep, boolean airborneSinceDeep, boolean supportPending,
            boolean blendActive, boolean landingApproach, boolean restingContact, int restingTicks) {
        if (!deep || !airborneSinceDeep || supportPending || blendActive || landingApproach || !restingContact)
            return Action.NONE;
        return restingTicks + 1 >= REST_TICKS ? Action.LAND : Action.NONE;
    }
}
