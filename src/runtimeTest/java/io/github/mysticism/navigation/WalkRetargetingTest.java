package io.github.mysticism.navigation;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;

/** Walk-retargeting regressions: the latest requested destination must win.
 *
 * Covers the pure {@link WalkIntentTracker} decisions now embedded in
 * {@link SpiritNavigationService}:
 * <ol>
 * <li>Only a fresh flight-toggle edge may start a walk request; retransmitted/stale
 * flight-off packets must never restart one after a retarget cancelled it.</li>
 * <li>A pending walk is superseded by any explicit destination change (epoch), never
 * completed against the older destination.</li>
 * <li>A fresh gesture after a retarget may legitimately request a new walk.</li>
 * <li>Server-driven flight corrections (abilities updates applied by vanilla without an echoed
 * flight-on packet) advance the recorded gesture state, so correction → cancellation/expiry →
 * genuine retry works without an intervening flight-on packet.</li>
 * <li>A flight-on packet queued before a server correction always requests deep mode and
 * cancels pending walking, even when its recorded state is classified as a duplicate.</li>
 * <li>Accepting an asynchronous capture supersedes older navigation intents at acceptance, and a
 * delayed capture result supersedes any interim walk.</li>
 * <li>Only an EXPLICIT attunement re-key supersedes a pending walk; background personal
 * drift on a non-explicit target is followed, not treated as a competing destination.</li>
 * </ol>
 * Deterministic, framework-free, no Minecraft server required.
 */
