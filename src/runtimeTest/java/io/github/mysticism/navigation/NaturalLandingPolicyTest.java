package io.github.mysticism.navigation;

/** Natural-landing decision gate regressions: arming, resting cadence and every refusal path. */
public final class NaturalLandingPolicyTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        arming();
        restingCadence();
        refusals();
        System.out.println("natural landing policy checks: " + checks);
    }

    private static NaturalLandingPolicy.Action decide(boolean deep, boolean airborne, boolean pending,
            boolean blend, boolean approach, boolean contact, int ticks) {
        return NaturalLandingPolicy.evaluate(deep, airborne, pending, blend, approach, contact, ticks);
    }

    private static void arming() {
        // A player who just toggled flight while standing on owned ground is NOT immediately re-landed:
        // without real airtime the gate stays closed no matter how long the contact persists.
        for (int ticks = 0; ticks <= NaturalLandingPolicy.REST_TICKS + 5; ticks++)
            check(decide(true, false, false, false, false, true, ticks) == NaturalLandingPolicy.Action.NONE,
                    "standing takeoff grace requires prior airtime (ticks=" + ticks + ")");
        check(decide(false, true, false, false, false, true, NaturalLandingPolicy.REST_TICKS)
                == NaturalLandingPolicy.Action.NONE, "shallow players are never naturally landed");
        // Airborne + resting contact counts up to the trigger.
        for (int ticks = 0; ticks < NaturalLandingPolicy.REST_TICKS - 1; ticks++)
            check(decide(true, true, false, false, false, true, ticks) == NaturalLandingPolicy.Action.NONE,
                    "resting contact below threshold keeps flight (ticks=" + ticks + ")");
        check(decide(true, true, false, false, false, true, NaturalLandingPolicy.REST_TICKS - 1)
                == NaturalLandingPolicy.Action.LAND, "sustained rest triggers natural landing");
        check(NaturalLandingPolicy.REST_TICKS == 10, "rest window is half a second");
        check(NaturalLandingPolicy.MIN_GROUND_NORMAL_Y >= .99, "natural landing ground normal matches walk acquisition");
        check(NaturalLandingPolicy.MAX_REST_VERTICAL >= 0 && NaturalLandingPolicy.MAX_REST_VERTICAL < .01,
                "rest excludes real ascending motion but tolerates collision settle jitter");
    }

    private static void restingCadence() {
        // The counter is consecutive: one ascending tick resets the whole rest window.
        check(decide(true, true, false, false, false, false, NaturalLandingPolicy.REST_TICKS - 1)
                == NaturalLandingPolicy.Action.NONE, "lost contact resets accumulated rest");
        check(decide(true, true, false, false, false, true, 0) == NaturalLandingPolicy.Action.NONE,
                "first contact tick does not trigger");
    }

    private static void refusals() {
        int ready = NaturalLandingPolicy.REST_TICKS - 1;
        check(decide(true, true, true, false, false, true, ready) == NaturalLandingPolicy.Action.NONE,
                "an in-flight walk request owns the support approach; natural landing never competes");
        check(decide(true, true, false, true, false, true, ready) == NaturalLandingPolicy.Action.NONE,
                "touch basis blending is not interrupted by natural landing");
        check(decide(true, true, false, false, true, true, ready) == NaturalLandingPolicy.Action.NONE,
                "a captured-target landing approach is not diverted by incidental ground contact");
        // Unavailable support is refused by the caller's currentSupport check; the policy itself
        // still reports LAND so callers can decide with live terrain state. Documented contract:
        check(decide(true, true, false, false, false, true, ready) == NaturalLandingPolicy.Action.LAND,
                "policy reports landing intent; support availability is validated by terrain");
    }
}
