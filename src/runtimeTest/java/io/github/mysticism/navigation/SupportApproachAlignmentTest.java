package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/** Regressions for guarded source-grid alignment pacing. Two failure modes are covered:
 * (1) the pre-fix fixed-anchor/(step+1)/40 schedule FROZE on the first refused step — the terrain
 * transition guard refuses any rotation whose swept render motion reaches the body, exactly the
 * state of an actor hovering over the support it asked to walk on, so every walk request taken with
 * a genuinely rotated deep basis expired without arriving; (2) the first ratchet fix halved the pace
 * PERMANENTLY, so three transient refusals stranded the pace at 1/320 and a 200-tick request reached
 * only interpolation fraction ~0.616 — still expiring even with every later transition safe. The
 * shipped policy must re-plan refused steps AND recover pace on every guard-validated accepted step,
 * converging to the EXACT destination basis the strict acquisition gate requires. */
public final class SupportApproachAlignmentTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final float ALIGNMENT_EPSILON = 1e-8f;
    private static final int REQUEST_BUDGET_TICKS = 200;

    /** Sustained guard model: refuses full-pace steps but admits half-pace steps, like a body
     * clearance that tolerates the halved swept motion but not the full one. */
    private static final float TIGHT_GUARD_SQUARE = 5e-4f;
    /** Wedged guard model: sits strictly between the recovered pace probes, so every recovery probe
     * is refused. Arrival may then legitimately spill past one request budget, but must never freeze. */
    private static final float WEDGED_GUARD_SQUARE = 1e-4f;

    public static void main(String[] args) {
        policyBounds();
        epochConvergesToExactDestination();
        refusedStepNeverRepeatsIdentically();
        transientRefusalsRecoverWithinRequestBudget();
        permanentRatchetWouldExpire();
        sustainedTightGuardConvergesWithinBudget();
        wedgedGuardNeverFreezes();
        oldScheduleWouldFreezeForever();
        discontinuityRefusalStillFires();
        System.out.println("support approach alignment checks: " + checks);
    }

    /** Orthonormal basis rotated by the given angle around the third axis, in the first 3 coordinates. */
    private static Basis384f rotated(double angle) {
        float c = (float)Math.cos(angle), s = (float)Math.sin(angle);
        return new Basis384f(vector(c, s, 0), vector(-s, c, 0), vector(0, 0, 1));
    }

    private static Vec384f vector(float x, float y, float z) {
        float[] v = Vec384f.ZERO().data(); v[0] = x; v[1] = y; v[2] = z; return new Vec384f(v);
    }

    private static float error(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }

    /** Largest single-axis SQUARED component movement a proposed step would apply. */
    private static float componentStep(Basis384f before, Basis384f proposed) {
        return Math.max(Math.max(before.i.squareDistance(proposed.i), before.j.squareDistance(proposed.j)),
                before.k.squareDistance(proposed.k));
    }

    /** Deterministic controller simulation mirroring attemptSupport's guarded alignment loop. */
    private static final class Simulation {
        interface Guard { boolean admits(Basis384f current, Basis384f proposed); }
        interface Pace { float onAccept(float pace); }
        Basis384f current, anchor, destination = new Basis384f();
        float pace = SupportApproachAlignment.INITIAL_PACE, progress;
        int ticks, holds, accepts;
        float maxErrorSeen;
        Simulation(double startAngle) { current = rotated(startAngle); anchor = current.clone(); maxErrorSeen = error(current, destination); }
        boolean run(Guard guard, Pace pacePolicy, int limit) {
            while (error(current, destination) >= ALIGNMENT_EPSILON && ticks++ < limit) {
                var proposed = TraversalSteering.blend(anchor, destination, SupportApproachAlignment.fraction(progress, pace));
                maxErrorSeen = Math.max(maxErrorSeen, error(current, destination));
                if (!guard.admits(current, proposed)) {
                    anchor = current.clone(); progress = 0;
                    pace = SupportApproachAlignment.paceOnHold(pace); holds++;
                } else {
                    check(!SupportApproachAlignment.discontinuous(current, proposed), "accepted guarded steps stay continuous");
                    current = proposed; progress = SupportApproachAlignment.fraction(progress, pace);
                    pace = pacePolicy.onAccept(pace); accepts++;
                }
            }
            return error(current, destination) < ALIGNMENT_EPSILON;
        }
    }

    private static void policyBounds() {
        check(SupportApproachAlignment.INITIAL_PACE > 0 && SupportApproachAlignment.INITIAL_PACE <= 1 / 40f + 1e-9f,
                "full pace matches the approved 40 accepted-step schedule");
        check(SupportApproachAlignment.paceOnAccept(SupportApproachAlignment.INITIAL_PACE) == SupportApproachAlignment.INITIAL_PACE,
                "recovery is capped at the approved full pace");
        float pace = SupportApproachAlignment.INITIAL_PACE;
        for (int i = 0; i < 3; i++) pace = SupportApproachAlignment.paceOnHold(pace);
        check(pace == 1 / 320f, "three refusals ratchet the pace to 1/320 (the reported failure arithmetic)");
        check(SupportApproachAlignment.paceOnAccept(SupportApproachAlignment.paceOnAccept(
                SupportApproachAlignment.paceOnAccept(pace))) == SupportApproachAlignment.INITIAL_PACE,
                "three accepted steps heal three refusals completely");
        float floored = pace;
        for (int i = 0; i < 64; i++) floored = SupportApproachAlignment.paceOnHold(floored);
        check(floored == SupportApproachAlignment.MIN_PACE, "the refusal ratchet is bounded below and stays positive");
        check(SupportApproachAlignment.fraction(0, SupportApproachAlignment.MIN_PACE) > 0,
                "even the floor pace keeps proposing guarded progress");
        check(SupportApproachAlignment.fraction(0.98f, SupportApproachAlignment.INITIAL_PACE) == 1f,
                "the epoch fraction clamps at exactly 1 so the final step is the EXACT destination");
        float previous = 0;
        for (int i = 0; i < 39; i++) {
            float next = SupportApproachAlignment.fraction(previous, SupportApproachAlignment.INITIAL_PACE);
            check(next > previous, "epoch fraction is strictly monotone toward 1");
            previous = next;
        }
    }

    private static void epochConvergesToExactDestination() {
        var sim = new Simulation(1.2);
        check(sim.run((current, proposed) -> true, SupportApproachAlignment::paceOnAccept, REQUEST_BUDGET_TICKS),
                "an unguarded alignment arrives");
        // 40 full-pace steps plus at most one extra tick from float rounding of the accumulated fraction.
        check(sim.ticks <= 41, "full pace completes within the approved 40-step schedule (+ rounding), took " + sim.ticks);
        check(error(sim.current, sim.destination) < ALIGNMENT_EPSILON,
                "the epoch reaches the EXACT destination basis, as the strict acquisition gate requires");
    }

    private static void refusedStepNeverRepeatsIdentically() {
        var anchor = rotated(1.2);
        var destination = new Basis384f();
        var current = anchor.clone();
        float pace = SupportApproachAlignment.INITIAL_PACE;
        var refused = TraversalSteering.blend(anchor, destination, SupportApproachAlignment.fraction(0, pace));
        float refusedStep = componentStep(current, refused);
        check(refusedStep > TIGHT_GUARD_SQUARE, "premise: the first full-pace step is refused by the tight guard");
        // Controller hold behavior: re-anchor at the live basis and ratchet the pace down.
        anchor = current.clone();
        pace = SupportApproachAlignment.paceOnHold(pace);
        var retried = TraversalSteering.blend(anchor, destination, SupportApproachAlignment.fraction(0, pace));
        check(componentStep(current, retried) < refusedStep,
                "a refused step must be retried with strictly smaller swept motion instead of freezing");
        check(Math.abs(componentStep(current, retried) - refusedStep / 4) < refusedStep * .05f,
                "each hold quarters the squared swept motion (half the rotation step)");
        pace = SupportApproachAlignment.paceOnHold(pace);
        var third = TraversalSteering.blend(current.clone(), destination, SupportApproachAlignment.fraction(0, pace));
        check(componentStep(current, third) <= TIGHT_GUARD_SQUARE,
                "the ratchet reaches the guard's admissible band instead of deadlocking");
    }

    /** The reported P2 scenario: three transient refusals, then every transition safe again. */
    private static void transientRefusalsRecoverWithinRequestBudget() {
        var sim = new Simulation(1.2);
        boolean arrived = sim.run(
                (current, proposed) -> sim.ticks > 3, // transient window: refuse the first three steps outright
                SupportApproachAlignment::paceOnAccept, REQUEST_BUDGET_TICKS);
        check(sim.holds == 3, "premise: exactly three transient refusals, got " + sim.holds);
        check(arrived, "the approach aligns and arrives after transient refusals");
        check(sim.ticks <= REQUEST_BUDGET_TICKS,
                "the arrival completes inside one walk-request budget, took " + sim.ticks + " ticks");
        check(sim.pace == SupportApproachAlignment.INITIAL_PACE,
                "accepted steps recover the pace fully instead of stranding it at 1/320");
    }

    /** Contrast: the previous ratchet (no recovery) cannot survive the same transient window. */
    private static void permanentRatchetWouldExpire() {
        var sim = new Simulation(1.2);
        boolean arrived = sim.run((current, proposed) -> sim.ticks > 3, pace -> pace, REQUEST_BUDGET_TICKS);
        check(!arrived, "a permanent ratchet strands the pace at 1/320 and the request expires");
        check(sim.ticks >= REQUEST_BUDGET_TICKS, "the request burns its entire budget without arriving");
    }

    private static void sustainedTightGuardConvergesWithinBudget() {
        var sim = new Simulation(1.2);
        boolean arrived = sim.run(
                (current, proposed) -> componentStep(current, proposed) <= TIGHT_GUARD_SQUARE,
                SupportApproachAlignment::paceOnAccept, REQUEST_BUDGET_TICKS);
        check(sim.holds > 0, "premise: the tight guard actually refused full-pace steps");
        check(arrived, "guarded alignment converges to the exact destination despite sustained refusals");
        check(sim.ticks <= REQUEST_BUDGET_TICKS,
                "the arrival completes inside one walk-request budget, took " + sim.ticks + " ticks");
    }

    private static void wedgedGuardNeverFreezes() {
        var sim = new Simulation(1.2);
        float previousError = error(sim.current, sim.destination);
        boolean arrived = sim.run((current, proposed) -> componentStep(current, proposed) <= WEDGED_GUARD_SQUARE,
                SupportApproachAlignment::paceOnAccept, 500) ;
        check(arrived, "even a guard wedged between recovery probes still converges — progress never freezes");
        check(sim.ticks > REQUEST_BUDGET_TICKS,
                "honest premise: a wedged guard legitimately spills past one request budget, took " + sim.ticks + " ticks");
        check(sim.maxErrorSeen <= previousError * 1.0001f, "guarded alignment error never grows back toward the start");
    }

    private static void oldScheduleWouldFreezeForever() {
        // The pre-fix schedule: fixed anchor, fixed (accepted+1)/40 fraction, and a refused step
        // changes nothing. Encode exactly that and show it can never arrive.
        var destination = new Basis384f();
        var current = rotated(1.2);
        var anchor = current.clone();
        int accepted = 0, ticks = 0;
        while (error(current, destination) >= ALIGNMENT_EPSILON && ticks++ < 400) {
            var proposed = TraversalSteering.blend(anchor, destination, (accepted + 1) / 40f);
            if (componentStep(current, proposed) > TIGHT_GUARD_SQUARE) continue; // frozen: nothing changes
            current = proposed; accepted++;
        }
        check(error(current, destination) >= ALIGNMENT_EPSILON,
                "the old fixed schedule deadlocks when the first guarded step is refused — the reported repeated walk-request failure");
    }

    private static void discontinuityRefusalStillFires() {
        var before = rotated(1.2);
        // Forcing the destination in one step from a large rotation is a discontinuous jump.
        check(SupportApproachAlignment.discontinuous(before, new Basis384f()),
                "a forced full-rotation jump is still refused");
        var near = rotated(1.2 + .001);
        check(!SupportApproachAlignment.discontinuous(before, near), "small guarded steps remain admissible");
    }
}
