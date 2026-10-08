package io.github.mysticism.navigation;

/**
 * Bounded truthful lifetime for one deep walk request (vanilla flight-off while deep).
 *
 * <p>The previous fixed 200-tick deadline expired requests that were still legitimately
 * pending: late semantic source discovery, an in-flight terrain ownership proof, or a
 * live current support whose acquisition was merely awaiting validation. Expiring also
 * cancelled the prepared terrain proof, so every repeated request restarted the expensive
 * source fetch from zero and an actor over slow-loading source chunks could remain stuck
 * in flight forever. This state machine separates genuinely stalled intents (bounded,
 * truthful expiry) from pending-valid ones (kept alive while real progress or a live
 * current support exists), under one absolute lifetime bound. Expiry itself never
 * destroys terrain-side proof; navigation retains it so a repeated request reuses it.</p>
 *
 * <p>Fixed-size per-player state, no model IO, joins or catalog scans; driven once per
 * pending movement tick by {@code SpiritNavigationService.attemptSupport}.</p>
 */
public final class WalkIntent {
    public enum Outcome { PENDING, PROGRESS, EXPIRED }

    /** Truthful, player-facing expiry reasons; never a generic "could not align". */
    public enum Reason {
        NONE(""),
        NO_SEMANTIC_ANCHOR("no source landmark has been discovered to walk on yet"),
        NO_CURRENT_SUPPORT("no continuously owned walkable support beneath the current flight"),
        UNRESOLVABLE_SUPPORT("the prepared source proof never finished validating within the bounded request window");
        private final String message;
        Reason(String message) { this.message = message; }
        public String message() { return message; }
    }

    public record Evaluation(Outcome outcome, Reason reason) {}

    /** Bounded wait for real late source discovery (async ownership publication anchoring). */
    public static final int DISCOVERY_WAIT_TICKS = 1200;
    /** Consecutive pending ticks with no current support before truthful expiry. */
    public static final int SUPPORT_STALL_TICKS = 200;
    /** Absolute bounded lifetime of one request, regardless of progress. */
    public static final int TOTAL_TICKS = 2400;

    private static final Evaluation PENDING = new Evaluation(Outcome.PENDING, Reason.NONE);
    private static final Evaluation PROGRESS = new Evaluation(Outcome.PROGRESS, Reason.NONE);

    private int age, discoveryWait, supportStall;
    private Reason expired = Reason.NONE;

    /** One pending movement tick. Terminal once expired; never restarts a consumed intent. */
    public Evaluation tick(boolean semanticReady, boolean supportPresent) {
        if (expired != Reason.NONE) return new Evaluation(Outcome.EXPIRED, expired);
        if (++age > TOTAL_TICKS) return expire(Reason.UNRESOLVABLE_SUPPORT);
        if (!semanticReady) { // Phase 1: real late discovery is pending, not a walk failure.
            supportStall = 0;
            if (++discoveryWait > DISCOVERY_WAIT_TICKS) return expire(Reason.NO_SEMANTIC_ANCHOR);
            return PENDING;
        }
        discoveryWait = 0;
        if (!supportPresent) { // Phase 2: flying with nothing owned/walkable beneath is real stall.
            if (++supportStall > SUPPORT_STALL_TICKS) return expire(Reason.NO_CURRENT_SUPPORT);
            return PENDING;
        }
        supportStall = 0; // A live current support keeps a valid intent pending, not stalled.
        return PROGRESS;
    }

    private Evaluation expire(Reason reason) { expired = reason; return new Evaluation(Outcome.EXPIRED, reason); }

    public boolean expired() { return expired != Reason.NONE; }
    public Reason expiryReason() { return expired; }
}
