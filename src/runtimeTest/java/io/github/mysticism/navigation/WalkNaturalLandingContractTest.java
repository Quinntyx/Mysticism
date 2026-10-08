package io.github.mysticism.navigation;

/**
 * Combined walk-request/natural-landing contract regressions for the merged navigation service.
 *
 * <p>An explicit walk request (repeated flight toggles) must be refused while an approach is
 * pending or the failed-request retry cooldown is armed — request/failure spam may not restart
 * terrain validation. A natural resting landing is a physical event decided solely by
 * {@link NaturalLandingPolicy}: it must NEVER be blocked by that cooldown, because an expired or
 * failed walk intent may not leave a settling deep player stuck in flight above available support.</p>
 */
public final class WalkNaturalLandingContractTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        cooldownRefusesExplicitRequests();
        naturalLandingBypassesCooldown();
        System.out.println("walk/natural-landing contract checks: " + checks);
    }

    private static void cooldownRefusesExplicitRequests() {
        var gate = new WalkRetryGate(60);
        long failedAt = 1000;
        gate.failed(failedAt);
        // Inside the cooldown, explicit walk requests are refused...
        for (long t = failedAt; t < failedAt + 60; t += 7)
            check(!SpiritNavigationService.explicitWalkRequestAccepted(false, gate, t),
                    "explicit walk request must be refused during the retry cooldown (t=" + t + ")");
        // ...and a pending approach refuses them regardless of the gate.
        check(!SpiritNavigationService.explicitWalkRequestAccepted(true, gate, failedAt + 200),
                "explicit walk request must be refused while an approach is pending");
        // At expiry, a fresh explicit request is accepted again (no permanent lockout).
        check(SpiritNavigationService.explicitWalkRequestAccepted(false, gate, failedAt + 60),
                "explicit walk request must be accepted once the cooldown expires");
        check(SpiritNavigationService.explicitWalkRequestAccepted(false, new WalkRetryGate(60), 0),
                "a fresh gate accepts explicit walk requests immediately");
    }

    private static void naturalLandingBypassesCooldown() {
        var gate = new WalkRetryGate(60);
        gate.failed(1000);
        long duringCooldown = 1030; // explicit requests refused here, verified above
        check(!SpiritNavigationService.explicitWalkRequestAccepted(false, gate, duringCooldown),
                "precondition: the cooldown refuses explicit requests at this tick");
        // The natural-landing decision is evaluated with the same armed gate present in the
        // session and must still trigger: the gate is not an input to NaturalLandingPolicy.
        var action = NaturalLandingPolicy.evaluate(
                true,   // deep
                true,   // actually airborne since entering deep
                false,  // no approach pending (previous walk intent expired)
                false,  // no touch blend in progress
                false,  // no semantic landing approach
                true,   // resting contact with owned, near-horizontal terrain
                NaturalLandingPolicy.REST_TICKS - 1);
        check(action == NaturalLandingPolicy.Action.LAND,
                "an expired/failed walk intent must not block a natural resting landing");
        // A settled player on unowned/unknown geometry still keeps free flight (terrain validates).
        check(NaturalLandingPolicy.evaluate(true, true, false, false, false, false, 99)
                == NaturalLandingPolicy.Action.NONE,
                "no resting contact means no natural landing");
        // While a walk approach IS pending, natural landing does not double-trigger it.
        check(NaturalLandingPolicy.evaluate(true, true, true, false, false, true, NaturalLandingPolicy.REST_TICKS)
                == NaturalLandingPolicy.Action.NONE,
                "a pending walk approach suppresses a duplicate natural landing trigger");
    }
}
