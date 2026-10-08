package io.github.mysticism.navigation;

/** Regressions for the failed-walk-request retry cooldown: repeated flight toggles during the
 * cooldown are refused instead of restarting terrain validation, without permanent lockout. */
public final class WalkRetryGateTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        var gate = new WalkRetryGate(60);
        check(gate.canRequest(1000), "Fresh gate must accept requests");
        gate.failed(1000);
        check(!gate.canRequest(1000) && !gate.canRequest(1059), "Cooldown must refuse repeated requests before expiry");
        check(gate.canRequest(1060), "Cooldown must accept a request at expiry");
        check(gate.remaining(1030) == 30 && gate.remaining(1060) == 0 && gate.remaining(2000) == 0,
                "Remaining must be exact and never negative");
        // A burst of failures inside one cooldown must not extend the lockout indefinitely.
        gate.failed(1000);
        gate.failed(1010); gate.failed(1030); gate.failed(1050);
        check(gate.canRequest(1060), "Repeated failures inside one window must not extend the lockout");
        // A failure after expiry re-arms normally.
        gate.failed(2000);
        check(!gate.canRequest(2059) && gate.canRequest(2060), "Failure after expiry must re-arm the cooldown");
        // A cooldown of zero never blocks (configurable safety valve).
        var open = new WalkRetryGate(0);
        open.failed(5);
        check(open.canRequest(5), "Zero cooldown must never block");
        try {
            new WalkRetryGate(-1);
            check(false, "Negative cooldown must be rejected");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("WalkRetryGateTest passed: " + checks + " checks");
    }
}
