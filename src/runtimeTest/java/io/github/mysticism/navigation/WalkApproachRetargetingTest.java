package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/** Combined-scope regression for the merged walking parents (walk-approach-completion x
 * walk-retargeting): the guarded alignment pacing epoch and the walk-intent arbitration epoch
 * must reset TOGETHER.
 *
 * <ol>
 * <li>A mid-approach explicit destination change supersedes the pending walk; the defensive
 * epoch net must end the approach before any further guarded step is applied, so the actor
 * never keeps blending toward the OLD destination grid.</li>
 * <li>A genuine retry after the retarget starts a FRESH alignment epoch: pace back at
 * INITIAL_PACE, progress back at 0, converging to the NEW destination — never resuming the
 * old epoch's fraction against the new grid (which would arrive at a corrupted basis).</li>
 * <li>Restart under the same tight guard still converges within the request budget, i.e. the
 * retarget does not reintroduce the freeze the approach-completion parent fixed.</li>
 * </ol>
 * Deterministic, framework-free, no Minecraft server required.
 */
public final class WalkApproachRetargetingTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final float ALIGNMENT_EPSILON = 1e-8f;
    private static final int REQUEST_BUDGET_TICKS = 200;
    /** Sustained guard model from SupportApproachAlignmentTest: admits only small swept steps. */
    private static final float TIGHT_GUARD_SQUARE = 5e-4f;

    private static Basis384f rotated(double angle) {
        float c = (float)Math.cos(angle), s = (float)Math.sin(angle);
        float[] i = Vec384f.ZERO().data(); i[0] = c; i[1] = s;
        float[] j = Vec384f.ZERO().data(); j[0] = -s; j[1] = c;
        float[] k = Vec384f.ZERO().data(); k[2] = 1;
        return new Basis384f(new Vec384f(i), new Vec384f(j), new Vec384f(k));
    }

    private static float error(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }

    private static float componentStep(Basis384f before, Basis384f proposed) {
        return Math.max(Math.max(before.i.squareDistance(proposed.i), before.j.squareDistance(proposed.j)),
                before.k.squareDistance(proposed.k));
    }

    /** Mirrors the merged attemptSupport loop: guarded pacing PLUS the per-tick epoch net. */
    private static final class Approach {
        final WalkIntentTracker walk = new WalkIntentTracker();
        Basis384f current, anchor, destination;
        float pace = SupportApproachAlignment.INITIAL_PACE, progress;
        int ticks, acceptedSteps;
        boolean ended;
        Approach(Basis384f start, Basis384f destination) {
            this.current = start.clone(); this.anchor = start.clone(); this.destination = destination.clone();
        }
        /** One service tick. Returns true while the approach is still running. */
        boolean tick(boolean guardRefuses) {
            // The merged defensive epoch net runs BEFORE any guarded step is applied.
            if (walk.superseded()) { walk.endWalk(); ended = true; return false; } // endSupportApproach cleanup
            if (error(current, destination) < ALIGNMENT_EPSILON) return false; // aligned
            float fraction = SupportApproachAlignment.fraction(progress, pace);
            var proposed = TraversalSteering.blend(anchor, destination, fraction);
            if (guardRefuses || componentStep(current, proposed) > TIGHT_GUARD_SQUARE) {
                anchor = current.clone(); progress = 0;
                pace = SupportApproachAlignment.paceOnHold(pace);
            } else {
                current = proposed; progress = fraction;
                pace = SupportApproachAlignment.paceOnAccept(pace);
                acceptedSteps++; // counts accepted guarded steps of whichever epoch is active
            }
            return true;
        }
    }

    /** Service gate: a walk may start only for a fresh gesture on a non-pending intent. */
    private static boolean gatedStart(WalkIntentTracker tracker, boolean flying) {
        return tracker.flightRequest(flying) == WalkIntentTracker.FlightRequest.WALK && tracker.startWalk();
    }

    public static void main(String[] args) {
        retargetCancelsMidApproachAlignment();
        restartedWalkStartsFreshEpochAgainstNewDestination();
        restartStillConvergesUnderTightGuard();
        System.out.println("walk approach retargeting checks: " + checks);
    }

    /** An explicit retarget mid-approach ends the walk before another guarded step lands. */
    private static void retargetCancelsMidApproachAlignment() {
        var approach = new Approach(rotated(1.2), new Basis384f());
        check(gatedStart(approach.walk, false), "Walk requested against the original destination");
        // A few guarded steps are applied, then the player captures a new destination.
        for (int i = 0; i < 5 && approach.tick(false); i++) {}
        int beforeRetarget = approach.acceptedSteps;
        check(beforeRetarget > 0, "premise: the approach made guarded progress");
        approach.walk.bumpDestination(); // explicit destination change during the approach
        boolean running = approach.tick(false);
        check(!running && approach.ended, "The epoch net ends the approach on the next tick");
        check(approach.acceptedSteps == beforeRetarget,
                "No further guarded step is applied after the retarget supersedes the walk");
        check(error(approach.current, new Basis384f()) > ALIGNMENT_EPSILON,
                "The actor never finished aligning to the OLD destination grid");
    }

    /** A genuine retry after the retarget starts a fresh pacing epoch at the NEW destination,
     * never resuming the old epoch's fraction against the new grid. */
    private static void restartedWalkStartsFreshEpochAgainstNewDestination() {
        var oldDestination = new Basis384f();
        var newDestination = rotated(Math.PI / 3);
        var approach = new Approach(rotated(1.2), oldDestination);
        check(gatedStart(approach.walk, false), "Walk requested");
        for (int i = 0; i < 10 && approach.tick(false); i++) {}
        approach.walk.bumpDestination(); // retarget
        approach.tick(false);
        check(approach.ended, "Retarget cancels the in-flight approach");
        // Genuine retry: explicit on/off gesture, then a new walk against the new destination.
        check(approach.walk.observeToggle(true), "Flight-on is a fresh gesture after the retarget");
        check(gatedStart(approach.walk, false), "Fresh flight-off edge requests the new walk");
        check(!approach.walk.superseded(), "The retry records the latest destination epoch");
        // Merged restart resets BOTH machines: intent epoch is current, pacing is a new epoch.
        Approach restarted = new Approach(approach.current, newDestination);
        restarted.walk.bumpDestination(); // the retarget bumped before the restart
        check(!restarted.walk.superseded(), "Restarted walk is not stale against the latest destination");
        // Resuming the OLD fraction/pace against the NEW grid would corrupt the basis: the merged
        // endSupportApproach resets pace/progress, so the restart must propose only tiny guarded
        // steps from the LIVE basis, never a blend that lands on the old destination's grid.
        float fraction = SupportApproachAlignment.fraction(0, SupportApproachAlignment.INITIAL_PACE);
        var resumed = TraversalSteering.blend(restarted.anchor, restarted.destination, fraction);
        check(!SupportApproachAlignment.discontinuous(restarted.current, resumed),
                "A fresh-epoch restart steps continuously from the live basis");
        check(error(resumed, newDestination) < error(restarted.current, newDestination),
                "Restart steps converge toward the NEW destination grid, never the superseded one");
    }

    /** The retarget must not reintroduce the approach-completion freeze: a restarted walk under
     * the same tight guard still converges to the EXACT new destination within one budget. */
    private static void restartStillConvergesUnderTightGuard() {
        var newDestination = rotated(Math.PI / 3);
        var approach = new Approach(rotated(1.2), new Basis384f());
        check(gatedStart(approach.walk, false), "First walk requested");
        for (int i = 0; i < 5 && approach.tick(false); i++) {}
        approach.walk.bumpDestination();
        approach.tick(false); // cancellation
        var restarted = new Approach(approach.current, newDestination);
        check(gatedStart(restarted.walk, false), "Retry requested");
        boolean arrived = false;
        while (restarted.ticks++ < REQUEST_BUDGET_TICKS) {
            if (!restarted.tick(false)) { arrived = error(restarted.current, newDestination) < ALIGNMENT_EPSILON; break; }
        }
        check(arrived, "The restarted approach converges to the exact NEW destination within one request budget");
        check(restarted.acceptedSteps > 0, "premise: the restarted approach makes guarded progress");
        check(error(restarted.current, newDestination) < ALIGNMENT_EPSILON,
                "Strict acquisition gate precondition holds: the basis IS the destination");
    }
}
