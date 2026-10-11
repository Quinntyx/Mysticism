package io.github.mysticism.navigation;

/** Bounded, per-player walk timing. Async source wait never consumes continuous alignment time. */
public final class WalkRequestClock {
    public static final int ALIGNMENT_TICKS = 200;
    public static final int SOURCE_WAIT_TICKS = 600;
    /** A suspended intent cannot surprise the player indefinitely after discovery fails. */
    public static final int RECOVERY_WAIT_TICKS = 1200;
    public static final int MAX_RECOVERIES = 3;

    public enum Phase { ALIGN, WAIT_SOURCE, EXPIRE, EXPIRE_RECOVERABLE }
    private int alignmentTick, sourceWaitTick, recoveryWaitTick, recoveries;
    private boolean armed;

    /** Called only for an active request, once per movement tick.
     * Validated current support takes priority over unrelated/background source streaming. */
    public Phase tick(boolean supportReady, boolean sourcePending) {
        if (!supportReady && sourcePending) {
            if (++sourceWaitTick > SOURCE_WAIT_TICKS) {
                sourceWaitTick = 0;
                if (recoveries < MAX_RECOVERIES) {
                    armed = true; recoveryWaitTick = 0;
                    return Phase.EXPIRE_RECOVERABLE;
                }
                return Phase.EXPIRE;
            }
            return Phase.WAIT_SOURCE;
        }
        // Both budgets are cumulative within this attempt. Intermittent ready frames must
        // not reset source wait and allow fluctuating discovery/proofs to extend it forever.
        return ++alignmentTick > ALIGNMENT_TICKS ? Phase.EXPIRE : Phase.ALIGN;
    }

    public boolean armed() { return armed; }
    public int recoveries() { return recoveries; }

    /** Poll a suspended intent. True resumes exactly once, only after actual source readiness. */
    public boolean recover(boolean sourceReady) {
        if (!armed) return false;
        if (++recoveryWaitTick > RECOVERY_WAIT_TICKS) { disarm(); return false; }
        if (!sourceReady) return false;
        ++recoveries;
        restart();
        return true;
    }

    /** Deliberate cancellation/supersession must not leave a stale intent that auto-resumes. */
    public void disarm() { armed = false; }
    /** Continuation of the SAME issued intent, retaining its consumed recovery allowance. */
    public void restart() { alignmentTick = 0; sourceWaitTick = 0; recoveryWaitTick = 0; armed = false; }
    /** New explicit request, not a repeated packet while active/suspended. */
    public void reissue() { restart(); recoveries = 0; }
}
