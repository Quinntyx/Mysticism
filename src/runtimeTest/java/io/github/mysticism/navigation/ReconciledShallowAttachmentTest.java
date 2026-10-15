package io.github.mysticism.navigation;

/** Reconciled shallow-attachment regressions: one combined decision per shallow tick must keep
 * ownership-pending mesh ground attached to its source locality (walk-request/entry locality work)
 * AND hold ordinary jumps, landings and small step-downs on the physical landing envelope
 * (landing-envelope work), while an edge walk-off or air-budget expiry still converts to deep
 * freeflight. Vanilla-kinematics models follow ShallowJumpLandingGraceTest; the superseded
 * rising-latch must not return as a competing decision (see EntryFailureRecoveryTest). */
public final class ReconciledShallowAttachmentTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final double GRAVITY = .08, DRAG = .98, JUMP_VELOCITY = .42;

    /** The envelope is the ONLY airborne decision, and the phase classifier feeds it: a
     * pending-ownership floor never reaches the airborne branch at all. */
    private static void reconciledDecisionContract() {
        double supportY = 64;
        // Attached phases are decided before any hold is consulted.
        check(WalkPolicy.phase(true, false, false, false) == WalkPolicy.SupportPhase.SUPPORTED,
                "owned floor is attached without mesh contact");
        check(WalkPolicy.phase(false, true, true, false) == WalkPolicy.SupportPhase.PENDING,
                "pending ownership over mesh ground stays walking, never flight");
        check(WalkPolicy.phase(false, true, false, true) == WalkPolicy.SupportPhase.PENDING,
                "blank mapped id with ground stays attached while discovery publishes");
        // Airborne: envelope/clamp holds; no rising-delta latch participates.
        check(WalkPolicy.landingEnvelopeHold(true, supportY - .05, supportY, 1), "clamped flicker holds shallow");
        check(WalkPolicy.landingEnvelopeHold(false, supportY - .4, supportY, 1), "step-down inside envelope holds shallow");
        check(WalkPolicy.landingEnvelopeHold(false, supportY + 1.25, supportY, 20), "jump apex holds shallow without any rising latch");
        check(!WalkPolicy.landingEnvelopeHold(false, supportY - WalkPolicy.LANDING_ENVELOPE - .01, supportY, 4),
                "falling past the landing envelope leaves shallow");
        check(!WalkPolicy.landingEnvelopeHold(false, 64, Double.NaN, 1), "unmeasured support while airborne does not hold shallow");
        check(WalkPolicy.landingEnvelopeHold(false, supportY, supportY, WalkPolicy.AIR_GRACE_TICKS), "air budget boundary holds");
        check(!WalkPolicy.landingEnvelopeHold(false, supportY, supportY, WalkPolicy.AIR_GRACE_TICKS + 1),
                "air budget expiry converts to deep");
    }

    /** The reconciled contract forbids two competing support decisions: the session must carry no
     * rising-latch field, and the airborne call path must be the shared envelope hold alone. */
    private static void singleDecisionAuthority() throws Exception {
        Class<?> session = Class.forName("io.github.mysticism.navigation.SpiritNavigationService$Session");
        for (java.lang.reflect.Field field : session.getDeclaredFields())
            check(!field.getName().equals("jumping"), "superseded rising-latch must stay out of the reconciled session");
        Class<?> navigation = Class.forName("io.github.mysticism.navigation.SpiritNavigationService");
        check(WalkPolicy.LANDING_ENVELOPE == SpiritNavigationEnvelopeProbe.envelope(navigation),
                "service envelope delegates to the shared pure policy constant");
        check(WalkPolicy.AIR_GRACE_TICKS == SpiritNavigationEnvelopeProbe.airGrace(navigation),
                "service air budget delegates to the shared pure policy constant");
        navigation.getDeclaredMethod("holdShallow", boolean.class, double.class, double.class, int.class);
        check(true, "service retains the envelope hold entry point");
    }

    /** Reflection shim keeps this test independent of service member visibility ordering. */
    static final class SpiritNavigationEnvelopeProbe {
        static double envelope(Class<?> navigation) throws Exception {
            var f = navigation.getDeclaredField("LANDING_ENVELOPE"); f.setAccessible(true);
            return f.getDouble(null);
        }
        static int airGrace(Class<?> navigation) throws Exception {
            var f = navigation.getDeclaredField("AIR_GRACE_TICKS"); f.setAccessible(true);
            return f.getInt(null);
        }
    }

    /** Full vanilla jump over an OWNED floor whose support measurement flickers off mid-air: the
     * reconciled decision must hold shallow for the entire airborne interval and land shallow. */
    private static void vanillaJumpOverOwnedFloor(int amplifier) {
        double floor = 64, feet = floor, velocity = JUMP_VELOCITY + .1 * amplifier;
        double supportY = feet;
        int unsupported = 0;
        boolean airborneSeen = false, landed = false;
        for (int tick = 0; tick < 100 && !landed; tick++) {
            double requested = velocity - GRAVITY;
            double nextFeet = feet + requested;
            boolean clamped = nextFeet < floor;
            double applied = clamped ? floor - feet : requested;
            boolean onGround = clamped && requested < 0;
            feet += applied;
            velocity = clamped ? 0 : (velocity - GRAVITY) * DRAG;
            boolean support = feet - floor <= .175;
            var phase = WalkPolicy.phase(support, !support, false, false);
            if (phase != WalkPolicy.SupportPhase.AIRBORNE) { unsupported = 0; supportY = feet; }
            else if (!WalkPolicy.landingEnvelopeHold(onGround, feet, supportY, ++unsupported))
                throw new AssertionError("reconciled hold dropped a vanilla jump (amplifier " + amplifier
                        + ") at tick " + tick + " feet=" + feet + " supportY=" + supportY);
            if (!support) airborneSeen = true;
            if (onGround && tick > 0) landed = true;
        }
        check(airborneSeen, "jump with amplifier " + amplifier + " actually leaves measured support mid-air");
        check(landed, "jump with amplifier " + amplifier + " lands back on its supporting terrain");
    }

    /** Ownership-pending entry: a supported body must never see an AIRBORNE phase while discovery
     * publishes, and once ownership lands the same body is fully SUPPORTED with identical pose. */
    private static void pendingEntryNeverFlies() {
        for (int tick = 0; tick < 40; tick++) {
            boolean grounded = true, pending = tick < 30, owned = tick >= 30;
            var phase = WalkPolicy.phase(owned, grounded, pending, !owned && tick < 10);
            check(phase != WalkPolicy.SupportPhase.AIRBORNE,
                    "supported entry tick " + tick + " must stay attached while ownership publishes");
        }
    }

    public static void main(String[] args) throws Exception {
        reconciledDecisionContract();
        singleDecisionAuthority();
        vanillaJumpOverOwnedFloor(0);
        vanillaJumpOverOwnedFloor(1);
        vanillaJumpOverOwnedFloor(2);
        pendingEntryNeverFlies();
        System.out.println("ReconciledShallowAttachmentTest: " + checks + " checks passed");
    }
}
