package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/** Regressions for guarded source-grid alignment pacing. The old fixed-anchor/(step+1)/40 schedule
 * FROZE on the first refused step: the terrain transition guard refuses any rotation whose swept
 * render motion reaches the body — exactly the state of an actor hovering over the support it asked
 * to walk on — so every walk request taken with a genuinely rotated deep basis expired without ever
 * arriving. The re-anchoring, pace-ratcheting schedule must always keep making guarded progress and
 * still converge to the EXACT destination basis the strict acquisition gate requires. */
public final class SupportApproachAlignmentTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    /** Adversarial transition-guard model: a step is admitted only when no rendered axis component
     * moves further than this SQUARED distance in one step. Initial-pace steps of a real deep-flight
     * rotation exceed it, like the real guard refusing render motion that would reach the body. */
    private static final float GUARD_MAX_STEP_SQUARE = 1e-4f;
    private static final float ALIGNMENT_EPSILON = 1e-8f;

    public static void main(String[] args) {
        policyBounds();
        epochConvergesToExactDestination();
        refusedStepNeverRepeatsIdentically();
        convergesWithinOneRequestBudgetDespiteHolds();
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

    /** Largest single-axis component movement a proposed step would apply. */
    private static float componentStep(Basis384f before, Basis384f proposed) {
        return Math.max(Math.max(before.i.squareDistance(proposed.i), before.j.squareDistance(proposed.j)),
                before.k.squareDistance(proposed.k));
    }

    private static void policyBounds() {
        check(SupportApproachAlignment.INITIAL_PACE > 0 && SupportApproachAlignment.INITIAL_PACE <= 1 / 40f + 1e-9f,
                "full pace matches the approved 40 accepted-step schedule");
        float pace = SupportApproachAlignment.INITIAL_PACE;
        for (int i = 0; i < 64; i++) pace = SupportApproachAlignment.paceOnHold(pace);
        check(pace == SupportApproachAlignment.MIN_PACE, "the refusal ratchet is bounded below and stays positive");
        check(SupportApproachAlignment.fraction(0, SupportApproachAlignment.MIN_PACE) > 0,
                "even the floor pace keeps proposing guarded progress");
        check(SupportApproachAlignment.fraction(39, SupportApproachAlignment.INITIAL_PACE) == 1f,
                "an unguarded alignment completes exactly within the 40 accepted-step schedule");
    }

    private static void epochConvergesToExactDestination() {
        var anchor = rotated(1.2);
        var destination = new Basis384f();
        var current = anchor.clone();
        for (int accepted = 0; accepted < 40; accepted++) {
            var proposed = TraversalSteering.blend(anchor, destination,
                    SupportApproachAlignment.fraction(accepted, SupportApproachAlignment.INITIAL_PACE));
            check(!SupportApproachAlignment.discontinuous(current, proposed), "full-pace steps stay continuous");
            current = proposed;
        }
        check(error(current, destination) < ALIGNMENT_EPSILON,
                "an unguarded epoch reaches the EXACT destination basis, as the strict acquisition gate requires");
    }

    private static void refusedStepNeverRepeatsIdentically() {
        var anchor = rotated(1.2);
        var destination = new Basis384f();
        var current = anchor.clone();
        float pace = SupportApproachAlignment.INITIAL_PACE;
        var refused = TraversalSteering.blend(anchor, destination, SupportApproachAlignment.fraction(0, pace));
        check(componentStep(current, refused) > GUARD_MAX_STEP_SQUARE, "premise: the first full-pace step is refused");
        float refusedStep = componentStep(current, refused);
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
        check(componentStep(current, third) <= GUARD_MAX_STEP_SQUARE,
                "the ratchet reaches the guard's admissible band instead of deadlocking");
    }

    private static void convergesWithinOneRequestBudgetDespiteHolds() {
        var destination = new Basis384f();
        var current = rotated(1.2);
        var anchor = current.clone();
        float pace = SupportApproachAlignment.INITIAL_PACE;
        int accepted = 0, holds = 0, ticks = 0;
        while (error(current, destination) >= ALIGNMENT_EPSILON && ticks++ < 200) {
            var proposed = TraversalSteering.blend(anchor, destination, SupportApproachAlignment.fraction(accepted, pace));
            if (componentStep(current, proposed) > GUARD_MAX_STEP_SQUARE) {
                anchor = current.clone(); accepted = 0; pace = SupportApproachAlignment.paceOnHold(pace); holds++;
            } else {
                check(!SupportApproachAlignment.discontinuous(current, proposed), "accepted guarded steps stay continuous");
                current = proposed; accepted++;
            }
        }
        check(holds > 0, "premise: the adversarial guard actually refused full-pace steps");
        check(error(current, destination) < ALIGNMENT_EPSILON,
                "guarded alignment converges to the exact destination despite refused steps");
        check(ticks <= 200, "the arrival completes inside one walk-request budget, took " + ticks + " ticks");
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
            if (componentStep(current, proposed) > GUARD_MAX_STEP_SQUARE) continue; // frozen: nothing changes
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
