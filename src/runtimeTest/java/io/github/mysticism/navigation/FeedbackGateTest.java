package io.github.mysticism.navigation;

/** Regressions for walk feedback deduplication: identical failure/request spam must be
 * suppressed inside the window, while new or changed information always shows. */
public final class FeedbackGateTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        var gate = new FeedbackGate(40);
        check(gate.allow(100, "Walk request expired: current support could not be continuously aligned/owned."),
                "First message must pass");
        check(!gate.allow(101, "Walk request expired: current support could not be continuously aligned/owned."),
                "Identical message inside window must be suppressed");
        check(!gate.allow(139, "Walk request expired: current support could not be continuously aligned/owned."),
                "Identical message at window end - 1 must be suppressed");
        check(gate.allow(140, "Walk request expired: current support could not be continuously aligned/owned."),
                "Identical message at window end must pass again");
        // A different message is new information even mid-window, and reopens its own window.
        check(gate.allow(150, "Walk request: validating current source-owned support."),
                "Different message must pass immediately");
        check(gate.allow(151, "Walk request expired: current support could not be continuously aligned/owned."),
                "Alternating distinct messages are each new information");
        // Zero window never suppresses; unbounded ticks are monotonic server ticks.
        var open = new FeedbackGate(0);
        check(open.allow(5, "same") && open.allow(5, "same"), "Zero window must never suppress");
        try {
            new FeedbackGate(-1);
            check(false, "Negative window must be rejected");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            gate.allow(200, null);
            check(false, "Null message must be rejected");
        } catch (NullPointerException expected) { checks++; }
        System.out.println("FeedbackGateTest passed: " + checks + " checks");
    }
}
