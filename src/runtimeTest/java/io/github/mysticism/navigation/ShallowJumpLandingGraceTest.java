package io.github.mysticism.navigation;

/** Real vanilla-kinematics regressions for shallow jump/landing stability on supported projected terrain.
 * The previous support logic (single-tick rising latch plus a 14-tick hard budget) converted ordinary
 * jumps, landing contact flicker and small step-downs into deep freeflight mid-air; these checks pin the
 * landing-envelope decision against actual swept-SAT mesh contact from MeshLandingContactTest. */
public final class ShallowJumpLandingGraceTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final double GRAVITY = .08, DRAG = .98, JUMP_VELOCITY = .42;
    private static void envelopeHoldsOrdinaryContact() {
        double supportY = 64;
        // Standing ground-contact detection flicker: body clamped at/below the last support, mesh still
        // holding it. Previously !jumping converted to deep flight on the very first missed tick.
        check(SpiritNavigationService.holdShallow(true, supportY - .05, supportY, 1), "clamped flicker holds shallow");
        check(SpiritNavigationService.holdShallow(true, supportY, supportY, 20), "sustained clamped flicker holds shallow");
        // Small step-down inside one step height: falls briefly, lands on the supporting terrain below.
        check(SpiritNavigationService.holdShallow(false, supportY - .4, supportY, 1), "step-down inside envelope holds shallow");
        check(SpiritNavigationService.holdShallow(false, supportY - SpiritNavigationService.LANDING_ENVELOPE, supportY, 3),
                "exactly at the envelope boundary still holds shallow");
        // Walked/fell past the envelope: shallow walking must yield to freeflight.
        check(!SpiritNavigationService.holdShallow(false, supportY - SpiritNavigationService.LANDING_ENVELOPE - .01, supportY, 4),
                "falling past the landing envelope leaves shallow");
        check(!SpiritNavigationService.holdShallow(false, supportY - 2, supportY, 6), "real edge walk-off enters deep");
        // No support ever measured (NaN): only a physically clamped body may hold.
        check(SpiritNavigationService.holdShallow(true, 64, Double.NaN, 1), "unmeasured support with clamped body holds shallow");
        check(!SpiritNavigationService.holdShallow(false, 64, Double.NaN, 1), "unmeasured support while airborne does not hold shallow");
        // Bounded air budget.
        check(SpiritNavigationService.holdShallow(false, supportY, supportY, SpiritNavigationService.AIR_GRACE_TICKS),
                "air budget boundary holds");
        check(!SpiritNavigationService.holdShallow(false, supportY, supportY, SpiritNavigationService.AIR_GRACE_TICKS + 1),
                "air budget expiry converts to deep");
        // Rising jumps from the support level always stay inside the envelope during ascent.
        check(SpiritNavigationService.holdShallow(false, supportY + 1.25, supportY, 8), "jump apex stays shallow");
    }

    /** Flat vanilla jump: support disappears once the feet clear the ground window and returns at
     * touchdown; the grace decision must hold shallow for the ENTIRE airborne interval. */
    private static void flatVanillaJumpStaysShallow(int amplifier) {
        double floor = 64, feet = floor;
        double velocity = JUMP_VELOCITY + .1 * amplifier;
        double supportY = feet; // last measured supported feet height
        int unsupported = 0;
        boolean airborneSeen = false, landed = false;
        for (int tick = 0; tick < 100 && !landed; tick++) {
            double requested = velocity - GRAVITY;
            double nextFeet = feet + requested;
            boolean clamped = nextFeet < floor; // swept mesh would clamp the body onto the floor plane
            double applied = clamped ? floor - feet : requested;
            boolean onGround = clamped && requested < 0;
            feet += applied;
            velocity = clamped ? 0 : (velocity - GRAVITY) * DRAG;
            // MeshCollision.ground contract: support contact only within its ground window below the feet.
            boolean support = feet - floor <= .175;
            if (support) { unsupported = 0; supportY = feet; }
            else if (!SpiritNavigationService.holdShallow(onGround, feet, supportY, ++unsupported))
                throw new AssertionError("jump with amplifier " + amplifier + " left shallow at tick " + tick
                        + " feet=" + feet + " supportY=" + supportY);
            if (!support) airborneSeen = true;
            if (onGround && tick > 0) landed = true;
        }
        check(airborneSeen, "jump with amplifier " + amplifier + " actually leaves measured support mid-air");
        check(landed, "jump with amplifier " + amplifier + " lands back on its supporting terrain");
    }

    /** Walking off an edge must still yield to deep freeflight, only after the envelope is breached. */
    private static void edgeWalkOffEntersDeepBounded() {
        double supportY = 64, feet = supportY;
        double velocity = 0;
        int unsupported = 0, firstDeep = -1;
        double firstDeepFeet = 0;
        for (int tick = 0; tick < 40; tick++) {
            double requested = velocity - GRAVITY;
            feet += requested; // no mesh below: free fall past the walked-off edge
            velocity = (velocity - GRAVITY) * DRAG;
            boolean onGround = false;
            if (!SpiritNavigationService.holdShallow(onGround, feet, supportY, ++unsupported) && firstDeep < 0) {
                firstDeep = tick; firstDeepFeet = feet;
            }
        }
        check(firstDeep >= 0, "edge walk-off eventually enters deep freeflight");
        check(firstDeep <= 9, "edge walk-off conversion stays prompt (tick " + firstDeep + ")");
        check(firstDeepFeet <= supportY - SpiritNavigationService.LANDING_ENVELOPE + 1e-9,
                "conversion happens at the landing envelope, not on a detection flicker");
    }

    public static void main(String[] args) {
        envelopeHoldsOrdinaryContact();
        flatVanillaJumpStaysShallow(0);
        flatVanillaJumpStaysShallow(1); // Jump Boost I exceeds the previous 14-tick budget
        flatVanillaJumpStaysShallow(2);
        edgeWalkOffEntersDeepBounded();
        System.out.println("ShallowJumpLandingGraceTest: " + checks + " checks passed");
    }
}