public final class WalkRetargetingTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static Vec384f unit(int index) {
        float[] values = new float[EmbeddingSpace.DIMENSIONS];
        values[index] = 1;
        return new Vec384f(values);
    }

    /** Models the service gate: a walk may start only for a fresh gesture on a non-pending intent. */
    private static boolean gatedStart(WalkIntentTracker tracker, boolean flying) {
        return tracker.flightRequest(flying) == WalkIntentTracker.FlightRequest.WALK && tracker.startWalk();
    }

    public static void main(String[] args) {
        toggleEdges();
        duplicatePacketsCannotRestart();
        correctionRetry();
        queuedFlightOnCancelsWalk();
        retargetSupersedesPendingWalk();
        acceptanceSupersede();
        freshGestureAfterRetarget();
        attunementPolicy();
        System.out.println("WalkRetargetingTest passed: " + assertions + " assertions");
    }

    private static void toggleEdges() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(tracker.observeToggle(false), "First observed packet is a fresh gesture");
        check(!tracker.observeToggle(false), "Retransmitted flight-off is not a fresh gesture");
        check(tracker.observeToggle(true), "Flight-on edge is a fresh gesture");
        check(!tracker.observeToggle(true), "Retransmitted flight-on is not a fresh gesture");
        check(tracker.observeToggle(false), "Flight-off edge after on is fresh again");
        check(!tracker.pending(), "Observing toggles alone never starts a walk");
    }

    private static void duplicatePacketsCannotRestart() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(gatedStart(tracker, false), "Fresh flight-off gesture starts a walk");
        check(tracker.pending(), "Walk intent is pending");
        check(!tracker.startWalk(), "A second start cannot replace or reset the pending intent");
        tracker.endWalk(); // e.g. cancelled by an explicit retarget
        check(!tracker.pending(), "Ended walk is no longer pending");
        // The client's local flight state is still off: a stale retransmission arrives.
        check(!gatedStart(tracker, false), "Stale flight-off retransmission must not restart the walk");
        check(!tracker.pending(), "No walk intent was resurrected by the stale packet");
    }

    /** P1: a server-driven flight correction (abilities update, applied by vanilla without an
     * echoed flight-on packet) must not make the next genuine gesture look like a duplicate.
     * correction → cancellation/expiry → genuine retry, with no intervening flight-on packet. */
    private static void correctionRetry() {
        WalkIntentTracker corrected = new WalkIntentTracker();
        check(corrected.observeToggle(false), "First flight-off gesture accepted");
        check(corrected.startWalk(), "Walk requested");
        corrected.serverCorrected(true); // server restores client flight via an abilities update
        corrected.endWalk(); // cancellation or expiry
        check(corrected.observeToggle(false), "Genuine retry after a server correction is fresh without an intervening flight-on packet");
        check(corrected.startWalk(), "Genuine retry after a server correction starts a new walk");

        WalkIntentTracker uncorrected = new WalkIntentTracker();
        uncorrected.observeToggle(false);
        uncorrected.startWalk();
        uncorrected.endWalk();
        check(!uncorrected.observeToggle(false), "Without a server correction the same-value packet remains a stale retransmission");

        WalkIntentTracker echo = new WalkIntentTracker();
        echo.observeToggle(false);
        echo.serverCorrected(true);
        check(!echo.observeToggle(true), "A packet matching the server-corrected state is still a duplicate");
        check(echo.observeToggle(false), "The opposite state after a correction is a fresh edge");
        check(echo.startWalk(), "Fresh edge after a correction starts a walk");
    }

    /** P2: flight-off → server correction → queued flight-on while walking is still pending.
     * Use the service's production arbitration, not the raw edge classifier: a matching flight-on
     * must still dispatch enterDeep, whose normal cleanup ends the walk and terrain acquisition. */
    private static void queuedFlightOnCancelsWalk() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(gatedStart(tracker, false), "Flight-off starts pending walking");
        tracker.serverCorrected(true);
        check(tracker.pending(), "Server flight restoration alone does not cancel the walk");
        // correctionRetry separately proves this value is a duplicate in the raw edge classifier.
        var request = tracker.flightRequest(true);
        check(request == WalkIntentTracker.FlightRequest.DEEP, "Duplicate flight-on must still dispatch enterDeep");
        check(tracker.pending(), "Arbitration retains pending state until the service cancels terrain acquisition");
        if (request == WalkIntentTracker.FlightRequest.DEEP) tracker.endWalk(); // enterDeep cleanup
        check(!tracker.pending(), "No pending walk remains to commit shallow after the newer flight-on");
        check(tracker.flightRequest(true) == WalkIntentTracker.FlightRequest.DEEP,
                "Repeated flight-on remains an idempotent deep request, never a walk");
        check(!tracker.pending(), "Repeated flight-on cannot resurrect walking");
        check(gatedStart(tracker, false), "A later genuine flight-off can request walking again");
        check(!gatedStart(tracker, false), "Duplicate flight-off cannot replace or reset the pending retry");
        check(tracker.pending() && !tracker.superseded(), "Retry retains the current destination epoch");
    }

    private static void retargetSupersedesPendingWalk() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(gatedStart(tracker, false), "Walk requested");
        tracker.bumpDestination(); // explicit target capture / attunement re-key during the walk
        check(tracker.superseded(), "Pending walk requested before the latest destination change is stale");
        tracker.endWalk();
        check(!tracker.superseded(), "Ended walk is never reported stale");
        check(!tracker.pending(), "Ended walk stays ended");
        // Multiple explicit changes keep the epoch monotonic.
        WalkIntentTracker other = new WalkIntentTracker();
        check(gatedStart(other, false), "Walk requested");
        other.bumpDestination();
        other.bumpDestination();
        check(other.superseded(), "Epoch comparison survives multiple destination changes");
    }

    /** P2: accepting an asynchronous capture is a destination request — older navigation intents
     * are superseded at acceptance (walk ended immediately, target cleared), never when the
     * delayed discovery completes, so an older walk cannot commit shallow mode in the meantime. */
    private static void acceptanceSupersede() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(tracker.observeToggle(false) && tracker.startWalk(), "Walk pending when the capture is accepted");
        tracker.bumpDestination(); // capture acceptance bumps the destination epoch
        tracker.endWalk(); // acceptance ends the walk immediately, not at discovery completion
        check(!tracker.pending(), "No walk survives capture acceptance to commit shallow mode");
        // Delayed discovery is still in flight; a walk may be requested in the meantime.
        check(tracker.observeToggle(true), "Flight-on after acceptance is a fresh gesture");
        check(tracker.observeToggle(false) && tracker.startWalk(), "Interim walk requested before delayed discovery completes");
        // The delayed capture result arrives and applies the newer destination.
        tracker.bumpDestination();
        check(tracker.superseded(), "Delayed capture result supersedes the interim walk");
        tracker.endWalk();
        check(!tracker.superseded() && !tracker.pending(), "Ended interim walk leaves no stale state");
    }

    private static void freshGestureAfterRetarget() {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(gatedStart(tracker, false), "First walk requested");
        tracker.bumpDestination(); // retarget cancels the walk in the service
        tracker.endWalk();
        // The player genuinely toggles flight on, then off again: a new walk gesture.
        check(tracker.observeToggle(true), "Flight-on is a fresh gesture");
        check(!tracker.pending(), "Flight-on alone never starts a walk intent");
        check(gatedStart(tracker, false), "Fresh flight-off edge after on may request a new walk");
        check(tracker.pending(), "New walk intent is pending");
        check(!tracker.superseded(), "New walk records the latest destination epoch");
    }

    private static void attunementPolicy() {
        Vec384f a = unit(1), b = unit(2), drift = a.clone().add(unit(3));
        check(!WalkIntentTracker.attunementSupersedes(a, a.clone(), true), "Equal explicit target never supersedes");
        check(!WalkIntentTracker.attunementSupersedes(a, drift, false), "Background drift on a non-explicit target never supersedes");
        check(WalkIntentTracker.attunementSupersedes(a, drift, true), "Explicit attunement re-key supersedes the pending walk");
        Vec384f zero = Vec384f.ZERO();
        check(!WalkIntentTracker.attunementSupersedes(zero, zero.clone(), true), "Retained captured ZERO is a real destination and does not supersede");
        check(zero.squareDistance(zero.clone()) == 0, "Snapshot comparison uses exact vector equality");
        check(a.squareDistance(drift) > 0, "Distinct vectors are detectable for the supersede decision");
    }
}
