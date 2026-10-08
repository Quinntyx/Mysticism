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
        return tracker.observeToggle(flying) && tracker.startWalk();
    }

    public static void main(String[] args) {
        toggleEdges();
        duplicatePacketsCannotRestart();
        retargetSupersedesPendingWalk();
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
