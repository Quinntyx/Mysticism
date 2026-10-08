package io.github.mysticism.navigation;

/** Cooldown after a failed walk request: repeated flight-toggle requests inside the window are
 * refused instead of cancelling and restarting expensive terrain validation, so a failing
 * support approach cannot become a request/failure spam loop. Pure Java, regression-testable. */
public final class WalkRetryGate {
    private final long cooldownTicks;
    private long blockedUntil = Long.MIN_VALUE;

    public WalkRetryGate(long cooldownTicks) {
        if (cooldownTicks < 0) throw new IllegalArgumentException("Negative walk retry cooldown");
        this.cooldownTicks = cooldownTicks;
    }

    /** True when a new walk request may be started at this tick. */
    public boolean canRequest(long tick) { return tick >= blockedUntil; }

    /** Records a failure; a burst of failures inside an already-armed window cannot extend the
     * lockout, so repeated failures can never lock a player out indefinitely. */
    public void failed(long tick) {
        if (tick < blockedUntil) return; // still cooling down from an earlier failure
        blockedUntil = tick + cooldownTicks;
    }

    /** Ticks until a new walk request is accepted; 0 when none is pending. */
    public long remaining(long tick) {
        long remaining = blockedUntil - tick;
        return remaining < 0 ? 0 : remaining;
    }

    public boolean coolingDown(long tick) { return remaining(tick) > 0; }
}
