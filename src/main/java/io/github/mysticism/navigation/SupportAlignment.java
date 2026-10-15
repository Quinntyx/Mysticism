package io.github.mysticism.navigation;

/**
 * Refusal-adaptive schedule for the guarded deep→source-grid basis alignment of a walk request.
 *
 * A full step is 1/40 of the requested rotation, so accepted work stays within the approved
 * bounded contract of at most 40 full-step equivalents (accepted increments sum to at most 1,
 * each no larger than 1/40). When the terrain transition guard refuses a proposal, the next
 * attempt halves the step and retries from the SAME accepted progress instead of repeating the
 * identical doomed proposal every tick until the request deadline. Accepted proposals always
 * advance monotonically along the same supportFrom→destination path, so the visible scene keeps
 * moving continuously and a previously refused geometry can be crossed with a finer step.
 */
public final class SupportAlignment {
    public static final int FULL_STEPS = 40;
    private static final float MIN_STEP = 1f / (FULL_STEPS * 64f);
    private float progress;
    private float step = 1f / FULL_STEPS;

    /** Next proposed fraction of the supportFrom→destination rotation; never below current progress. */
    public float nextFraction() { return Math.min(1f, progress + step); }
    /** The terrain guard accepted the proposal; progress advances to exactly the accepted fraction.
     * The step is deliberately kept: a guard tolerance between the halved and doubled step would
     * make optimistic recovery oscillate and waste the request deadline. */
    public void accept(float fraction) {
        progress = Math.max(progress, Math.min(1f, fraction));
        if (progress >= 1f - 1e-6f) progress = 1f; // float-accumulated 39/40 steps must still complete
    }
    /** The terrain guard refused the proposal; retry finer next tick. Progress never regresses. */
    public void refuse() { step = Math.max(step * .5f, MIN_STEP); }
    public boolean complete() { return progress >= 1f; }
    public float progress() { return progress; }
    public float step() { return step; }
}
