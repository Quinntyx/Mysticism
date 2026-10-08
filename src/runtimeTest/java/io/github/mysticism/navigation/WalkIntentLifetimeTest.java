package io.github.mysticism.navigation;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Regressions for the bounded truthful walk-intent lifetime.
 *
 * <p>Old behavior: a raw 200-tick deadline expired a pending-valid walk request that was
 * merely waiting on late semantic source discovery or an in-flight terrain ownership
 * proof, and expiry cancelled the prepared terrain proof so every repeated request
 * restarted the expensive source fetch from zero — the reported "repeated failed walk
 * requests / expired walk intent leaves flight stuck" loop.</p>
 *
 * <p>Required behavior: pending-valid intents (late discovery, live current support,
 * pending proof) stay alive; genuine stalls expire boundedly with a truthful reason;
 * one absolute lifetime bound exists for any input; expiry is terminal; the production
 * session actually drives this state machine.</p>
 */
public final class WalkIntentLifetimeTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        constantsAreCoherentlyBounded();
        discoveryWaitDoesNotConsumeSupportBudget();
        discoveryWaitExpiresBoundedAndTruthfully();
        supportStallExpiresBounded();
        supportPresenceResetsStall();
        absoluteLifetimeBoundHoldsForAdversarialSequences();
        expiryIsTerminal();
        reasonsAreDistinctAndTruthful();
        productionSessionDrivesWalkIntent();
        System.out.println("WalkIntentLifetimeTest: " + checks + " checks passed");
    }

    private static void constantsAreCoherentlyBounded() {
        check(WalkIntent.SUPPORT_STALL_TICKS > 0, "support stall bound must be positive");
        check(WalkIntent.DISCOVERY_WAIT_TICKS > 0, "discovery wait bound must be positive");
        check(WalkIntent.TOTAL_TICKS >= WalkIntent.DISCOVERY_WAIT_TICKS,
                "absolute lifetime must cover the discovery wait");
        check(WalkIntent.TOTAL_TICKS > WalkIntent.SUPPORT_STALL_TICKS,
                "absolute lifetime must cover the support stall");
    }

    /** The core regression: discovery latency alone must not expire a valid request at 200 ticks. */
    private static void discoveryWaitDoesNotConsumeSupportBudget() {
        WalkIntent intent = new WalkIntent();
        for (int tick = 1; tick <= 300; tick++) {
            WalkIntent.Evaluation evaluation = intent.tick(false, false);
            check(evaluation.outcome() == WalkIntent.Outcome.PENDING,
                    "pending-valid discovery wait expired at tick " + tick + " (" + evaluation.reason() + ")");
            check(evaluation.reason() == WalkIntent.Reason.NONE, "pending evaluation carries no failure reason");
        }
        // Discovery then completes with a live current support: the request must still be usable.
        WalkIntent.Evaluation evaluation = intent.tick(true, true);
        check(evaluation.outcome() == WalkIntent.Outcome.PROGRESS,
                "request must survive late discovery and progress on current support");
    }

    private static void discoveryWaitExpiresBoundedAndTruthfully() {
        WalkIntent intent = new WalkIntent();
        WalkIntent.Evaluation evaluation = evaluationAfter(intent, WalkIntent.DISCOVERY_WAIT_TICKS, false, false);
        check(evaluation.outcome() == WalkIntent.Outcome.PENDING,
                "discovery wait must not expire before its bound");
        evaluation = intent.tick(false, false);
        check(evaluation.outcome() == WalkIntent.Outcome.EXPIRED, "discovery wait must expire at its bound");
        check(evaluation.reason() == WalkIntent.Reason.NO_SEMANTIC_ANCHOR,
                "discovery expiry must report the truthful no-anchor reason");
        check(!evaluation.reason().message().isBlank(), "expiry reason must carry a player-facing message");
    }

    private static void supportStallExpiresBounded() {
        WalkIntent intent = new WalkIntent();
        WalkIntent.Evaluation evaluation = null;
        for (int tick = 1; tick <= WalkIntent.SUPPORT_STALL_TICKS; tick++) {
            evaluation = intent.tick(true, false);
            check(evaluation.outcome() == WalkIntent.Outcome.PENDING,
                    "support stall must not expire before its bound (tick " + tick + ")");
        }
        evaluation = intent.tick(true, false);
        check(evaluation.outcome() == WalkIntent.Outcome.EXPIRED, "support stall must expire at its bound");
        check(evaluation.reason() == WalkIntent.Reason.NO_CURRENT_SUPPORT,
                "stall expiry must report the truthful no-support reason");
    }

    /** A live current support means the intent is pending-valid, not stalled: presence resets the stall. */
    private static void supportPresenceResetsStall() {
        WalkIntent intent = new WalkIntent();
        for (int round = 0; round < 3; round++) {
            for (int tick = 1; tick <= WalkIntent.SUPPORT_STALL_TICKS - 1; tick++) {
                WalkIntent.Evaluation evaluation = intent.tick(true, false);
                check(evaluation.outcome() != WalkIntent.Outcome.EXPIRED,
                        "stall with recurring support must not expire (round " + round + " tick " + tick + ")");
            }
            WalkIntent.Evaluation evaluation = intent.tick(true, true);
            check(evaluation.outcome() == WalkIntent.Outcome.PROGRESS,
                    "present support is real progress and resets the stall");
            check(intent.tick(true, false).outcome() == WalkIntent.Outcome.PENDING,
                    "stall restarts counting after progress");
        }
    }

    /** Whatever the input, expiry must arrive within the absolute lifetime bound. */
    private static void absoluteLifetimeBoundHoldsForAdversarialSequences() {
        alwaysSupportExpiresAtAbsoluteBound();
        alternatingSupportExpiresAtAbsoluteBound();
    }

    private static void alwaysSupportExpiresAtAbsoluteBound() {
        WalkIntent intent = new WalkIntent();
        int ticks = 0;
        WalkIntent.Evaluation evaluation = null;
        while (true) {
            evaluation = intent.tick(true, true); // present support forever, acquisition never succeeds
            ticks++;
            if (evaluation.outcome() == WalkIntent.Outcome.EXPIRED) break;
            check(ticks <= WalkIntent.TOTAL_TICKS, "always-supported request exceeded the absolute bound");
        }
        check(ticks == WalkIntent.TOTAL_TICKS + 1, "absolute bound must fire exactly at TOTAL_TICKS");
        check(evaluation.reason() == WalkIntent.Reason.UNRESOLVABLE_SUPPORT,
                "absolute bound must report the truthful unresolved-support reason");
    }

    private static void alternatingSupportExpiresAtAbsoluteBound() {
        WalkIntent intent = new WalkIntent();
        int ticks = 0;
        while (true) {
            WalkIntent.Evaluation evaluation = intent.tick(true, ticks % 2 == 0);
            ticks++;
            if (evaluation.outcome() == WalkIntent.Outcome.EXPIRED) break;
            check(ticks <= WalkIntent.TOTAL_TICKS + 1, "alternating request exceeded the absolute bound");
        }
        check(ticks <= WalkIntent.TOTAL_TICKS + 1, "alternating request must stay bounded");
    }

    private static void expiryIsTerminal() {
        WalkIntent intent = new WalkIntent();
        for (int tick = 0; tick <= WalkIntent.SUPPORT_STALL_TICKS; tick++) intent.tick(true, false);
        WalkIntent.Evaluation first = intent.tick(true, false);
        check(first.outcome() == WalkIntent.Outcome.EXPIRED, "expected expiry before terminality check");
        for (int tick = 0; tick < 10; tick++) {
            WalkIntent.Evaluation evaluation = intent.tick(true, true); // even good news cannot resurrect it
            check(evaluation.outcome() == WalkIntent.Outcome.EXPIRED, "expiry must be terminal");
            check(evaluation.reason() == first.reason(), "terminal reason must not change");
        }
        check(intent.expired() && intent.expiryReason() == first.reason(), "query accessors agree with evaluations");
    }

    private static void reasonsAreDistinctAndTruthful() {
        Set<String> messages = new HashSet<>();
        for (WalkIntent.Reason reason : WalkIntent.Reason.values()) {
            if (reason == WalkIntent.Reason.NONE) { check(reason.message().isEmpty(), "NONE carries no message"); continue; }
            check(!reason.message().isBlank(), reason + " must carry a truthful message");
            check(messages.add(reason.message()), reason + " message must be distinct");
        }
        check(WalkIntent.Reason.values().length == 4, "unexpected reason set");
    }

    /** The production navigation session must actually drive this state machine, not a raw tick counter. */
    private static void productionSessionDrivesWalkIntent() throws Exception {
        Class<?> session = Class.forName("io.github.mysticism.navigation.SpiritNavigationService$Session");
        Field walkIntent = session.getDeclaredField("walkIntent");
        check(walkIntent.getType() == WalkIntent.class, "session must own a WalkIntent instance");
        boolean hasRawCounter = false;
        for (Field field : session.getDeclaredFields()) if (field.getName().equals("supportTick")) hasRawCounter = true;
        check(!hasRawCounter, "raw 200-tick support counter must be gone");
        boolean hasExpiry = false;
        for (var method : Class.forName("io.github.mysticism.navigation.SpiritNavigationService").getDeclaredMethods())
            if (method.getName().equals("expireSupport")) hasExpiry = true;
        check(hasExpiry, "truthful retained-proof expiry path must exist");
    }

    private static WalkIntent.Evaluation evaluationAfter(WalkIntent intent, int ticks, boolean semanticReady, boolean supportPresent) {
        WalkIntent.Evaluation evaluation = null;
        for (int tick = 0; tick < ticks; tick++) evaluation = intent.tick(semanticReady, supportPresent);
        return evaluation;
    }
}
