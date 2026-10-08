package io.github.mysticism.navigation;

import io.github.mysticism.vector.Vec384f;

/** Per-player walk-intent arbitration: the latest requested destination must win.
 *
 * Pure server-thread state machine (no Minecraft types) so the retargeting rules are
 * unit-testable without a running server. Owns two decisions that previously lived as
 * fragile flags in {@link SpiritNavigationService}:
 *
 * <ul>
 * <li><b>Flight-toggle edges.</b> Vanilla only sends an abilities packet when the client's
 * local flight state changes, so a retransmitted/stale {@code flying=false} packet is not a
 * new walk gesture. Only a fresh edge may start a walk request; duplicates must never
 * restart one or override the latest requested destination with an old intent.</li>
 * <li><b>Destination epochs.</b> Every explicit destination request (target capture,
 * explicit attunement re-key) bumps an epoch. A pending walk records the epoch it was
 * requested against; if the epoch moves, the walk is stale and must be ended, never
 * completed against the older destination.</li>
 * </ul>
 *
 * Background personal-concept drift (LatentAttunement.observe on a non-explicit target) is
 * NOT a destination request and must neither bump the epoch nor cancel a pending walk.
 */
public final class WalkIntentTracker {
    private boolean toggleTracked, lastToggleFlying, pending;
    private long destinationEpoch, walkEpoch;

    /** Registers a client flight-toggle packet.
     * @return true when this packet is a fresh gesture (first observed state or a state edge),
     *         false for a retransmission of the already-recorded state. */
    public boolean observeToggle(boolean flying) {
        boolean fresh = !toggleTracked || lastToggleFlying != flying;
        toggleTracked = true;
        lastToggleFlying = flying;
        return fresh;
    }

    /** An explicit destination request (target capture, explicit attunement re-key) that
     * supersedes any walk intent requested earlier. */
    public void bumpDestination() { ++destinationEpoch; }

    /** Starts a walk intent unless one is already pending, recording the destination epoch it
     * was requested against.
     * @return true when this call created the pending intent. */
    public boolean startWalk() {
        if (pending) return false;
        pending = true;
        walkEpoch = destinationEpoch;
        return true;
    }

    public void endWalk() { pending = false; }
    public boolean pending() { return pending; }

    /** True when a pending walk was requested before the latest explicit destination change,
     * so completing it would override the newest requested destination. */
    public boolean superseded() { return pending && walkEpoch != destinationEpoch; }

    /** Attunement policy for a pending walk: only an EXPLICIT re-key of the attunement target
     * supersedes the walk; background personal drift on a non-explicit target is followed. */
    public static boolean attunementSupersedes(Vec384f snapshot, Vec384f current, boolean explicitTarget) {
        return explicitTarget && snapshot.squareDistance(current) > 0;
    }
}
