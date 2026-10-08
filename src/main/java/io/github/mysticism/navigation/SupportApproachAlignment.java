package io.github.mysticism.navigation;

import io.github.mysticism.vector.Basis384f;

/** Guarded source-grid alignment pacing for support approaches.
 *
 * A walk request must be able to FINISH once the actual support becomes available. The old
 * schedule blended from a fixed anchor at a fixed fraction (acceptedStep+1)/40 and simply froze
 * whenever the terrain transition guard refused a step — but the guard refuses any rotation whose
 * swept render motion reaches the body, which is exactly the state of an actor hovering just above
 * the support it asked to walk on. One refused step therefore deadlocked the whole approach until
 * the 200-tick expiry, so every walk request taken with a genuinely rotated deep basis failed
 * repeatedly and the actor stayed in flight.
 *
 * The policy keeps the bounded guarded-step contract but makes refused steps RE-PLAN instead of
 * freeze: the controller re-anchors the blend at the live basis and halves the pace (bounded
 * below), so each refused step retries with a smaller swept motion that can fit under the guard,
 * and each epoch still converges to the EXACT destination basis (fraction reaches 1), which the
 * strict acquisition gate requires. */
public final class SupportApproachAlignment {
    /** Approved full-pace schedule: an unguarded alignment completes in at most 40 accepted steps. */
    public static final float INITIAL_PACE = 1 / 40f;
    /** Refusal ratchet floor: even a full remaining rotation stepped at this pace moves rendered
     * geometry well under the transition guard's body clearance, so progress never freezes. */
    public static final float MIN_PACE = 1 / 5120f;
    /** Single-step discontinuity refusal (squared per-axis component delta), unchanged contract. */
    public static final float MAX_STEP_SQUARE = .01f;
    private SupportApproachAlignment() {}

    /** Blend fraction of the CURRENT epoch anchor toward the destination for the next guarded step. */
    public static float fraction(int acceptedSteps, float pace) {
        return Math.min(1f, (acceptedSteps + 1) * pace);
    }

    /** A refused step must never repeat identically: halve the pace so the retry sweeps less. */
    public static float paceOnHold(float pace) {
        return Math.max(pace / 2f, MIN_PACE);
    }

    /** True when applying the proposed basis in one step would be a discontinuous jump. */
    public static boolean discontinuous(Basis384f before, Basis384f proposed) {
        return before.i.squareDistance(proposed.i) > MAX_STEP_SQUARE || before.j.squareDistance(proposed.j) > MAX_STEP_SQUARE
                || before.k.squareDistance(proposed.k) > MAX_STEP_SQUARE;
    }
}
