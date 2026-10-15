package io.github.mysticism.navigation;

/** Regressions for the /spirit status response: it must describe the real current navigation
 * progress (mode, semantic readiness, live walk-request progress, retry cooldown, landing
 * approach, captured target, pending discovery) instead of a static label. */
public final class NavigationReportTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void checkText(String text, String expected, String message) {
        checks++; if (!expected.equals(text)) throw new AssertionError(message + "\n  expected: " + expected + "\n  actual:   " + text);
    }

    public static void main(String[] args) {
        checkText(NavigationReport.snapshot().mode(false, false, false).text(),
                "Spirit inactive; in the source world.", "Inactive state must be reported");

        // Shallow, source not yet discovered (semantic anchor still pending).
        String shallow = NavigationReport.snapshot()
                .mode(true, false, false)
                .source("minecraft:overworld", "", "10.0 64.0 -3.5")
                .walk(false, 0, 200, 0, 40, "", "")
                .walkCooldown(0)
                .landing(false)
                .target(false, "", "", "", Double.NaN)
                .capture(false)
                .text();
        check(shallow.contains("Shallow spirit") && shallow.contains("minecraft:overworld")
                        && shallow.contains("(undiscovered)") && shallow.contains("10.0 64.0 -3.5")
                        && shallow.contains("semantic travel waits for source discovery"),
                "Shallow undiscovered state must be described truthfully: " + shallow);

        // Shallow anchored, with a captured target and real distance.
        String anchored = NavigationReport.snapshot()
                .mode(true, false, true)
                .source("minecraft:overworld", "landmark-1", "0.0 64.0 0.0")
                .target(true, "minecraft:overworld", "landmark-2", "8, 64, 3", 5.0)
                .capture(true)
                .text();
        check(anchored.contains("landmark-1") && anchored.contains("walking normally")
                        && anchored.contains("target minecraft:overworld/landmark-2 at 8, 64, 3 (distance 5.00)")
                        && anchored.contains("source capture pending"),
                "Anchored shallow state must include target and capture progress: " + anchored);

        // Deep free flight without a semantic anchor: the confusing case must be explicit.
        String free = NavigationReport.snapshot()
                .mode(true, true, false)
                .source("minecraft:overworld", "", "")
                .text();
        check(free.contains("Deep spirit") && free.contains("free flight")
                        && free.contains("semantic travel awaits real source discovery"),
                "Deep free flight must say semantic travel awaits discovery: " + free);

        // Deep semantic travel with a live walk request: real tick/alignment progress must show.
        String walking = NavigationReport.snapshot()
                .mode(true, true, true)
                .walk(true, 84, 200, 12, 40, "landmark-1", "minecraft:overworld")
                .text();
        check(walking.contains("semantic travel ready")
                        && walking.contains("walk request pending: validating current support (84/200 alignment t)")
                        && walking.contains("support landmark-1 @ minecraft:overworld")
                        && walking.contains("grid alignment 12/40"),
                "Pending walk request must show live progress: " + walking);

        // Discovery merged with retry/landing progress: don't invent alignment while no
        // source support is acquired, and distinguish extraction from waiting for an anchor.
        String discovering = NavigationReport.snapshot().mode(true, true, false)
                .walk(true, 40, 200, 0, 40, "", "").walkDiscovery(true).text();
        check(discovering.contains("extracting/publishing discovered source target (40/200 alignment t)"),
                "In-flight real discovery must show its current phase: " + discovering);
        check(!discovering.contains("grid alignment"), "Unacquired support has no alignment progress");
        String awaiting = NavigationReport.snapshot().mode(true, true, false)
                .walk(true, 80, 200, 0, 40, "", "").walkDiscovery(false).text();
        check(awaiting.contains("awaiting real source anchor (80/200 alignment t)"),
                "Missing semantic anchor must not be reported as acquired support");
        check(!awaiting.contains("extracting/publishing"), "No pending future means no extraction claim");
        String readyDiscovery = NavigationReport.snapshot().mode(true, true, true)
                .walk(true, 100, 200, 0, 40, "", "").walkDiscovery(true).text();
        check(readyDiscovery.contains("semantic travel ready") && readyDiscovery.contains("extracting/publishing"),
                "Cross-owner target discovery is distinct from the actor's semantic readiness");
        String ended = NavigationReport.snapshot().mode(true, true, true)
                .walk(false, 0, 200, 0, 40, "", "").walkDiscovery(true).text();
        check(!ended.contains("walk request pending"), "Ended intents must not report stale progress");

        // The merged controller has independent source/alignment budgets and suspended recovery.
        // Use its actual clock, not fabricated elapsed ticks, for command progress snapshots.
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        for (int i = 0; i < 12; i++) clock.tick(true, false);
        for (int i = 0; i < 300; i++) clock.tick(false, true);
        String waiting = NavigationReport.snapshot().mode(true, true, true)
                .walk(true, 312, 200, 8, 40, "owner", "minecraft:overworld")
                .walkClock(clock.alignmentTicks(), clock.sourceWaitTicks(), clock.armed(), clock.recoveryWaitTicks())
                .walkDiscovery(true).text();
        check(waiting.contains("12/200 alignment t") && waiting.contains("source wait 300/600t")
                && !waiting.contains("312/200"), "Source waits must not masquerade as alignment failure: " + waiting);
        for (int i = 300; i <= WalkRequestClock.SOURCE_WAIT_TICKS; i++) clock.tick(false, true);
        clock.recover(false);
        String suspended = NavigationReport.snapshot().mode(true, true, true)
                .walk(false, 0, 200, 8, 40, "owner", "minecraft:overworld")
                .walkClock(clock.alignmentTicks(), clock.sourceWaitTicks(), clock.armed(), clock.recoveryWaitTicks())
                .walkDiscovery(true).text();
        check(suspended.contains("automatic recovery 1/1200t") && !suspended.contains("walk request pending"),
                "Suspended intent must report real automatic recovery, not active alignment: " + suspended);
        check(clock.recover(true), "Ready source resumes the same reported intent");
        String resumed = NavigationReport.snapshot().mode(true, true, true)
                .walk(true, 0, 200, 0, 40, "owner", "minecraft:overworld")
                .walkClock(clock.alignmentTicks(), clock.sourceWaitTicks(), clock.armed(), clock.recoveryWaitTicks()).text();
        check(resumed.contains("0/200 alignment t") && !resumed.contains("automatic recovery")
                && !resumed.contains("source wait"), "Resume must clear waiting progress: " + resumed);

        // Deep after a failed request: cooldown remaining instead of a new silent request.
        String cooldown = NavigationReport.snapshot()
                .mode(true, true, true)
                .walk(false, 0, 200, 0, 40, "", "")
                .walkCooldown(23)
                .text();
        check(cooldown.contains("walk retry available in 23t"),
                "Retry cooldown must be reported with remaining ticks: " + cooldown);

        // Deep landing approach in progress.
        String landing = NavigationReport.snapshot()
                .mode(true, true, true)
                .walk(false, 0, 200, 0, 40, "", "")
                .landing(true)
                .target(true, "minecraft:the_nether", "landmark-3", "1, 32, 2", 0.03)
                .text();
        check(landing.contains("landing approach active") && landing.contains("distance 0.03"),
                "Landing approach must be reported: " + landing);

        // Fluent chaining must return the same builder.
        check(NavigationReport.snapshot().mode(true, false, false) instanceof NavigationReport,
                "Builder methods must chain");
        System.out.println("NavigationReportTest passed: " + checks + " checks");
    }
}
